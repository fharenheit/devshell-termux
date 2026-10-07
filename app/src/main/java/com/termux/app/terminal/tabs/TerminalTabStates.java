package com.termux.app.terminal.tabs;

import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The state shown on the terminal tabs of sessions that are not being displayed, like whether they
 * have new output or rang the bell since they were last displayed.
 *
 * It is kept for the whole process instead of in {@link com.termux.app.TermuxActivity}, so that
 * the state is not lost while the activity is stopped or destroyed and recreated, since sessions
 * keep running in the background in {@link com.termux.app.TermuxService}.
 *
 * Sessions are weakly referenced so that the state of removed sessions does not need to be cleared.
 * All methods are called on the main thread, where {@link TerminalSession} callbacks are called.
 */
public final class TerminalTabStates {

    public static final class State {
        /** Whether the session had output since it was last displayed. */
        public boolean hasUnreadOutput;
        /** Whether the session rang the bell since it was last displayed. */
        public boolean hasBell;
    }

    private static final Map<TerminalSession, State> sStates = new WeakHashMap<>();

    /** The tabs to refresh when a state changes. Weakly referenced to not leak the activity. */
    private static WeakReference<TerminalTabsController> sTabsController = new WeakReference<>(null);

    private TerminalTabStates() {}

    static synchronized void setTabsController(@Nullable TerminalTabsController tabsController) {
        sTabsController = new WeakReference<>(tabsController);
    }

    /** Get the state of the session, or {@code null} if it has nothing to show. */
    @Nullable
    public static synchronized State get(@Nullable TerminalSession session) {
        return session == null ? null : sStates.get(session);
    }

    /**
     * Record that a session that is not being displayed had output. This is called for every
     * output, so the tabs are only refreshed when the state changes.
     */
    public static void markOutput(@Nullable TerminalSession session) {
        if (session == null) return;
        synchronized (TerminalTabStates.class) {
            State state = getOrCreate(session);
            if (state.hasUnreadOutput) return;
            state.hasUnreadOutput = true;
        }
        notifyChanged();
    }

    /** Record that a session that is not being displayed rang the bell. */
    public static void markBell(@Nullable TerminalSession session) {
        if (session == null) return;
        synchronized (TerminalTabStates.class) {
            State state = getOrCreate(session);
            if (state.hasBell) return;
            state.hasBell = true;
        }
        notifyChanged();
    }

    /** Clear the state of a session, since it is now being displayed. */
    public static void markSeen(@Nullable TerminalSession session) {
        if (session == null) return;
        synchronized (TerminalTabStates.class) {
            if (sStates.remove(session) == null) return;
        }
        notifyChanged();
    }

    private static State getOrCreate(TerminalSession session) {
        State state = sStates.get(session);
        if (state == null) {
            state = new State();
            sStates.put(session, state);
        }
        return state;
    }

    private static void notifyChanged() {
        TerminalTabsController tabsController;
        synchronized (TerminalTabStates.class) {
            tabsController = sTabsController.get();
        }
        if (tabsController != null) tabsController.refresh();
    }

}
