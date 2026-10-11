package com.termux.app.terminal.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
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
        StringBuilder text = new StringBuilder();
        switch (format) {
            case CSV:
            case TSV: {
                char separator = format == CSV ? ',' : '\t';
                for (int c = 0; c < columnCount; c++) {
                    if (c > 0) text.append(separator);
                    text.append(quoteCsv(result.columns.get(column(columns, c)).name, separator));
                }
                text.append("\r\n");
                for (int r = 0; r < rowCount; r++) {
                    Object[] row = result.rows.get(row(rows, r));
                    for (int c = 0; c < columnCount; c++) {
                        if (c > 0) text.append(separator);
                        Object value = row[column(columns, c)];
                        if (value != null) text.append(quoteCsv(QueryResult.toText(value), separator));
                    }
                    text.append("\r\n");
                }
                return text.toString();
            }
            case JSON: {
                JSONArray array = new JSONArray();
                try {
                    for (int r = 0; r < rowCount; r++) {
                        Object[] row = result.rows.get(row(rows, r));
                        JSONObject object = new JSONObject();
                        for (int c = 0; c < columnCount; c++) {
                            int index = column(columns, c);
                            Object value = row[index];
                            object.put(result.columns.get(index).name, value == null ? JSONObject.NULL : jsonValue(value, result.columns.get(index)));
                        }
                        array.put(object);
                    }
                    return array.toString(2);
                } catch (JSONException e) {
                    throw new IllegalStateException(e);
                }
            }
            case MARKDOWN: {
                text.append('|');
                for (int c = 0; c < columnCount; c++) text.append(' ').append(escapeMarkdown(result.columns.get(column(columns, c)).name)).append(" |");
                text.append("\n|");
                for (int c = 0; c < columnCount; c++) text.append(result.columns.get(column(columns, c)).isNumeric() ? "---:|" : "---|");
                text.append('\n');
                for (int r = 0; r < rowCount; r++) {
                    Object[] row = result.rows.get(row(rows, r));
                    text.append('|');
                    for (int c = 0; c < columnCount; c++) {
                        Object value = row[column(columns, c)];
                        text.append(' ').append(value == null ? "NULL" : escapeMarkdown(QueryResult.toText(value))).append(" |");
                    }
                    text.append('\n');
                }
                return text.toString();
            }
            default: {
                String name = table != null && !table.isEmpty() ? table : "table_name";
                StringBuilder names = new StringBuilder();
                for (int c = 0; c < columnCount; c++) {
                    if (c > 0) names.append(", ");
                    names.append(type.quote(result.columns.get(column(columns, c)).name));
                }
                for (int r = 0; r < rowCount; r++) {
                    Object[] row = result.rows.get(row(rows, r));
                    text.append("INSERT INTO ").append(name).append(" (").append(names).append(") VALUES (");
                    for (int c = 0; c < columnCount; c++) {
                        if (c > 0) text.append(", ");
                        int index = column(columns, c);
                        text.append(toLiteral(row[index], result.columns.get(index), type));
                    }
                    text.append(");\n");
                }
                return text.toString();
            }
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
