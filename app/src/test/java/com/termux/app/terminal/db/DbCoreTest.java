package com.termux.app.terminal.db;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28})
public class DbCoreTest {

    @Test
    public void testConfigJson() throws Exception {
        DbConnectionConfig config = new DbConnectionConfig();
        config.name = "운영 DB";
        config.type = DbType.MARIADB;
        config.host = "db.example.com";
        config.port = 3307;
        config.database = "shop";
        config.user = "tester";
        config.ssl = DbConnectionConfig.SSL_VERIFY;
        config.parameters = "socketTimeout=5000\n# comment\nbad line\n";
        config.sshHost = "bastion";
        config.color = 1;
        config.readOnly = true;
        DbConnectionConfig copy = DbConnectionConfig.fromJson(config.toJson());
        Assert.assertEquals(config.id, copy.id);
        Assert.assertEquals(DbType.MARIADB, copy.type);
        Assert.assertEquals("tester@db.example.com:3307/shop", copy.getAddress());
        Assert.assertEquals("운영 DB", copy.getTitle());
        Assert.assertEquals(Collections.singletonMap("socketTimeout", "5000"), copy.getParameters());
        Assert.assertTrue(copy.readOnly);
        Assert.assertEquals("bastion", copy.sshHost);
        Assert.assertNotEquals(config.id, config.copy().id);
        Assert.assertEquals(0, config.validate());
        config.host = "";
        Assert.assertNotEquals(0, config.validate());
    }

    @Test
    public void testDriverSettings() {
        DbConnectionConfig config = new DbConnectionConfig();
        config.database = "shop";
        config.user = "tester";
        Assert.assertEquals("jdbc:postgresql://db:5432/shop", JdbcSession.getUrl(config, "db", 5432));
        Assert.assertEquals("jdbc:postgresql://[::1]:5432/shop", JdbcSession.getUrl(config, "::1", 5432));
        config.ssl = DbConnectionConfig.SSL_VERIFY;
        config.parameters = "sslmode=verify-ca";
        Properties properties = JdbcSession.getProperties(config, "pw", 15);
        Assert.assertEquals("pw", properties.getProperty("password"));
        // The parameters of the connection win.
        Assert.assertEquals("verify-ca", properties.getProperty("sslmode"));
        Assert.assertEquals(AndroidHostnameVerifier.class.getName(), properties.getProperty("sslhostnameverifier"));
        config.type = DbType.MARIADB;
        config.parameters = "";
        config.ssl = DbConnectionConfig.SSL_REQUIRE;
        properties = JdbcSession.getProperties(config, null, 15);
        Assert.assertEquals("jdbc:mariadb://db:3306/shop", JdbcSession.getUrl(config, "db", 3306));
        Assert.assertEquals("15000", properties.getProperty("connectTimeout"));
        Assert.assertEquals("true", properties.getProperty("trustServerCertificate"));
        Assert.assertNull(properties.getProperty("password"));
    }

    private static QueryResult sample() {
        List<QueryResult.Column> columns = Arrays.asList(
            new QueryResult.Column("id", "int4", Types.INTEGER),
            new QueryResult.Column("name", "text", Types.VARCHAR),
            new QueryResult.Column("data", "bytea", Types.BINARY));
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"1", "it's \"a\", b", new byte[]{1, (byte) 0xab}});
        rows.add(new Object[]{"2", null, null});
        return new QueryResult("select", columns, rows, -1);
    }

    @Test
    public void testExport() {
        QueryResult result = sample();
        Assert.assertEquals("id,name,data\r\n1,\"it's \"\"a\"\", b\",0x01ab\r\n2,,\r\n",
            ResultExport.format(ResultExport.CSV, result, null, null, DbType.POSTGRESQL, null));
        Assert.assertEquals("name\r\n\r\n", ResultExport.format(ResultExport.TSV, result, Collections.singletonList(1),
            Collections.singletonList(1), DbType.POSTGRESQL, null));
        Assert.assertEquals("INSERT INTO \"t\" (\"id\", \"name\", \"data\") VALUES (1, 'it''s \"a\", b', '\\x01ab');\n" +
                "INSERT INTO \"t\" (\"id\", \"name\", \"data\") VALUES (2, NULL, NULL);\n",
            ResultExport.format(ResultExport.INSERT, result, null, null, DbType.POSTGRESQL, "\"t\""));
        String json = ResultExport.format(ResultExport.JSON, result, null, null, DbType.POSTGRESQL, null);
        Assert.assertTrue(json, json.contains("\"id\": 1") && json.contains("\"name\": null"));
        String markdown = ResultExport.format(ResultExport.MARKDOWN, result, null, null, DbType.POSTGRESQL, null);
        Assert.assertTrue(markdown, markdown.startsWith("| id | name | data |\n|---:|---|---|\n"));
        Assert.assertEquals("'a\\\\b'", ResultExport.toLiteral("a\\b", null, DbType.MARIADB));
        Assert.assertEquals("X'01ab'", ResultExport.toLiteral(new byte[]{1, (byte) 0xab}, null, DbType.SQLITE));
    }

    @Test
    public void testHistory() {
        List<QueryHistory.Entry> entries = new ArrayList<>();
        for (int i = 0; i < QueryHistory.MAX_HISTORY + 5; i++)
            QueryHistory.addTo(entries, new QueryHistory.Entry("c", "select " + i, "", i, 1, 1));
        Assert.assertEquals(QueryHistory.MAX_HISTORY, entries.size());
        QueryHistory.addTo(entries, new QueryHistory.Entry("c", "select 10", "", 999, 1, 1));
        Assert.assertEquals("select 10", entries.get(0).sql);
        Assert.assertEquals(QueryHistory.MAX_HISTORY, entries.size());
        entries.add(new QueryHistory.Entry("", "saved", "name", 0, 0, 0));
        entries.add(new QueryHistory.Entry("other", "x", "", 0, 0, 0));
        Assert.assertEquals(QueryHistory.MAX_HISTORY + 1, QueryHistory.filter(entries, "c").size());
    }

}
