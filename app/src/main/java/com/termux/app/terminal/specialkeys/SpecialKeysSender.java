package com.termux.app.terminal.specialkeys;

import androidx.annotation.NonNull;

import com.termux.shared.terminal.io.TerminalExtraKeys;
import com.termux.view.TerminalView;

/**
 * Sends special keys to the terminal like the extra keys do, so that function and cursor keys
 * send the codes of the terminal's current modes.
 */
class SpecialKeysSender extends TerminalExtraKeys {

    SpecialKeysSender(@NonNull TerminalView terminalView) {
        super(terminalView);
    }

    /**
     * Send the keys of a macro, where `CTRL`, `ALT` and `SHIFT` apply to the next key. The
     * {@code ctrl} and {@code alt} of the extra keys apply to the first key.
     */
    void send(@NonNull String macro, boolean ctrl, boolean alt) {
        boolean shift = false;
        for (String token : macro.split(" ")) {
            if (token.isEmpty()) continue;
            switch (token) {
                case "CTRL": ctrl = true; continue;
                case "ALT": alt = true; continue;
                case "SHIFT": shift = true; continue;
            }
            onTerminalExtraKeyButtonClick(null, token, ctrl, alt, shift, false);
            ctrl = false;
            alt = false;
            shift = false;
        }
    }

}
