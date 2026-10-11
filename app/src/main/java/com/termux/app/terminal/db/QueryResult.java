package com.termux.app.terminal.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The result of a statement: the rows it returned, or the number of rows it changed. Values are
 * {@code null}, a {@link String}, or a {@code byte[]} for binary data.
 */
public final class QueryResult {

    /** A column of the rows. */
    public static final class Column {
        @NonNull public final String name;
        /** The type name of the database, like `int4` or `varchar`. */
        @NonNull public final String typeName;
        /** The {@link java.sql.Types} value, or {@link java.sql.Types#OTHER} if not known. */
        public final int sqlType;

        public Column(@NonNull String name, @NonNull String typeName, int sqlType) {
            this.name = name;
            this.typeName = typeName;
            this.sqlType = sqlType;
        }

        public boolean isNumeric() {
            switch (sqlType) {
                case java.sql.Types.TINYINT: case java.sql.Types.SMALLINT: case java.sql.Types.INTEGER:
                case java.sql.Types.BIGINT: case java.sql.Types.FLOAT: case java.sql.Types.REAL:
                case java.sql.Types.DOUBLE: case java.sql.Types.NUMERIC: case java.sql.Types.DECIMAL:
                    return true;
                default:
                    return false;
            }
        }
    }

    @NonNull public final String sql;
    @NonNull public final List<Column> columns;
    @NonNull public final List<Object[]> rows;
    /** The number of rows changed, or -1 if the statement returned rows. */
    public final long updateCount;
    public long elapsedMs;
    /** Whether there are more rows than were fetched. */
    public boolean hasMore;
    /** Whether rows were left out to save memory. */
    public boolean truncated;
    /** Messages of the database, like warnings or notices, or {@code null}. */
    @Nullable public String message;

    public QueryResult(@NonNull String sql, @NonNull List<Column> columns, @NonNull List<Object[]> rows, long updateCount) {
        this.sql = sql;
        this.columns = columns;
        this.rows = rows;
        this.updateCount = updateCount;
    }

    /** A result of a statement that returned no rows. */
    @NonNull
    public static QueryResult ofUpdate(@NonNull String sql, long updateCount) {
        return new QueryResult(sql, new ArrayList<>(), new ArrayList<>(), updateCount);
    }

    public boolean hasRows() {
        return updateCount < 0;
    }

    /** The value as text, like it is shown: empty for null, and hex for binary data. */
    @NonNull
    public static String toText(@Nullable Object value) {
        if (value == null) return "";
        if (value instanceof byte[]) return toHex((byte[]) value);
        return value.toString();
    }

    @NonNull
    public static String toHex(@NonNull byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2 + 2).append("0x");
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }

}
