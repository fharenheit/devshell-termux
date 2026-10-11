package com.termux.app.terminal.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.Closeable;
import java.sql.SQLException;
import java.util.List;

/**
 * An open connection to a database. Its methods block and are called on one background thread,
 * except {@link #cancel()}, which stops the running statement from another thread.
 */
public abstract class DbSession implements Closeable {

    /** Cells of results beyond this many characters in total are left out, to save memory. */
    static final long MAX_RESULT_CHARS = 64L * 1024 * 1024;
    /** A text or binary value longer than this is cut. */
    static final int MAX_VALUE_CHARS = 1024 * 1024;

    @NonNull protected final DbConnectionConfig mConfig;

    protected DbSession(@NonNull DbConnectionConfig config) {
        mConfig = config;
    }

    @NonNull
    public DbConnectionConfig getConfig() {
        return mConfig;
    }

    @NonNull
    public DbType getType() {
        return mConfig.type;
    }

    /** Like `PostgreSQL 16.4`. */
    @NonNull
    public abstract String getServerVersion();

    /**
     * Run a statement, fetching at most {@code maxRows} rows, and {@link QueryResult#hasMore} if
     * there are more. A timeout of 0 waits until it is done or {@link #cancel() cancelled}.
     */
    @NonNull
    public abstract QueryResult execute(@NonNull String sql, int maxRows, int timeoutSeconds) throws SQLException;

    /** Run a statement with parameters, like the ones made to change the rows of a table, and return the rows changed. */
    public abstract int executeUpdate(@NonNull String sql, @NonNull List<Object> parameters) throws SQLException;

    /** Stop the running statement, from another thread. */
    public abstract void cancel();

    public abstract boolean getAutoCommit() throws SQLException;

    public abstract void setAutoCommit(boolean autoCommit) throws SQLException;

    public abstract void commit() throws SQLException;

    public abstract void rollback() throws SQLException;

    /** Whether a transaction has changes that are not committed, as far as known. */
    public abstract boolean isInTransaction();

    /** Whether the connection still works. */
    public abstract boolean isValid();

    @Override
    public abstract void close();

    // Metadata

    /** The schemas, or the databases of a MySQL server. */
    @NonNull
    public abstract List<String> listSchemas() throws SQLException;

    /** The schema statements run in by default, or {@code null}. */
    @Nullable
    public abstract String getDefaultSchema() throws SQLException;

    @NonNull
    public abstract List<DbObjects.Table> listTables(@Nullable String schema) throws SQLException;

    @NonNull
    public abstract List<DbObjects.Column> listColumns(@Nullable String schema, @NonNull String table) throws SQLException;

    /** The indexes of a table, each as its definition or a description. */
    @NonNull
    public abstract List<String> listIndexes(@Nullable String schema, @NonNull String table) throws SQLException;

    /** The foreign keys of a table, each as a description like `customer_id → customers(id)`. */
    @NonNull
    public abstract List<String> listForeignKeys(@Nullable String schema, @NonNull String table) throws SQLException;

    /** The statements that create a table or view, like `CREATE TABLE ...`. */
    @NonNull
    public abstract String getDdl(@NonNull DbObjects.Table table) throws SQLException;

    /** The statement that shows how the database runs a statement. */
    @NonNull
    public String getExplainStatement(@NonNull String sql) {
        return "EXPLAIN " + sql;
    }

    /** The number of rows of a table. */
    public long countRows(@NonNull DbObjects.Table table) throws SQLException {
        QueryResult result = execute("SELECT COUNT(*) FROM " + getType().quote(table.schema, table.name), 1, 0);
        if (result.rows.isEmpty() || result.rows.get(0)[0] == null) return 0;
        return Long.parseLong(result.rows.get(0)[0].toString());
    }

    /** Receives the rows of a statement one by one. */
    public interface RowSink {
        void columns(@NonNull List<QueryResult.Column> columns) throws java.io.IOException;

        /** Take a row, or return {@code false} to stop. The array is reused for the next row. */
        boolean row(@NonNull Object[] row) throws java.io.IOException;
    }

    /**
     * Run a query and pass all its rows, with whole values, to a sink without keeping them, to
     * export a large table. Returns the number of rows passed.
     */
    public abstract long stream(@NonNull String sql, @NonNull RowSink sink) throws SQLException, java.io.IOException;

}
