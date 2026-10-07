package com.termux.app.terminal.tabs;

import android.app.AlertDialog;
import android.graphics.Paint;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.app.terminal.TermuxTerminalSessionClient;
import com.termux.shared.shell.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Shows the {@link TermuxService} sessions as tabs above the terminal, in addition to the sessions
 * list in the drawer. The single {@link com.termux.view.TerminalView} is switched between sessions
 * with {@link TermuxTerminalSessionClient#setCurrentSession(TerminalSession)}, the sessions keep
 * running in the background in {@link TermuxService} like before.
 */
public class TerminalTabsController {

    private final TermuxActivity mActivity;
    private final View mTabsBar;
    private final HorizontalScrollView mScrollView;
    private final LinearLayout mTabsLayout;

    /** Sessions closed from a tab while running, to be removed once their process has exited. */
    private static final Set<TerminalSession> sSessionsToRemoveOnExit = Collections.newSetFromMap(new WeakHashMap<>());

    public TerminalTabsController(@NonNull TermuxActivity activity) {
        mActivity = activity;
        mTabsBar = activity.findViewById(R.id.terminal_tabs_bar);
        mScrollView = activity.findViewById(R.id.terminal_tabs_scroll_view);
        mTabsLayout = activity.findViewById(R.id.terminal_tabs_layout);

        View newSessionButton = activity.findViewById(R.id.terminal_tabs_new_session_button);
        newSessionButton.setOnClickListener(v -> getSessionClient().addNewSession(false, null));
        newSessionButton.setOnLongClickListener(v -> {
            mActivity.showNewNamedSessionDialog();
            return true;
        });

        TerminalTabStates.setTabsController(this);

        TerminalTabsSettings.load();
        applySettings();
    }

    /** Reload the settings from the `termux.properties` file, like after `termux-reload-settings`. */
    public void reloadSettings() {
        TerminalTabsSettings.load();
        applySettings();
        refresh();
    }

    /** Show or hide the tab bar, and put it above or below the terminal. */
    private void applySettings() {
        mTabsBar.setVisibility(TerminalTabsSettings.areTabsShown() ? View.VISIBLE : View.GONE);

        ViewGroup parent = (ViewGroup) mTabsBar.getParent();
        int index = TerminalTabsSettings.areTabsAtBottom() ? parent.getChildCount() - 1 : 0;
        if (parent.indexOfChild(mTabsBar) != index) {
            parent.removeView(mTabsBar);
            parent.addView(mTabsBar, index);
        }
    }

    /** Whether the tab bar is shown. */
    public boolean isShown() {
        return TerminalTabsSettings.areTabsShown();
    }

    private TermuxTerminalSessionClient getSessionClient() {
        return mActivity.getTermuxTerminalSessionClient();
    }

    /** Called when the session displayed in the terminal has changed or may have changed. */
    public void onCurrentSessionChanged(TerminalSession currentSession) {
        TerminalTabStates.markSeen(currentSession);
        // The session may be changed while the activity is not visible, like when another one exits.
        if (mActivity.isVisible())
            TerminalSessionNotifications.onSessionSeen(mActivity, currentSession);
        refresh(true);
    }

    /** Rebuild the tabs from the current {@link TermuxService} sessions. */
    public void refresh() {
        refresh(false);
    }

    private void refresh(boolean scrollToSelectedTab) {
        TermuxService service = mActivity.getTermuxService();
        List<TermuxSession> sessions = service == null ? Collections.emptyList() : service.getTermuxSessions();
        TerminalSession currentSession = mActivity.getCurrentSession();

        // Reuse the tab views and only add or remove views when the number of sessions changes.
        while (mTabsLayout.getChildCount() > sessions.size())
            mTabsLayout.removeViewAt(mTabsLayout.getChildCount() - 1);
        LayoutInflater inflater = mActivity.getLayoutInflater();
        while (mTabsLayout.getChildCount() < sessions.size())
            mTabsLayout.addView(inflater.inflate(R.layout.item_terminal_tab, mTabsLayout, false));

        int selectedIndex = -1;
        for (int i = 0; i < sessions.size(); i++) {
            TerminalSession session = sessions.get(i).getTerminalSession();
            bindTab(mTabsLayout.getChildAt(i), i, session, session == currentSession);
            if (session == currentSession) selectedIndex = i;
        }

        if (scrollToSelectedTab && selectedIndex >= 0) scrollToTab(selectedIndex);
    }

    private void bindTab(View tabView, int index, TerminalSession session, boolean selected) {
        TextView titleView = tabView.findViewById(R.id.terminal_tab_title);
        titleView.setText(getTabTitle(index, session));

        // The current session is being displayed, so it has nothing unread.
        TerminalTabStates.State state = selected ? null : TerminalTabStates.get(session);
        TextView indicatorView = tabView.findViewById(R.id.terminal_tab_indicator);
        String stateDescription = null;
        if (state != null && state.hasBell) {
            indicatorView.setText(R.string.terminal_tab_indicator_bell);
            indicatorView.setTextColor(ContextCompat.getColor(mActivity, R.color.terminal_tab_indicator_bell));
            stateDescription = mActivity.getString(R.string.terminal_tab_state_bell);
        } else if (state != null && state.hasUnreadOutput) {
            indicatorView.setText(R.string.terminal_tab_indicator_output);
            indicatorView.setTextColor(ContextCompat.getColor(mActivity, R.color.terminal_tab_indicator_output));
            stateDescription = mActivity.getString(R.string.terminal_tab_state_output);
        }
        indicatorView.setVisibility(stateDescription != null ? View.VISIBLE : View.GONE);

        tabView.setActivated(selected);

        boolean running = session.isRunning();
        if (running)
            titleView.setPaintFlags(titleView.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        else
            titleView.setPaintFlags(titleView.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);

        int colorId;
        if (!running && session.getExitStatus() != 0) colorId = R.color.terminal_tab_text_failed;
        else if (selected) colorId = R.color.terminal_tab_text_selected;
        else colorId = R.color.terminal_tab_text;
        titleView.setTextColor(ContextCompat.getColor(mActivity, colorId));

        tabView.setContentDescription(stateDescription == null ? titleView.getText() : titleView.getText() + ", " + stateDescription);
        tabView.setOnClickListener(v -> getSessionClient().setCurrentSession(session));
        tabView.setOnLongClickListener(v -> {
            showTabMenu(index, session);
            return true;
        });
        View closeButton = tabView.findViewById(R.id.terminal_tab_close_button);
        closeButton.setVisibility(TerminalTabsSettings.shouldShowCloseButton() ? View.VISIBLE : View.GONE);
        closeButton.setOnClickListener(v -> closeSession(session));
    }

    @NonNull
    private static String getTabTitle(int index, TerminalSession session) {
        // Like the drawer sessions list: the session name if set, otherwise the title set by the program.
        String label = !TextUtils.isEmpty(session.mSessionName) ? session.mSessionName : session.getTitle();
        String number = "[" + (index + 1) + "]";
        return TextUtils.isEmpty(label) ? number : number + " " + label;
    }

    private void scrollToTab(int index) {
        // Post since the tab may not have been laid out yet if it was just added.
        mScrollView.post(() -> {
            if (index >= mTabsLayout.getChildCount()) return;
            View tab = mTabsLayout.getChildAt(index);
            int left = tab.getLeft();
            int right = tab.getRight();
            int scrollX = mScrollView.getScrollX();
            int width = mScrollView.getWidth();
            if (left < scrollX)
                mScrollView.smoothScrollTo(left, 0);
            else if (right > scrollX + width)
                mScrollView.smoothScrollTo(right - width, 0);
        });
    }

    private void showTabMenu(int index, TerminalSession session) {
        TermuxService service = mActivity.getTermuxService();
        int sessionsCount = service == null ? 0 : service.getTermuxSessionsSize();

        List<CharSequence> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        items.add(mActivity.getString(R.string.action_rename_terminal_tab));
        actions.add(() -> getSessionClient().renameSession(session));
        if (index > 0) {
            items.add(mActivity.getString(R.string.action_move_terminal_tab_left));
            actions.add(() -> moveSession(session, -1));
        }
        if (index < sessionsCount - 1) {
            items.add(mActivity.getString(R.string.action_move_terminal_tab_right));
            actions.add(() -> moveSession(session, 1));
        }
        items.add(mActivity.getString(R.string.action_close_terminal_tab_menu));
        actions.add(() -> closeSession(session));

        new AlertDialog.Builder(mActivity)
            .setTitle(mActivity.getString(R.string.title_terminal_tab_menu, index + 1))
            .setItems(items.toArray(new CharSequence[0]), (dialog, which) -> actions.get(which).run())
            .show();
    }

    /**
     * Move a session by the offset in the {@link TermuxService} sessions list. The drawer shows the
     * same list, so its order changes too.
     */
    private void moveSession(TerminalSession session, int offset) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        synchronized (service) {
            List<TermuxSession> sessions = service.getTermuxSessions();
            int index = service.getIndexOfSession(session);
            int newIndex = index + offset;
            if (index < 0 || newIndex < 0 || newIndex >= sessions.size()) return;
            Collections.swap(sessions, index, newIndex);
        }

        mActivity.termuxSessionListNotifyUpdated();
        getSessionClient().checkAndScrollToSession(mActivity.getCurrentSession());
    }

    /** Close a session from its tab, asking first if its process is still running. */
    private void closeSession(TerminalSession session) {
        if (!session.isRunning()) {
            getSessionClient().removeFinishedSession(session);
            return;
        }

        new AlertDialog.Builder(mActivity)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setMessage(R.string.title_confirm_kill_process)
            .setPositiveButton(android.R.string.yes, (dialog, id) -> {
                dialog.dismiss();
                sSessionsToRemoveOnExit.add(session);
                session.finishIfRunning();
            })
            .setNegativeButton(android.R.string.no, null)
            .show();
    }

    /**
     * Whether the session was closed from its tab while running, and should be removed instead of
     * being kept to show its exit status. Called when the session finishes.
     */
    public static boolean shouldRemoveOnExit(@Nullable TerminalSession session) {
        return session != null && sSessionsToRemoveOnExit.remove(session);
    }

}
