package com.termux.app.terminal.tabs;

import android.app.AlertDialog;
import android.graphics.Paint;
import android.text.TextUtils;
import android.content.Intent;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.app.activities.SettingsActivity;
import com.termux.app.terminal.TermuxTerminalSessionClient;
import com.termux.app.terminal.backup.SettingsBackup;
import com.termux.app.terminal.fonts.TerminalFontDialog;
import com.termux.app.terminal.fonts.TerminalFonts;
import com.termux.app.terminal.sftp.SftpBrowserActivity;
import com.termux.app.terminal.ssh.SshConnector;
import com.termux.app.terminal.ssh.SshHostsDialog;
import com.termux.shared.settings.properties.TermuxPropertyConstants;
import com.termux.shared.shell.TermuxSession;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

import java.io.File;
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

        View menuButton = activity.findViewById(R.id.terminal_tabs_menu_button);
        menuButton.setOnClickListener(this::showMenu);

        TerminalTabStates.setTabsController(this);

        TerminalTabsSettings.load();
        applySettings();
    }

    /** Reload the settings from the `termux.properties` file, like after `termux-reload-settings`. */
    public void reloadSettings() {
        TerminalTabsSettings.load();
        TerminalTabTitles.clearAutoTitles();
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

    private static final int MENU_SETTINGS = 1;
    private static final int MENU_SESSIONS = 2;
    private static final int MENU_KEYBOARD = 3;
    private static final int MENU_EDIT_PROPERTIES = 4;
    private static final int MENU_RELOAD_SETTINGS = 5;
    private static final int MENU_FONT = 6;
    private static final int MENU_SSH = 7;
    private static final int MENU_EXPORT_SETTINGS = 8;
    private static final int MENU_IMPORT_SETTINGS = 9;
    private static final int MENU_KEYBOARD_SHORTCUTS = 10;

    /** Show the menu of the settings button of the tab bar. */
    private void showMenu(View anchor) {
        PopupMenu popupMenu = new PopupMenu(mActivity, anchor);
        Menu menu = popupMenu.getMenu();
        menu.add(Menu.NONE, MENU_SSH, Menu.NONE, R.string.action_terminal_tabs_menu_ssh);
        menu.add(Menu.NONE, MENU_SETTINGS, Menu.NONE, R.string.action_terminal_tabs_menu_settings);
        menu.add(Menu.NONE, MENU_SESSIONS, Menu.NONE, R.string.action_terminal_tabs_menu_sessions);
        menu.add(Menu.NONE, MENU_KEYBOARD, Menu.NONE, R.string.action_terminal_tabs_menu_keyboard);
        menu.add(Menu.NONE, MENU_FONT, Menu.NONE, R.string.action_terminal_tabs_menu_font);
        menu.add(Menu.NONE, MENU_EDIT_PROPERTIES, Menu.NONE, R.string.action_terminal_tabs_menu_edit_properties);
        menu.add(Menu.NONE, MENU_RELOAD_SETTINGS, Menu.NONE, R.string.action_terminal_tabs_menu_reload_settings);
        menu.add(Menu.NONE, MENU_EXPORT_SETTINGS, Menu.NONE, R.string.action_terminal_tabs_menu_export_settings);
        menu.add(Menu.NONE, MENU_IMPORT_SETTINGS, Menu.NONE, R.string.action_terminal_tabs_menu_import_settings);
        menu.add(Menu.NONE, MENU_KEYBOARD_SHORTCUTS, Menu.NONE, R.string.title_terminal_keyboard_shortcuts);
        popupMenu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_SETTINGS:
                    mActivity.startActivity(new Intent(mActivity, SettingsActivity.class));
                    return true;
                case MENU_SESSIONS:
                    mActivity.getDrawer().openDrawer(Gravity.LEFT);
                    return true;
                case MENU_KEYBOARD:
                    mActivity.getTermuxTerminalViewClient().onToggleSoftKeyboardRequest();
                    return true;
                case MENU_EXPORT_SETTINGS:
                    SettingsBackup.export(mActivity);
                    return true;
                case MENU_IMPORT_SETTINGS:
                    SettingsBackup.startImport(mActivity);
                    return true;
                case MENU_KEYBOARD_SHORTCUTS:
                    TerminalKeyboardShortcuts.showHelp(mActivity);
                    return true;
                case MENU_SSH:
                    SshHostsDialog.show(mActivity);
                    return true;
                case MENU_FONT:
                    TerminalFontDialog.show(mActivity, () -> {
                        // Load the font now instead of when the activity is started again.
                        TerminalFonts.consumeFontChanged();
                        getSessionClient().checkForFontAndColors();
                    }, () -> mActivity.getTermuxTerminalViewClient().applySavedFontSize());
                    return true;
                case MENU_EDIT_PROPERTIES:
                    editPropertiesFile();
                    return true;
                case MENU_RELOAD_SETTINGS:
                    // Like `termux-reload-settings`.
                    TermuxActivity.updateTermuxActivityStyling(mActivity);
                    mActivity.showToast(mActivity.getString(R.string.msg_terminal_tabs_settings_reloaded), false);
                    return true;
                default:
                    return false;
            }
        });
        popupMenu.show();
    }

    /**
     * Open the `termux.properties` file in an editor in a new session: `$EDITOR`, `nano` or `vi`,
     * whichever is found first. The settings are reloaded with `termux-reload-settings` when the
     * editor exits, and the session is then removed since it exits normally.
     */
    private void editPropertiesFile() {
        File propertiesFile = TermuxPropertyConstants.getTermuxPropertiesFile();
        String path = propertiesFile != null ? propertiesFile.getAbsolutePath() : TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH;

        String script =
            "mkdir -p \"$(dirname \"$1\")\"\n" +
            "for editor in \"$EDITOR\" nano vi; do\n" +
            "  if [ -n \"$editor\" ] && command -v \"$editor\" >/dev/null 2>&1; then\n" +
            "    \"$editor\" \"$1\" || exit\n" +
            "    if command -v termux-reload-settings >/dev/null 2>&1; then termux-reload-settings; fi\n" +
            "    exit 0\n" +
            "  fi\n" +
            "done\n" +
            "echo \"No editor found. Install one with: pkg install nano\"\n" +
            "exit 1\n";

        startScriptSession(propertiesFile != null ? propertiesFile.getName() : "termux.properties", script, path);
    }

    /**
     * Start a new session that runs a shell script, in the home directory, and switch to it.
     * The script gets the arguments as `$1`, `$2`, ... Shows the max sessions dialog instead if
     * there are too many sessions.
     *
     * @return The new session, or {@code null} if it was not started.
     */
    @Nullable
    public TerminalSession startScriptSession(@NonNull String sessionName, @NonNull String script, @NonNull String... arguments) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return null;

        if (service.getTermuxSessionsSize() >= TerminalTabsSettings.getMaxSessions()) {
            new AlertDialog.Builder(mActivity).setTitle(R.string.title_max_terminals_reached).setMessage(R.string.msg_max_terminals_reached)
                .setPositiveButton(android.R.string.ok, null).show();
            return null;
        }

        File termuxShell = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "sh");
        String shell = termuxShell.canExecute() ? termuxShell.getAbsolutePath() : "/system/bin/sh";

        String[] shellArguments = new String[arguments.length + 3];
        shellArguments[0] = "-c";
        shellArguments[1] = script;
        shellArguments[2] = "sh";
        System.arraycopy(arguments, 0, shellArguments, 3, arguments.length);

        TermuxSession termuxSession = service.createTermuxSession(shell, shellArguments,
            null, TermuxConstants.TERMUX_HOME_DIR_PATH, false, sessionName);
        if (termuxSession == null) return null;
        getSessionClient().setCurrentSession(termuxSession.getTerminalSession());
        return termuxSession.getTerminalSession();
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
        titleView.setText(TerminalTabTitles.getNumberedLabel(index, session));

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
        // Tapping the tab of the current session renames it, since it is already displayed.
        tabView.setOnClickListener(v -> {
            if (session == mActivity.getCurrentSession()) showRenameDialog(session);
            else getSessionClient().setCurrentSession(session);
        });
        tabView.setOnLongClickListener(v -> {
            showTabMenu(index, session);
            return true;
        });
        View closeButton = tabView.findViewById(R.id.terminal_tab_close_button);
        closeButton.setVisibility(TerminalTabsSettings.shouldShowCloseButton() ? View.VISIBLE : View.GONE);
        closeButton.setOnClickListener(v -> closeSession(session));
    }

    /**
     * Show a dialog to set the name of a session, which is shown on its tab and in the drawer
     * instead of the title set by the program or the automatic title. The current name is selected
     * so that it can be replaced by typing, the hint shows the title shown without a name, and
     * the name can be reset to show that title again.
     */
    public void showRenameDialog(@Nullable TerminalSession session) {
        if (session == null) return;

        final EditText input = new EditText(mActivity);
        input.setSingleLine();
        if (!TextUtils.isEmpty(session.mSessionName)) {
            input.setText(session.mSessionName);
            input.selectAll();
        }
        String unnamedLabel = TerminalTabTitles.getUnnamedLabel(session);
        if (!TextUtils.isEmpty(unnamedLabel)) input.setHint(unnamedLabel);

        int padding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16, mActivity.getResources().getDisplayMetrics()));
        LinearLayout layout = new LinearLayout(mActivity);
        layout.setPadding(padding, padding, padding, padding);
        layout.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final AlertDialog dialog = new AlertDialog.Builder(mActivity)
            .setTitle(R.string.title_rename_session)
            .setView(layout)
            .setPositiveButton(R.string.action_rename_session_confirm, (d, which) -> setSessionName(session, input.getText().toString()))
            .setNeutralButton(R.string.action_reset_terminal_tab_name, (d, which) -> setSessionName(session, null))
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        input.setOnEditorActionListener((v, actionId, event) -> {
            setSessionName(session, input.getText().toString());
            dialog.dismiss();
            return true;
        });
        dialog.setCanceledOnTouchOutside(false);
        Window window = dialog.getWindow();
        if (window != null) window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.show();
        input.requestFocus();
    }

    private void setSessionName(@NonNull TerminalSession session, @Nullable String name) {
        if (name != null) name = name.trim();
        session.mSessionName = TextUtils.isEmpty(name) ? null : name;
        mActivity.termuxSessionListNotifyUpdated();
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
        if (SshConnector.isSshSession(session)) {
            if (session.isRunning()) {
                items.add(mActivity.getString(R.string.action_ssh_disconnect));
                actions.add(() -> SshConnector.disconnect(session));
            } else {
                items.add(mActivity.getString(R.string.action_ssh_reconnect));
                actions.add(() -> SshConnector.reconnect(mActivity, session));
            }
            String hostName = SshConnector.getHostName(session);
            items.add(mActivity.getString(R.string.action_sftp_browse_files));
            actions.add(() -> SftpBrowserActivity.start(mActivity, hostName, SshConnector.getControlPath(session)));
        }
        items.add(mActivity.getString(R.string.action_rename_terminal_tab));
        actions.add(() -> showRenameDialog(session));
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
    public void closeSession(TerminalSession session) {
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
