package com.termux.app.terminal.db;

import android.text.Editable;
import android.text.Layout;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListPopupWindow;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Completion of keywords, and of the names of tables and columns that the schema tree has
 * loaded, in the SQL editor. After a name and a dot, like {@code o.} for {@code orders o},
 * the columns of that table are offered, loading them if needed.
 */
final class SqlCompletion {

    static final String[] KEYWORDS = (
        "SELECT FROM WHERE AND OR NOT NULL IS IN LIKE ILIKE BETWEEN EXISTS AS DISTINCT ORDER BY GROUP HAVING LIMIT OFFSET " +
        "JOIN LEFT RIGHT INNER OUTER FULL CROSS ON USING UNION ALL EXCEPT INTERSECT CASE WHEN THEN ELSE END " +
        "INSERT INTO VALUES UPDATE SET DELETE RETURNING CREATE TABLE VIEW INDEX UNIQUE PRIMARY KEY FOREIGN REFERENCES " +
        "DROP ALTER ADD COLUMN RENAME TO TRUNCATE DEFAULT CONSTRAINT CHECK CASCADE BEGIN COMMIT ROLLBACK EXPLAIN ANALYZE " +
        "WITH RECURSIVE ASC DESC NULLS FIRST LAST COUNT SUM AVG MIN MAX COALESCE CAST NOW CURRENT_DATE CURRENT_TIMESTAMP " +
        "INTEGER BIGINT TEXT VARCHAR BOOLEAN TIMESTAMP DATE NUMERIC SERIAL TRUE FALSE IF").split(" ");

    private static final int MAX_ITEMS = 40;

    private final DbTabView mView;
    private final ListPopupWindow mPopup;
    private final ArrayAdapter<String> mAdapter;
    private final List<String> mItems = new ArrayList<>();
    /** The start of the word being completed. */
    private int mWordStart;
    private boolean mInserting;

