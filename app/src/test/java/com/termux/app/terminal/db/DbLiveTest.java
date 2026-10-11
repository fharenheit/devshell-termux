package com.termux.app.terminal.db;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests against real database servers, given as `host:port:database:user:password` in the
 * environment variables `DEVSHELL_TEST_PG` and `DEVSHELL_TEST_MARIADB`, with the sample tables of
 * `custom/docs/db-client.md`. Skipped without them.
 */
public class DbLiveTest {

    private static DbConnectionConfig config(String variable, DbType type) {
        String value = System.getenv(variable);
        Assume.assumeTrue(variable + " is not set", value != null && !value.isEmpty());
        String[] parts = value.split(":", 5);
        DbConnectionConfig config = new DbConnectionConfig();
        config.type = type;
        config.host = parts[0];
        config.port = Integer.parseInt(parts[1]);
        config.database = parts[2];
        config.user = parts[3];
        config.parameters = parts[4];
        return config;
    }

    private static JdbcSession connect(DbConnectionConfig config) throws SQLException {
        String password = config.parameters;
        config.parameters = "";
        return JdbcSession.connect(config, password, config.host, config.getPort(), 10);
    }

    private static List<String> tableNames(DbSession session, String schema) throws SQLException {
        List<String> names = new ArrayList<>();
        for (DbObjects.Table table : session.listTables(schema)) names.add(table.name + ":" + table.isView());
        Collections.sort(names);
        return names;
    }

    private void checkCommon(DbSession session, String schema) throws Exception {
        Assert.assertTrue(session.isValid());
        Assert.assertTrue(session.listSchemas().contains(schema));
        Assert.assertEquals(schema, session.getDefaultSchema());
        Assert.assertTrue(tableNames(session, schema).containsAll(java.util.Arrays.asList("customers:false", "orders:false")));

        List<DbObjects.Column> columns = session.listColumns(schema, "customers");
        Assert.assertEquals("id", columns.get(0).name);
        Assert.assertEquals(1, columns.get(0).primaryKey);
        Assert.assertFalse(columns.get(1).nullable);
        Assert.assertEquals(0, columns.get(1).primaryKey);
        Assert.assertTrue(session.listForeignKeys(schema, "orders").get(0).startsWith("customer_id → customers(id)"));
        Assert.assertFalse(session.listIndexes(schema, "customers").isEmpty());
        String ddl = session.getDdl(new DbObjects.Table(schema, "customers", "TABLE"));
        Assert.assertTrue(ddl, ddl.startsWith("CREATE TABLE"));
        Assert.assertTrue(ddl, ddl.contains("email"));

        // Rows, with a limit, and unicode.
        QueryResult result = session.execute("select id, name from customers order by id", 1, 0);
        Assert.assertEquals(1, result.rows.size());
        Assert.assertTrue(result.hasMore);
        Assert.assertEquals("김철수", result.rows.get(0)[1]);
        Assert.assertEquals("name", result.columns.get(1).name);
        Assert.assertTrue(result.columns.get(0).isNumeric());
        Assert.assertEquals(2, session.countRows(new DbObjects.Table(schema, "customers", "TABLE")) >= 2 ? 2 : -1);

        // A transaction that is rolled back.
        session.setAutoCommit(false);
        QueryResult update = session.execute("update customers set note = 'x' where id = 1", 0, 0);
        Assert.assertEquals(1, update.updateCount);
        Assert.assertTrue(session.isInTransaction());
        session.rollback();
        Assert.assertFalse(session.isInTransaction());
        session.setAutoCommit(true);
        Assert.assertNotEquals("x", session.execute("select note from customers where id = 1", 0, 0).rows.get(0)[0]);

        // A statement with parameters, like the data editor makes.
        session.setAutoCommit(false);
        Assert.assertEquals(1, session.executeUpdate("update customers set note = ? where id = ?", java.util.Arrays.asList("p", "2")));
        Assert.assertEquals("p", session.execute("select note from customers where id = 2", 0, 0).rows.get(0)[0]);
        session.rollback();
        session.setAutoCommit(true);

        // Rows passed one by one, as a table is exported.
        long[] streamed = {0};
        long count = session.stream("select * from orders", new DbSession.RowSink() {
            @Override
            public void columns(List<QueryResult.Column> columns) {
                Assert.assertEquals("id", columns.get(0).name);
            }

            @Override
            public boolean row(Object[] row) {
                streamed[0]++;
                return true;
            }
        });
        long orders = Long.parseLong(session.execute("select count(*) from orders", 0, 0).rows.get(0)[0].toString());
        Assert.assertTrue(orders > 1);
        Assert.assertEquals(orders, count);
        Assert.assertEquals(orders, streamed[0]);
        Assert.assertTrue(session.getAutoCommit());
        java.io.StringWriter csv = new java.io.StringWriter();
        session.stream("select id, name from customers where id = 1", new DbSession.RowSink() {
            ResultExport.Writer writer;

            @Override
            public void columns(List<QueryResult.Column> columns) throws java.io.IOException {
                writer = new ResultExport.Writer(ResultExport.CSV, csv, columns, session.getType(), null);
            }

            @Override
            public boolean row(Object[] row) throws java.io.IOException {
                writer.row(row);
                writer.finish();
                return true;
            }
        });
        Assert.assertEquals("id,name\r\n1,김철수\r\n", csv.toString());

        // Changes of the data editor, in a transaction that a missing row undoes.
        Object note = session.execute("select note from customers where id = 1", 0, 0).rows.get(0)[0];
        List<DbDataEditor.Change> changes = new ArrayList<>();
        changes.add(new DbDataEditor.Change("update customers set note = ? where id = ?", java.util.Arrays.asList("edited", "1"), true, "1"));
        changes.add(new DbDataEditor.Change("update customers set note = ? where id = ?", java.util.Arrays.asList("edited", "999999"), true, "999999"));
        String error = DbDataEditor.applyChanges(session, changes, "missing %s", Throwable::getMessage);
        Assert.assertEquals("missing 999999", error);
        Assert.assertEquals(note, session.execute("select note from customers where id = 1", 0, 0).rows.get(0)[0]);
        Assert.assertTrue(session.getAutoCommit());
        changes.remove(1);
        Assert.assertNull(DbDataEditor.applyChanges(session, changes, "missing %s", Throwable::getMessage));
        Assert.assertEquals("edited", session.execute("select note from customers where id = 1", 0, 0).rows.get(0)[0]);
        // In an open transaction, a failure undoes only the changes, up to a savepoint.
        session.setAutoCommit(false);
        session.execute("update customers set note = 'kept' where id = 2", 0, 0);
        changes.clear();
        changes.add(new DbDataEditor.Change("update customers set note = ? where id = ?", java.util.Arrays.asList(note, "1"), true, "1"));
        changes.add(new DbDataEditor.Change("insert into customers (id, name) values (?, ?)", java.util.Arrays.asList("1", "dup"), false, "CSV #2"));
        error = DbDataEditor.applyChanges(session, changes, "missing %s", Throwable::getMessage);
        Assert.assertNotNull(error);
        Assert.assertTrue(error, error.endsWith("CSV #2"));
        Assert.assertEquals("edited", session.execute("select note from customers where id = 1", 0, 0).rows.get(0)[0]);
        Assert.assertEquals("kept", session.execute("select note from customers where id = 2", 0, 0).rows.get(0)[0]);
        session.rollback();
        session.setAutoCommit(true);
        changes.remove(1);
        Assert.assertNull(DbDataEditor.applyChanges(session, changes, "missing %s", Throwable::getMessage));
        Assert.assertEquals(note, session.execute("select note from customers where id = 1", 0, 0).rows.get(0)[0]);

        try {
            session.execute("select * from no_such_table", 0, 0);
            Assert.fail();
        } catch (SQLException expected) {
            Assert.assertTrue(session.isValid());
        }
    }

