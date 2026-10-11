package com.termux.app.terminal.db;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The database tabs, shown in the tab bar after the web tabs. A selected one is shown in place of
 * the terminal like a web tab. The tabs and their connections live as long as the app process;
 * their connections and SQL are kept, and the tabs are opened again, not connected, when the app is.
 */
public final class DbTabs {

    /** The tab bar, which shows the database tabs and switches between all tabs. */
    public interface Host {
        void onDbTabsChanged();

        void onDbTabsShown();

        void onDbTabsHidden();

        void switchTab(boolean forward);

        void openLocalShell();
    }

    static final int MAX_TABS = 10;
    private static final String PREFERENCES = "db_tabs";
    private static final String KEY_TABS = "tabs";
    private static final String LOG_TAG = "DbTabs";

    private static final List<DbTab> sTabs = new ArrayList<>();
    private static boolean sRestored;

    private final Activity mActivity;
    private final Host mHost;
    private final FrameLayout mContainer;
    @Nullable private DbTab mSelected;
    @Nullable private DbTabView mView;

    public DbTabs(@NonNull Activity activity, @NonNull Host host, @NonNull View terminalView) {
        mActivity = activity;
        mHost = host;
        mContainer = new FrameLayout(activity);
        mContainer.setVisibility(View.GONE);
        // In place of the terminal when shown.
        ViewGroup parent = (ViewGroup) terminalView.getParent();
        parent.addView(mContainer, parent.indexOfChild(terminalView) + 1,
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        if (!sRestored) {
            sRestored = true;
            restore();
        }
    }

    @NonNull
    Activity getActivity() {
        return mActivity;
    }

    @NonNull
    Host getHost() {
        return mHost;
    }

    @NonNull
    public List<DbTab> getTabs() {
        return sTabs;
    }

    @Nullable
    public DbTab getSelected() {
        return mSelected;
    }

    @Nullable
    DbTabView getView() {
        return mView;
    }

    public boolean isShown() {
        return mSelected != null;
    }

    /** The label of a tab in the tab bar: the name of its connection, with ● while a statement runs. */
    @NonNull
    public String getLabel(@NonNull DbTab tab) {
        DbConnectionConfig config = DbConnectionConfig.find(DbConnectionStore.load(), tab.connectionId);
        String title = config != null ? config.getTitle() : "?";
        if (tab.state == DbTab.RUNNING || tab.state == DbTab.CONNECTING) title += " …";
        if (tab.connection != null && tab.connection.session.isInTransaction()) title += " *";
        return title;
    }

    /** Open a new tab of a connection, and connect. */
    public void open(@NonNull DbConnectionConfig config) {
        if (sTabs.size() >= MAX_TABS) {
            Toast.makeText(mActivity, mActivity.getString(R.string.msg_db_max_tabs, MAX_TABS), Toast.LENGTH_LONG).show();
            return;
        }
        if (config.type.isNetwork() && !DbConnector.isSupported()) {
            Toast.makeText(mActivity, R.string.msg_db_android_version, Toast.LENGTH_LONG).show();
            return;
        }
        DbTab tab = new DbTab(config.id);
        sTabs.add(tab);
        save();
        select(tab);
    }

    /** Show a tab in place of the terminal, connecting it if it is not connected. */
    public void select(@NonNull DbTab tab) {
        if (mSelected == tab && mView != null) return;
        boolean wasShown = isShown();
        if (mView != null) mView.detach();
        mSelected = tab;
        if (!wasShown) {
            mHost.onDbTabsShown();
            mContainer.setVisibility(View.VISIBLE);
        }
        mContainer.removeAllViews();
        mView = new DbTabView(this, tab);
        mContainer.addView(mView);
        mView.onShown();
        mHost.onDbTabsChanged();
    }

    /** Stop showing a database tab. The host is told unless another kind of tab is being shown. */
    public void hide(boolean notifyHost) {
        if (!isShown()) return;
        if (mView != null) mView.detach();
        mView = null;
        mSelected = null;
        mContainer.removeAllViews();
        mContainer.setVisibility(View.GONE);
        save();
        if (notifyHost) mHost.onDbTabsHidden();
        mHost.onDbTabsChanged();
    }

    /** Close a tab, asking first if it has changes that are not committed. */
    public void close(@NonNull DbTab tab) {
        if (tab.connection != null && tab.connection.session.isInTransaction()) {
            new AlertDialog.Builder(mActivity)
                .setMessage(R.string.msg_db_close_uncommitted)
                .setPositiveButton(R.string.action_db_rollback_and_close, (d, which) -> doClose(tab))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return;
        }
        doClose(tab);
    }

    private void doClose(@NonNull DbTab tab) {
        int index = sTabs.indexOf(tab);
        if (index < 0) return;
        sTabs.remove(index);
        disconnect(tab);
        tab.executor.shutdown();
        save();
        if (tab == mSelected) {
            if (!sTabs.isEmpty()) select(sTabs.get(Math.min(index, sTabs.size() - 1)));
            else hide(true);
        } else {
            mHost.onDbTabsChanged();
        }
    }

    /** Close the connection of a tab in its thread, rolling back changes that are not committed. */
    void disconnect(@NonNull DbTab tab) {
        DbConnector.Connection connection = tab.connection;
        tab.reset();
        if (connection == null) return;
        tab.executor.execute(() -> {
            try {
                if (connection.session.isInTransaction()) connection.session.rollback();
            } catch (java.sql.SQLException | RuntimeException ignored) {
            }
            connection.close();
        });
    }

    public void move(@NonNull DbTab tab, int offset) {
        int index = sTabs.indexOf(tab);
        int newIndex = index + offset;
        if (index < 0 || newIndex < 0 || newIndex >= sTabs.size()) return;
        sTabs.remove(index);
        sTabs.add(newIndex, tab);
        save();
        mHost.onDbTabsChanged();
    }

    /** Show the actions of a tab, from a long press on it in the tab bar. */
    public void showTabMenu(@NonNull DbTab tab, int position) {
        int index = sTabs.indexOf(tab);
        List<CharSequence> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (tab.connection != null) {
            items.add(mActivity.getString(R.string.action_db_disconnect));
            actions.add(() -> {
                disconnect(tab);
                if (tab == mSelected && mView != null) mView.update();
                mHost.onDbTabsChanged();
            });
        } else {
            items.add(mActivity.getString(R.string.action_db_connect));
            actions.add(() -> {
                select(tab);
                if (mView != null) mView.connect();
            });
        }
        if (index > 0) {
            items.add(mActivity.getString(R.string.action_move_terminal_tab_left));
            actions.add(() -> move(tab, -1));
        }
        if (index < sTabs.size() - 1) {
            items.add(mActivity.getString(R.string.action_move_terminal_tab_right));
            actions.add(() -> move(tab, 1));
        }
        items.add(mActivity.getString(R.string.action_close_terminal_tab_menu));
        actions.add(() -> close(tab));
        new AlertDialog.Builder(mActivity)
            .setTitle(mActivity.getString(R.string.title_terminal_tab_menu, position + 1))
            .setItems(items.toArray(new CharSequence[0]), (dialog, which) -> actions.get(which).run())
            .show();
    }

    /** Work of a tab ended after its view was replaced: show it in the current view. */
    void onTabStateChanged(@NonNull DbTab tab) {
        if (tab == mSelected && mView != null) mView.update();
        else mHost.onDbTabsChanged();
    }

    /** A tab changed, like its state or label. Called on the main thread. */
    void onTabChanged(@NonNull DbTab tab) {
        mHost.onDbTabsChanged();
    }

    // Saved tabs

    private SharedPreferences getPreferences() {
        return mActivity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    void save() {
        JSONArray array = new JSONArray();
        try {
            for (DbTab tab : sTabs) {
                JSONObject json = new JSONObject();
                json.put("connection", tab.connectionId);
                json.put("sql", tab.sql.length() > 100000 ? tab.sql.substring(0, 100000) : tab.sql);
                array.put(json);
            }
        } catch (JSONException e) {
            return;
        }
        getPreferences().edit().putString(KEY_TABS, array.toString()).apply();
    }

    private void restore() {
        List<DbConnectionConfig> configs = DbConnectionStore.load();
        try {
            JSONArray array = new JSONArray(getPreferences().getString(KEY_TABS, "[]"));
            for (int i = 0; i < array.length() && sTabs.size() < MAX_TABS; i++) {
                JSONObject json = array.getJSONObject(i);
                String id = json.optString("connection", "");
                if (DbConnectionConfig.find(configs, id) == null) continue;
                DbTab tab = new DbTab(id);
                tab.sql = json.optString("sql", "");
                sTabs.add(tab);
            }
        } catch (JSONException e) {
            Logger.logDebug(LOG_TAG, "Failed to read the database tabs: " + e.getMessage());
        }
    }

}
