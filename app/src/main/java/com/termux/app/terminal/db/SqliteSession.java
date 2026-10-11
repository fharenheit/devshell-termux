package com.termux.app.terminal.db;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A session of an SQLite database file, with the SQLite of Android. Transactions are made with the
 * transaction methods of Android, also for BEGIN, COMMIT and ROLLBACK statements, which Android
 * would otherwise not know about.
 */
public final class SqliteSession extends DbSession {

    /** Statements that return rows. */
    private static final Set<String> QUERY_KEYWORDS = new HashSet<>(Arrays.asList("SELECT", "PRAGMA", "WITH", "VALUES", "EXPLAIN"));

    private final SQLiteDatabase mDatabase;
    @Nullable private volatile CancellationSignal mCancellation;
    private boolean mAutoCommit = true;
    private boolean mChanged;

    private SqliteSession(@NonNull DbConnectionConfig config, @NonNull SQLiteDatabase database) {
        super(config);
        mDatabase = database;
    }

    /** Open the database file of a connection, creating it if it does not exist unless read-only. */
    @NonNull
    public static SqliteSession open(@NonNull DbConnectionConfig config) throws SQLException {
        // Without localized collators, Android does not add its android_metadata table to the file.
        int flags = SQLiteDatabase.NO_LOCALIZED_COLLATORS |
            (config.readOnly ? SQLiteDatabase.OPEN_READONLY : SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.CREATE_IF_NECESSARY);
        try {
            return new SqliteSession(config, SQLiteDatabase.openDatabase(config.database, null, flags));
        } catch (SQLiteException e) {
            throw new SQLException(e.getMessage(), e);
        }
    }

    @NonNull
    @Override
    public String getServerVersion() {
        try (Cursor cursor = mDatabase.rawQuery("SELECT sqlite_version()", null)) {
            return cursor.moveToFirst() ? "SQLite " + cursor.getString(0) : "SQLite";
        } catch (SQLiteException e) {
            return "SQLite";
        }
    }

    @NonNull
    @Override
    public QueryResult execute(@NonNull String sql, int maxRows, int timeoutSeconds) throws SQLException {
        long startTime = System.nanoTime();
        String keyword = SqlSafety.firstKeyword(sql, getType());
        QueryResult result;
        try {
            switch (keyword) {
                case "BEGIN":
                    setAutoCommit(false);
                    result = QueryResult.ofUpdate(sql, 0);
                    break;
                case "COMMIT":
                case "END":
                    commit();
                    result = QueryResult.ofUpdate(sql, 0);
                    break;
                case "ROLLBACK":
                    if (sql.toUpperCase(java.util.Locale.ROOT).contains(" TO ")) {
                        mDatabase.execSQL(sql);
                    } else {
                        rollback();
                    }
                    result = QueryResult.ofUpdate(sql, 0);
                    break;
                default:
                    result = QUERY_KEYWORDS.contains(keyword) ? query(sql, maxRows) : update(sql);
            }
        } catch (SQLiteException e) {
            throw new SQLException(e.getMessage(), e);
        } catch (OperationCanceledException e) {
            throw new SQLException("Cancelled", e);
        }
        result.elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
        return result;
    }

    @NonNull
    private QueryResult query(@NonNull String sql, int maxRows) {
        CancellationSignal cancellation = new CancellationSignal();
        mCancellation = cancellation;
        try (Cursor cursor = mDatabase.rawQueryWithFactory(null, sql, null, null, cancellation)) {
            int count = cursor.getColumnCount();
            List<Object[]> rows = new ArrayList<>();
            int[] types = new int[count];
            long chars = 0;
            boolean hasMore = false;
            boolean truncated = false;
            while (cursor.moveToNext()) {
                if (maxRows > 0 && rows.size() >= maxRows) {
                    hasMore = true;
                    break;
                }
                Object[] row = new Object[count];
                for (int i = 0; i < count; i++) {
                    int type = cursor.getType(i);
                    if (types[i] == 0 && type != Cursor.FIELD_TYPE_NULL) types[i] = type;
                    if (type == Cursor.FIELD_TYPE_NULL) {
                        row[i] = null;
                    } else if (type == Cursor.FIELD_TYPE_BLOB) {
                        byte[] bytes = cursor.getBlob(i);
                        if (bytes.length > MAX_VALUE_CHARS) bytes = Arrays.copyOf(bytes, MAX_VALUE_CHARS);
                        row[i] = bytes;
                        chars += bytes.length * 2L;
                    } else {
                        String text = cursor.getString(i);
                        if (text.length() > MAX_VALUE_CHARS) text = text.substring(0, MAX_VALUE_CHARS);
                        row[i] = text;
                        chars += text.length();
                    }
                }
                rows.add(row);
                if (chars > MAX_RESULT_CHARS) {
                    hasMore = truncated = true;
                    break;
                }
            }
            List<QueryResult.Column> columns = new ArrayList<>(count);
            for (int i = 0; i < count; i++) columns.add(new QueryResult.Column(cursor.getColumnName(i), getTypeName(types[i]), getSqlType(types[i])));
            QueryResult result = new QueryResult(sql, columns, rows, -1);
            result.hasMore = hasMore;
            result.truncated = truncated;
            return result;
        } finally {
            mCancellation = null;
        }
    }

