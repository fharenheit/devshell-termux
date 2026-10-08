package com.termux.app.terminal.tabs;

import androidx.annotation.NonNull;

import com.termux.shared.logger.Logger;
import com.termux.shared.settings.properties.SharedProperties;
import com.termux.shared.settings.properties.TermuxPropertyConstants;

import java.util.Properties;

/**
 * The terminal tabs settings in the `termux.properties` file.
 *
 * They are read from the file here instead of being added to the upstream
 * {@link TermuxPropertyConstants} and {@link com.termux.shared.settings.properties.TermuxSharedProperties},
 * whose in-memory cache only has the keys defined upstream, to keep the fork patches small.
 *
 * <pre>
 * terminal-tabs = top                # top | bottom | off
 * terminal-tabs-show-close = true    # true | false
 * max-sessions = 8                   # 1..32
 * terminal-session-notifications = all  # all | exit | bell | off
 * terminal-tab-auto-title = process  # process | cwd | off
 * terminal-keyboard-shortcuts = true   # true | false
 * </pre>
 */
public final class TerminalTabsSettings {

    public static final String KEY_TERMINAL_TABS = "terminal-tabs";
    public static final String VALUE_TERMINAL_TABS_TOP = "top";
    public static final String VALUE_TERMINAL_TABS_BOTTOM = "bottom";
    public static final String VALUE_TERMINAL_TABS_OFF = "off";

    public static final String KEY_TERMINAL_TABS_SHOW_CLOSE = "terminal-tabs-show-close";

    public static final String KEY_MAX_SESSIONS = "max-sessions";
    /** The upstream limit, used if not set. */
    public static final int DEFAULT_MAX_SESSIONS = 8;
    public static final int MIN_MAX_SESSIONS = 1;
    public static final int MAX_MAX_SESSIONS = 32;

    /** Notify when sessions exit or ring the bell while the activity is not visible, see {@link TerminalSessionNotifications}. */
    public static final String KEY_TERMINAL_SESSION_NOTIFICATIONS = "terminal-session-notifications";
    public static final String VALUE_NOTIFICATIONS_ALL = "all";
    public static final String VALUE_NOTIFICATIONS_EXIT = "exit";
    public static final String VALUE_NOTIFICATIONS_BELL = "bell";
    public static final String VALUE_NOTIFICATIONS_OFF = "off";

    /** The title of tabs without a name or a title set by the program, see {@link TerminalTabTitles}. */
    public static final String KEY_TERMINAL_TAB_AUTO_TITLE = "terminal-tab-auto-title";
    public static final String VALUE_AUTO_TITLE_PROCESS = "process";
    public static final String VALUE_AUTO_TITLE_CWD = "cwd";
    public static final String VALUE_AUTO_TITLE_OFF = "off";

    /** Desktop terminal shortcuts like Ctrl+Tab, see {@link TerminalKeyboardShortcuts}. */
    public static final String KEY_TERMINAL_KEYBOARD_SHORTCUTS = "terminal-keyboard-shortcuts";

    private static final String LOG_TAG = "TerminalTabsSettings";

    private static volatile String sTerminalTabs = VALUE_TERMINAL_TABS_TOP;
    private static volatile boolean sShowCloseButton = true;
    private static volatile int sMaxSessions = DEFAULT_MAX_SESSIONS;
    private static volatile String sSessionNotifications = VALUE_NOTIFICATIONS_ALL;
    private static volatile String sAutoTitle = VALUE_AUTO_TITLE_PROCESS;
    private static volatile boolean sKeyboardShortcuts = true;

    private TerminalTabsSettings() {}

    /** Reload the settings from the `termux.properties` file, like when the activity is created or reloaded. */
    public static void load() {
        // Do not pass a context so that no toast is shown again if the file can't be read, since
        // the upstream properties loading already shows one.
        Properties properties = SharedProperties.getPropertiesFromFile(null, TermuxPropertyConstants.getTermuxPropertiesFile());
        if (properties == null) properties = new Properties();

        sTerminalTabs = getTerminalTabs(getValue(properties, KEY_TERMINAL_TABS));
        sShowCloseButton = SharedProperties.getBooleanValueForStringValue(KEY_TERMINAL_TABS_SHOW_CLOSE,
            getValue(properties, KEY_TERMINAL_TABS_SHOW_CLOSE), true, true, LOG_TAG);
        sMaxSessions = getMaxSessions(getValue(properties, KEY_MAX_SESSIONS));
        sSessionNotifications = getSessionNotifications(getValue(properties, KEY_TERMINAL_SESSION_NOTIFICATIONS));
        sAutoTitle = getOneOf(KEY_TERMINAL_TAB_AUTO_TITLE, getValue(properties, KEY_TERMINAL_TAB_AUTO_TITLE),
            VALUE_AUTO_TITLE_PROCESS, VALUE_AUTO_TITLE_CWD, VALUE_AUTO_TITLE_OFF);
        sKeyboardShortcuts = SharedProperties.getBooleanValueForStringValue(KEY_TERMINAL_KEYBOARD_SHORTCUTS,
            getValue(properties, KEY_TERMINAL_KEYBOARD_SHORTCUTS), true, true, LOG_TAG);
    }