    SqlCompletion(@NonNull DbTabView view) {
        mView = view;
        mAdapter = new ArrayAdapter<String>(view.getContext(), android.R.layout.simple_list_item_1, mItems) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView text = (TextView) super.getView(position, convertView, parent);
                text.setTypeface(android.graphics.Typeface.MONOSPACE);
                text.setTextSize(14);
                text.setMinHeight(0);
                text.setPadding(DbDialogs.dp(getContext(), 12), DbDialogs.dp(getContext(), 6), DbDialogs.dp(getContext(), 12), DbDialogs.dp(getContext(), 6));
                return text;
            }
        };
        mPopup = new ListPopupWindow(view.getContext());
        mPopup.setAdapter(mAdapter);
        mPopup.setAnchorView(view.mEditor);
        mPopup.setModal(false);
        mPopup.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
        mPopup.setWidth(DbDialogs.dp(view.getContext(), 260));
        mPopup.setOnItemClickListener((parent, item, position, id) -> accept(position));
        view.mEditor.addTextChangedListener(new DbTabView.SimpleWatcher(() -> {
            if (!mInserting) show(false);
        }));
    }

    @NonNull
    List<String> getItems() {
        return mItems;
    }

    boolean isShowing() {
        return mPopup.isShowing();
    }

    void dismiss() {
        mPopup.dismiss();
    }

    /**
     * Show the names that start with the word at the cursor. Typing shows them after two
     * letters, or after a dot; {@code explicit}, with Ctrl+Space, at once.
     */
    void show(boolean explicit) {
        Editable text = mView.mEditor.getText();
        int cursor = mView.mEditor.getSelectionStart();
        if (cursor < 0 || cursor != mView.mEditor.getSelectionEnd() || !mView.mEditor.hasFocus() && !explicit) {
            dismiss();
            return;
        }
        int start = cursor;
        while (start > 0 && isNameChar(text.charAt(start - 1))) start--;
        String prefix = text.subSequence(start, cursor).toString();
        String qualifier = null;
        if (start > 0 && text.charAt(start - 1) == '.') {
            int qualifierStart = start - 1;
            while (qualifierStart > 0 && isNameChar(text.charAt(qualifierStart - 1))) qualifierStart--;
            qualifier = text.subSequence(qualifierStart, start - 1).toString();
        }
        if (!explicit && qualifier == null && prefix.length() < 2 || qualifier != null && qualifier.isEmpty()) {
            dismiss();
            return;
        }
        mWordStart = start;
        List<String> items = qualifier != null ? columnsOf(qualifier, text.toString(), prefix) : candidates(prefix);
        if (items.isEmpty() || items.size() == 1 && items.get(0).equalsIgnoreCase(prefix)) {
            dismiss();
            return;
        }
        mItems.clear();
        mItems.addAll(items);
        mAdapter.notifyDataSetChanged();
        place(cursor);
        if (!mPopup.isShowing()) mPopup.show();
        else mPopup.show();
        mPopup.setSelection(0);
    }

    /** Put the popup under the line of the cursor. */
    private void place(int cursor) {
        Layout layout = mView.mEditor.getLayout();
        if (layout == null) return;
        int line = layout.getLineForOffset(cursor);
        int x = (int) layout.getPrimaryHorizontal(cursor) + mView.mEditor.getTotalPaddingLeft() - mView.mEditor.getScrollX();
        int y = layout.getLineBottom(line) + mView.mEditor.getTotalPaddingTop() - mView.mEditor.getScrollY();
        mPopup.setDropDownGravity(Gravity.START);
        mPopup.setHorizontalOffset(Math.max(0, Math.min(x, mView.mEditor.getWidth() - mPopup.getWidth())));
        mPopup.setVerticalOffset(y - mView.mEditor.getHeight());
    }

    static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** Tables, columns that were loaded, and keywords that start with the prefix, ignoring case. */
    @NonNull
    private List<String> candidates(@NonNull String prefix) {
        DbTab tab = mView.getTab();
        String lower = prefix.toLowerCase(Locale.ROOT);
        Set<String> items = new LinkedHashSet<>();
        for (List<DbObjects.Table> tables : tab.tables.values()) {
            for (DbObjects.Table table : tables) if (table.name.toLowerCase(Locale.ROOT).startsWith(lower)) items.add(table.name);
        }
        for (String schema : tab.schemas) if (schema.toLowerCase(Locale.ROOT).startsWith(lower)) items.add(schema);
        for (List<DbObjects.Column> columns : tab.columns.values()) {
            for (DbObjects.Column column : columns) if (column.name.toLowerCase(Locale.ROOT).startsWith(lower)) items.add(column.name);
        }
        boolean upper = !prefix.isEmpty() && Character.isUpperCase(prefix.charAt(0));
        for (String keyword : KEYWORDS) {
            if (keyword.toLowerCase(Locale.ROOT).startsWith(lower)) items.add(upper ? keyword : keyword.toLowerCase(Locale.ROOT));
        }
        List<String> list = new ArrayList<>(items);
        return list.size() > MAX_ITEMS ? list.subList(0, MAX_ITEMS) : list;
    }

    /**
     * The columns of the table named by a qualifier, which is a table name or an alias of one
     * in the script, like {@code o} in {@code FROM orders o}. Their tables are loaded if needed,
     * and the popup is shown again when they are.
     */
    @NonNull
    private List<String> columnsOf(@NonNull String qualifier, @NonNull String script, @NonNull String prefix) {
        DbTab tab = mView.getTab();
        DbObjects.Table table = findTable(tab, qualifier);
        if (table == null) {
            String aliased = findAliasedTable(script, qualifier, mView.getType());
            if (aliased != null) table = findTable(tab, aliased);
        }
        if (table == null) {
            // A schema: its tables.
            List<DbObjects.Table> tables = findSchemaTables(tab, qualifier);
            List<String> items = new ArrayList<>();
            if (tables != null) for (DbObjects.Table t : tables) {
                if (t.name.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) items.add(t.name);
            }
            return items;
        }
        List<DbObjects.Column> columns = tab.columns.get(DbTab.key(table.schema, table.name));
        if (columns == null) {
            mView.loadColumns(table, () -> show(true));
            return new ArrayList<>();
        }
        List<String> items = new ArrayList<>();
        for (DbObjects.Column column : columns) {
            if (column.name.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) items.add(column.name);
        }
        return items;
    }

    @Nullable
    private static DbObjects.Table findTable(@NonNull DbTab tab, @NonNull String name) {
        DbObjects.Table found = null;
        for (Map.Entry<String, List<DbObjects.Table>> entry : tab.tables.entrySet()) {
            for (DbObjects.Table table : entry.getValue()) {
                if (!table.name.equalsIgnoreCase(name)) continue;
                // Prefer the default schema.
                if (found == null || entry.getKey().equals(tab.defaultSchema)) found = table;
            }
        }
        return found;
    }

    @Nullable
    private static List<DbObjects.Table> findSchemaTables(@NonNull DbTab tab, @NonNull String name) {
        for (Map.Entry<String, List<DbObjects.Table>> entry : tab.tables.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }

    private static final Set<String> NOT_ALIASES = new java.util.HashSet<>(Arrays.asList(
        "WHERE", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "CROSS", "ON", "USING", "GROUP", "ORDER", "LIMIT",
        "SET", "VALUES", "UNION", "HAVING", "NATURAL", "WINDOW", "RETURNING"));

    /** The table of an alias in the script, like {@code orders} for {@code o} in {@code FROM orders o} or {@code JOIN orders AS o}. */
    @Nullable
    static String findAliasedTable(@NonNull String script, @NonNull String alias, @NonNull DbType type) {
        List<SqlLexer.Token> tokens = SqlLexer.tokenize(script, type);
        for (int i = 0; i + 1 < tokens.size(); i++) {
            String keyword = tokens.get(i).type == SqlLexer.WORD ? tokens.get(i).upper() : "";
            if (!keyword.equals("FROM") && !keyword.equals("JOIN") && !keyword.equals("UPDATE") && !keyword.equals("INTO")) continue;
            // The name, maybe with a schema.
            int j = i + 1;
            String table = unquote(tokens.get(j));
            if (table == null) continue;
            while (j + 2 < tokens.size() && tokens.get(j + 1).text.equals(".")) {
                String next = unquote(tokens.get(j + 2));
                if (next == null) break;
                table = next;
                j += 2;
            }
            j++;
            if (j < tokens.size() && tokens.get(j).type == SqlLexer.WORD && tokens.get(j).upper().equals("AS")) j++;
            if (j >= tokens.size()) continue;
            String name = unquote(tokens.get(j));
            if (name != null && name.equalsIgnoreCase(alias) && !NOT_ALIASES.contains(name.toUpperCase(Locale.ROOT))) return table;
        }
        return null;
    }

    @Nullable
    private static String unquote(@NonNull SqlLexer.Token token) {
        if (token.type == SqlLexer.WORD) return token.text;
        if (token.type == SqlLexer.QUOTED_NAME && token.text.length() >= 2) return token.text.substring(1, token.text.length() - 1);
        return null;
    }

    private void accept(int position) {
        if (position < 0 || position >= mItems.size()) return;
        String item = mItems.get(position);
        Editable text = mView.mEditor.getText();
        int cursor = mView.mEditor.getSelectionStart();
        if (cursor < mWordStart) return;
        DbType type = mView.getType();
        boolean keyword = Arrays.asList(KEYWORDS).contains(item.toUpperCase(Locale.ROOT)) && !isName(item);
        String insert = keyword || item.matches("[a-z_][a-z0-9_$]*") ? item : type.quote(item);
        mInserting = true;
        text.replace(mWordStart, cursor, insert);
        mInserting = false;
        dismiss();
    }

    /** Whether a text is the name of a table or column that was loaded. */
    private boolean isName(@NonNull String name) {
        DbTab tab = mView.getTab();
        for (List<DbObjects.Table> tables : tab.tables.values()) for (DbObjects.Table table : tables) if (table.name.equals(name)) return true;
        for (List<DbObjects.Column> columns : tab.columns.values()) for (DbObjects.Column column : columns) if (column.name.equals(name)) return true;
        return false;
    }

    /** Keys while the popup is shown: arrows choose, Enter or Tab inserts, Escape closes. */
    boolean onKey(@NonNull KeyEvent event) {
        if (!mPopup.isShowing()) return false;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_UP: {
                int position = mPopup.getSelectedItemPosition();
                if (position < 0) position = 0;
                position += event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1;
                mPopup.setSelection(Math.max(0, Math.min(mItems.size() - 1, position)));
                return true;
            }
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_TAB:
                if (event.isCtrlPressed()) return false;
                accept(Math.max(0, mPopup.getSelectedItemPosition()));
                return true;
            case KeyEvent.KEYCODE_ESCAPE:
                dismiss();
                return true;
            default:
                return false;
        }
    }

}