    @NonNull
    private static String getTypeName(int cursorType) {
        switch (cursorType) {
            case Cursor.FIELD_TYPE_INTEGER: return "INTEGER";
            case Cursor.FIELD_TYPE_FLOAT: return "REAL";
            case Cursor.FIELD_TYPE_BLOB: return "BLOB";
            case Cursor.FIELD_TYPE_STRING: return "TEXT";
            default: return "";
        }
    }

    private static int getSqlType(int cursorType) {
        switch (cursorType) {
            case Cursor.FIELD_TYPE_INTEGER: return Types.BIGINT;
            case Cursor.FIELD_TYPE_FLOAT: return Types.DOUBLE;
            case Cursor.FIELD_TYPE_BLOB: return Types.BLOB;
            case Cursor.FIELD_TYPE_STRING: return Types.VARCHAR;
            default: return Types.OTHER;
        }
    }

    @NonNull
    private QueryResult update(@NonNull String sql) {
        mDatabase.execSQL(sql);
        if (!mAutoCommit) mChanged = true;
        return QueryResult.ofUpdate(sql, getChanges());
    }

    private long getChanges() {
        try (Cursor cursor = mDatabase.rawQuery("SELECT changes()", null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    @Override
    public int executeUpdate(@NonNull String sql, @NonNull List<Object> parameters) throws SQLException {
        try {
            mDatabase.execSQL(sql, parameters.toArray());
        } catch (SQLiteException e) {
            throw new SQLException(e.getMessage(), e);
        }
        if (!mAutoCommit) mChanged = true;
        return (int) getChanges();
    }

    @Override
    public void cancel() {
        CancellationSignal cancellation = mCancellation;
        if (cancellation != null) cancellation.cancel();
    }

    @Override
    public boolean getAutoCommit() {
        return mAutoCommit;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        if (autoCommit == mAutoCommit) return;
        if (autoCommit) {
            commit();
            endTransaction(true);
        } else {
            mDatabase.beginTransaction();
        }
        mAutoCommit = autoCommit;
    }

    /** End the transaction, keeping its changes if {@code successful}. */
    private void endTransaction(boolean successful) {
        if (!mDatabase.inTransaction()) return;
        if (successful) mDatabase.setTransactionSuccessful();
        mDatabase.endTransaction();
    }

    @Override
    public void commit() throws SQLException {
        if (mAutoCommit) return;
        try {
            endTransaction(true);
            mDatabase.beginTransaction();
        } catch (SQLiteException e) {
            throw new SQLException(e.getMessage(), e);
        }
        mChanged = false;
    }

    @Override
    public void rollback() throws SQLException {
        if (mAutoCommit) return;
        try {
            endTransaction(false);
            mDatabase.beginTransaction();
        } catch (SQLiteException e) {
            throw new SQLException(e.getMessage(), e);
        }
        mChanged = false;
    }

    @Override
    public boolean isInTransaction() {
        return mChanged;
    }

    @Override
    public boolean isValid() {
        return mDatabase.isOpen();
    }

    @Override
    public void close() {
        try {
            if (!mAutoCommit) endTransaction(false);
        } catch (SQLiteException ignored) {
        }
        mDatabase.close();
    }

    // Metadata

    @NonNull
    private List<String[]> rows(@NonNull String sql, @Nullable String... arguments) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (Cursor cursor = mDatabase.rawQuery(sql, arguments)) {
            while (cursor.moveToNext()) {
                String[] row = new String[cursor.getColumnCount()];
                for (int i = 0; i < row.length; i++) row[i] = cursor.isNull(i) ? null : cursor.getString(i);
                rows.add(row);
            }
        } catch (SQLiteException e) {
            throw new SQLException(e.getMessage(), e);
        }
        return rows;
    }

    @NonNull
    private String prefix(@Nullable String schema) {
        return schema == null || schema.isEmpty() ? "" : getType().quote(schema) + ".";
    }

    @NonNull
    @Override
    public List<String> listSchemas() throws SQLException {
        List<String> schemas = new ArrayList<>();
        for (String[] row : rows("PRAGMA database_list")) if (!"temp".equals(row[1])) schemas.add(row[1]);
        return schemas;
    }

    @Nullable
    @Override
    public String getDefaultSchema() {
        return "main";
    }

    @NonNull
    @Override
    public List<DbObjects.Table> listTables(@Nullable String schema) throws SQLException {
        List<DbObjects.Table> tables = new ArrayList<>();
        for (String[] row : rows("SELECT name, type FROM " + prefix(schema) + "sqlite_master " +
            "WHERE type IN ('table', 'view') AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' AND name != 'android_metadata' ORDER BY name"))
            tables.add(new DbObjects.Table(schema, row[0], row[1].toUpperCase(java.util.Locale.ROOT)));
        return tables;
    }

    @NonNull
    @Override
    public List<DbObjects.Column> listColumns(@Nullable String schema, @NonNull String table) throws SQLException {
        List<DbObjects.Column> columns = new ArrayList<>();
        for (String[] row : rows("PRAGMA " + prefix(schema) + "table_info(" + getType().quote(table) + ")")) {
            // cid, name, type, notnull, dflt_value, pk
            columns.add(new DbObjects.Column(row[1], row[2] != null ? row[2] : "", "0".equals(row[3]), row[4],
                row[5] != null ? Integer.parseInt(row[5]) : 0));
        }
        return columns;
    }

    @NonNull
    @Override
    public List<String> listIndexes(@Nullable String schema, @NonNull String table) throws SQLException {
        List<String> indexes = new ArrayList<>();
        for (String[] row : rows("SELECT name, sql FROM " + prefix(schema) + "sqlite_master WHERE type = 'index' AND tbl_name = ? ORDER BY name", table))
            indexes.add(row[1] != null ? row[1] : row[0]);
        return indexes;
    }

    @NonNull
    @Override
    public List<String> listForeignKeys(@Nullable String schema, @NonNull String table) throws SQLException {
        Map<String, String[]> keys = new LinkedHashMap<>();
        // id, seq, table, from, to, on_update, on_delete, match
        for (String[] row : rows("PRAGMA " + prefix(schema) + "foreign_key_list(" + getType().quote(table) + ")")) {
            String[] key = keys.get(row[0]);
            if (key == null) keys.put(row[0], new String[]{row[3], row[2], row[4] != null ? row[4] : ""});
            else {
                key[0] += ", " + row[3];
                key[2] += ", " + row[4];
            }
        }
        List<String> descriptions = new ArrayList<>();
        for (String[] key : keys.values()) descriptions.add(key[0] + " → " + key[1] + "(" + key[2] + ")");
        return descriptions;
    }

    @NonNull
    @Override
    public String getDdl(@NonNull DbObjects.Table table) throws SQLException {
        StringBuilder ddl = new StringBuilder();
        for (String[] row : rows("SELECT sql FROM " + prefix(table.schema) + "sqlite_master WHERE tbl_name = ? AND sql IS NOT NULL " +
            "ORDER BY CASE type WHEN 'table' THEN 0 WHEN 'view' THEN 0 ELSE 1 END, name", table.name)) {
            if (ddl.length() > 0) ddl.append('\n');
            ddl.append(row[0]).append(';');
        }
        return ddl.toString();
    }

    @NonNull
    @Override
    public String getExplainStatement(@NonNull String sql) {
        return "EXPLAIN QUERY PLAN " + sql;
    }

}
