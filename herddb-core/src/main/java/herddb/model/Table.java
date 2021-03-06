/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */

package herddb.model;

import com.google.common.collect.ImmutableSet;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import herddb.model.commands.AlterTableStatement;
import herddb.sql.expressions.BindableTableScanColumnNameResolver;
import herddb.utils.Bytes;
import herddb.utils.ExtendedDataInputStream;
import herddb.utils.ExtendedDataOutputStream;
import herddb.utils.SimpleByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Table definition
 *
 * @author enrico.olivelli
 */
@SuppressFBWarnings(value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"})
public class Table implements ColumnsList, BindableTableScanColumnNameResolver {

    private static final Logger LOG = Logger.getLogger(Table.class.getName());
    private static final int COLUMNVERSION_1 = 1;

    private static final int COLUMNFLAGS_NO_FLAGS = 0;
    private static final int COLUMNFLAGS_HAS_DEFAULT_VALUE = 1;
    private static final int TABLEFLAGS_HAS_FOREIGN_KEYS = 1;

    public final String uuid;
    public final String name;
    public final String tablespace;
    public final Column[] columns;
    public final String[] columnNames;
    public final Map<String, Column> columnsByName;
    public final Map<Integer, Column> columnsBySerialPosition;
    public final String[] primaryKey;
    public final int[] primaryKeyProjection;
    // CHECKSTYLE.OFF: MemberName
    public final boolean auto_increment;
    // CHECKSTYLE.ON: MemberName
    private final Set<String> primaryKeyColumns;
    public final int maxSerialPosition;
    public final ForeignKeyDef[] foreignKeys;

    /**
     * Best case:
     * <ul>
     * <li> PK is the before the other columns (so the 'key' is before the
     * 'value')
     * <li> PK columns are in the same order of the logical order of columns
     * <li> non-PK columns are in the same order of the logical order of columns
     * </ul>
     * In this case in order to serve a 'SELECT * FROM TABLE' we can dump the
     * key and than the value.
     */
    public final boolean physicalLayoutLikeLogicalLayout;

    private Table(String uuid, String name, Column[] columns, String[] primaryKey, String tablespace, boolean auto_increment, int maxSerialPosition, ForeignKeyDef[] foreignKeys) {
        this.uuid = uuid;
        this.name = name;
        this.columns = columns;
        this.maxSerialPosition = maxSerialPosition;
        this.foreignKeys = foreignKeys;
        this.primaryKey = primaryKey;
        this.tablespace = tablespace;
        this.columnsByName = new HashMap<>();
        this.columnsBySerialPosition = new HashMap<>();
        this.auto_increment = auto_increment;
        this.columnNames = new String[columns.length];
        int i = 0;
        this.primaryKeyProjection = new int[columns.length];
        for (Column c : this.columns) {
            String cname = c.name.toLowerCase();
            columnsByName.put(cname, c);
            if (c.serialPosition < 0) {
                throw new IllegalArgumentException();
            }
            columnsBySerialPosition.put(c.serialPosition, c);
            columnNames[i] = cname;
            primaryKeyProjection[i] = findPositionInArray(cname, primaryKey);
            i++;
        }
        this.primaryKeyColumns = ImmutableSet.<String>builder().addAll(Arrays.asList(primaryKey)).build();

        boolean primaryKeyIsInKeyAndOrdered = true;
        for (int k = 0; k < primaryKey.length; k++) {
            if (!columnNames[k].equals(primaryKey[k])) {
                primaryKeyIsInKeyAndOrdered = false;
            }
        }

        boolean columnsOrderedAsInPhysicalOrder = false;
        // check if columns are in the relative order as 'serialPosition'
        List<String> columnsNamesAsList = Arrays.asList(columnNames);
        List<String> columnsNamesOrderedBySerialPosition = new ArrayList<>(columnsNamesAsList);
        columnsNamesOrderedBySerialPosition.sort(Comparator.comparingInt(s -> {
            return columnsByName.get(s).serialPosition;
        }));
        columnsOrderedAsInPhysicalOrder = columnsNamesOrderedBySerialPosition.equals(columnsNamesAsList);

        this.physicalLayoutLikeLogicalLayout = primaryKeyIsInKeyAndOrdered && columnsOrderedAsInPhysicalOrder;

        if (LOG.isLoggable(Level.FINEST)) {
            LOG.log(Level.FINEST, "Table: ", tablespace + "." + name + "\n"
                    + "Columns: " + columnsNamesAsList + "\n"
                    + "PrimaryKey: " + Arrays.asList(primaryKey) + "\n"
                    + "Columns ordered physically: " + columnsNamesOrderedBySerialPosition + "\n"
                    + "PrimaryKeyIsInKeyAndOrdered: " + primaryKeyIsInKeyAndOrdered + "\n"
                    + "ColumnsOrderedAsInPhysicalOrder: " + columnsOrderedAsInPhysicalOrder + "\n"
                    + "PhysicalLayoutLikeLogicalLayout: " + physicalLayoutLikeLogicalLayout + "\n"
            );
        }
    }

    public boolean isPrimaryKeyColumn(String column) {
        return primaryKeyColumns.contains(column);
    }

    public boolean isPrimaryKeyColumn(int index) {
        return primaryKeyProjection[index] >= 0;
    }

    public ForeignKeyDef[] getForeignKeys() {
        return foreignKeys;
    }

    public boolean isChildOfTable(String parentUuid) {
        if (foreignKeys == null) {
            return false;
        }
        for (ForeignKeyDef fk : foreignKeys) {
            if (fk.parentTableId.equals(parentUuid)) {
                return true;
            }
        }
        return false;
    }

    public static Builder builder() {
        return new Builder();
    }

    @SuppressFBWarnings("OS_OPEN_STREAM")
    public static Table deserialize(byte[] data) {
        try {
            SimpleByteArrayInputStream ii = new SimpleByteArrayInputStream(data);
            ExtendedDataInputStream dii = new ExtendedDataInputStream(ii);
            long tversion = dii.readVLong(); // version
            long tflags = dii.readVLong(); // flags for future implementations
            if (tversion != 1 || tflags != 0) {
                throw new IOException("corrupted table file");
            }
            String tablespace = dii.readUTF();
            String name = dii.readUTF();
            String uuid = dii.readUTF();
            boolean auto_increment = dii.readByte() > 0;
            int maxSerialPosition = dii.readVInt();
            byte pkcols = dii.readByte();
            String[] primaryKey = new String[pkcols];
            for (int i = 0; i < pkcols; i++) {
                primaryKey[i] = dii.readUTF();
            }
            int flags = dii.readVInt(); // for future implementations
            int ncols = dii.readVInt();
            Column[] columns = new Column[ncols];
            for (int i = 0; i < ncols; i++) {
                long cversion = dii.readVLong(); // version
                long cflags = dii.readVLong(); // flags for future implementations
                if (cversion != COLUMNVERSION_1
                        || (cflags != COLUMNFLAGS_NO_FLAGS
                        && cflags != COLUMNFLAGS_HAS_DEFAULT_VALUE)) {
                    throw new IOException("corrupted table file");
                }
                String cname = dii.readUTF();
                int type = dii.readVInt();
                int serialPosition = dii.readVInt();
                Bytes defaultValue = null;
                if ((cflags & COLUMNFLAGS_HAS_DEFAULT_VALUE) == COLUMNFLAGS_HAS_DEFAULT_VALUE) {
                    defaultValue = dii.readBytes();
                }
                columns[i] = Column.column(cname, type, serialPosition, defaultValue);
            }
            ForeignKeyDef[] foreignKeys = null;
            if ((flags & TABLEFLAGS_HAS_FOREIGN_KEYS) == TABLEFLAGS_HAS_FOREIGN_KEYS) {
                int numForeignKeys = dii.readVInt();
                if (numForeignKeys > 0) {
                    foreignKeys = new ForeignKeyDef[numForeignKeys];
                    for (int i = 0; i < numForeignKeys; i++) {
                        ForeignKeyDef.Builder builder = ForeignKeyDef.builder();
                        String fkName = dii.readUTF();
                        String parentTableId = dii.readUTF();
                        builder.parentTableId(parentTableId);
                        builder.name(fkName);
                        int numColumns = dii.readVInt();
                        for (int k = 0; k < numColumns; k++) {
                            String col = dii.readUTF();
                            builder.column(col);
                        }
                        for (int k = 0; k < numColumns; k++) {
                            String col = dii.readUTF();
                            builder.parentTableColumn(col);
                        }
                        builder.onUpdateAction(dii.readVInt());
                        builder.onDeleteAction(dii.readVInt());
                        foreignKeys[i] = builder.build();
                    }
                }
            }
            return new Table(uuid, name, columns, primaryKey, tablespace, auto_increment, maxSerialPosition, foreignKeys);
        } catch (IOException err) {
            throw new IllegalArgumentException(err);
        }
    }

    public byte[] serialize() {
        ByteArrayOutputStream oo = new ByteArrayOutputStream();
        try (ExtendedDataOutputStream doo = new ExtendedDataOutputStream(oo)) {
            doo.writeVLong(1); // version
            doo.writeVLong(0); // flags for future implementations
            doo.writeUTF(tablespace);
            doo.writeUTF(name);
            doo.writeUTF(uuid);
            doo.writeByte(auto_increment ? 1 : 0);
            doo.writeVInt(maxSerialPosition);
            doo.writeByte(primaryKey.length);
            for (String primaryKeyColumn : primaryKey) {
                doo.writeUTF(primaryKeyColumn);
            }
            doo.writeVInt(0 + ((foreignKeys != null && foreignKeys.length > 0) ? TABLEFLAGS_HAS_FOREIGN_KEYS : 0)); // flags for future implementations
            doo.writeVInt(columns.length);
            for (Column c : columns) {
                doo.writeVLong(COLUMNVERSION_1); // version
                if (c.defaultValue != null) {
                    doo.writeVLong(COLUMNFLAGS_HAS_DEFAULT_VALUE);
                } else {
                    doo.writeVLong(COLUMNFLAGS_NO_FLAGS);
                }
                doo.writeUTF(c.name);
                doo.writeVInt(c.type);
                doo.writeVInt(c.serialPosition);
                if (c.defaultValue != null) {
                    doo.writeArray(c.defaultValue);
                }
            }
            if (foreignKeys != null && foreignKeys.length > 0) {
                doo.writeVInt(foreignKeys.length);
                for (ForeignKeyDef k : foreignKeys) {
                    doo.writeUTF(k.name);
                    doo.writeUTF(k.parentTableId);
                    doo.writeVInt(k.columns.length);
                    for (String col : k.columns) {
                        doo.writeUTF(col);
                    }
                    for (String col : k.parentTableColumns) {
                        doo.writeUTF(col);
                    }
                    doo.writeVInt(k.onUpdateAction);
                    doo.writeVInt(k.onDeleteAction);
                }
            }
        } catch (IOException ee) {
            throw new RuntimeException(ee);
        }
        return oo.toByteArray();
    }

    @Override
    public Column getColumn(String cname) {
        return columnsByName.get(cname);
    }

    @Override
    public Column[] getColumns() {
        return columns;
    }

    @Override
    public Column resolveColumName(int columnReference) {
        return columns[columnReference];
    }

    @Override
    public String[] getPrimaryKey() {
        return primaryKey;
    }

    @Override
    public boolean allowNullsForIndexedValues() {
        // this refers to the PK, the PK cannot be NULL
        return false;
    }


    public Table applyAlterTable(AlterTableStatement alterTableStatement) {
        int new_maxSerialPosition = this.maxSerialPosition;
        String newTableName = alterTableStatement.getNewTableName() != null ? alterTableStatement.getNewTableName().toLowerCase()
                : this.name;

        Builder builder = builder()
                .name(newTableName)
                .uuid(this.uuid)
                .tablespace(this.tablespace);

        List<String> dropColumns = alterTableStatement.getDropColumns().stream().map(String::toLowerCase)
                .collect(Collectors.toList());
        for (String dropColumn : dropColumns) {
            if (this.getColumn(dropColumn) == null) {
                throw new IllegalArgumentException("column " + dropColumn + " not found int table " + this.name);
            }
            if (isPrimaryKeyColumn(dropColumn)) {
                throw new IllegalArgumentException("column " + dropColumn + " cannot be dropped because is part of the primary key of table " + this.name);
            }
        }
        Set<String> changedColumns = new HashSet<>();
        Map<Integer, Column> realStructure =
                Stream
                        .of(columns)
                        .collect(
                                Collectors.toMap(
                                        t -> t.serialPosition,
                                        Function.identity()
                                ));
        if (alterTableStatement.getModifyColumns() != null) {
            for (Column newColumn : alterTableStatement.getModifyColumns()) {
                Column oldColumn = realStructure.get(newColumn.serialPosition);
                if (oldColumn == null) {
                    throw new IllegalArgumentException("column " + newColumn.name + " not found int table " + this.name
                            + ", looking for serialPosition = " + newColumn.serialPosition);

                }
                changedColumns.add(oldColumn.name);
            }
        }

        for (Column c : this.columns) {
            String lowercase = c.name.toLowerCase();
            if (!dropColumns.contains(lowercase)
                    && !changedColumns.contains(lowercase)) {
                builder.column(c.name, c.type, c.serialPosition, c.defaultValue);
            }
            new_maxSerialPosition = Math.max(new_maxSerialPosition, c.serialPosition);
        }

        if (alterTableStatement.getAddColumns() != null) {
            for (Column c : alterTableStatement.getAddColumns()) {
                if (getColumn(c.name) != null) {
                    throw new IllegalArgumentException("column " + c.name + " already found int table " + this.name);
                }
                builder.column(c.name, c.type, ++new_maxSerialPosition, c.defaultValue);
            }
        }
        String[] newPrimaryKey = new String[primaryKey.length];
        System.arraycopy(primaryKey, 0, newPrimaryKey, 0, primaryKey.length);
        if (alterTableStatement.getModifyColumns() != null) {
            for (Column c : alterTableStatement.getModifyColumns()) {

                builder.column(c.name, c.type, c.serialPosition, c.defaultValue);
                new_maxSerialPosition = Math.max(new_maxSerialPosition, c.serialPosition);

                // RENAME PK
                Column oldcolumn = realStructure.get(c.serialPosition);
                if (isPrimaryKeyColumn(oldcolumn.name)) {
                    for (int i = 0; i < newPrimaryKey.length; i++) {
                        if (newPrimaryKey[i].equals(oldcolumn.name)) {
                            newPrimaryKey[i] = c.name;
                        }
                    }
                }
            }
        }
        boolean new_auto_increment = alterTableStatement.getChangeAutoIncrement() != null
                ? alterTableStatement.getChangeAutoIncrement()
                : this.auto_increment;
        for (String pk : newPrimaryKey) {
            builder.primaryKey(pk, new_auto_increment);
        }
        builder.maxSerialPosition(new_maxSerialPosition);

        List<ForeignKeyDef> newForeignKeyDefs = new ArrayList<>();
        if (foreignKeys != null) {
            newForeignKeyDefs.addAll(Arrays.asList(foreignKeys));
        }
        // duplicate names will be checked in the Builder
        newForeignKeyDefs.addAll(alterTableStatement.getAddForeignKeys());

        // remove FKs
        for (String fk : alterTableStatement.getDropForeignKeys()) {
            newForeignKeyDefs.removeIf(f->f.name.equalsIgnoreCase(fk));
        }
        newForeignKeyDefs.forEach(builder::foreingKey);
        return builder.build();

    }

    public Column getColumnBySerialPosition(int serialPosition) {
        return columnsBySerialPosition.get(serialPosition);
    }

    public int[] getPrimaryKeyProjection() {
        return primaryKeyProjection;
    }

    private static int findPositionInArray(String cname, String[] primaryKey) {
        for (int i = 0; i < primaryKey.length; i++) {
            if (primaryKey[i].equals(cname)) {
                return i;
            }
        }
        return -1;
    }

    public Column getColumn(int index) {
        return columns[index];
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Table [name=").append(name).append(", tablespace=").append(tablespace).append("]");
        return sb.toString();
    }

    public Table withForeignKeys(ForeignKeyDef[] foreignKeys) {
        if (this.foreignKeys != null) {
            throw new IllegalStateException();
        }
        return new Table(uuid, name, columns, primaryKey, tablespace, auto_increment, maxSerialPosition, foreignKeys);
    }

    public static class Builder {

        private final List<Column> columns = new ArrayList<>();
        private final List<ForeignKeyDef> foreignKeys = new ArrayList<>();
        private String name;
        private String uuid;
        private final List<String> primaryKey = new ArrayList<>();
        private String tablespace = TableSpace.DEFAULT;
        // CHECKSTYLE.OFF: MemberName
        private boolean auto_increment;
        // CHECKSTYLE.ON: MemberName
        private int maxSerialPosition = 0;

        private Builder() {
        }

        public Builder uuid(String uuid) {
            this.uuid = uuid.toLowerCase();
            return this;
        }

        public Builder name(String name) {
            this.name = name.toLowerCase();
            return this;
        }

        public Builder maxSerialPosition(int maxSerialPosition) {
            this.maxSerialPosition = maxSerialPosition;
            return this;
        }

        public Builder tablespace(String tablespace) {
            this.tablespace = tablespace;
            return this;
        }

        public Builder primaryKey(String pk) {
            return primaryKey(pk, false);
        }

        public Builder primaryKey(String pk, boolean auto_increment) {
            if (pk == null || pk.isEmpty()) {
                throw new IllegalArgumentException();
            }
            if (this.auto_increment && auto_increment) {
                throw new IllegalArgumentException("auto_increment can be used only on one column");
            }
            pk = pk.toLowerCase();
            if (auto_increment) {
                this.auto_increment = true;
            }
            if (!this.primaryKey.contains(pk)) {
                this.primaryKey.add(pk);
            }
            return this;
        }

        public Builder foreingKey(ForeignKeyDef fk) {
            this.foreignKeys.add(fk);
            return this;
        }

        public Builder column(String name, int type) {
            return column(name, type, maxSerialPosition++, null);
        }

        public Builder column(String name, int type, Bytes defaultValue) {
            return column(name, type, maxSerialPosition++, defaultValue);
        }

        public Builder column(String name, int type, int serialPosition, Bytes defaultValue) {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException();
            }
            String _name = name.toLowerCase();
            if (this.columns.stream().filter(c -> (c.name.equals(_name))).findAny().isPresent()) {
                throw new IllegalArgumentException("column " + name + " already exists");
            }
            this.columns.add(Column.column(_name, type, serialPosition, defaultValue));
            return this;
        }

        public Table build() {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("name is not defined");
            }
            if (uuid == null || uuid.isEmpty()) {
                uuid = UUID.randomUUID().toString();
            }
            if (primaryKey.isEmpty()) {
                throw new IllegalArgumentException("primary key is not defined");
            }
            for (String pkColumn : primaryKey) {
                Column pk = columns.stream().filter(c -> c.name.equals(pkColumn)).findAny().orElse(null);
                if (pk == null) {
                    throw new IllegalArgumentException("column " + pkColumn + " is not defined in table");
                }
                if (!validatePrimaryKeyDataType(pk.type)) {
                    throw new IllegalArgumentException("primary key " + pkColumn + " must be a string or long or integer or timestamp");
                }
            }

            columns.sort((Column o1, Column o2) -> o1.serialPosition - o2.serialPosition);

            // look for duplicate FK names
            Set<String> distinctNames = foreignKeys.stream().map(f->f.name.toLowerCase()).collect(Collectors.toSet());
            if (distinctNames.size() != foreignKeys.size()) {
                throw new IllegalArgumentException("Duplicate foreign key names discovered");
            }

            return new Table(uuid, name,
                    columns.toArray(new Column[columns.size()]), primaryKey.toArray(new String[primaryKey.size()]),
                    tablespace, auto_increment, maxSerialPosition, foreignKeys.isEmpty() ? null : foreignKeys.toArray(new ForeignKeyDef[foreignKeys.size()]));
        }

        /**
         * Validate whether the primary key type is valid.
         * Note: In order to keep backward compatibility we are allowing nullable data types to be part of primary key
         *
         * @param type
         * @return true if a valid primary key type or false
         */
        private static boolean validatePrimaryKeyDataType(int type) {
            switch (type) {
                case ColumnTypes.NOTNULL_INTEGER:
                case ColumnTypes.INTEGER:
                case ColumnTypes.NOTNULL_LONG:
                case ColumnTypes.LONG:
                case ColumnTypes.NOTNULL_STRING:
                case ColumnTypes.STRING:
                case ColumnTypes.TIMESTAMP:
                case ColumnTypes.NOTNULL_TIMESTAMP:
                case ColumnTypes.BYTEARRAY:
                    return true;
                default:
                    return false;
            }
        }

        public Builder cloning(Table tableSchema) {
            this.columns.addAll(Arrays.asList(tableSchema.columns));
            this.name = tableSchema.name;
            this.uuid = tableSchema.uuid;
            this.primaryKey.addAll(Arrays.asList(tableSchema.primaryKey));
            this.tablespace = tableSchema.tablespace;
            this.auto_increment = tableSchema.auto_increment;
            this.maxSerialPosition = tableSchema.maxSerialPosition;
            if (tableSchema.foreignKeys != null) {
                this.foreignKeys.addAll(Arrays.asList(tableSchema.foreignKeys));
            }
            return this;
        }
    }

    @Override
    public int hashCode() {
        int hash = 5;
        hash = 23 * hash + Objects.hashCode(this.uuid);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final Table other = (Table) obj;
        if (this.auto_increment != other.auto_increment) {
            return false;
        }
        if (this.maxSerialPosition != other.maxSerialPosition) {
            return false;
        }
        if (!Objects.equals(this.uuid, other.uuid)) {
            return false;
        }
        if (!Objects.equals(this.name, other.name)) {
            return false;
        }
        if (!Objects.equals(this.tablespace, other.tablespace)) {
            return false;
        }
        if (!Arrays.deepEquals(this.columns, other.columns)) {
            return false;
        }
        if (!Arrays.deepEquals(this.primaryKey, other.primaryKey)) {
            return false;
        }
        return true;
    }


}
