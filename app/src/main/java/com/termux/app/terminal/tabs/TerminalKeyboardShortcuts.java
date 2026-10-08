package com.termux.app.terminal.tabs;

import android.app.AlertDialog;
import android.view.KeyEvent;

import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminal.TermuxTerminalSessionClient;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

/**
 * Hardware keyboard shortcuts like the ones of desktop terminals, in addition to the upstream
 * Ctrl+Alt ones. They can be turned off with `terminal-keyboard-shortcuts = false` in
 * `termux.properties`, so that the keys go to the terminal programs.
 *
 * <pre>
 * Ctrl+Tab / Ctrl+Shift+Tab   next / previous tab
 * Ctrl+Shift+T                new tab
 * Ctrl+Shift+W                close tab
 * Ctrl+Shift+C / Ctrl+Shift+V copy the selection / paste
 * Ctrl+= or Ctrl++ / Ctrl+-   larger / smaller text on the screen
 * Ctrl+0                      text at the saved size
 * Ctrl+Shift+F                find in the terminal output
 * Ctrl+Shift+S                snippets
 * </pre>
 */
public final class TerminalKeyboardShortcuts {

    /** Actions of shortcuts that other parts of the fork register, like find and snippets. */
    public interface Action {
        void run(@NonNull TermuxActivity activity);
    }

    private static Action sFindAction;
    private static Action sSnippetsAction;

    private TerminalKeyboardShortcuts() {}

    public static void setFindAction(Action action) {
        sFindAction = action;
    }

    public static void setSnippetsAction(Action action) {
        sSnippetsAction = action;
    }

    /**
     * Handle a key down event if it is a shortcut, and return whether it was. Called before the
     * key goes to the terminal.
     */
    public static boolean onKeyDown(@NonNull TermuxActivity activity, @NonNull TermuxTerminalViewClient viewClient,
                                    @NonNull TermuxTerminalSessionClient sessionClient, int keyCode,
                                    @NonNull KeyEvent event, @NonNull TerminalSession session) {
        if (!TerminalTabsSettings.areKeyboardShortcutsEnabled()) return false;
        if (!event.isCtrlPressed() || event.isAltPressed() || event.isMetaPressed()) return false;
        boolean shift = event.isShiftPressed();
        // Holding a key down repeats it, which should not open many tabs or dialogs.
        boolean repeat = event.getRepeatCount() > 0;

        switch (keyCode) {
            case KeyEvent.KEYCODE_TAB:
                sessionClient.switchToSession(!shift);
                return true;
            case KeyEvent.KEYCODE_EQUALS:
            case KeyEvent.KEYCODE_PLUS:
            case KeyEvent.KEYCODE_NUMPAD_ADD:
                viewClient.changeFontSize(true);
                return true;
            case KeyEvent.KEYCODE_MINUS:
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT:
                if (shift) return false; // Ctrl+_ is a terminal key, like undo in nano and emacs.
                viewClient.changeFontSize(false);
                return true;
            case KeyEvent.KEYCODE_0:
            case KeyEvent.KEYCODE_NUMPAD_0:
                if (shift) return false;
                viewClient.applySavedFontSize();
                return true;
        }

        if (!shift) return false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_T:
                if (!repeat) sessionClient.addNewSession(false, null);
                return true;
            case KeyEvent.KEYCODE_W:
                if (!repeat && activity.getTerminalTabsController() != null)
                    activity.getTerminalTabsController().closeSession(session);
                return true;
            case KeyEvent.KEYCODE_C:
                TerminalView terminalView = activity.getTerminalView();
                if (terminalView.isSelectingText()) {
                    String text = terminalView.getSelectedText();
                    terminalView.stopTextSelectionMode();
                    if (text != null) sessionClient.onCopyTextToClipboard(session, text);
                }
                return true;
            case KeyEvent.KEYCODE_V:
                if (!repeat) sessionClient.onPasteTextFromClipboard(session);
                return true;
            case KeyEvent.KEYCODE_F:
                if (!repeat && sFindAction != null) sFindAction.run(activity);
                return sFindAction != null;
            case KeyEvent.KEYCODE_S:
                if (!repeat && sSnippetsAction != null) sSnippetsAction.run(activity);
                return sSnippetsAction != null;
            default:
                return false;
        }
    }

    /** Show the shortcuts, with how to turn them off. */
    public static void showHelp(@NonNull TermuxActivity activity) {
        new AlertDialog.Builder(activity)
            .setTitle(R.string.title_terminal_keyboard_shortcuts)
            .setMessage(R.string.msg_terminal_keyboard_shortcuts)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

}
