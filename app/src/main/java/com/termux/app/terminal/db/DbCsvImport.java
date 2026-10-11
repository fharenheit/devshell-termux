package com.termux.app.terminal.db;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Imports rows into a table from CSV or TSV text, from a file or the clipboard. The columns are
 * matched to the table by the names of the first row, or else in order, and the rows are shown
 * before they are inserted in one transaction.
 */
final class DbCsvImport {

    static final long MAX_FILE_SIZE = 50L * 1024 * 1024;
    private static final int PREVIEW_ROWS = 5;

    private DbCsvImport() {}

    /** Ask for the file of the rows, or to use the clipboard. */
    static void start(@NonNull DbTabView view, @NonNull DbObjects.Table table) {
        Context context = view.getContext();
        EditText path = new EditText(context);
        path.setSingleLine();
        path.setText("~/");
        path.setSelection(2);
        path.setTypeface(Typeface.MONOSPACE);
        path.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = DbDialogs.dp(context, 20);
        form.setPadding(padding, padding / 2, padding, 0);
        TextView help = new TextView(context);
        help.setText(R.string.msg_db_import_file_help);
        form.addView(help);
        form.addView(path);
        new AlertDialog.Builder(view.getActivity())
            .setTitle(context.getString(R.string.title_db_import, table.name))
            .setView(form)
            .setPositiveButton(R.string.action_db_import_file, (d, which) -> readFile(view, table, path.getText().toString().trim()))
            .setNeutralButton(R.string.action_db_import_clipboard, (d, which) -> {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = clipboard != null ? clipboard.getPrimaryClip() : null;
                CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(context) : null;
                if (text == null || text.length() == 0) {
                    Toast.makeText(context, R.string.msg_db_clipboard_empty, Toast.LENGTH_SHORT).show();
                    return;
                }
                showPreview(view, table, text.toString());
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private static void readFile(@NonNull DbTabView view, @NonNull DbObjects.Table table, @NonNull String path) {
        File file = new File(SqliteSession.getPath(path));
        Context context = view.getContext();
        new Thread(() -> {
            String text = null;
            String error = null;
            try {
                if (!file.isFile()) throw new IOException(context.getString(R.string.msg_db_file_not_found, file.getPath()));
                if (file.length() > MAX_FILE_SIZE) throw new IOException(context.getString(R.string.msg_db_file_too_large, MAX_FILE_SIZE / 1024 / 1024));
                text = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                error = e.getMessage();
            }
            String finalText = text;
            String finalError = error;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (finalError != null) DbDialogs.showProgressError(view.getActivity(), finalError);
                else showPreview(view, table, finalText);
            });
        }, "DbCsvRead").start();
    }

    /** The separator of the text: the one of comma, tab and semicolon that the first line has most of. */
    static char detectSeparator(@NonNull String text) {
        int end = text.indexOf('\n');
        String line = end < 0 ? text : text.substring(0, end);
        char best = ',';
        int bestCount = 0;
        for (char separator : new char[]{',', '\t', ';'}) {
            int count = 0;
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') quoted = !quoted;
                else if (c == separator && !quoted) count++;
            }
            if (count > bestCount) {
                best = separator;
                bestCount = count;
            }
        }
        return best;
    }

