package com.termux.app.terminal.db;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Edits the rows of a table with a primary key in the results grid: values are changed, rows
 * added and deleted, and the changes are kept until they are applied as UPDATE, INSERT and
 * DELETE statements by primary key, shown first, in one transaction. A row that another
 * change has meanwhile updated or deleted makes all the changes fail.
 */
final class DbDataEditor implements ResultGridView.Marker {

    private static final int INSERTED_COLOR = 0xFF1B3A1F;
    private static final int DELETED_COLOR = 0xFF4A1C1C;
    private static final int CHANGED_COLOR = 0xFF4A4418;
    private static final String SAVEPOINT = "devshell_edit";

    private final DbTab mTab;
    final DbObjects.Table table;
    private final List<DbObjects.Column> mColumns;
    @NonNull String where = "";
    @NonNull String orderBy = "";
    private int mLimit;

    @Nullable private QueryResult mResult;
    /** The column of the table for each column of the result, or -1. */
    private int[] mTableColumns = new int[0];
    private final List<Integer> mKeyColumns = new ArrayList<>();
    /** The values before the change of changed rows. */
    private final Map<Object[], Object[]> mOriginals = new IdentityHashMap<>();
    /** The columns given a value in added rows. */
    private final Map<Object[], boolean[]> mInserted = new IdentityHashMap<>();
    private final Map<Object[], Boolean> mDeleted = new IdentityHashMap<>();

    @Nullable private DbTabView mView;
    @Nullable private View mBar;
    @Nullable private TextView mTitle;
    @Nullable private Button mApplyButton;

    private DbDataEditor(@NonNull DbTab tab, @NonNull DbObjects.Table table, @NonNull List<DbObjects.Column> columns) {
        mTab = tab;
        this.table = table;
        mColumns = columns;
        mLimit = tab.pageSize;
    }

    /** Edit the rows of a table, if it has a primary key; otherwise show them. */
    static void open(@NonNull DbTabView view, @NonNull DbObjects.Table table) {
        DbTab tab = view.getTab();
        List<DbObjects.Column> columns = tab.columns.get(DbTab.key(table.schema, table.name));
        if (columns == null) {
            view.loadColumns(table, () -> open(view, table));
            return;
        }
        boolean hasKey = false;
        for (DbObjects.Column column : columns) if (column.primaryKey > 0) hasKey = true;
        if (!hasKey) {
            Toast.makeText(view.getContext(), R.string.msg_db_no_primary_key, Toast.LENGTH_LONG).show();
            List<String> statements = new ArrayList<>();
            statements.add("SELECT * FROM " + view.getType().quote(table.schema, table.name));
            view.runStatements(statements);
            return;
        }
        if (tab.dataEditor != null && tab.dataEditor.hasChanges()) {
            tab.dataEditor.confirmDiscard(view, () -> open(view, table));
            return;
        }
        DbDataEditor editor = new DbDataEditor(tab, table, columns);
        tab.dataEditor = editor;
        tab.editTable = table;
        editor.mView = view;
        editor.load();
    }

    boolean hasChanges() {
        return !mOriginals.isEmpty() || !mInserted.isEmpty() || !mDeleted.isEmpty();
    }

    int getChangeCount() {
        return mOriginals.size() + mInserted.size() + mDeleted.size();
    }

