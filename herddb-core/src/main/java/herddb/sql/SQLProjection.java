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
package herddb.sql;

import herddb.model.Column;
import herddb.model.ColumnTypes;
import herddb.model.Projection;
import herddb.model.StatementExecutionException;
import herddb.model.Table;
import herddb.model.Tuple;
import herddb.sql.functions.BuiltinFunctions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.TimestampValue;
import net.sf.jsqlparser.statement.select.SelectExpressionItem;
import net.sf.jsqlparser.statement.select.SelectItem;

/**
 * Projection based on SQL
 *
 * @author enrico.olivelli
 */
public class SQLProjection implements Projection {

    private final List<SelectItem> selectItems;
    private final Column[] columns;
    private final String[] fieldNames;

    public SQLProjection(Table table, List<SelectItem> selectItems) throws StatementExecutionException {
        this.selectItems = selectItems;
        List<Column> _columns = new ArrayList<>();
        int pos = 0;
        for (SelectItem item : selectItems) {
            pos++;
            String fieldName = null;
            Object value;
            int columType;
            if (item instanceof SelectExpressionItem) {
                SelectExpressionItem si = (SelectExpressionItem) item;
                Alias alias = si.getAlias();
                if (alias != null && alias.getName() != null) {
                    fieldName = alias.getName();
                }
                Expression exp = si.getExpression();
                if (exp instanceof net.sf.jsqlparser.schema.Column) {
                    net.sf.jsqlparser.schema.Column c = (net.sf.jsqlparser.schema.Column) exp;
                    if (fieldName == null) {
                        fieldName = c.getColumnName();
                    }
                    Column column = table.getColumn(c.getColumnName());
                    if (column == null) {
                        throw new StatementExecutionException("invalid column name " + c.getColumnName());
                    }
                    columType = column.type;
                } else {
                    if (exp instanceof StringValue) {
                        columType = ColumnTypes.STRING;
                    } else {
                        if (exp instanceof LongValue) {
                            columType = ColumnTypes.LONG;
                        } else {
                            if (exp instanceof Function) {
                                Function f = (Function) exp;
                                columType = BuiltinFunctions.typeOfFunction(f.getName());
                            } else {
                                throw new StatementExecutionException("unhandled select expression type " + exp.getClass() + ": " + exp);
                            }
                        }
                    }
                }
                if (fieldName == null) {
                    fieldName = "item" + pos;
                }
                _columns.add(Column.column(fieldName, columType));
            } else {
                throw new StatementExecutionException("unhandled select item type " + item.getClass() + ": " + item);
            }
        }
        this.columns = _columns.toArray(new Column[_columns.size()]);
        this.fieldNames = new String[columns.length];
        int i = 0;
        for (Column c : columns) {
            this.fieldNames[i++] = c.name;
        }
    }

    @Override
    public Tuple map(Tuple tuple) throws StatementExecutionException {
        Map<String, Object> record = tuple.toMap();
        List<Object> values = new ArrayList<>(selectItems.size());
        int pos = 0;
        for (SelectItem item : selectItems) {
            if (item instanceof SelectExpressionItem) {
                SelectExpressionItem si = (SelectExpressionItem) item;
                Expression exp = si.getExpression();
                Object value;
                value = BuiltinFunctions.computeValue(exp, record);
                values.add(value);
            } else {
                throw new StatementExecutionException("unhandled select item type " + item.getClass() + ": " + item);
            }
        }
        return new Tuple(
                fieldNames,
                values.toArray()
        );
    }

    @Override
    public Column[] getColumns() {
        return columns;
    }

}
