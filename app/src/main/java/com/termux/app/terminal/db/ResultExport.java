package com.termux.app.terminal.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/** Turns rows of a result into text: CSV or TSV, JSON, Markdown, or INSERT statements. */
public final class ResultExport {

    public static final int CSV = 0;
    public static final int TSV = 1;
    public static final int JSON = 2;
    public static final int MARKDOWN = 3;
    public static final int INSERT = 4;

    public static final String[] EXTENSIONS = {"csv", "tsv", "json", "md", "sql"};
    public static final String[] MIME_TYPES = {"text/csv", "text/tab-separated-values", "application/json", "text/markdown", "application/sql"};

    private ResultExport() {}

    /**
     * Format the rows and columns given by index, or all of them for {@code null}. The table
     * name is used for INSERT statements.
     */
    @NonNull
    public static String format(int format, @NonNull QueryResult result, @Nullable List<Integer> rows,
                                @Nullable List<Integer> columns, @NonNull DbType type, @Nullable String table) {
        int rowCount = rows != null ? rows.size() : result.rows.size();
        int columnCount = columns != null ? columns.size() : result.columns.size();
        List<QueryResult.Column> shownColumns = new java.util.ArrayList<>(columnCount);
        for (int c = 0; c < columnCount; c++) shownColumns.add(result.columns.get(column(columns, c)));
        java.io.StringWriter text = new java.io.StringWriter();
        try {
            Writer writer = new Writer(format, text, shownColumns, type, table);
            Object[] values = new Object[columnCount];
            for (int r = 0; r < rowCount; r++) {
                Object[] row = result.rows.get(row(rows, r));
                for (int c = 0; c < columnCount; c++) values[c] = row[column(columns, c)];
                writer.row(values);
            }
            writer.finish();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return text.toString();
    }

    /** Writes rows in a format one by one, to export a whole table without keeping it in memory. */
    public static final class Writer {
        private final int mFormat;
        private final java.io.Writer mOut;
        private final List<QueryResult.Column> mColumns;
        private final DbType mType;
        @Nullable private final String mTable;
        @Nullable private String mInsertNames;
        private long mRows;

        public Writer(int format, @NonNull java.io.Writer out, @NonNull List<QueryResult.Column> columns,
                      @NonNull DbType type, @Nullable String table) throws java.io.IOException {
            mFormat = format;
            mOut = out;
            mColumns = columns;
            mType = type;
            mTable = table;
            int count = columns.size();
            switch (format) {
                case CSV:
                case TSV: {
                    char separator = format == CSV ? ',' : '\t';
                    for (int c = 0; c < count; c++) {
                        if (c > 0) out.write(separator);
                        out.write(quoteCsv(columns.get(c).name, separator));
                    }
                    out.write("\r\n");
                    break;
                }
                case JSON:
                    out.write("[");
                    break;
                case MARKDOWN: {
                    StringBuilder text = new StringBuilder("|");
                    for (int c = 0; c < count; c++) text.append(' ').append(escapeMarkdown(columns.get(c).name)).append(" |");
                    text.append("\n|");
                    for (int c = 0; c < count; c++) text.append(columns.get(c).isNumeric() ? "---:|" : "---|");
                    text.append('\n');
                    out.write(text.toString());
                    break;
                }
                default: {
                    StringBuilder names = new StringBuilder();
                    for (int c = 0; c < count; c++) {
                        if (c > 0) names.append(", ");
                        names.append(type.quote(columns.get(c).name));
                    }
                    mInsertNames = names.toString();
                }
            }
        }

        public long getRowCount() {
            return mRows;
        }

        /** Write a row with a value for each column. */
        public void row(@NonNull Object[] row) throws java.io.IOException {
            int count = mColumns.size();
            StringBuilder text = new StringBuilder();
            switch (mFormat) {
                case CSV:
                case TSV: {
                    char separator = mFormat == CSV ? ',' : '\t';
                    for (int c = 0; c < count; c++) {
                        if (c > 0) text.append(separator);
                        if (row[c] != null) text.append(quoteCsv(QueryResult.toText(row[c]), separator));
                    }
                    text.append("\r\n");
                    break;
                }
                case JSON: {
                    JSONObject object = new JSONObject();
                    try {
                        for (int c = 0; c < count; c++) {
                            object.put(mColumns.get(c).name, row[c] == null ? JSONObject.NULL : jsonValue(row[c], mColumns.get(c)));
                        }
                        text.append(mRows > 0 ? ",\n  " : "\n  ").append(object.toString(2).replace("\n", "\n  "));
                    } catch (JSONException e) {
                        throw new java.io.IOException(e);
                    }
                    break;
                }
                case MARKDOWN: {
                    text.append('|');
                    for (int c = 0; c < count; c++) {
                        text.append(' ').append(row[c] == null ? "NULL" : escapeMarkdown(QueryResult.toText(row[c]))).append(" |");
                    }
                    text.append('\n');
                    break;
                }
                default: {
                    String name = mTable != null && !mTable.isEmpty() ? mTable : "table_name";
                    text.append("INSERT INTO ").append(name).append(" (").append(mInsertNames).append(") VALUES (");
                    for (int c = 0; c < count; c++) {
                        if (c > 0) text.append(", ");
                        text.append(toLiteral(row[c], mColumns.get(c), mType));
                    }
                    text.append(");\n");
                }
            }
            mOut.write(text.toString());
            mRows++;
        }

        /** Write the end of the format and flush. */
        public void finish() throws java.io.IOException {
            if (mFormat == JSON) mOut.write(mRows > 0 ? "\n]\n" : "]\n");
            mOut.flush();
        }
    }

    private static int row(@Nullable List<Integer> rows, int index) {
        return rows != null ? rows.get(index) : index;
    }

    private static int column(@Nullable List<Integer> columns, int index) {
        return columns != null ? columns.get(index) : index;
    }

    @NonNull
    static String quoteCsv(@NonNull String value, char separator) {
        if (value.indexOf(separator) < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    @NonNull
    private static Object jsonValue(@NonNull Object value, @NonNull QueryResult.Column column) {
        if (value instanceof byte[]) return QueryResult.toHex((byte[]) value);
        String text = value.toString();
        if (column.isNumeric()) {
            try {
                return text.contains(".") || text.contains("e") || text.contains("E") ? (Object) Double.parseDouble(text) : (Object) Long.parseLong(text);
            } catch (NumberFormatException ignored) {
            }
        }
        if (column.sqlType == java.sql.Types.BOOLEAN || column.sqlType == java.sql.Types.BIT) {
            if (text.equals("t") || text.equals("true")) return true;
            if (text.equals("f") || text.equals("false")) return false;
        }
        return text;
    }

    @NonNull
    private static String escapeMarkdown(@NonNull String text) {
        return text.replace("|", "\\|").replace("\r", "").replace("\n", "<br>");
    }

    /** A value as an SQL literal, like `NULL`, `42` or `'it''s'`. */
    @NonNull
    public static String toLiteral(@Nullable Object value, @Nullable QueryResult.Column column, @NonNull DbType type) {
        if (value == null) return "NULL";
        if (value instanceof byte[]) {
            String hex = QueryResult.toHex((byte[]) value).substring(2);
            if (type == DbType.POSTGRESQL) return "'\\x" + hex + "'";
            return "X'" + hex + "'";
        }
        String text = value.toString();
        if (column != null && column.isNumeric() && text.matches("-?[0-9]+(\\.[0-9]+)?([eE][-+]?[0-9]+)?")) return text;
        String escaped = text.replace("'", "''");
        if (type.isMysqlSyntax()) escaped = escaped.replace("\\", "\\\\");
        return "'" + escaped + "'";
    }

}
