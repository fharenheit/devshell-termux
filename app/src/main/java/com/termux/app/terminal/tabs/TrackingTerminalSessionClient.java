package com.termux.app.terminal.tabs;

import com.termux.shared.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalSession;

/**
 * The {@link com.termux.terminal.TerminalSessionClient} that {@link com.termux.app.TermuxService}
 * gives sessions while no {@link com.termux.app.TermuxActivity} is bound to it. Like
 * {@link TermuxTerminalSessionClientBase} it does nothing, except recording output and bells in
 * {@link TerminalTabStates}, so that the tabs show them when the activity is opened again.
 */
public class TrackingTerminalSessionClient extends TermuxTerminalSessionClientBase {

    @Override
    public void onTextChanged(TerminalSession changedSession) {
        TerminalTabStates.markOutput(changedSession);
    }

    @Override
    public void onBell(TerminalSession session) {
        TerminalTabStates.markBell(session);
    }

}
