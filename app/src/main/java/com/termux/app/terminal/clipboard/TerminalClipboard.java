package com.termux.app.terminal.clipboard;

import android.widget.Toast;

import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.interact.ShareUtils;
import com.termux.terminal.TerminalEmulator;
import com.termux.view.TerminalView;

/**
 * Copying terminal text for the `COPY` and `COPY_SCREEN` extra keys and Ctrl+Shift+C. Pasting is
 * the upstream `PASTE` key.
 */
public final class TerminalClipboard {

    private TerminalClipboard() {}

    /** Copy the selected text and end the selection, or tell how to select text if there is none. */
    public static void copySelection(@NonNull TermuxActivity activity) {
        TerminalView terminalView = activity.getTerminalView();
        if (!terminalView.isSelectingText()) {
            Toast.makeText(activity, R.string.msg_terminal_copy_select_first, Toast.LENGTH_SHORT).show();
            return;
        }
        String text = terminalView.getSelectedText();
        terminalView.stopTextSelectionMode();
        if (text == null || text.isEmpty()) return;
        ShareUtils.copyTextToClipboard(activity, text, activity.getString(R.string.msg_terminal_copied_selection));
    }

    /** Copy the text shown on the screen, which may be scrolled up into the output above it. */
    public static void copyScreen(@NonNull TermuxActivity activity) {
        TerminalView terminalView = activity.getTerminalView();
        TerminalEmulator emulator = terminalView.mEmulator;
        if (emulator == null) return;
        int topRow = terminalView.getTopRow();
        String text = emulator.getScreen().getSelectedText(0, topRow, emulator.mColumns, topRow + emulator.mRows - 1).trim();
        if (text.isEmpty()) return;
        if (terminalView.isSelectingText()) terminalView.stopTextSelectionMode();
        ShareUtils.copyTextToClipboard(activity, text, activity.getString(R.string.msg_terminal_copied_screen));
    }

}