    /** Ask before the changes are thrown away. */
    void confirmDiscard(@NonNull DbTabView view, @NonNull Runnable discard) {
        new AlertDialog.Builder(view.getActivity())
            .setMessage(view.getContext().getString(R.string.msg_db_discard_changes, getChangeCount()))
            .setPositiveButton(R.string.action_db_discard_changes, (d, which) -> {
                clearChanges();
                discard.run();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void clearChanges() {
        mOriginals.clear();
        mInserted.clear();
        mDeleted.clear();
    }

    @NonNull
    private String getSelect(@NonNull DbType type) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(type.quote(table.schema, table.name));
        if (!where.trim().isEmpty()) sql.append(" WHERE ").append(where.trim());
        if (!orderBy.trim().isEmpty()) sql.append(" ORDER BY ").append(orderBy.trim());
        else {
            // Rows in the order of the key, so that they stay in place after changes.
            sql.append(" ORDER BY ");
            boolean first = true;
            for (DbObjects.Column column : mColumns) {
                if (column.primaryKey <= 0) continue;
                if (!first) sql.append(", ");
                sql.append(type.quote(column.name));
                first = false;
            }
        }
        return sql.toString();
    }

    /** Load the rows, throwing away the changes. */
    void load() {
        DbTabView view = mView;
        DbSession session = mTab.getSession();
        if (view == null || session == null || mTab.state != DbTab.READY) return;
        DbTab tab = mTab;
        String sql = getSelect(session.getType());
        int limit = mLimit;
        int timeout = tab.timeoutSeconds;
        tab.state = DbTab.RUNNING;
        view.update();
        tab.executor.execute(() -> {
            QueryResult result;
            try {
                result = session.execute(sql, limit, timeout);
            } catch (SQLException | RuntimeException e) {
                result = QueryResult.ofError(sql, DbTabView.describe(view.getContext(), e));
            }
            QueryResult loaded = result;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                tab.state = DbTab.READY;
                if (tab.dataEditor != this) return;
                if (loaded.error != null) {
                    if (mView != null) DbDialogs.showProgressError(mView.getActivity(), loaded.error);
                    if (mResult == null) close(false);
                } else {
                    setResult(loaded);
                }
                if (mView != null) mView.update();
            });
        });
    }

    /** Load more rows, if there are no changes. */
    void loadMore() {
        if (mView == null) return;
        if (hasChanges()) {
            Toast.makeText(mView.getContext(), R.string.msg_db_apply_first, Toast.LENGTH_SHORT).show();
            return;
        }
        mLimit += mTab.pageSize;
        load();
    }

    private void setResult(@NonNull QueryResult result) {
        mResult = result;
        clearChanges();
        mTableColumns = new int[result.columns.size()];
        mKeyColumns.clear();
        for (int c = 0; c < result.columns.size(); c++) {
            mTableColumns[c] = -1;
            for (int t = 0; t < mColumns.size(); t++) {
                if (mColumns.get(t).name.equals(result.columns.get(c).name)) mTableColumns[c] = t;
            }
            if (mTableColumns[c] >= 0 && mColumns.get(mTableColumns[c]).primaryKey > 0) mKeyColumns.add(c);
        }
        if (mView != null) mView.getGrid().setResult(result, false);
    }

    /** Leave editing, asking first if there are changes. */
    void close(boolean ask) {
        DbTabView view = mView;
        if (ask && hasChanges() && view != null) {
            confirmDiscard(view, () -> close(false));
            return;
        }
        if (mTab.dataEditor == this) {
            mTab.dataEditor = null;
            mTab.editTable = null;
        }
        if (view != null) {
            unbind(view);
            view.getGrid().setResult(null, false);
            view.update();
        }
    }

    /** Show the editor in a view of the tab. */
    void bind(@NonNull DbTabView view) {
        if (mView != view && mView != null) unbind(mView);
        mView = view;
        if (mBar == null || mBar.getParent() == null) {
            mBar = createBar(view);
            view.getEditBarHolder().removeAllViews();
            view.getEditBarHolder().addView(mBar);
        }
        view.getEditBarHolder().setVisibility(View.VISIBLE);
        ResultGridView grid = view.getGrid();
        grid.setMarker(this);
        if (mResult != null && grid.getResult() != mResult) grid.setResult(mResult, true);
        grid.setVisibility(mResult != null ? View.VISIBLE : View.GONE);
        view.getResultMessageFrame().setVisibility(mResult != null ? View.GONE : View.VISIBLE);
        if (mResult == null) view.getResultMessage().setText("");
        view.getFilterRow().setVisibility(View.VISIBLE);
        updateStatus();
    }

