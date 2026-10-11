package com.termux.app.terminal.db;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SqlEditingTest {

    @Test
    public void testFormat() {
        Assert.assertEquals("select a, b\nfrom t\nwhere a = 1\n  and b <= 'x, y'\norder by a",
            SqlFormatter.format("select a,b from t where a = 1 and b <= 'x, y' order by a", DbType.POSTGRESQL));
        Assert.assertEquals("SELECT count(*)\nFROM orders o\nLEFT JOIN customers c\n  ON c.id = o.customer_id\nGROUP BY c.name",
            SqlFormatter.format("SELECT count(*) FROM orders o LEFT JOIN customers c ON c.id = o.customer_id GROUP BY c.name", DbType.POSTGRESQL));
        // Subqueries are indented, and functions with FROM inside are kept on the line.
        Assert.assertEquals("select *\nfrom t\nwhere id in (select id\n  from u)\n  and extract(year from d) between 1 and 2",
            SqlFormatter.format("select * from t where id in (select id from u) and extract(year from d) between 1 and 2", DbType.POSTGRESQL));
        Assert.assertEquals("select 1\nunion all\nselect 2;\n\nupdate t\nset a = 1\nwhere b = 2;",
            SqlFormatter.format("select 1 union all select 2; update t set a = 1 where b = 2;", DbType.MARIADB));
        Assert.assertEquals("-- note\nselect 1",
            SqlFormatter.format("-- note\nselect 1", DbType.SQLITE));
    }

    @Test
    public void testAliases() {
        String script = "select o.id from public.orders o join customers as c on c.id = o.customer_id where o.";
        Assert.assertEquals("orders", SqlCompletion.findAliasedTable(script, "o", DbType.POSTGRESQL));
        Assert.assertEquals("customers", SqlCompletion.findAliasedTable(script, "c", DbType.POSTGRESQL));
        Assert.assertNull(SqlCompletion.findAliasedTable(script, "x", DbType.POSTGRESQL));
        Assert.assertNull(SqlCompletion.findAliasedTable("select * from t where a.", "where", DbType.POSTGRESQL));
        Assert.assertEquals("t", SqlCompletion.findAliasedTable("update `t` x set x.", "x", DbType.MARIADB));
    }

    @Test
    public void testCsv() {
        Assert.assertEquals(',', DbCsvImport.detectSeparator("a,b\n1,2"));
        Assert.assertEquals('\t', DbCsvImport.detectSeparator("a\tb\tc,d\n"));
        Assert.assertEquals(';', DbCsvImport.detectSeparator("\"x;y\",a;b;c"));
        List<String[]> rows = DbCsvImport.parse("﻿id,name\r\n1,\"a, \"\"b\"\"\nc\"\n\n2,\n3", ',');
        Assert.assertEquals(4, rows.size());
        Assert.assertArrayEquals(new String[]{"id", "name"}, rows.get(0));
        Assert.assertArrayEquals(new String[]{"1", "a, \"b\"\nc"}, rows.get(1));
        Assert.assertArrayEquals(new String[]{"2", ""}, rows.get(2));
        Assert.assertArrayEquals(new String[]{"3"}, rows.get(3));

        List<DbObjects.Column> columns = Arrays.asList(new DbObjects.Column("id", "int", false, null, 1),
            new DbObjects.Column("Name", "text", true, null, 0));
        Assert.assertArrayEquals(new int[]{0, 1}, DbCsvImport.mapColumns(columns, rows.get(0), true));
        Assert.assertArrayEquals(new int[]{-1, 1}, DbCsvImport.mapColumns(columns, new String[]{"x", "NAME"}, true));
        Assert.assertNull(DbCsvImport.mapColumns(columns, new String[]{"x"}, true));
        Assert.assertArrayEquals(new int[]{0, 1, -1}, DbCsvImport.mapColumns(columns, new String[]{"1", "2", "3"}, false));

        List<DbDataEditor.Change> inserts = DbCsvImport.getInserts(DbType.POSTGRESQL, new DbObjects.Table("s", "t", "TABLE"), columns,
            rows, new int[]{0, 1}, true, true);
        Assert.assertEquals(3, inserts.size());
        Assert.assertEquals("INSERT INTO \"s\".\"t\" (\"id\", \"Name\") VALUES (?, ?)", inserts.get(0).sql);
        Assert.assertEquals(Arrays.asList("2", null), inserts.get(1).parameters);
        Assert.assertEquals(Arrays.asList("3", null), inserts.get(2).parameters);
        Assert.assertEquals("CSV #4", inserts.get(2).preview);
        Assert.assertEquals(new ArrayList<>(Arrays.asList("2", "")),
            DbCsvImport.getInserts(DbType.POSTGRESQL, new DbObjects.Table("s", "t", "TABLE"), columns, rows, new int[]{0, 1}, true, false).get(1).parameters);
    }

    @Test
    public void testSqlitePath() {
        Assert.assertTrue(SqliteSession.getPath("~/a.db").endsWith("/home/a.db"));
        Assert.assertEquals("/sdcard/a.db", SqliteSession.getPath("/sdcard/a.db"));
        Assert.assertEquals("a~/b", SqliteSession.getPath("a~/b"));
    }

}
