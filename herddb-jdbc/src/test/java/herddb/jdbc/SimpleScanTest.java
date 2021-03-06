/*
 * Licensed to Diennea S.r.l. under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Diennea S.r.l. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package herddb.jdbc;

import static herddb.utils.TestUtils.NOOP;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import herddb.client.ClientConfiguration;
import herddb.client.HDBClient;
import herddb.model.TableSpace;
import herddb.model.Transaction;
import herddb.server.Server;
import herddb.server.StaticClientSideMetadataProvider;
import herddb.utils.TestUtils;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Simple SQL scan tests
 *
 * @author diego.salvi
 */
public class SimpleScanTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void test() throws Exception {
        try (Server server = new Server(herddb.jdbc.TestUtils.newServerConfigurationWithAutoPort(folder.newFolder().toPath()))) {
            server.start();
            server.waitForStandaloneBoot();

            try (HDBClient client = new HDBClient(new ClientConfiguration(folder.newFolder().toPath()))) {
                client.setClientSideMetadataProvider(new StaticClientSideMetadataProvider(server));

                try (BasicHerdDBDataSource dataSource = new BasicHerdDBDataSource(client)) {

                    try (Connection con = dataSource.getConnection();  Statement statement = con.createStatement()) {
                        statement.execute(
                                "CREATE TABLE mytable (k1 string primary key, n1 int, l1 long, t1 timestamp, nu string, b1 bool, d1 double, s1 smallint)");

                    }

                    try (Connection con = dataSource.getConnection();  PreparedStatement statement = con.prepareStatement(
                            "INSERT INTO mytable(k1,n1,l1,t1,nu,b1,d1,s1) values(?,?,?,?,?,?,?,?)")) {

                        for (int n = 0; n < 10; ++n) {
                            int i = 1;
                            statement.setString(i++, "mykey_" + n);
                            statement.setInt(i++, n);
                            statement.setLong(i++, n);
                            statement.setTimestamp(i++, new java.sql.Timestamp(System.currentTimeMillis()));
                            statement.setString(i++, null);
                            statement.setBoolean(i++, true);
                            statement.setDouble(i++, n + 0.5);
                            statement.setShort(i++, (short) n);

                            statement.addBatch();
                        }

                        int[] batches = statement.executeBatch();
                        Assert.assertEquals(10, batches.length);

                        for (int batch : batches) {
                            Assert.assertEquals(1, batch);
                        }
                    }

                    try (Connection con = dataSource.getConnection()) {

                        // Get and projection
                        try (PreparedStatement statement = con.prepareStatement(
                                "SELECT n1, k1 FROM mytable WHERE k1 = 'mykey_1'")) {
                            int count = 0;
                            try (ResultSet rs = statement.executeQuery()) {
                                while (rs.next()) {
                                    assertEquals(1, rs.getInt(1));
                                    assertNotNull(rs.getString(2));
                                    ++count;
                                }
                            }
                            assertEquals(1, count);
                        }

                        // Scan and projection
                        try (PreparedStatement statement = con.prepareStatement(
                                "SELECT n1, k1 FROM mytable WHERE n1 > 1")) {
                            int count = 0;
                            try (ResultSet rs = statement.executeQuery()) {
                                while (rs.next()) {
                                    assertTrue(rs.getInt(1) > 1);
                                    assertNotNull(rs.getString(2));
                                    ++count;
                                }
                            }
                            assertEquals(8, count);
                        }

                        // Scan, sort and projection
                        try (PreparedStatement statement = con.prepareStatement(
                                "SELECT n1, k1 FROM mytable WHERE n1 > 1 ORDER BY n1")) {
                            int count = 0;
                            try (ResultSet rs = statement.executeQuery()) {
                                while (rs.next()) {
                                    assertTrue(rs.getInt(1) > 1);
                                    assertNotNull(rs.getString(2));
                                    ++count;
                                }
                            }
                            assertEquals(8, count);
                        }

                        // verify all data types and table contents
                        try (PreparedStatement statement = con.prepareStatement(
                                "SELECT * FROM mytable ORDER BY n1")) {
                            try (ResultSet rs = statement.executeQuery()) {

                                for (int n = 0; n < 10; ++n) {
                                    int i = 1;
                                    assertTrue(rs.next());
                                    assertEquals("mykey_" + n, rs.getString(i++));

                                    assertEquals(n, rs.getInt(i++));
                                    assertEquals(n, rs.getLong(i++));
                                    assertNotNull(rs.getTimestamp(i++));
                                    assertNull(rs.getString(i++));
                                    assertTrue(rs.getBoolean(i++));
                                    assertEquals(n + 0.5, rs.getDouble(i++), 0d);
                                    assertEquals((short) n, rs.getShort(i++));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void testSingleColumnHeapTable() throws Exception {
        try (Server server = new Server(herddb.jdbc.TestUtils.newServerConfigurationWithAutoPort(folder.newFolder().toPath()))) {
            server.start();
            server.waitForStandaloneBoot();

            try (HDBClient client = new HDBClient(new ClientConfiguration(folder.newFolder().toPath()))) {
                client.setClientSideMetadataProvider(new StaticClientSideMetadataProvider(server));

                try (BasicHerdDBDataSource dataSource = new BasicHerdDBDataSource(client)) {

                    try (Connection con = dataSource.getConnection();  Statement statement = con.createStatement()) {
                        statement.execute("CREATE TABLE mytable (id string)"); // no primary key

                    }

                    try (Connection con = dataSource.getConnection();  PreparedStatement statement = con.prepareStatement("INSERT INTO mytable(id) values(?)")) {

                        for (int n = 0; n < 10; ++n) {
                            int i = 1;
                            statement.setString(i++, "mykey_" + n);
                            statement.addBatch();
                        }

                        int[] batches = statement.executeBatch();
                        Assert.assertEquals(10, batches.length);

                        for (int batch : batches) {
                            Assert.assertEquals(1, batch);
                        }
                    }

                    try (Connection con = dataSource.getConnection()) {

                        try (PreparedStatement statement = con.prepareStatement("SELECT * FROM mytable")) {
                            int count = 0;
                            try (ResultSet rs = statement.executeQuery()) {
                                while (rs.next()) {
                                    assertNotNull(rs.getString(1));
                                    ++count;
                                }
                            }
                            assertEquals(10, count);
                        }

                    }

                }
            }
        }
    }

    @Test
    public void testCloseResultSetsOnCloseConnection() throws Exception {
        try (Server server = new Server(herddb.jdbc.TestUtils.newServerConfigurationWithAutoPort(folder.newFolder().toPath()))) {
            server.start();
            server.waitForStandaloneBoot();

            try (HDBClient client = new HDBClient(new ClientConfiguration(folder.newFolder().toPath()))) {
                client.setClientSideMetadataProvider(new StaticClientSideMetadataProvider(server));

                try (BasicHerdDBDataSource dataSource = new BasicHerdDBDataSource(client)) {
                    try (Connection con = dataSource.getConnection();  Statement statement = con.createStatement()) {
                        statement.execute(
                                "CREATE TABLE mytable (k1 string primary key, n1 int, l1 long, t1 timestamp, nu string, b1 bool, d1 double)");

                    }

                    try (Connection con = dataSource.getConnection();  PreparedStatement statement = con.prepareStatement(
                            "INSERT INTO mytable(k1,n1,l1,t1,nu,b1,d1) values(?,?,?,?,?,?,?)")) {

                        for (int n = 0; n < 10; ++n) {
                            int i = 1;
                            statement.setString(i++, "mykey_" + n);
                            statement.setInt(i++, n);
                            statement.setLong(i++, n);
                            statement.setTimestamp(i++, new java.sql.Timestamp(System.currentTimeMillis()));
                            statement.setString(i++, null);
                            statement.setBoolean(i++, true);
                            statement.setDouble(i++, n + 0.5);

                            statement.addBatch();
                        }

                        int[] batches = statement.executeBatch();
                        Assert.assertEquals(10, batches.length);

                        for (int batch : batches) {
                            Assert.assertEquals(1, batch);
                        }
                    }

                    try (Connection con = dataSource.getConnection()) {
                        con.setAutoCommit(false);
                        Transaction tx;
                        try (Statement s = con.createStatement()) {
                            // force the creation of the transaction, by issuing a DML command
                            s.executeUpdate("UPDATE mytable set n1=1 where k1 ='aaa'");
                        }
                        try (PreparedStatement statement = con.prepareStatement("SELECT n1, k1 FROM mytable")) {
                            statement.setFetchSize(1);
                            ResultSet rs = statement.executeQuery();
                            assertTrue(rs.next());
                            List<Transaction> transactions =
                                    server.getManager().getTableSpaceManager(TableSpace.DEFAULT).getTransactions();
                            assertEquals(1, transactions.size());
                            tx = transactions.get(0);
                            assertEquals(1, tx.getRefCount());
                        }
                        // close statement -> close result set -> transaction refcount = 0
                        // closing of scanners is non blocking, we have to wait
                        TestUtils.waitForCondition(() -> tx.getRefCount() == 0, NOOP, 100);

                    }

                    Transaction tx;
                    try (Connection con = dataSource.getConnection()) {
                        con.setAutoCommit(false);
                        try (Statement s = con.createStatement()) {
                            // force the creation of the transaction, by issuing a DML command
                            s.executeUpdate("UPDATE mytable set n1=1 where k1 ='aaa'");
                        }
                        PreparedStatement statement = con.prepareStatement("SELECT n1, k1 FROM mytable");
                        statement.setFetchSize(1);
                        ResultSet rs = statement.executeQuery();
                        assertTrue(rs.next());
                        List<Transaction> transactions =
                                server.getManager().getTableSpaceManager(TableSpace.DEFAULT).getTransactions();
                        assertEquals(1, transactions.size());
                        tx = transactions.get(0);
                        assertEquals(1, tx.getRefCount());
                    }
                    // close connection -> close statement -> close result set -> transaction refcount = 0 (and rolled back)
                    TestUtils.waitForCondition(() -> tx.getRefCount() == 0, NOOP, 100);

                }
            }
        }
    }
}