    private void unbind(@NonNull DbTabView view) {
        view.getGrid().setMarker(null);
        view.getEditBarHolder().removeAllViews();
        view.getEditBarHolder().setVisibility(View.GONE);
        mBar = null;
    }

    @NonNull
    private View createBar(@NonNull DbTabView view) {
        Context context = view.getContext();
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(0xFF1A2330);
        LinearLayout first = new LinearLayout(context);
        first.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText("✎ " + table.name);
        title.setTextColor(0xFFE0E0E0);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setPadding(DbDialogs.dp(context, 8), 0, DbDialogs.dp(context, 4), 0);
        title.setSingleLine();
        first.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        mTitle = title;
        addButton(first, "＋", R.string.action_db_add_row, v -> addRow(null));
        mApplyButton = addButton(first, "✓", R.string.action_db_apply_changes, v -> apply());
        addButton(first, "↺", R.string.action_db_discard_changes, v -> {
            if (hasChanges()) confirmDiscard(view, this::discard);
        });
        addButton(first, "⟳", R.string.action_db_refresh, v -> {
            if (hasChanges()) confirmDiscard(view, this::load);
            else load();
        });
        addButton(first, "✕", R.string.action_db_close_editor, v -> close(true));
        bar.addView(first);
        LinearLayout second = new LinearLayout(context);
        second.setGravity(Gravity.CENTER_VERTICAL);
        EditText whereText = createField(context, "WHERE", where);
        EditText orderText = createField(context, "ORDER BY", orderBy);
        second.addView(whereText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3));
        second.addView(orderText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2));
        Runnable apply = () -> {
            where = whereText.getText().toString();
            orderBy = orderText.getText().toString();
            if (hasChanges()) confirmDiscard(view, this::load);
            else load();
        };
        for (EditText field : new EditText[]{whereText, orderText}) {
            field.setOnEditorActionListener((v, actionId, event) -> {
                apply.run();
                return true;
            });
        }
        addButton(second, "▶", R.string.action_db_run, v -> apply.run());
        bar.addView(second);
        return bar;
    }

    @NonNull
    private static EditText createField(@NonNull Context context, @NonNull String hint, @NonNull String text) {
        EditText field = new EditText(context);
        field.setSingleLine();
        field.setHint(hint);
        field.setText(text);
        field.setTypeface(Typeface.MONOSPACE);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        return field;
    }

    @NonNull
    private static Button addButton(@NonNull LinearLayout bar, @NonNull String text, int descriptionId, @NonNull View.OnClickListener listener) {
        Context context = bar.getContext();
        Button button = new Button(context, null, android.R.attr.borderlessButtonStyle);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(0xFFE0E0E0);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setMinWidth(DbDialogs.dp(context, 40));
        button.setMinimumWidth(DbDialogs.dp(context, 40));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(0, 0, 0, 0);
        button.setFocusable(false);
        button.setContentDescription(context.getString(descriptionId));
        button.setOnClickListener(listener);
        bar.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, DbDialogs.dp(context, 38)));
        return button;
    }

    void updateStatus() {
        DbTabView view = mView;
        QueryResult result = mResult;
        if (view == null) return;
        Context context = view.getContext();
        if (mApplyButton != null) {
            mApplyButton.setEnabled(hasChanges());
            mApplyButton.setTextColor(hasChanges() ? 0xFF81C784 : 0xFF606060);
        }
        if (mTitle != null) mTitle.setText("✎ " + table.name + (hasChanges() ? "  " + context.getString(R.string.db_changes, getChangeCount()) : ""));
        if (result == null) {
            view.setResultStatus("", false);
            return;
        }
        String status = context.getString(R.string.db_rows, result.rows.size() - mInserted.size()) + (result.hasMore ? "+" : "");
        status += " · " + result.elapsedMs + " ms";
        view.setResultStatus(status, result.hasMore);
    }

    // Marker

    @Override
    public int getRowColor(int row) {
        if (mResult == null || row >= mResult.rows.size()) return 0;
        Object[] values = mResult.rows.get(row);
        if (mDeleted.containsKey(values)) return DELETED_COLOR;
        if (mInserted.containsKey(values)) return INSERTED_COLOR;
        return 0;
    }

    @Override
    public int getCellColor(int row, int column) {
        if (mResult == null || row >= mResult.rows.size()) return 0;
        Object[] values = mResult.rows.get(row);
        Object[] original = mOriginals.get(values);
        if (original != null && !Objects.equals(original[column], values[column]) && !(original[column] instanceof byte[])) return CHANGED_COLOR;
        boolean[] set = mInserted.get(values);
        if (set != null && set[column]) return CHANGED_COLOR;
        return 0;
    }

    // Changes

    void onCellDoubleTap(int row, int column) {
        editValue(row, column);
    }

    void onCellLongPress(int row, int column) {
        DbTabView view = mView;
        QueryResult result = mResult;
        if (view == null || result == null) return;
        Context context = view.getContext();
        Object[] values = result.rows.get(row);
        boolean deleted = mDeleted.containsKey(values);
        List<CharSequence> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (!deleted && mTableColumns[column] >= 0) {
            items.add(context.getString(R.string.action_db_edit_value));
            actions.add(() -> editValue(row, column));
            items.add(context.getString(R.string.action_db_set_null));
            actions.add(() -> setValue(row, column, null));
        }
        items.add(context.getString(R.string.action_db_view_value));
        actions.add(() -> DbDialogs.showText(view.getActivity(), result.columns.get(column).name, DbDialogs.formatValue(values[column]), null));
        items.add(context.getString(R.string.action_db_copy_value));
        actions.add(() -> DbDialogs.copy(context, values[column] == null ? "NULL" : QueryResult.toText(values[column])));
        addRowActions(row, items, actions);
        new AlertDialog.Builder(view.getActivity())
            .setTitle(result.columns.get(column).name)
            .setItems(items.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
            .show();
    }

    void onRowLongPress(int row) {
        DbTabView view = mView;
        QueryResult result = mResult;
        if (view == null || result == null) return;
        List<CharSequence> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        addRowActions(row, items, actions);
        items.add(view.getContext().getString(R.string.action_db_row_card));
        actions.add(() -> DbDialogs.showRowCard(view.getActivity(), result, row));
        new AlertDialog.Builder(view.getActivity())
            .setTitle(view.getContext().getString(R.string.title_db_row, row + 1))
            .setItems(items.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
            .show();
    }

    private void addRowActions(int row, @NonNull List<CharSequence> items, @NonNull List<Runnable> actions) {
        DbTabView view = mView;
        QueryResult result = mResult;
        if (view == null || result == null) return;
        Context context = view.getContext();
        Object[] values = result.rows.get(row);
        if (mDeleted.containsKey(values)) {
            items.add(context.getString(R.string.action_db_restore_row));
            actions.add(() -> {
                mDeleted.remove(values);
                changed();
            });
        } else {
            items.add(context.getString(R.string.action_db_delete_row));
            actions.add(() -> deleteRows(row));
        }
        items.add(context.getString(R.string.action_db_duplicate_row));
        actions.add(() -> addRow(values));
    }

    /** Delete a row, or the rows selected with their numbers if it is one of them. */
    void deleteRows(int row) {
        DbTabView view = mView;
        QueryResult result = mResult;
        if (view == null || result == null) return;
        List<Integer> rows = view.getGrid().getSelectedRows();
        if (!rows.contains(row)) {
            rows = new ArrayList<>();
            rows.add(row);
        }
        List<Object[]> removed = new ArrayList<>();
        for (int r : rows) {
            Object[] values = result.rows.get(r);
            if (mInserted.remove(values) != null) removed.add(values);
            else mDeleted.put(values, Boolean.TRUE);
        }
        // Added rows are just removed.
        for (Object[] values : removed) result.rows.remove(values);
        if (!removed.isEmpty()) view.getGrid().clearSelection();
        changed();
    }

    /** Add a row, empty or with the values of another one except the key with a default value. */
    void addRow(@Nullable Object[] copy) {
        DbTabView view = mView;
        QueryResult result = mResult;
        if (view == null || result == null) return;
        int count = result.columns.size();
        Object[] values = new Object[count];
        boolean[] set = new boolean[count];
        if (copy != null) {
            for (int c = 0; c < count; c++) {
                int t = mTableColumns[c];
                if (t < 0) continue;
                DbObjects.Column column = mColumns.get(t);
                if (column.primaryKey > 0 && column.defaultValue != null) continue;
                if (column.primaryKey > 0 && mKeyColumns.size() == 1 && column.type.toUpperCase(java.util.Locale.ROOT).startsWith("INT")
                    && view.getType() == DbType.SQLITE) continue;
                values[c] = copy[c];
                set[c] = true;
            }
        }
        result.rows.add(values);
        mInserted.put(values, set);
        changed();
        view.getGrid().scrollToRow(result.rows.size() - 1);
    }

    private void editValue(int row, int column) {
        DbTabView view = mView;
        QueryResult result = mResult;
        if (view == null || result == null || mTableColumns[column] < 0) return;
        Object[] values = result.rows.get(row);
        if (mDeleted.containsKey(values)) return;
        Object value = values[column];
        if (value instanceof byte[]) {
            Toast.makeText(view.getContext(), R.string.msg_db_binary_not_editable, Toast.LENGTH_SHORT).show();
            return;
        }
        Context context = view.getContext();
        EditText input = new EditText(context);
        input.setTypeface(Typeface.MONOSPACE);
        input.setText(value == null ? "" : value.toString());
        input.setHint(value == null ? "NULL" : "");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMaxLines(10);
        input.setSelectAllOnFocus(true);
        LinearLayout frame = new LinearLayout(context);
        int padding = DbDialogs.dp(context, 20);
        frame.setPadding(padding, padding / 2, padding, 0);
        frame.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        DbObjects.Column info = mColumns.get(mTableColumns[column]);
        AlertDialog dialog = new AlertDialog.Builder(view.getActivity())
            .setTitle(info.name + "  " + info.type)
            .setView(frame)
            .setPositiveButton(android.R.string.ok, (d, which) -> setValue(row, column, input.getText().toString()))
            .setNeutralButton("NULL", (d, which) -> setValue(row, column, null))
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> input.requestFocus());
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
    }

    void setValue(int row, int column, @Nullable String value) {
        QueryResult result = mResult;
        if (result == null) return;
        Object[] values = result.rows.get(row);
        boolean[] set = mInserted.get(values);
        if (set != null) {
            set[column] = true;
        } else {
            Object[] original = mOriginals.get(values);
            if (original == null) mOriginals.put(values, values.clone());
        }
        values[column] = value;
        Object[] original = mOriginals.get(values);
        if (original != null && java.util.Arrays.equals(original, values)) mOriginals.remove(values);
        changed();
    }

    private void discard() {
        QueryResult result = mResult;
        if (result == null) return;
        for (Map.Entry<Object[], Object[]> entry : mOriginals.entrySet()) {
            System.arraycopy(entry.getValue(), 0, entry.getKey(), 0, entry.getKey().length);
        }
        for (Object[] values : mInserted.keySet()) result.rows.remove(values);
        clearChanges();
        if (mView != null) mView.getGrid().clearSelection();
        changed();
    }

    private void changed() {
        if (mView != null) {
            mView.getGrid().refresh();
            updateStatus();
            mView.update();
        }
    }

    /** A statement of the changes, with its parameters. */
    static final class Change {
        @NonNull final String sql;
        @NonNull final List<Object> parameters;
        /** Whether it must change one row, as an UPDATE or DELETE by key. */
        final boolean byKey;
        @NonNull final String preview;

        Change(@NonNull String sql, @NonNull List<Object> parameters, boolean byKey, @NonNull String preview) {
            this.sql = sql;
            this.parameters = parameters;
            this.byKey = byKey;
            this.preview = preview;
        }
    }

    /** The statements of the changes: deletes, then updates, then inserts. */
    @NonNull
    List<Change> getChanges(@NonNull DbType type) {
        QueryResult result = mResult;
        List<Change> changes = new ArrayList<>();
        if (result == null) return changes;
        String name = type.quote(table.schema, table.name);
        for (Object[] values : result.rows) {
            if (!mDeleted.containsKey(values)) continue;
            Object[] original = mOriginals.containsKey(values) ? mOriginals.get(values) : values;
            List<Object> parameters = new ArrayList<>();
            StringBuilder preview = new StringBuilder();
            String where = keyCondition(type, original, result, parameters, preview);
            changes.add(new Change("DELETE FROM " + name + " WHERE " + where, parameters, true,
                "DELETE FROM " + name + " WHERE " + preview));
        }
        for (Object[] values : result.rows) {
            Object[] original = mOriginals.get(values);
            if (original == null || mDeleted.containsKey(values)) continue;
            StringBuilder sql = new StringBuilder("UPDATE ").append(name).append(" SET ");
            StringBuilder preview = new StringBuilder(sql);
            List<Object> parameters = new ArrayList<>();
            boolean first = true;
            for (int c = 0; c < values.length; c++) {
                if (mTableColumns[c] < 0 || Objects.equals(values[c], original[c])) continue;
                if (!first) {
                    sql.append(", ");
                    preview.append(", ");
                }
                String column = type.quote(result.columns.get(c).name);
                sql.append(column).append(" = ?");
                preview.append(column).append(" = ").append(ResultExport.toLiteral(values[c], result.columns.get(c), type));
                parameters.add(values[c]);
                first = false;
            }
            if (first) continue;
            StringBuilder keyPreview = new StringBuilder();
            String where = keyCondition(type, original, result, parameters, keyPreview);
            changes.add(new Change(sql + " WHERE " + where, parameters, true, preview + " WHERE " + keyPreview));
        }
        for (Object[] values : result.rows) {
            boolean[] set = mInserted.get(values);
            if (set == null) continue;
            StringBuilder names = new StringBuilder();
            StringBuilder marks = new StringBuilder();
            StringBuilder literals = new StringBuilder();
            List<Object> parameters = new ArrayList<>();
            for (int c = 0; c < values.length; c++) {
                if (!set[c] || mTableColumns[c] < 0) continue;
                if (names.length() > 0) {
                    names.append(", ");
                    marks.append(", ");
                    literals.append(", ");
                }
                names.append(type.quote(result.columns.get(c).name));
                marks.append('?');
                literals.append(ResultExport.toLiteral(values[c], result.columns.get(c), type));
                parameters.add(values[c]);
            }
            if (names.length() == 0) {
                String sql = type.isMysqlSyntax() ? "INSERT INTO " + name + " () VALUES ()" : "INSERT INTO " + name + " DEFAULT VALUES";
                changes.add(new Change(sql, parameters, false, sql));
            } else {
                changes.add(new Change("INSERT INTO " + name + " (" + names + ") VALUES (" + marks + ")", parameters, false,
                    "INSERT INTO " + name + " (" + names + ") VALUES (" + literals + ")"));
            }
        }
        return changes;
    }

    @NonNull
    private String keyCondition(@NonNull DbType type, @NonNull Object[] values, @NonNull QueryResult result,
                                @NonNull List<Object> parameters, @NonNull StringBuilder preview) {
        StringBuilder sql = new StringBuilder();
        for (int key : mKeyColumns) {
            if (sql.length() > 0) {
                sql.append(" AND ");
                preview.append(" AND ");
            }
            String column = type.quote(result.columns.get(key).name);
            if (values[key] == null) {
                sql.append(column).append(" IS NULL");
                preview.append(column).append(" IS NULL");
            } else {
                sql.append(column).append(" = ?");
                preview.append(column).append(" = ").append(ResultExport.toLiteral(values[key], result.columns.get(key), type));
                parameters.add(values[key]);
            }
        }
        return sql.toString();
    }

    /** Show the statements of the changes, and run them in a transaction when confirmed. */
    private void apply() {
        DbTabView view = mView;
        DbSession session = mTab.getSession();
        if (view == null || session == null || !hasChanges() || mTab.state != DbTab.READY) return;
        List<Change> changes = getChanges(session.getType());
        StringBuilder preview = new StringBuilder();
        for (Change change : changes) preview.append(change.preview).append(";\n");
        TextView text = new TextView(view.getContext());
        text.setText(preview);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        text.setTextIsSelectable(true);
        int padding = DbDialogs.dp(view.getContext(), 16);
        text.setPadding(padding, padding / 2, padding, 0);
        android.widget.ScrollView scroll = new android.widget.ScrollView(view.getContext());
        scroll.addView(text);
        new AlertDialog.Builder(view.getActivity())
            .setTitle(view.getContext().getString(R.string.title_db_apply_changes, changes.size()))
            .setView(scroll)
            .setPositiveButton(R.string.action_db_apply_changes, (d, which) -> run(changes))
            .setNeutralButton(R.string.action_db_copy, (d, which) -> DbDialogs.copy(view.getContext(), preview.toString()))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    void run(@NonNull List<Change> changes) {
        DbTabView view = mView;
        DbTab tab = mTab;
        DbSession session = tab.getSession();
        if (view == null || session == null) return;
        Context context = view.getContext();
        tab.state = DbTab.RUNNING;
        view.update();
        tab.executor.execute(() -> {
            String error = applyChanges(session, changes, context);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                tab.state = DbTab.READY;
                if (error != null) {
                    if (mView != null) DbDialogs.showProgressError(mView.getActivity(), error);
                    if (mView != null) mView.update();
                    return;
                }
                Toast.makeText(context, context.getString(R.string.msg_db_changes_applied, changes.size()), Toast.LENGTH_SHORT).show();
                clearChanges();
                load();
            });
        });
    }

    /**
     * Run the statements in a transaction, of their own with auto commit, or else in the open
     * one up to a savepoint, so that a failed change undoes all of them. Returns an error.
     */
    @Nullable
    static String applyChanges(@NonNull DbSession session, @NonNull List<Change> changes, @NonNull Context context) {
        return applyChanges(session, changes, context.getString(R.string.msg_db_row_not_found, "%s"), e -> DbTabView.describe(context, e));
    }

    /** Like {@link #applyChanges(DbSession, List, Context)}, with the message of a missing row and of errors. */
    @Nullable
    static String applyChanges(@NonNull DbSession session, @NonNull List<Change> changes, @NonNull String notFoundFormat,
                               @NonNull java.util.function.Function<Throwable, String> describe) {
        boolean autoCommit;
        try {
            autoCommit = session.getAutoCommit();
            if (autoCommit) session.setAutoCommit(false);
            else session.execute("SAVEPOINT " + SAVEPOINT, 0, 0);
        } catch (SQLException | RuntimeException e) {
            return describe.apply(e);
        }
        Change current = null;
        try {
            for (Change change : changes) {
                current = change;
                int count = session.executeUpdate(change.sql, change.parameters);
                if (change.byKey && count != 1) throw new SQLException(notFoundFormat.replace("%s", change.preview));
            }
            current = null;
            if (autoCommit) session.commit();
            else session.execute("RELEASE SAVEPOINT " + SAVEPOINT, 0, 0);
            return null;
        } catch (SQLException | RuntimeException e) {
            try {
                if (autoCommit) session.rollback();
                else session.execute("ROLLBACK TO SAVEPOINT " + SAVEPOINT, 0, 0);
            } catch (SQLException | RuntimeException ignored) {
            }
            String error = describe.apply(e);
            return current != null && !error.contains(current.preview) ? error + "\n\n" + current.preview : error;
        } finally {
            if (autoCommit) {
                try {
                    session.setAutoCommit(true);
                } catch (SQLException | RuntimeException ignored) {
                }
            }
        }
    }

}