    public static boolean areKeyboardShortcutsEnabled() {
        return sKeyboardShortcuts;
    }

    /** Get the value of a key with trailing whitespace removed, which {@link Properties#load} keeps. */
    private static String getValue(Properties properties, String key) {
        String value = properties.getProperty(key);
        return value == null ? null : value.trim();
    }

    @NonNull
    private static String getTerminalTabs(String value) {
        if (value == null) return VALUE_TERMINAL_TABS_TOP;
        value = value.toLowerCase();
        switch (value) {
            case VALUE_TERMINAL_TABS_TOP:
            case VALUE_TERMINAL_TABS_BOTTOM:
            case VALUE_TERMINAL_TABS_OFF:
                return value;
            default:
                Logger.logError(LOG_TAG, "Invalid value \"" + value + "\" for the \"" + KEY_TERMINAL_TABS + "\" property, using \"" + VALUE_TERMINAL_TABS_TOP + "\"");
                return VALUE_TERMINAL_TABS_TOP;
        }
    }

    /** Get the value if it is one of the values, ignoring case, otherwise the first one, which is the default. */
    @NonNull
    private static String getOneOf(String key, String value, String... values) {
        if (value == null) return values[0];
        value = value.toLowerCase();
        for (String validValue : values)
            if (validValue.equals(value)) return value;
        Logger.logError(LOG_TAG, "Invalid value \"" + value + "\" for the \"" + key + "\" property, using \"" + values[0] + "\"");
        return values[0];
    }

    @NonNull
    private static String getSessionNotifications(String value) {
        if (value == null) return VALUE_NOTIFICATIONS_ALL;
        value = value.toLowerCase();
        switch (value) {
            case VALUE_NOTIFICATIONS_ALL:
            case VALUE_NOTIFICATIONS_EXIT:
            case VALUE_NOTIFICATIONS_BELL:
            case VALUE_NOTIFICATIONS_OFF:
                return value;
            default:
                Logger.logError(LOG_TAG, "Invalid value \"" + value + "\" for the \"" + KEY_TERMINAL_SESSION_NOTIFICATIONS + "\" property, using \"" + VALUE_NOTIFICATIONS_ALL + "\"");
                return VALUE_NOTIFICATIONS_ALL;
        }
    }

    private static int getMaxSessions(String value) {
        if (value == null) return DEFAULT_MAX_SESSIONS;
        int maxSessions;
        try {
            maxSessions = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            Logger.logError(LOG_TAG, "Invalid value \"" + value + "\" for the \"" + KEY_MAX_SESSIONS + "\" property, using " + DEFAULT_MAX_SESSIONS);
            return DEFAULT_MAX_SESSIONS;
        }
        return SharedProperties.getDefaultIfNotInRange(KEY_MAX_SESSIONS, maxSessions, DEFAULT_MAX_SESSIONS,
            MIN_MAX_SESSIONS, MAX_MAX_SESSIONS, true, false, LOG_TAG);
    }

    /** Whether the tab bar is shown. */
    public static boolean areTabsShown() {
        return !VALUE_TERMINAL_TABS_OFF.equals(sTerminalTabs);
    }

    /** Whether the tab bar is below the terminal instead of above it. */
    public static boolean areTabsAtBottom() {
        return VALUE_TERMINAL_TABS_BOTTOM.equals(sTerminalTabs);
    }

    /** Whether tabs have a close button. */
    public static boolean shouldShowCloseButton() {
        return sShowCloseButton;
    }

    /** Whether to notify when a session exits while the activity is not visible. */
    public static boolean areExitNotificationsEnabled() {
        return VALUE_NOTIFICATIONS_ALL.equals(sSessionNotifications) || VALUE_NOTIFICATIONS_EXIT.equals(sSessionNotifications);
    }

    /** Whether to notify when a session rings the bell while the activity is not visible. */
    public static boolean areBellNotificationsEnabled() {
        return VALUE_NOTIFICATIONS_ALL.equals(sSessionNotifications) || VALUE_NOTIFICATIONS_BELL.equals(sSessionNotifications);
    }

    /** Whether tabs without a name or title show nothing more than their number. */
    public static boolean isAutoTitleOff() {
        return VALUE_AUTO_TITLE_OFF.equals(sAutoTitle);
    }

    /** Whether tabs without a name or title show the foreground process, otherwise the working directory. */
    public static boolean isAutoTitleProcess() {
        return VALUE_AUTO_TITLE_PROCESS.equals(sAutoTitle);
    }

    /** The maximum number of sessions that can be created from the app. */
    public static int getMaxSessions() {
        return sMaxSessions;
    }

}
