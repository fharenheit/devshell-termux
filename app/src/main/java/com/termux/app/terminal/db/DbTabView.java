package com.termux.app.terminal.db;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.terminal.sftp.EditHistory;
import com.termux.app.terminal.sftp.LineNumberEditText;
import com.termux.app.terminal.sftp.SyntaxHighlighter;
import com.termux.app.terminal.ssh.SshHostKeyDialog;
import com.termux.shared.logger.Logger;
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The view of a database tab: a toolbar to run statements and control the transaction, the schema
 * tree, the SQL editor and the results. On a wide screen the three are side by side; on a narrow
 * one, one at a time with buttons to switch. Database work runs on the thread of the tab and the
 * view is updated from {@link DbTab} when it is done, so a new view of the same tab shows the
 * same state.
 */
@SuppressLint("ViewConstructor")
final class DbTabView extends LinearLayout {

    private static final int TOOLBAR_COLOR = 0xFF1E1E1E;
    private static final int WIDE_SCREEN_DP = 720;
    private static final String LOG_TAG = "DbTabView";

    private final DbTabs mTabs;
    private final DbTab mTab;
    private final Activity mActivity;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mAttached = true;

    private final View mColorStripe;
    private final TextView mTitle;
    private final TextView mStatus;
    private final Button mRunButton;
    private final Button mRunAllButton;
    private final Button mToggleResultsButton;
    private final Button mCancelButton;
    private final Button mCommitButton;
    private final Button mRollbackButton;
    private final ProgressBar mProgress;

    private final LinearLayout mSegments;
    private final Button[] mSegmentButtons = new Button[3];
    private final FrameLayout mContent;
    private boolean mWide;

    private final LinearLayout mSchemaPanel;
    private final EditText mSchemaFilter;
    private final ListView mSchemaList;
    private final SchemaAdapter mSchemaAdapter = new SchemaAdapter();

    private final LinearLayout mEditorPanel;
    final LineNumberEditText mEditor;
    private EditHistory mEditHistory;
    @Nullable SqlCompletion mCompletion;

    private final LinearLayout mResultsPanel;
    private final HorizontalScrollView mResultTabsScroll;
    private final LinearLayout mResultTabs;
    private final LinearLayout mFilterRow;
    private final EditText mResultFilter;
    private ResultGridView mGrid;
    private final TextView mMessage;
    private final TextView mResultStatus;
    private final Button mMoreButton;
    private final FrameLayout mEditBarHolder;
    private final View mMessageFrame;

    private final Runnable mSaveSql = this::saveSql;

