package com.termux.app.terminal.db;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28})
public class SqliteSessionTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testSession() throws Exception {
        DbConnectionConfig config = new DbConnectionConfig();
        config.type = DbType.SQLITE;
        config.database = new File(folder.getRoot(), "test.db").getAbsolutePath();
        try (SqliteSession session = SqliteSession.open(config)) {
            Assert.assertTrue(session.getServerVersion().startsWith("SQLite"));
            for (SqlScript.Statement statement : SqlScript.split(
                "create table customers (id integer primary key, name text not null, data blob);" +
                "create table orders (id integer primary key, customer_id integer references customers(id), total real);" +
                "create index orders_customer on orders(customer_id);" +
                "create view big as select * from orders where total > 10;" +
                "insert into customers (name, data) values ('김철수', x'01ab');" +
                // One row at a time for the older SQLite of the tests.
                "insert into customers (name, data) values ('Alice', null);", DbType.SQLITE))
                try {
                    session.execute(statement.text, 0, 0);
                } catch (java.sql.SQLException e) {
                    throw new AssertionError(statement.text, e);
                }

            QueryResult result = session.execute("select * from customers order by id", 1, 0);
            Assert.assertEquals(1, result.rows.size());
            Assert.assertTrue(result.hasMore);
            Assert.assertEquals("김철수", result.rows.get(0)[1]);
            Assert.assertArrayEquals(new byte[]{1, (byte) 0xab}, (byte[]) result.rows.get(0)[2]);
            Assert.assertTrue(result.columns.get(0).isNumeric());
            Assert.assertEquals(2, session.execute("update customers set name = name", 0, 0).updateCount);

            Assert.assertEquals(Arrays.asList("main"), session.listSchemas());
            Assert.assertEquals(3, session.listTables("main").size());
            Assert.assertEquals(1, session.listColumns("main", "customers").get(0).primaryKey);
            Assert.assertEquals("customer_id → customers(id)", session.listForeignKeys("main", "orders").get(0));
            Assert.assertTrue(session.listIndexes("main", "orders").get(0).startsWith("CREATE INDEX orders_customer"));
            Assert.assertTrue(session.getDdl(new DbObjects.Table("main", "orders", "TABLE")).contains("CREATE INDEX"));
            Assert.assertEquals(2, session.countRows(new DbObjects.Table("main", "customers", "TABLE")));

            // Transactions, also with BEGIN and ROLLBACK statements.
            session.execute("begin", 0, 0);
            session.execute("delete from customers", 0, 0);
            Assert.assertTrue(session.isInTransaction());
            session.execute("rollback", 0, 0);
            Assert.assertEquals(2, session.countRows(new DbObjects.Table("main", "customers", "TABLE")));
            session.setAutoCommit(true);
            Assert.assertEquals(1, session.executeUpdate("update customers set name = ? where id = ?", Arrays.asList("B", "2")));
            Assert.assertEquals("B", session.execute("select name from customers where id = 2", 0, 0).rows.get(0)[0]);
        }
    }

}
