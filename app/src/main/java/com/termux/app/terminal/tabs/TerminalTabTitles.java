package com.termux.app.terminal.tabs;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The labels shown on the terminal tabs and in session notifications.
 *
 * The label of a session is, in order:
 * 1. the name set by the user, {@link TerminalSession#mSessionName},
 * 2. the title set by the program running in it with an OSC 0/2 escape sequence,
 * 3. the automatic title set by {@link TerminalTabsSettings#KEY_TERMINAL_TAB_AUTO_TITLE}: the name
 *    of the foreground process like `vim`, or the shell working directory when the shell is in
 *    the foreground, or only the working directory.
 *
 * The automatic title is read from `/proc` when the session has output, at most once per
 * {@link #AUTO_TITLE_CHECK_INTERVAL_MS} for each session, since programs usually draw something
 * when they start and the shell prints its prompt when they exit.
 *
 * All methods are called on the main thread, where {@link TerminalSession} callbacks are called.
 */
public final class TerminalTabTitles {

    private static final long AUTO_TITLE_CHECK_INTERVAL_MS = 1000;

    private static final class AutoTitle {
        @Nullable String title;
        long lastCheckTime;
        boolean checkScheduled;
    }

    private static final Map<TerminalSession, AutoTitle> sAutoTitles = new WeakHashMap<>();

    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    private TerminalTabTitles() {}

    /** Forget the automatic titles, like when the settings are reloaded. */
    public static void clearAutoTitles() {
        sAutoTitles.clear();
    }

    /** Get the label of the session, without its number, or {@code null} if it has none. */
    @Nullable
    public static String getLabel(@NonNull TerminalSession session) {
        if (!TextUtils.isEmpty(session.mSessionName)) return session.mSessionName;
        return getUnnamedLabel(session);
    }

    /** Get the label the session has without a name set by the user, or {@code null} if it has none. */
    @Nullable
    public static String getUnnamedLabel(@NonNull TerminalSession session) {
        String title = session.getTitle();
        if (!TextUtils.isEmpty(title)) return title;
        if (TerminalTabsSettings.isAutoTitleOff()) return null;

        AutoTitle autoTitle = sAutoTitles.get(session);
        if (autoTitle == null) {
            // Not checked yet, like for a session that had no output since the app was opened.
            autoTitle = new AutoTitle();
            sAutoTitles.put(session, autoTitle);
            updateAutoTitle(session, autoTitle);
        }
        return autoTitle.title;
    }

    /** Get the label of the session with its number, like `[2] vim`, as shown on its tab. */
    @NonNull
    public static String getNumberedLabel(int index, @NonNull TerminalSession session) {
        String label = getLabel(session);
        String number = "[" + (index + 1) + "]";
        return TextUtils.isEmpty(label) ? number : number + " " + label;
    }

    /** Called when a session had output, to update its automatic title. */
    public static void onTextChanged(@Nullable TerminalSession session) {
        if (session == null || TerminalTabsSettings.isAutoTitleOff()) return;

        AutoTitle autoTitle = sAutoTitles.get(session);
        if (autoTitle == null) {
            autoTitle = new AutoTitle();
            sAutoTitles.put(session, autoTitle);
        }
        if (autoTitle.checkScheduled) return;

        long elapsed = SystemClock.uptimeMillis() - autoTitle.lastCheckTime;
        if (elapsed >= AUTO_TITLE_CHECK_INTERVAL_MS) {
            checkAutoTitle(session, autoTitle);
        } else {
            // Check again after the interval, so that the title is not left stale if the last
            // output, like the prompt after a program exits, came right after a check.
            autoTitle.checkScheduled = true;
            final AutoTitle scheduledAutoTitle = autoTitle;
            sHandler.postDelayed(() -> {
                scheduledAutoTitle.checkScheduled = false;
                checkAutoTitle(session, scheduledAutoTitle);
            }, AUTO_TITLE_CHECK_INTERVAL_MS - elapsed);
        }
    }

    /** Update the automatic title and refresh the tabs if it changed. */
    private static void checkAutoTitle(@NonNull TerminalSession session, @NonNull AutoTitle autoTitle) {
        if (updateAutoTitle(session, autoTitle))
            TerminalTabStates.notifyChanged();
    }

    /** Update the automatic title, returning whether it changed. */
    private static boolean updateAutoTitle(@NonNull TerminalSession session, @NonNull AutoTitle autoTitle) {
        autoTitle.lastCheckTime = SystemClock.uptimeMillis();

        // Keep the last title of sessions that have exited.
        int shellPid = session.getPid();
        if (shellPid < 1) return false;

        String title = null;
        if (TerminalTabsSettings.isAutoTitleProcess()) {
            int foregroundPid = getForegroundProcessGroup(shellPid);
            if (foregroundPid > 0 && foregroundPid != shellPid)
                title = readFirstLine("/proc/" + foregroundPid + "/comm");
        }
        if (title == null)
            title = getDirectoryLabel(session.getCwd());

        if (TextUtils.equals(title, autoTitle.title)) return false;
        autoTitle.title = title;
        return true;
    }

    /**
     * Get the foreground process group of the terminal of a process, which is the pid of the
     * program running in the foreground of the shell, or the shell itself.
     *
     * It is the `tpgid` field of `/proc/<pid>/stat`: `pid (comm) state ppid pgrp session tty_nr tpgid ...`.
     * The `comm` may contain spaces and parentheses, so the fields are counted after the last `)`.
     */
    private static int getForegroundProcessGroup(int pid) {
        String stat = readFirstLine("/proc/" + pid + "/stat");
        if (stat == null) return -1;
        int commEnd = stat.lastIndexOf(')');
        if (commEnd < 0) return -1;
        String[] fields = stat.substring(commEnd + 1).trim().split(" ");
        // state ppid pgrp session tty_nr tpgid
        if (fields.length < 6) return -1;
        try {
            return Integer.parseInt(fields[5]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Get the label of a directory: `~` for home, otherwise its name. */
    @Nullable
    private static String getDirectoryLabel(@Nullable String path) {
        if (TextUtils.isEmpty(path)) return null;
        if (path.equals(TermuxConstants.TERMUX_HOME_DIR_PATH)) return "~";
        String name = new File(path).getName();
        return name.isEmpty() ? path : name;
    }

    @Nullable
    private static String readFirstLine(String path) {
        byte[] buffer = new byte[1024];
        int length;
        try (FileInputStream in = new FileInputStream(path)) {
            length = in.read(buffer);
        } catch (IOException | SecurityException e) {
            return null;
        }
        if (length <= 0) return null;
        String text = new String(buffer, 0, length, StandardCharsets.UTF_8);
        int newline = text.indexOf('\n');
        if (newline >= 0) text = text.substring(0, newline);
        text = text.trim();
        return text.isEmpty() ? null : text;
    }

}