    private void checkCancel(DbSession session, String sleep) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                session.execute(sleep, 0, 0);
            } catch (Throwable e) {
                error.set(e);
            }
        });
        long start = System.currentTimeMillis();
        thread.start();
        Thread.sleep(1000);
        session.cancel();
        thread.join(10000);
        Assert.assertFalse(thread.isAlive());
        Assert.assertTrue(System.currentTimeMillis() - start < 9000);
        Assert.assertTrue(String.valueOf(error.get()), error.get() instanceof SQLException);
    }

    @Test
    public void testPostgresql() throws Exception {
        try (JdbcSession session = connect(config("DEVSHELL_TEST_PG", DbType.POSTGRESQL))) {
            Assert.assertTrue(session.getServerVersion(), session.getServerVersion().startsWith("PostgreSQL"));
            checkCommon(session, "public");
            Assert.assertTrue(tableNames(session, "public").contains("big_orders:true"));
            String viewDdl = session.getDdl(new DbObjects.Table("public", "big_orders", "VIEW"));
            Assert.assertTrue(viewDdl, viewDdl.startsWith("CREATE VIEW \"public\".\"big_orders\" AS"));
            Assert.assertEquals("numeric(10,2)", session.listColumns("public", "orders").get(2).type);
            QueryResult binary = session.execute("select '\\x01ab'::bytea, null::text, '{\"a\":1}'::jsonb", 0, 0);
            Assert.assertArrayEquals(new byte[]{1, (byte) 0xab}, (byte[]) binary.rows.get(0)[0]);
            Assert.assertNull(binary.rows.get(0)[1]);
            Assert.assertEquals("{\"a\": 1}", binary.rows.get(0)[2]);
            QueryResult many = session.execute("select * from orders", 200, 0);
            Assert.assertEquals(200, many.rows.size());
            Assert.assertTrue(many.hasMore);
            QueryResult notice = session.execute("do $$ begin raise notice 'hello; world'; end $$", 0, 0);
            Assert.assertEquals("hello; world", notice.message);
            checkCancel(session, "select pg_sleep(30)");
        }
    }

    @Test
    public void testMariadb() throws Exception {
        try (JdbcSession session = connect(config("DEVSHELL_TEST_MARIADB", DbType.MARIADB))) {
            Assert.assertTrue(session.getServerVersion(), session.getServerVersion().contains("MariaDB") || session.getServerVersion().contains("MySQL"));
            checkCommon(session, "shop");
            Assert.assertEquals("decimal(10,2)", session.listColumns("shop", "orders").get(2).type);
            checkCancel(session, "select sleep(30)");
        }
    }

}