    /** The rows of CSV text, where quoted values can have separators, line breaks and doubled quotes. */
    @NonNull
    static List<String[]> parse(@NonNull String text, char separator) {
        List<String[]> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        boolean wasQuoted = false;
        int i = text.startsWith("﻿") ? 1 : 0;
        int length = text.length();
        for (; i < length; i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < length && text.charAt(i + 1) == '"') {
                        value.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    value.append(c);
                }
            } else if (c == '"' && value.length() == 0 && !wasQuoted) {
                quoted = wasQuoted = true;
            } else if (c == separator) {
                row.add(value.toString());
                value.setLength(0);
                wasQuoted = false;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n') i++;
                row.add(value.toString());
                value.setLength(0);
                wasQuoted = false;
                // Skip empty lines.
                if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row.toArray(new String[0]));
                row.clear();
            } else {
                value.append(c);
            }
        }
        if (value.length() > 0 || wasQuoted || !row.isEmpty()) {
            row.add(value.toString());
            rows.add(row.toArray(new String[0]));
        }
        return rows;
    }

    /**
     * The column of the table for each column of the text, or -1 to skip it: by name for a
     * header, or else in order. Returns {@code null} if no column matches.
     */
    @Nullable
    static int[] mapColumns(@NonNull List<DbObjects.Column> columns, @NonNull String[] first, boolean header) {
        int[] map = new int[first.length];
        boolean any = false;
        for (int c = 0; c < first.length; c++) {
            map[c] = -1;
            if (header) {
                for (int t = 0; t < columns.size(); t++) {
                    if (columns.get(t).name.equalsIgnoreCase(first[c].trim())) map[c] = t;
                }
            } else if (c < columns.size()) {
                map[c] = c;
            }
            if (map[c] >= 0) any = true;
        }
        return any ? map : null;
    }

    /** The INSERT statements of the rows. */
    @NonNull
    static List<DbDataEditor.Change> getInserts(@NonNull DbType type, @NonNull DbObjects.Table table, @NonNull List<DbObjects.Column> columns,
                                                 @NonNull List<String[]> rows, int[] map, boolean header, boolean emptyAsNull) {
        StringBuilder names = new StringBuilder();
        StringBuilder marks = new StringBuilder();
        for (int c = 0; c < map.length; c++) {
            if (map[c] < 0) continue;
            if (names.length() > 0) {
                names.append(", ");
                marks.append(", ");
            }
            names.append(type.quote(columns.get(map[c]).name));
            marks.append('?');
        }
        String sql = "INSERT INTO " + type.quote(table.schema, table.name) + " (" + names + ") VALUES (" + marks + ")";
        List<DbDataEditor.Change> changes = new ArrayList<>();
        for (int r = header ? 1 : 0; r < rows.size(); r++) {
            String[] row = rows.get(r);
            List<Object> parameters = new ArrayList<>();
            for (int c = 0; c < map.length; c++) {
                if (map[c] < 0) continue;
                String value = c < row.length ? row[c] : null;
                parameters.add(value == null || emptyAsNull && value.isEmpty() ? null : value);
            }
            changes.add(new DbDataEditor.Change(sql, parameters, false, "CSV #" + (r + 1)));
        }
        return changes;
    }

    private static void showPreview(@NonNull DbTabView view, @NonNull DbObjects.Table table, @NonNull String text) {
        DbTab tab = view.getTab();
        List<DbObjects.Column> columns = tab.columns.get(DbTab.key(table.schema, table.name));
        if (columns == null) {
            view.loadColumns(table, () -> showPreview(view, table, text));
            return;
        }
        Context context = view.getContext();
        List<String[]> rows = parse(text, detectSeparator(text));
        if (rows.isEmpty()) {
            Toast.makeText(context, R.string.msg_db_import_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        // The first row is a header if it names a column of the table.
        boolean header = mapColumns(columns, rows.get(0), true) != null;
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = DbDialogs.dp(context, 20);
        form.setPadding(padding, padding / 2, padding, 0);
        CheckBox headerBox = new CheckBox(context);
        headerBox.setText(R.string.db_import_header);
        headerBox.setChecked(header);
        form.addView(headerBox);
        CheckBox nullBox = new CheckBox(context);
        nullBox.setText(R.string.db_import_empty_null);
        nullBox.setChecked(true);
        form.addView(nullBox);
        TextView preview = new TextView(context);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        preview.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(preview);
        form.addView(scroll);
        Runnable updatePreview = () -> preview.setText(describe(context, columns, rows, headerBox.isChecked()));
        headerBox.setOnCheckedChangeListener((button, checked) -> updatePreview.run());
        updatePreview.run();
        new AlertDialog.Builder(view.getActivity())
            .setTitle(context.getString(R.string.title_db_import, table.name))
            .setView(form)
            .setPositiveButton(R.string.action_db_import, (d, which) -> {
                int[] map = mapColumns(columns, rows.get(0), headerBox.isChecked());
                if (map == null) {
                    Toast.makeText(context, R.string.msg_db_import_no_columns, Toast.LENGTH_LONG).show();
                    return;
                }
                run(view, getInserts(view.getType(), table, columns, rows, map, headerBox.isChecked(), nullBox.isChecked()));
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    @NonNull
    private static String describe(@NonNull Context context, @NonNull List<DbObjects.Column> columns, @NonNull List<String[]> rows, boolean header) {
        int[] map = mapColumns(columns, rows.get(0), header);
        StringBuilder text = new StringBuilder();
        int count = rows.size() - (header ? 1 : 0);
        text.append(context.getString(R.string.msg_db_import_rows, count)).append("\n\n");
        if (map == null) {
            text.append(context.getString(R.string.msg_db_import_no_columns));
            return text.toString();
        }
        String[] first = rows.get(0);
        for (int c = 0; c < map.length; c++) {
            String source = header ? first[c] : "#" + (c + 1);
            text.append(source).append(" → ").append(map[c] >= 0 ? columns.get(map[c]).name : context.getString(R.string.db_import_skipped)).append('\n');
        }
        text.append('\n');
        for (int r = header ? 1 : 0, shown = 0; r < rows.size() && shown < PREVIEW_ROWS; r++, shown++) {
            text.append(String.join(" | ", rows.get(r))).append('\n');
        }
        return text.toString();
    }

    private static void run(@NonNull DbTabView view, @NonNull List<DbDataEditor.Change> inserts) {
        DbTab tab = view.getTab();
        DbSession session = tab.getSession();
        if (session == null || tab.state != DbTab.READY) return;
        Context context = view.getActivity();
        tab.state = DbTab.RUNNING;
        view.update();
        tab.executor.execute(() -> {
            String error = DbDataEditor.applyChanges(session, inserts, context);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                tab.state = DbTab.READY;
                if (error != null) DbDialogs.showProgressError(view.getActivity(), error);
                else Toast.makeText(context, context.getString(R.string.msg_db_import_done, inserts.size()), Toast.LENGTH_LONG).show();
                view.refreshAfterWork();
            });
        });
    }

}
