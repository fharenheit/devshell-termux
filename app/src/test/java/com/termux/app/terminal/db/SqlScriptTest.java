package com.termux.app.terminal.db;

import com.termux.R;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class SqlScriptTest {

    private static String[] texts(String script, DbType type) {
        List<SqlScript.Statement> statements = SqlScript.split(script, type);
        String[] texts = new String[statements.size()];
        for (int i = 0; i < texts.length; i++) texts[i] = statements.get(i).text;
        return texts;
    }

    @Test
    public void testSplit() {
        Assert.assertArrayEquals(new String[]{"select 1", "select 2"}, texts("select 1;\n  select 2 ;\n", DbType.POSTGRESQL));
        Assert.assertArrayEquals(new String[]{"select ';' as a", "select 2"}, texts("select ';' as a; select 2", DbType.POSTGRESQL));
        Assert.assertArrayEquals(new String[]{"select 'it''s;'"}, texts("select 'it''s;'", DbType.POSTGRESQL));
        Assert.assertArrayEquals(new String[]{"select \"a;b\" from t"}, texts("select \"a;b\" from t", DbType.POSTGRESQL));
        // Comments only are not statements, and keep a ; inside.
        Assert.assertArrayEquals(new String[]{"select 1 -- a; b", "select 2"}, texts("-- just a comment;\nselect 1 -- a; b\n; /* x; */ select 2", DbType.POSTGRESQL));
        // Dollar quotes of PostgreSQL functions.
        String function = "create function f() returns int as $body$ begin; return 1; end; $body$ language plpgsql";
        Assert.assertArrayEquals(new String[]{function, "select f()"}, texts(function + "; select f()", DbType.POSTGRESQL));
        Assert.assertArrayEquals(new String[]{"select $1, $2"}, texts("select $1, $2", DbType.POSTGRESQL));
        // MySQL backslash escapes, backticks and # comments.
        Assert.assertArrayEquals(new String[]{"select 'a\\';b'", "select `x;y`", "select 3"},
            texts("select 'a\\';b'; select `x;y`;# c; d\nselect 3", DbType.MARIADB));
        Assert.assertEquals(0, texts("  ;; -- nothing", DbType.SQLITE).length);
    }

    @Test
    public void testStatementAtCursor() {
        String script = "select 1;\nselect 2;\n\nselect 3";
        Assert.assertEquals("select 1", SqlScript.at(script, 0, DbType.POSTGRESQL).text);
        Assert.assertEquals("select 1", SqlScript.at(script, 9, DbType.POSTGRESQL).text);
        Assert.assertEquals("select 2", SqlScript.at(script, 12, DbType.POSTGRESQL).text);
        // After a ; at the end of a line, the statement before.
        Assert.assertEquals("select 2", SqlScript.at(script, 19, DbType.POSTGRESQL).text);
        // On an empty line between statements, the next one.
        Assert.assertEquals("select 3", SqlScript.at(script, 20, DbType.POSTGRESQL).text);
        Assert.assertEquals("select 3", SqlScript.at(script, script.length(), DbType.POSTGRESQL).text);
        Assert.assertNull(SqlScript.at("  ", 1, DbType.POSTGRESQL));
    }

    @Test
    public void testWrite() {
        Assert.assertFalse(SqlSafety.isWrite("SELECT * FROM t", DbType.POSTGRESQL));
        Assert.assertFalse(SqlSafety.isWrite("  -- c\n(select 1)", DbType.POSTGRESQL));
        Assert.assertFalse(SqlSafety.isWrite("show create table t", DbType.MARIADB));
        Assert.assertFalse(SqlSafety.isWrite("explain select * from t", DbType.POSTGRESQL));
        Assert.assertFalse(SqlSafety.isWrite("select * from t for update", DbType.POSTGRESQL));
        Assert.assertFalse(SqlSafety.isWrite("select 'delete' from t", DbType.POSTGRESQL));
        Assert.assertFalse(SqlSafety.isWrite("pragma table_info(t)", DbType.SQLITE));
        Assert.assertTrue(SqlSafety.isWrite("pragma foreign_keys = on", DbType.SQLITE));
        Assert.assertTrue(SqlSafety.isWrite("insert into t values (1)", DbType.POSTGRESQL));
        Assert.assertTrue(SqlSafety.isWrite("with x as (delete from t returning *) select * from x", DbType.POSTGRESQL));
        Assert.assertTrue(SqlSafety.isWrite("explain analyze update t set a = 1", DbType.POSTGRESQL));
        Assert.assertTrue(SqlSafety.isWrite("select * into new_table from t", DbType.POSTGRESQL));
        Assert.assertTrue(SqlSafety.isWrite("create table x (a int)", DbType.SQLITE));
    }

    @Test
    public void testWarnings() {
        Assert.assertEquals(R.string.msg_db_confirm_delete_all, SqlSafety.getWarning("delete from t", DbType.POSTGRESQL));
        Assert.assertEquals(0, SqlSafety.getWarning("delete from t where id = 1", DbType.POSTGRESQL));
        // A WHERE of a subquery does not count.
        Assert.assertEquals(R.string.msg_db_confirm_update_all,
            SqlSafety.getWarning("update t set a = (select max(b) from u where u.c = 1)", DbType.POSTGRESQL));
        Assert.assertEquals(R.string.msg_db_confirm_drop, SqlSafety.getWarning("DROP TABLE t", DbType.MARIADB));
        Assert.assertEquals(R.string.msg_db_confirm_truncate, SqlSafety.getWarning("truncate t", DbType.POSTGRESQL));
        Assert.assertEquals(0, SqlSafety.getWarning("select 'drop table t'", DbType.POSTGRESQL));
    }

}