    DbTabView(@NonNull DbTabs tabs, @NonNull DbTab tab) {
        super(tabs.getActivity());
        mTabs = tabs;
        mTab = tab;
        mActivity = tabs.getActivity();
        Context context = mActivity;
        setOrientation(VERTICAL);
        setBackgroundColor(Color.BLACK);

        // Toolbar.
        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(TOOLBAR_COLOR);
        mColorStripe = new View(context);
        toolbar.addView(mColorStripe, new LayoutParams(dp(4), dp(44)));
        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(VERTICAL);
        titles.setPadding(dp(8), 0, dp(4), 0);
        mTitle = new TextView(context);
        mTitle.setTextColor(Color.WHITE);
        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mTitle.setSingleLine();
        mTitle.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(mTitle);
        mStatus = new TextView(context);
        mStatus.setTextColor(0xFFA0A0A0);
        mStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        mStatus.setSingleLine();
        mStatus.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(mStatus);
        titles.setOnClickListener(this::showConnectionMenu);
        toolbar.addView(titles, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        mRunButton = addToolbarButton(toolbar, "▶", R.string.action_db_run, v -> run(false));
        mRunAllButton = addToolbarButton(toolbar, "▶▶", R.string.action_db_run_all, v -> run(true));
        mToggleResultsButton = addToolbarButton(toolbar, "⌄", R.string.action_db_expand_results, v -> toggleResults());
        mCancelButton = addToolbarButton(toolbar, "■", R.string.action_db_cancel, v -> cancel());
        mCommitButton = addToolbarButton(toolbar, "✓", R.string.action_db_commit, v -> commit(true));
        mRollbackButton = addToolbarButton(toolbar, "↶", R.string.action_db_rollback, v -> commit(false));
        addToolbarButton(toolbar, "⋮", R.string.action_db_menu, this::showMenu);
        addView(toolbar, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mProgress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        mProgress.setIndeterminate(true);
        mProgress.setVisibility(INVISIBLE);
        addView(mProgress, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        // Buttons to switch panels on a narrow screen.
        mSegments = new LinearLayout(context);
        mSegments.setBackgroundColor(TOOLBAR_COLOR);
        int[] segmentNames = {R.string.db_panel_schema, R.string.db_panel_sql, R.string.db_panel_results};
        for (int i = 0; i < 3; i++) {
            Button button = new Button(context, null, android.R.attr.borderlessButtonStyle);
            button.setText(segmentNames[i]);
            button.setAllCaps(false);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            button.setMinHeight(0);
            button.setMinimumHeight(0);
            button.setFocusable(false);
            int panel = i;
            button.setOnClickListener(v -> showPanel(panel));
            mSegments.addView(button, new LayoutParams(0, dp(36), 1));
            mSegmentButtons[i] = button;
        }
        addView(mSegments, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mContent = new FrameLayout(context);
        addView(mContent, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Schema.
        mSchemaPanel = new LinearLayout(context);
        mSchemaPanel.setOrientation(VERTICAL);
        LinearLayout schemaBar = new LinearLayout(context);
        schemaBar.setGravity(Gravity.CENTER_VERTICAL);
        mSchemaFilter = new EditText(context);
        mSchemaFilter.setSingleLine();
        mSchemaFilter.setHint(R.string.db_schema_filter_hint);
        mSchemaFilter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mSchemaFilter.addTextChangedListener(new SimpleWatcher(() -> mSchemaAdapter.rebuild()));
        schemaBar.addView(mSchemaFilter, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        addSmallButton(schemaBar, "⟳", R.string.action_db_refresh, v -> refreshSchema());
        mSchemaPanel.addView(schemaBar);
        mSchemaList = new ListView(context);
        mSchemaList.setAdapter(mSchemaAdapter);
        mSchemaList.setOnItemClickListener((parent, view, position, id) -> onSchemaClick(mSchemaAdapter.getItem(position)));
        mSchemaList.setOnItemLongClickListener((parent, view, position, id) -> {
            onSchemaLongClick(mSchemaAdapter.getItem(position));
            return true;
        });
        mSchemaPanel.addView(mSchemaList, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Editor.
        mEditorPanel = new LinearLayout(context);
        mEditorPanel.setOrientation(VERTICAL);
        LinearLayout editorBar = new LinearLayout(context);
        editorBar.setBackgroundColor(0xFF161616);
        addSmallButton(editorBar, "↶", R.string.action_db_undo, v -> mEditHistory.undo());
        addSmallButton(editorBar, "↷", R.string.action_db_redo, v -> mEditHistory.redo());
        addSmallButton(editorBar, "🕘", R.string.action_db_history, v -> DbDialogs.showHistory(mActivity, mTab.connectionId, this::insertSql));
        addSmallButton(editorBar, "★", R.string.action_db_saved_queries, v -> DbDialogs.showSaved(mActivity, mTab.connectionId, this::insertSql));
        addSmallButton(editorBar, "＋★", R.string.action_db_save_query, v -> saveCurrentQuery());
        addSmallButton(editorBar, "EXPLAIN", R.string.action_db_explain, v -> explain());
        mEditorPanel.addView(editorBar, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mEditor = new LineNumberEditText(context);
        mEditor.setTypeface(Typeface.MONOSPACE);
        mEditor.setLanguage(SyntaxHighlighter.find("SQL"));
        mEditor.setHint(R.string.db_editor_hint);
        mEditor.setHintTextColor(0xFF606060);
        mEditor.setText(tab.sql);
        mEditHistory = new EditHistory(mEditor);
        mEditor.addTextChangedListener(new SimpleWatcher(() -> {
            mHandler.removeCallbacks(mSaveSql);
            mHandler.postDelayed(mSaveSql, 1000);
        }));
        mEditorPanel.addView(mEditor, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Results.
        mResultsPanel = new LinearLayout(context);
        mResultsPanel.setOrientation(VERTICAL);
        mResultTabsScroll = new HorizontalScrollView(context);
        mResultTabsScroll.setBackgroundColor(0xFF161616);
        mResultTabs = new LinearLayout(context);
        mResultTabsScroll.addView(mResultTabs);
        mResultsPanel.addView(mResultTabsScroll);
        mFilterRow = new LinearLayout(context);
        mFilterRow.setGravity(Gravity.CENTER_VERTICAL);
        mResultFilter = new EditText(context);
        mResultFilter.setSingleLine();
        mResultFilter.setHint(R.string.db_result_filter_hint);
        mResultFilter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mResultFilter.addTextChangedListener(new SimpleWatcher(() -> mGrid.setFilter(mResultFilter.getText().toString())));
        mFilterRow.addView(mResultFilter, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        addSmallButton(mFilterRow, "⧉", R.string.action_db_copy, v -> copyResult());
        addSmallButton(mFilterRow, "⤓", R.string.action_db_export, v -> exportResult());
        mResultsPanel.addView(mFilterRow);
        mEditBarHolder = new FrameLayout(context);
        mEditBarHolder.setVisibility(GONE);
        mResultsPanel.addView(mEditBarHolder);
        FrameLayout gridFrame = new FrameLayout(context);
        mGrid = new ResultGridView(context);
        mGrid.setListener(new GridListener());
        gridFrame.addView(mGrid);
        mMessage = new TextView(context);
        mMessage.setTextColor(0xFFE0E0E0);
        mMessage.setTypeface(Typeface.MONOSPACE);
        mMessage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mMessage.setTextIsSelectable(true);
        mMessage.setPadding(dp(12), dp(12), dp(12), dp(12));
        ScrollView messageScroll = new ScrollView(context);
        messageScroll.addView(mMessage);
        gridFrame.addView(messageScroll);
        mMessageFrame = messageScroll;
        mResultsPanel.addView(gridFrame, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout statusRow = new LinearLayout(context);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setBackgroundColor(0xFF161616);
        mResultStatus = new TextView(context);
        mResultStatus.setTextColor(0xFFA0A0A0);
        mResultStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mResultStatus.setPadding(dp(8), 0, dp(8), 0);
        mResultStatus.setSingleLine();
        mResultStatus.setEllipsize(TextUtils.TruncateAt.END);
        statusRow.addView(mResultStatus, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        mMoreButton = addSmallButton(statusRow, mActivity.getString(R.string.action_db_fetch_more), R.string.action_db_fetch_more, v -> fetchMore());
        mResultsPanel.addView(statusRow);

        mCompletion = new SqlCompletion(this);
        addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            boolean wide = (right - left) / getResources().getDisplayMetrics().density >= WIDE_SCREEN_DP;
            if (wide != mWide || mContent.getChildCount() == 0) post(() -> arrangePanels(wide));
        });
        applyFontSize();
        updateToggleButton();
        arrangePanels(context.getResources().getConfiguration().screenWidthDp >= WIDE_SCREEN_DP);
        update();
    }

    /** Use the app's configured font size for the editor, results and messages. */
    private void applyFontSize() {
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(mActivity, true);
        if (preferences == null) return;
        float px = preferences.getFontSize();
        if (px <= 0) return;
        mEditor.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        mMessage.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        if (mGrid != null) mGrid.setTextSize(px);
    }

    /** Collapse or expand the results pane, from the toolbar button. */
    private void toggleResults() {
        mTab.resultsCollapsed = !mTab.resultsCollapsed;
        if (mWide) arrangePanels(true);
        else showPanel(mTab.resultsCollapsed ? DbTab.PANEL_SQL : DbTab.PANEL_RESULTS);
        updateToggleButton();
    }

    /** The toggle button shows a down chevron to expand, an up chevron to collapse. */
    private void updateToggleButton() {
        boolean collapsed = mTab.resultsCollapsed;
        mToggleResultsButton.setText(collapsed ? "⌄" : "⌃");
        mToggleResultsButton.setContentDescription(getContext().getString(
            collapsed ? R.string.action_db_expand_results : R.string.action_db_collapse_results));
    }

    private int dp(float value) {
        return DbDialogs.dp(getContext(), value);
    }

    @NonNull
    private Button addToolbarButton(@NonNull LinearLayout bar, @NonNull String text, int descriptionId, @NonNull OnClickListener listener) {
        Button button = new Button(getContext(), null, android.R.attr.borderlessButtonStyle);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setMinWidth(dp(40));
        button.setMinimumWidth(dp(40));
        button.setPadding(0, 0, 0, 0);
        button.setFocusable(false);
        button.setContentDescription(getContext().getString(descriptionId));
        button.setOnClickListener(listener);
        bar.addView(button, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        return button;
    }

    @NonNull
    private Button addSmallButton(@NonNull LinearLayout bar, @NonNull String text, int descriptionId, @NonNull OnClickListener listener) {
        Button button = new Button(getContext(), null, android.R.attr.borderlessButtonStyle);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(0xFFE0E0E0);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        button.setMinWidth(dp(36));
        button.setMinimumWidth(dp(36));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setFocusable(false);
        button.setContentDescription(getContext().getString(descriptionId));
        button.setOnClickListener(listener);
        bar.addView(button, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        return button;
    }

    // Layout

    /** Put the panels side by side on a wide screen, or one at a time with the segment buttons. */
    private void arrangePanels(boolean wide) {
        mWide = wide;
        for (View panel : new View[]{mSchemaPanel, mEditorPanel, mResultsPanel}) {
            ViewGroup parent = (ViewGroup) panel.getParent();
            if (parent != null) parent.removeView(panel);
        }
        mContent.removeAllViews();
        if (wide) {
            LinearLayout row = new LinearLayout(getContext());
            row.addView(mSchemaPanel, new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3));
            View line = new View(getContext());
            line.setBackgroundColor(0xFF333333);
            row.addView(line, new LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout right = new LinearLayout(getContext());
            right.setOrientation(VERTICAL);
            // When collapsed, the editor fills the right column and the results pane is hidden.
            int editorWeight = mTab.resultsCollapsed ? 1 : 4;
            right.addView(mEditorPanel, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, editorWeight));
            if (!mTab.resultsCollapsed) {
                View line2 = new View(getContext());
                line2.setBackgroundColor(0xFF333333);
                right.addView(line2, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
                right.addView(mResultsPanel, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 6));
            }
            row.addView(right, new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 7));
            mContent.addView(row);
            mSegments.setVisibility(GONE);
            mSchemaPanel.setVisibility(VISIBLE);
            mEditorPanel.setVisibility(VISIBLE);
            mResultsPanel.setVisibility(mTab.resultsCollapsed ? GONE : VISIBLE);
        } else {
            mContent.addView(mSchemaPanel);
            mContent.addView(mEditorPanel);
            mContent.addView(mResultsPanel);
            mSegments.setVisibility(VISIBLE);
            showPanel(mTab.panel);
        }
    }

    /** Show one panel on a narrow screen. */
    void showPanel(int panel) {
        mTab.panel = panel;
        if (mWide) return;
        mSchemaPanel.setVisibility(panel == DbTab.PANEL_SCHEMA ? VISIBLE : GONE);
        mEditorPanel.setVisibility(panel == DbTab.PANEL_SQL ? VISIBLE : GONE);
        mResultsPanel.setVisibility(panel == DbTab.PANEL_RESULTS ? VISIBLE : GONE);
        for (int i = 0; i < 3; i++) {
            mSegmentButtons[i].setTextColor(i == panel ? 0xFF4FC3F7 : 0xFFB0B0B0);
            mSegmentButtons[i].setTypeface(null, i == panel ? Typeface.BOLD : Typeface.NORMAL);
        }
        mTab.resultsCollapsed = panel != DbTab.PANEL_RESULTS;
        updateToggleButton();
        if (panel == DbTab.PANEL_SQL) mEditor.requestFocus();
        else hideKeyboard();
    }

    void onShown() {
        applyFontSize();
        if (mTab.connection == null && mTab.state != DbTab.CONNECTING) connect();
        else if (mTab.panel == DbTab.PANEL_SQL || mWide) mEditor.requestFocus();
    }

    /** The view is replaced or removed; work that ends later updates only the tab. */
    void detach() {
        mAttached = false;
        mHandler.removeCallbacks(mSaveSql);
        saveSql();
        if (mCompletion != null) mCompletion.dismiss();
        hideKeyboard();
    }

    private void saveSql() {
        mTab.sql = mEditor.getText().toString();
        mTabs.save();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    @Nullable
    DbConnectionConfig getConfig() {
        return DbConnectionConfig.find(DbConnectionStore.load(), mTab.connectionId);
    }

    @NonNull
    DbType getType() {
        DbSession session = mTab.getSession();
        if (session != null) return session.getType();
        DbConnectionConfig config = getConfig();
        return config != null ? config.type : DbType.POSTGRESQL;
    }

    @NonNull
    DbTab getTab() {
        return mTab;
    }

    @NonNull
    Activity getActivity() {
        return mActivity;
    }

    // State

    /** Show the state of the tab. */
    void update() {
        DbConnectionConfig config = getConfig();
        mTitle.setText(config == null ? "?" : config.getTitle() + (config.readOnly ? "  🔒" : ""));
        mColorStripe.setBackgroundColor(config == null ? 0 : DbConnectionConfig.COLORS[config.color]);
        String status;
        switch (mTab.state) {
            case DbTab.CONNECTING:
                status = getContext().getString(R.string.msg_db_connecting);
                break;
            case DbTab.RUNNING:
                status = mTab.runTotal > 1 ? getContext().getString(R.string.msg_db_running_count, mTab.runDone + 1, mTab.runTotal)
                    : getContext().getString(R.string.msg_db_running);
                break;
            case DbTab.READY:
                status = mTab.serverVersion != null ? mTab.serverVersion : "";
                if (!mTab.autoCommit) status = getContext().getString(R.string.db_manual_commit) + " · " + status;
                break;
            case DbTab.FAILED:
                status = mTab.error != null ? mTab.error : "";
                break;
            default:
                status = getContext().getString(R.string.msg_db_disconnected);
        }
        mStatus.setText(status);
        boolean running = mTab.state == DbTab.RUNNING || mTab.state == DbTab.CONNECTING;
        mProgress.setVisibility(running ? VISIBLE : INVISIBLE);
        boolean ready = mTab.state == DbTab.READY;
        mRunButton.setEnabled(ready);
        mRunAllButton.setEnabled(ready);
        mRunButton.setAlpha(ready ? 1f : 0.4f);
        mRunAllButton.setAlpha(ready ? 1f : 0.4f);
        mCancelButton.setVisibility(running ? VISIBLE : GONE);
        boolean manual = mTab.isConnected() && !mTab.autoCommit;
        mCommitButton.setVisibility(manual ? VISIBLE : GONE);
        mRollbackButton.setVisibility(manual ? VISIBLE : GONE);
        boolean changed = mTab.connection != null && mTab.connection.session.isInTransaction();
        mCommitButton.setTextColor(changed ? 0xFF81C784 : Color.WHITE);
        mSchemaAdapter.rebuild();
        updateResults(false);
        mTabs.onTabChanged(mTab);
    }

    private void post(@NonNull DbTab tab, @NonNull Runnable runnable) {
        mHandler.post(() -> {
            runnable.run();
            if (mAttached) update();
            else mTabs.onTabStateChanged(tab);
        });
    }

    /** A message for an error of connecting or running, without the class names of exceptions. */
    @NonNull
    static String describe(@NonNull Context context, @NonNull Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) message = e.getClass().getSimpleName();
        if (e instanceof SQLException && ((SQLException) e).getSQLState() != null) {
            String state = ((SQLException) e).getSQLState();
            if (!message.contains(state)) message += " [" + state + "]";
        }
        if (e instanceof NoClassDefFoundError || e instanceof UnsupportedOperationException)
            message = context.getString(R.string.msg_db_not_supported, message);
        return message;
    }

    // Connecting

    /** Connect the tab, asking for the password first if it is not known. */
    void connect() {
        DbConnectionConfig config = getConfig();
        if (config == null) {
            mTab.state = DbTab.FAILED;
            mTab.error = getContext().getString(R.string.msg_db_connection_missing);
            update();
            return;
        }
        if (mTab.state == DbTab.CONNECTING || mTab.connection != null) return;
        if (config.type.isNetwork() && mTab.password == null) {
            String saved = DbConnectionStore.loadPassword(mActivity, config);
            if (saved == null && !config.user.isEmpty()) {
                DbDialogs.askPassword(mActivity, config, password -> {
                    mTab.password = password;
                    connect();
                }, null);
                return;
            }
            mTab.password = saved;
        }
        DbTab tab = mTab;
        tab.state = DbTab.CONNECTING;
        tab.error = null;
        update();
        String password = tab.password;
        tab.executor.execute(() -> {
            try {
                DbConnector.Connection connection = DbConnector.connect(mActivity, config, password);
                DbSession session = connection.session;
                String version = session.getServerVersion();
                List<String> schemas = session.listSchemas();
                String defaultSchema = session.getDefaultSchema();
                List<DbObjects.Table> tables = defaultSchema != null ? session.listTables(defaultSchema) : new ArrayList<>();
                post(tab, () -> {
                    tab.connection = connection;
                    tab.state = DbTab.READY;
                    tab.serverVersion = version;
                    tab.autoCommit = true;
                    tab.schemas.clear();
                    tab.schemas.addAll(schemas);
                    tab.defaultSchema = defaultSchema;
                    if (defaultSchema != null) {
                        tab.tables.put(defaultSchema, tables);
                        tab.expandedSchemas.add(defaultSchema);
                    }
                });
            } catch (SshTunnel.HostKeyException e) {
                post(tab, () -> {
                    tab.state = DbTab.FAILED;
                    tab.error = e.getMessage();
                    if (mAttached) new AlertDialog.Builder(mActivity)
                        .setMessage(e.getMessage())
                        .setPositiveButton(R.string.action_ssh_host_key_check, (d, which) ->
                            SshHostKeyDialog.show(mActivity, config.sshHost, () -> {
                                tab.state = DbTab.DISCONNECTED;
                                connect();
                            }))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                });
            } catch (Exception | LinkageError e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to connect to " + config.getTitle(), e);
                boolean login = e instanceof SQLException && ((SQLException) e).getSQLState() != null
                    && ((SQLException) e).getSQLState().startsWith("28");
                String message = describe(mActivity, e);
                post(tab, () -> {
                    tab.state = DbTab.FAILED;
                    tab.error = message;
                    // A wrong password is asked again.
                    if (login) tab.password = null;
                    if (mAttached) showConnectError(message);
                });
            }
        });
    }

    private void showConnectError(@NonNull String message) {
        new AlertDialog.Builder(mActivity)
            .setTitle(R.string.title_db_connect_failed)
            .setMessage(message)
            .setPositiveButton(R.string.action_db_retry, (d, which) -> {
                mTab.state = DbTab.DISCONNECTED;
                connect();
            })
            .setNeutralButton(R.string.action_db_edit, (d, which) -> {
                DbConnectionConfig config = getConfig();
                if (config != null) DbDialogs.showEditor(mActivity, config, saved -> {
                    mTab.password = null;
                    mTab.state = DbTab.DISCONNECTED;
                    if (mAttached) connect();
                });
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showConnectionMenu(@NonNull View anchor) {
        PopupMenu popup = new PopupMenu(mActivity, anchor);
        Menu menu = popup.getMenu();
        if (mTab.connection != null) menu.add(0, 1, 0, R.string.action_db_disconnect);
        else menu.add(0, 2, 0, R.string.action_db_connect);
        menu.add(0, 3, 0, R.string.action_db_edit);
        menu.add(0, 4, 0, R.string.title_db_connections);
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    mTabs.disconnect(mTab);
                    update();
                    return true;
                case 2:
                    mTab.state = DbTab.DISCONNECTED;
                    connect();
                    return true;
                case 3:
                    DbConnectionConfig config = getConfig();
                    if (config != null) DbDialogs.showEditor(mActivity, config, saved -> {
                        mTab.password = null;
                        update();
                    });
                    return true;
                case 4:
                    DbDialogs.showConnections(mActivity, mTabs::open);
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    // Running

    /**
     * Run the statement at the cursor, or all statements, of the selection if there is one, or
     * of the editor. Statements that write are refused on read-only connections, and dangerous
     * ones are asked about first.
     */
    void run(boolean all) {
        DbSession session = mTab.getSession();
        if (session == null || mTab.state != DbTab.READY) return;
        DbType type = session.getType();
        String text = mEditor.getText().toString();
        int selectionStart = Math.min(mEditor.getSelectionStart(), mEditor.getSelectionEnd());
        int selectionEnd = Math.max(mEditor.getSelectionStart(), mEditor.getSelectionEnd());
        List<String> statements = new ArrayList<>();
        if (selectionStart >= 0 && selectionEnd > selectionStart) {
            for (SqlScript.Statement statement : SqlScript.split(text.substring(selectionStart, selectionEnd), type)) statements.add(statement.text);
        } else if (all) {
            for (SqlScript.Statement statement : SqlScript.split(text, type)) statements.add(statement.text);
        } else {
            SqlScript.Statement statement = SqlScript.at(text, Math.max(0, mEditor.getSelectionStart()), type);
            if (statement != null) {
                statements.add(statement.text);
                // Show what runs.
                mEditor.setSelection(statement.start, statement.end);
            }
        }
        if (statements.isEmpty()) {
            Toast.makeText(mActivity, R.string.msg_db_nothing_to_run, Toast.LENGTH_SHORT).show();
            return;
        }
        runStatements(statements);
    }

    /** Run statements after the checks, and show their results. */
    void runStatements(@NonNull List<String> statements) {
        DbSession session = mTab.getSession();
        if (session == null) return;
        DbType type = session.getType();
        DbDataEditor editor = mTab.dataEditor;
        if (editor != null) {
            if (editor.hasChanges()) {
                editor.confirmDiscard(this, () -> {
                    editor.close(false);
                    runStatements(statements);
                });
                return;
            }
            editor.close(false);
        }
        if (session.getConfig().readOnly) {
            for (String sql : statements) {
                if (SqlSafety.isWrite(sql, type)) {
                    DbDialogs.showProgressError(mActivity, getContext().getString(R.string.msg_db_read_only_refused, firstLine(sql)));
                    return;
                }
            }
        }
        StringBuilder warnings = new StringBuilder();
        for (String sql : statements) {
            int warning = SqlSafety.getWarning(sql, type);
            if (warning != 0) warnings.append(getContext().getString(warning)).append("\n  ").append(firstLine(sql)).append("\n\n");
        }
        if (warnings.length() > 0) {
            new AlertDialog.Builder(mActivity)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(R.string.title_db_confirm)
                .setMessage(warnings.toString().trim())
                .setPositiveButton(R.string.action_db_run, (d, which) -> execute(statements))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return;
        }
        execute(statements);
    }

    @NonNull
    private static String firstLine(@NonNull String sql) {
        String line = sql.trim().split("\n")[0];
        return line.length() > 120 ? line.substring(0, 120) + "…" : line;
    }

    private void execute(@NonNull List<String> statements) {
        DbTab tab = mTab;
        DbSession session = tab.getSession();
        if (session == null) return;
        tab.state = DbTab.RUNNING;
        tab.runDone = 0;
        tab.runTotal = statements.size();
        tab.results.clear();
        tab.resultIndex = 0;
        update();
        String connectionId = tab.connectionId;
        int pageSize = tab.pageSize;
        int timeout = tab.timeoutSeconds;
        tab.executor.execute(() -> {
            List<QueryResult> results = new ArrayList<>();
            for (String sql : statements) {
                long start = System.currentTimeMillis();
                QueryResult result;
                try {
                    result = session.execute(sql, pageSize, timeout);
                    QueryHistory.add(new QueryHistory.Entry(connectionId, sql, "", start, result.elapsedMs,
                        result.hasRows() ? result.rows.size() : result.updateCount));
                } catch (SQLException | RuntimeException e) {
                    result = QueryResult.ofError(sql, describe(mActivity, e));
                    result.elapsedMs = System.currentTimeMillis() - start;
                    QueryHistory.add(new QueryHistory.Entry(connectionId, sql, "", start, result.elapsedMs, -1));
                }
                results.add(result);
                QueryResult finalResult = result;
                mHandler.post(() -> {
                    tab.results.add(finalResult);
                    tab.runDone++;
                    tab.resultIndex = tab.results.size() - 1;
                    if (mAttached) update();
                });
                if (result.error != null) break;
            }
            boolean schemaChanged = false;
            for (String sql : statements) {
                String keyword = SqlSafety.firstKeyword(sql, session.getType());
                if (keyword.equals("CREATE") || keyword.equals("DROP") || keyword.equals("ALTER") || keyword.equals("RENAME")) schemaChanged = true;
            }
            boolean refresh = schemaChanged;
            post(tab, () -> {
                tab.state = DbTab.READY;
                tab.runTotal = 0;
                // Show the first result with rows, or the error, or the last one.
                for (int i = 0; i < tab.results.size(); i++) {
                    if (tab.results.get(i).error != null) {
                        tab.resultIndex = i;
                        break;
                    }
                }
                if (mAttached && !tab.results.isEmpty()) {
                    if (!mWide) {
                        showPanel(DbTab.PANEL_RESULTS);
                    } else if (mTab.resultsCollapsed) {
                        mTab.resultsCollapsed = false;
                        arrangePanels(true);
                        updateToggleButton();
                    }
                }
                if (refresh) {
                    tab.tables.clear();
                    tab.columns.clear();
                    if (mAttached) refreshSchema();
                }
            });
        });
    }

    private void cancel() {
        DbSession session = mTab.getSession();
        if (session != null) session.cancel();
    }

    /** Fetch more rows of the shown result, by running its statement again with a larger limit. */
    private void fetchMore() {
        DbTab tab = mTab;
        DbSession session = tab.getSession();
        if (session == null || tab.state != DbTab.READY) return;
        if (tab.dataEditor != null) {
            tab.dataEditor.loadMore();
            return;
        }
        if (tab.results.isEmpty()) return;
        int index = tab.resultIndex;
        QueryResult old = tab.results.get(index);
        if (!old.hasMore || SqlSafety.isWrite(old.sql, session.getType())) return;
        tab.state = DbTab.RUNNING;
        update();
        int limit = old.rows.size() + tab.pageSize;
        int timeout = tab.timeoutSeconds;
        tab.executor.execute(() -> {
            QueryResult result;
            try {
                result = session.execute(old.sql, limit, timeout);
            } catch (SQLException | RuntimeException e) {
                result = QueryResult.ofError(old.sql, describe(mActivity, e));
            }
            QueryResult finalResult = result;
            post(tab, () -> {
                tab.state = DbTab.READY;
                if (index < tab.results.size()) tab.results.set(index, finalResult);
                if (mAttached) updateResults(true);
            });
        });
    }

    /** Commit or roll back the transaction. */
    private void commit(boolean commit) {
        DbTab tab = mTab;
        DbSession session = tab.getSession();
        if (session == null || tab.state != DbTab.READY) return;
        tab.state = DbTab.RUNNING;
        update();
        tab.executor.execute(() -> {
            String error = null;
            try {
                if (commit) session.commit();
                else session.rollback();
            } catch (SQLException | RuntimeException e) {
                error = describe(mActivity, e);
            }
            String finalError = error;
            post(tab, () -> {
                tab.state = DbTab.READY;
                if (finalError != null) DbDialogs.showProgressError(mActivity, finalError);
                else Toast.makeText(mActivity, commit ? R.string.msg_db_committed : R.string.msg_db_rolled_back, Toast.LENGTH_SHORT).show();
            });
        });
    }

    /** Turn auto commit on or off. Turning it on commits the changes. */
    private void setAutoCommit(boolean autoCommit) {
        DbTab tab = mTab;
        DbSession session = tab.getSession();
        if (session == null || tab.state != DbTab.READY) return;
        if (autoCommit && session.isInTransaction()) {
            new AlertDialog.Builder(mActivity)
                .setMessage(R.string.msg_db_auto_commit_commits)
                .setPositiveButton(R.string.action_db_commit, (d, which) -> doSetAutoCommit(true))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return;
        }
        doSetAutoCommit(autoCommit);
    }

    private void doSetAutoCommit(boolean autoCommit) {
        DbTab tab = mTab;
        DbSession session = tab.getSession();
        if (session == null) return;
        tab.executor.execute(() -> {
            String error = null;
            try {
                session.setAutoCommit(autoCommit);
            } catch (SQLException | RuntimeException e) {
                error = describe(mActivity, e);
            }
            String finalError = error;
            post(tab, () -> {
                if (finalError != null) DbDialogs.showProgressError(mActivity, finalError);
                else tab.autoCommit = autoCommit;
            });
        });
    }

    private void explain() {
        DbSession session = mTab.getSession();
        if (session == null || mTab.state != DbTab.READY) return;
        String text = mEditor.getText().toString();
        SqlScript.Statement statement = SqlScript.at(text, Math.max(0, mEditor.getSelectionStart()), session.getType());
        if (statement == null) {
            Toast.makeText(mActivity, R.string.msg_db_nothing_to_run, Toast.LENGTH_SHORT).show();
            return;
        }
        String keyword = SqlSafety.firstKeyword(statement.text, session.getType());
        String sql = keyword.equals("EXPLAIN") ? statement.text : session.getExplainStatement(statement.text);
        List<String> statements = new ArrayList<>();
        statements.add(sql);
        runStatements(statements);
    }

    private void saveCurrentQuery() {
        String text = mEditor.getText().toString();
        int start = Math.min(mEditor.getSelectionStart(), mEditor.getSelectionEnd());
        int end = Math.max(mEditor.getSelectionStart(), mEditor.getSelectionEnd());
        String sql;
        if (start >= 0 && end > start) sql = text.substring(start, end);
        else {
            SqlScript.Statement statement = SqlScript.at(text, Math.max(0, start), getType());
            sql = statement != null ? statement.text : "";
        }
        if (sql.trim().isEmpty()) {
            Toast.makeText(mActivity, R.string.msg_db_nothing_to_run, Toast.LENGTH_SHORT).show();
            return;
        }
        DbDialogs.saveQuery(mActivity, mTab.connectionId, sql.trim());
    }

    /** Insert SQL at the cursor, on a line of its own, and show the editor. */
    void insertSql(@NonNull String sql) {
        Editable text = mEditor.getText();
        int position = Math.max(0, mEditor.getSelectionEnd());
        String insert = sql;
        if (position > 0 && text.charAt(position - 1) != '\n') insert = "\n" + insert;
        if (!insert.trim().endsWith(";")) insert = insert + ";";
        mEditHistory.startNewStep();
        text.insert(position, insert);
        mEditor.setSelection(position + insert.length());
        showPanel(DbTab.PANEL_SQL);
    }

    /** Insert text at the cursor, like a table or column name. */
    void insertText(@NonNull String insert) {
        Editable text = mEditor.getText();
        int start = Math.max(0, Math.min(mEditor.getSelectionStart(), mEditor.getSelectionEnd()));
        int end = Math.max(0, Math.max(mEditor.getSelectionStart(), mEditor.getSelectionEnd()));
        text.replace(start, end, insert);
        mEditor.setSelection(start + insert.length());
        if (!mWide) showPanel(DbTab.PANEL_SQL);
    }

    private void showMenu(@NonNull View anchor) {
        PopupMenu popup = new PopupMenu(mActivity, anchor);
        Menu menu = popup.getMenu();
        menu.add(0, 1, 0, R.string.action_db_auto_commit).setCheckable(true).setChecked(mTab.autoCommit).setEnabled(mTab.state == DbTab.READY);
        menu.add(0, 2, 0, R.string.action_db_page_size);
        menu.add(0, 3, 0, R.string.action_db_timeout);
        menu.add(0, 4, 0, R.string.action_db_format_sql);
        menu.add(0, 5, 0, R.string.action_db_clear_editor);
        menu.add(0, 6, 0, R.string.title_db_connections);
        menu.add(0, 7, 0, R.string.action_close_terminal_tab_menu);
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: setAutoCommit(!mTab.autoCommit); return true;
                case 2: choose(R.string.action_db_page_size, new int[]{100, 200, 500, 1000, 5000}, mTab.pageSize, value -> mTab.pageSize = value); return true;
                case 3: choose(R.string.action_db_timeout, new int[]{0, 10, 30, 60, 300}, mTab.timeoutSeconds, value -> mTab.timeoutSeconds = value); return true;
                case 4: formatSql(); return true;
                case 5:
                    mEditHistory.startNewStep();
                    mEditor.getText().clear();
                    return true;
                case 6: DbDialogs.showConnections(mActivity, mTabs::open); return true;
                case 7: mTabs.close(mTab); return true;
                default: return false;
            }
        });
        popup.show();
    }

    private void choose(int titleId, @NonNull int[] values, int current, @NonNull java.util.function.IntConsumer set) {
        CharSequence[] labels = new CharSequence[values.length];
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = titleId == R.string.action_db_timeout
                ? (values[i] == 0 ? getContext().getString(R.string.db_timeout_none) : getContext().getString(R.string.db_seconds, values[i]))
                : getContext().getString(R.string.db_rows, values[i]);
            if (values[i] == current) checked = i;
        }
        new AlertDialog.Builder(mActivity)
            .setTitle(titleId)
            .setSingleChoiceItems(labels, checked, (d, which) -> {
                set.accept(values[which]);
                d.dismiss();
                update();
            })
            .show();
    }

    /** Put the main clauses of the statement at the cursor, or of the selection, on lines of their own. */
    private void formatSql() {
        String text = mEditor.getText().toString();
        int start = Math.min(mEditor.getSelectionStart(), mEditor.getSelectionEnd());
        int end = Math.max(mEditor.getSelectionStart(), mEditor.getSelectionEnd());
        if (start < 0 || end <= start) {
            SqlScript.Statement statement = SqlScript.at(text, Math.max(0, start), getType());
            if (statement == null) return;
            start = statement.start;
            end = statement.end;
        }
        String formatted = SqlFormatter.format(text.substring(start, end), getType());
        mEditHistory.startNewStep();
        mEditor.getText().replace(start, end, formatted);
    }

    // Schema

    private void refreshSchema() {
        DbTab tab = mTab;
        DbSession session = tab.getSession();
        if (session == null || tab.state != DbTab.READY) return;
        List<String> expanded = new ArrayList<>(tab.expandedSchemas);
        tab.state = DbTab.RUNNING;
        update();
        tab.executor.execute(() -> {
            String error = null;
            List<String> schemas = new ArrayList<>();
            java.util.Map<String, List<DbObjects.Table>> tables = new java.util.HashMap<>();
            try {
                schemas = session.listSchemas();
                for (String schema : expanded) if (schemas.contains(schema)) tables.put(schema, session.listTables(schema));
            } catch (SQLException | RuntimeException e) {
                error = describe(mActivity, e);
            }
            List<String> finalSchemas = schemas;
            String finalError = error;
            post(tab, () -> {
                tab.state = DbTab.READY;
                if (finalError != null) {
                    DbDialogs.showProgressError(mActivity, finalError);
                    return;
                }
                tab.schemas.clear();
                tab.schemas.addAll(finalSchemas);
                tab.tables.clear();
                tab.tables.putAll(tables);
                tab.columns.clear();
                tab.expandedTables.clear();
                tab.expandedSchemas.retainAll(tables.keySet());
            });
        });
    }

    /** A row of the schema tree. */
    static final class Node {
        static final int SCHEMA = 0;
        static final int TABLE = 1;
        static final int COLUMN = 2;
        static final int LOADING = 3;

        final int kind;
        final int level;
        @Nullable final String schema;
        @Nullable final DbObjects.Table table;
        @Nullable final DbObjects.Column column;

        Node(int kind, int level, @Nullable String schema, @Nullable DbObjects.Table table, @Nullable DbObjects.Column column) {
            this.kind = kind;
            this.level = level;
            this.schema = schema;
            this.table = table;
            this.column = column;
        }
    }

    private final class SchemaAdapter extends BaseAdapter {
        private final List<Node> mNodes = new ArrayList<>();

        void rebuild() {
            mNodes.clear();
            String filter = mSchemaFilter.getText().toString().trim().toLowerCase(Locale.ROOT);
            for (String schema : mTab.schemas) {
                boolean expanded = mTab.expandedSchemas.contains(schema) || !filter.isEmpty() && mTab.tables.containsKey(schema);
                mNodes.add(new Node(Node.SCHEMA, 0, schema, null, null));
                if (!expanded) continue;
                List<DbObjects.Table> tables = mTab.tables.get(schema);
                if (tables == null) {
                    mNodes.add(new Node(Node.LOADING, 1, schema, null, null));
                    continue;
                }
                for (DbObjects.Table table : tables) {
                    if (!filter.isEmpty() && !table.name.toLowerCase(Locale.ROOT).contains(filter)) continue;
                    mNodes.add(new Node(Node.TABLE, 1, schema, table, null));
                    String key = DbTab.key(schema, table.name);
                    if (!mTab.expandedTables.contains(key)) continue;
                    List<DbObjects.Column> columns = mTab.columns.get(key);
                    if (columns == null) mNodes.add(new Node(Node.LOADING, 2, schema, table, null));
                    else for (DbObjects.Column column : columns) mNodes.add(new Node(Node.COLUMN, 2, schema, table, column));
                }
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mNodes.size();
        }

        @Override
        public Node getItem(int position) {
            return mNodes.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView view = convertView instanceof TextView ? (TextView) convertView : new TextView(getContext());
            Node node = mNodes.get(position);
            view.setPadding(dp(10 + 16 * node.level), dp(7), dp(8), dp(7));
            view.setSingleLine();
            view.setEllipsize(TextUtils.TruncateAt.END);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            view.setTypeface(Typeface.MONOSPACE);
            view.setTextColor(0xFFE0E0E0);
            switch (node.kind) {
                case Node.SCHEMA:
                    boolean expanded = mTab.expandedSchemas.contains(node.schema);
                    view.setText((expanded ? "▾ " : "▸ ") + node.schema + (node.schema != null && node.schema.equals(mTab.defaultSchema) ? "  ●" : ""));
                    view.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                    break;
                case Node.TABLE:
                    boolean open = mTab.expandedTables.contains(DbTab.key(node.schema, node.table.name));
                    view.setText((open ? "▾ " : "▸ ") + (node.table.isView() ? "👁 " : "▦ ") + node.table.name);
                    break;
                case Node.COLUMN:
                    DbObjects.Column column = node.column;
                    view.setText((column.primaryKey > 0 ? "🔑 " : "   ") + column.name + "  " + column.type + (column.nullable ? "" : " NOT NULL"));
                    view.setTextColor(0xFFB0B0B0);
                    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                    break;
                default:
                    view.setText("…");
                    view.setTextColor(0xFF808080);
            }
            return view;
        }
    }

    private void onSchemaClick(@NonNull Node node) {
        if (node.kind == Node.SCHEMA) {
            if (!mTab.expandedSchemas.remove(node.schema)) {
                mTab.expandedSchemas.add(node.schema);
                if (!mTab.tables.containsKey(node.schema)) loadTables(node.schema);
            }
            mSchemaAdapter.rebuild();
        } else if (node.kind == Node.TABLE) {
            String key = DbTab.key(node.schema, node.table.name);
            if (!mTab.expandedTables.remove(key)) {
                mTab.expandedTables.add(key);
                if (!mTab.columns.containsKey(key)) loadColumns(node.table);
            }
            mSchemaAdapter.rebuild();
        } else if (node.kind == Node.COLUMN) {
            insertText(getType().quote(node.column.name));
        }
    }

    private void loadTables(@NonNull String schema) {
        DbTab tab = mTab;
        DbSession session = tab.getSession();
        if (session == null) return;
        tab.executor.execute(() -> {
            try {
                List<DbObjects.Table> tables = session.listTables(schema);
                post(tab, () -> tab.tables.put(schema, tables));
            } catch (SQLException | RuntimeException e) {
                String message = describe(mActivity, e);
                post(tab, () -> {
                    tab.expandedSchemas.remove(schema);
                    if (mAttached) DbDialogs.showProgressError(mActivity, message);
                });
            }
        });
    }

    /** Load the columns of a table, then run {@code done} if given. */
    void loadColumns(@NonNull DbObjects.Table table, @Nullable Runnable done) {
        DbTab tab = mTab;
        DbSession session = tab.getSession();
        if (session == null) return;
        String key = DbTab.key(table.schema, table.name);
        tab.executor.execute(() -> {
            try {
                List<DbObjects.Column> columns = session.listColumns(table.schema, table.name);
                post(tab, () -> {
                    tab.columns.put(key, columns);
                    if (done != null) done.run();
                });
            } catch (SQLException | RuntimeException e) {
                String message = describe(mActivity, e);
                post(tab, () -> {
                    tab.expandedTables.remove(key);
                    if (mAttached) DbDialogs.showProgressError(mActivity, message);
                });
            }
        });
    }

    private void loadColumns(@NonNull DbObjects.Table table) {
        loadColumns(table, null);
    }

    private void onSchemaLongClick(@NonNull Node node) {
        if (node.kind == Node.COLUMN && node.column != null) {
            DbDialogs.copy(mActivity, node.column.name);
            return;
        }
        if (node.kind == Node.SCHEMA && node.schema != null) {
            CharSequence[] items = {getContext().getString(R.string.action_db_copy_name), getContext().getString(R.string.action_db_use_schema)};
            new AlertDialog.Builder(mActivity).setTitle(node.schema).setItems(items, (d, which) -> {
                if (which == 0) DbDialogs.copy(mActivity, node.schema);
                else insertSql(useSchemaStatement(node.schema));
            }).show();
            return;
        }
        if (node.kind != Node.TABLE || node.table == null) return;
        DbObjects.Table table = node.table;
        String name = getType().quote(table.schema, table.name);
        List<CharSequence> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        items.add(getContext().getString(R.string.action_db_select_rows));
        actions.add(() -> {
            insertSql("SELECT * FROM " + name);
            List<String> statements = new ArrayList<>();
            statements.add("SELECT * FROM " + name);
            runStatements(statements);
        });
        if (!table.isView() && !mTab.connectionReadOnly()) {
            items.add(getContext().getString(R.string.action_db_edit_rows));
            actions.add(() -> DbDataEditor.open(this, table));
        }
        items.add(getContext().getString(R.string.action_db_count_rows));
        actions.add(() -> countRows(table));
        items.add(getContext().getString(R.string.action_db_table_info));
        actions.add(() -> showTableInfo(table));
        items.add(getContext().getString(R.string.action_db_ddl));
        actions.add(() -> showDdl(table));
        items.add(getContext().getString(R.string.action_db_export_table));
        actions.add(() -> DbTableExport.start(this, table));
        if (!table.isView() && !mTab.connectionReadOnly()) {
            items.add(getContext().getString(R.string.action_db_import_csv));
            actions.add(() -> DbCsvImport.start(this, table));
        }
        items.add(getContext().getString(R.string.action_db_insert_name));
        actions.add(() -> insertText(name));
        items.add(getContext().getString(R.string.action_db_copy_name));
        actions.add(() -> DbDialogs.copy(mActivity, name));
        new AlertDialog.Builder(mActivity)
            .setTitle(table.name)
            .setItems(items.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
            .show();
    }

    @NonNull
    private String useSchemaStatement(@NonNull String schema) {
        switch (getType()) {
            case POSTGRESQL: return "SET search_path TO " + getType().quote(schema);
            case MARIADB: return "USE " + getType().quote(schema);
            default: return "-- " + schema;
        }
    }

    /** Run a metadata operation on the thread of the tab and show its text. */
    private void showMetadata(@NonNull String title, @NonNull MetadataTask task, boolean insertable) {
        DbTab tab = mTab;
        DbSession session = tab.getSession();
        if (session == null || tab.state != DbTab.READY) return;
        tab.state = DbTab.RUNNING;
        update();
        tab.executor.execute(() -> {
            String text;
            boolean failed = false;
            try {
                text = task.run(session);
            } catch (SQLException | RuntimeException e) {
                text = describe(mActivity, e);
                failed = true;
            }
            String finalText = text;
            boolean finalFailed = failed;
            post(tab, () -> {
                tab.state = DbTab.READY;
                if (!mAttached) return;
                if (finalFailed) DbDialogs.showProgressError(mActivity, finalText);
                else DbDialogs.showText(mActivity, title, finalText, insertable ? this::insertSql : null);
            });
        });
    }

    private interface MetadataTask {
        String run(DbSession session) throws SQLException;
    }

    private void countRows(@NonNull DbObjects.Table table) {
        showMetadata(table.name, session -> getContext().getString(R.string.db_rows, session.countRows(table)), false);
    }

    private void showDdl(@NonNull DbObjects.Table table) {
        showMetadata(table.name, session -> session.getDdl(table), true);
    }

    private void showTableInfo(@NonNull DbObjects.Table table) {
        showMetadata(table.name, session -> {
            StringBuilder text = new StringBuilder();
            text.append(getContext().getString(R.string.db_info_columns)).append('\n');
            for (DbObjects.Column column : session.listColumns(table.schema, table.name)) {
                text.append("  ").append(column.primaryKey > 0 ? "🔑 " : "   ").append(column.name).append("  ").append(column.type);
                if (!column.nullable) text.append(" NOT NULL");
                if (column.defaultValue != null) text.append(" DEFAULT ").append(column.defaultValue);
                text.append('\n');
            }
            List<String> indexes = session.listIndexes(table.schema, table.name);
            text.append('\n').append(getContext().getString(R.string.db_info_indexes)).append('\n');
            if (indexes.isEmpty()) text.append("  -\n");
            for (String index : indexes) text.append("  ").append(index).append('\n');
            List<String> keys = session.listForeignKeys(table.schema, table.name);
            text.append('\n').append(getContext().getString(R.string.db_info_foreign_keys)).append('\n');
            if (keys.isEmpty()) text.append("  -\n");
            for (String key : keys) text.append("  ").append(key).append('\n');
            return text.toString();
        }, false);
    }

    // Results

    /** Show the results of the tab, keeping the grid position if {@code keepPosition}. */
    void updateResults(boolean keepPosition) {
        List<QueryResult> results = mTab.results;
        mResultTabs.removeAllViews();
        mResultTabsScroll.setVisibility(results.size() > 1 ? VISIBLE : GONE);
        for (int i = 0; i < results.size(); i++) {
            QueryResult result = results.get(i);
            Button button = new Button(getContext(), null, android.R.attr.borderlessButtonStyle);
            button.setAllCaps(false);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            button.setMinHeight(0);
            button.setMinimumHeight(0);
            button.setFocusable(false);
            String label = (i + 1) + ": " + SqlSafety.firstKeyword(result.sql, getType());
            if (result.error != null) label += " ✗";
            else label += " " + (result.hasRows() ? result.rows.size() : result.updateCount);
            button.setText(label);
            button.setTextColor(i == mTab.resultIndex ? 0xFF4FC3F7 : 0xFFB0B0B0);
            int index = i;
            button.setOnClickListener(v -> {
                mTab.resultIndex = index;
                updateResults(false);
            });
            mResultTabs.addView(button, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
        }
        QueryResult result = results.isEmpty() ? null : results.get(Math.min(mTab.resultIndex, results.size() - 1));
        if (mTab.dataEditor != null) {
            mResultTabsScroll.setVisibility(GONE);
            mTab.dataEditor.bind(this);
            return;
        }
        mEditBarHolder.setVisibility(GONE);
        mGrid.setMarker(null);
        if (result != null && result.hasRows()) {
            if (mGrid.getResult() != result) mGrid.setResult(result, keepPosition);
            mGrid.setVisibility(VISIBLE);
            mMessageFrame.setVisibility(GONE);
            mFilterRow.setVisibility(VISIBLE);
        } else {
            mGrid.setResult(null, false);
            mGrid.setVisibility(GONE);
            mMessageFrame.setVisibility(VISIBLE);
            mFilterRow.setVisibility(GONE);
            if (result == null) {
                mMessage.setText(mTab.state == DbTab.RUNNING ? "" : getContext().getString(R.string.msg_db_no_results));
                mMessage.setTextColor(0xFF808080);
            } else if (result.error != null) {
                mMessage.setText(getContext().getString(R.string.msg_db_statement_failed, result.error, result.sql));
                mMessage.setTextColor(0xFFEF9A9A);
            } else {
                String text = getContext().getString(R.string.msg_db_rows_changed, result.updateCount);
                if (result.message != null) text += "\n\n" + result.message;
                mMessage.setText(text);
                mMessage.setTextColor(0xFFE0E0E0);
            }
        }
        updateResultStatus(result);
    }

    void updateResultStatus(@Nullable QueryResult result) {
        if (result == null) {
            mResultStatus.setText("");
            mMoreButton.setVisibility(GONE);
            return;
        }
        String status;
        if (result.hasRows()) {
            status = getContext().getString(R.string.db_rows, result.rows.size());
            if (mGrid.getShownRowCount() != result.rows.size()) status = getContext().getString(R.string.db_rows_shown, mGrid.getShownRowCount(), result.rows.size());
            if (result.hasMore) status += "+";
            List<Integer> selected = mGrid.getSelectedRows();
            if (!selected.isEmpty()) status += " · " + getContext().getString(R.string.db_rows_selected, selected.size());
        } else {
            status = result.error != null ? getContext().getString(R.string.db_history_failed) : getContext().getString(R.string.msg_db_rows_changed, result.updateCount);
        }
        status += " · " + result.elapsedMs + " ms";
        if (result.message != null && result.hasRows()) status += " · " + result.message.split("\n")[0];
        mResultStatus.setText(status);
        mMoreButton.setVisibility(result.hasMore && result.hasRows() ? VISIBLE : GONE);
    }

    @Nullable
    private QueryResult getShownResult() {
        return mGrid.getResult();
    }

    private void copyResult() {
        QueryResult result = getShownResult();
        if (result == null) return;
        List<Integer> selected = mGrid.getSelectedRows();
        DbDialogs.copy(mActivity, ResultExport.format(ResultExport.TSV, result, selected.isEmpty() ? mGrid.getShownRows() : selected, null, getType(), null));
    }

    private void exportResult() {
        QueryResult result = getShownResult();
        if (result == null) return;
        List<Integer> selected = mGrid.getSelectedRows();
        DbDialogs.showExport(mActivity, result, selected.isEmpty() ? mGrid.getShownRows() : selected, getType(), null);
    }

    @NonNull
    ResultGridView getGrid() {
        return mGrid;
    }

    /** Show the tab after work that ended, in this view or the one that replaced it. */
    void refreshAfterWork() {
        if (mAttached) update();
        else mTabs.onTabStateChanged(mTab);
    }

    @NonNull
    FrameLayout getEditBarHolder() {
        return mEditBarHolder;
    }

    @NonNull
    View getResultMessageFrame() {
        return mMessageFrame;
    }

    /** Show a status under the results, with the button to fetch more rows if {@code more}. */
    void setResultStatus(@NonNull String status, boolean more) {
        mResultStatus.setText(status);
        mMoreButton.setVisibility(more ? VISIBLE : GONE);
    }

    @NonNull
    TextView getResultMessage() {
        return mMessage;
    }

    @NonNull
    LinearLayout getFilterRow() {
        return mFilterRow;
    }

    private final class GridListener implements ResultGridView.Listener {
        @Override
        public void onCellLongPress(int row, int column) {
            QueryResult result = mGrid.getResult();
            if (result == null) return;
            if (mTab.dataEditor != null) {
                mTab.dataEditor.onCellLongPress(row, column);
                return;
            }
            Object value = result.rows.get(row)[column];
            CharSequence[] items = {
                getContext().getString(R.string.action_db_view_value), getContext().getString(R.string.action_db_copy_value),
                getContext().getString(R.string.action_db_copy_row), getContext().getString(R.string.action_db_copy_column),
                getContext().getString(R.string.action_db_row_card), getContext().getString(R.string.action_db_filter_value)};
            new AlertDialog.Builder(mActivity)
                .setTitle(result.columns.get(column).name)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            DbDialogs.showText(mActivity, result.columns.get(column).name, DbDialogs.formatValue(value), null);
                            break;
                        case 1:
                            DbDialogs.copy(mActivity, value == null ? "NULL" : QueryResult.toText(value));
                            break;
                        case 2:
                            DbDialogs.copy(mActivity, ResultExport.format(ResultExport.TSV, result, java.util.Collections.singletonList(row), null, getType(), null));
                            break;
                        case 3:
                            DbDialogs.copy(mActivity, ResultExport.format(ResultExport.TSV, result, mGrid.getShownRows(),
                                java.util.Collections.singletonList(column), getType(), null));
                            break;
                        case 4:
                            DbDialogs.showRowCard(mActivity, result, row);
                            break;
                        default:
                            mResultFilter.setText(value == null ? "" : QueryResult.toText(value));
                    }
                })
                .show();
        }

        @Override
        public void onRowNumberLongPress(int row) {
            QueryResult result = mGrid.getResult();
            if (result == null) return;
            if (mTab.dataEditor != null) {
                mTab.dataEditor.onRowLongPress(row);
                return;
            }
            DbDialogs.showRowCard(mActivity, result, row);
        }

        @Override
        public void onHeaderLongPress(int column) {
            QueryResult result = mGrid.getResult();
            if (result == null) return;
            QueryResult.Column info = result.columns.get(column);
            CharSequence[] items = {getContext().getString(R.string.action_db_copy_name), getContext().getString(R.string.action_db_copy_column),
                getContext().getString(R.string.action_db_insert_name)};
            new AlertDialog.Builder(mActivity)
                .setTitle(info.name + " (" + info.typeName + ")")
                .setItems(items, (d, which) -> {
                    if (which == 0) DbDialogs.copy(mActivity, info.name);
                    else if (which == 1) DbDialogs.copy(mActivity, ResultExport.format(ResultExport.TSV, result, mGrid.getShownRows(),
                        java.util.Collections.singletonList(column), getType(), null));
                    else insertText(getType().quote(info.name));
                })
                .show();
        }

        @Override
        public void onSelectionChanged() {
            if (mTab.dataEditor != null) mTab.dataEditor.updateStatus();
            else updateResultStatus(mGrid.getResult());
        }

        @Override
        public void onCellDoubleTap(int row, int column) {
            QueryResult result = mGrid.getResult();
            if (result == null) return;
            if (mTab.dataEditor != null) mTab.dataEditor.onCellDoubleTap(row, column);
            else DbDialogs.showText(mActivity, result.columns.get(column).name, DbDialogs.formatValue(result.rows.get(row)[column]), null);
        }
    }

    // Keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && handleKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private boolean handleKey(@NonNull KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (mCompletion != null && mCompletion.onKey(event)) return true;
        boolean repeat = event.getRepeatCount() > 0;
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (repeat) return true;
            if (!mWide && mTab.panel != DbTab.PANEL_SQL) showPanel(DbTab.PANEL_SQL);
            else mTabs.hide(true);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && mTab.state == DbTab.RUNNING) {
            cancel();
            return true;
        }
        if (!event.isCtrlPressed() || event.isAltPressed() || event.isMetaPressed()) return false;
        boolean shift = event.isShiftPressed();
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!repeat) run(shift);
                return true;
            case KeyEvent.KEYCODE_TAB:
                mTabs.getHost().switchTab(!shift);
                return true;
            case KeyEvent.KEYCODE_SPACE:
                if (mCompletion != null) mCompletion.show(true);
                return true;
            case KeyEvent.KEYCODE_1: case KeyEvent.KEYCODE_2: case KeyEvent.KEYCODE_3:
                if (shift) return false;
                showPanel(keyCode - KeyEvent.KEYCODE_1);
                return true;
            case KeyEvent.KEYCODE_E:
                if (shift) return false;
                if (!repeat) explain();
                return true;
            case KeyEvent.KEYCODE_T:
                if (!shift) return false;
                if (!repeat) mTabs.getHost().openLocalShell();
                return true;
            case KeyEvent.KEYCODE_W:
                if (!shift) return false;
                if (!repeat) mTabs.close(mTab);
                return true;
            default:
                return false;
        }
    }

    /** Calls a runnable after a text change. */
    static final class SimpleWatcher implements TextWatcher {
        private final Runnable mRunnable;

        SimpleWatcher(@NonNull Runnable runnable) {
            mRunnable = runnable;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}

        @Override
        public void afterTextChanged(Editable s) {
            mRunnable.run();
        }
    }

}
