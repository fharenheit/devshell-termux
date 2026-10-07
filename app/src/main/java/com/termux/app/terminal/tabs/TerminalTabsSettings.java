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

    private static final String LOG_TAG = "TerminalTabsSettings";

    private static volatile String sTerminalTabs = VALUE_TERMINAL_TABS_TOP;
    private static volatile boolean sShowCloseButton = true;
    private static volatile int sMaxSessions = DEFAULT_MAX_SESSIONS;

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

    /** The maximum number of sessions that can be created from the app. */
    public static int getMaxSessions() {
        return sMaxSessions;
    }

}
