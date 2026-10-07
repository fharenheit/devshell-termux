package com.termux.app.terminal.tabs;

import androidx.annotation.NonNull;

import com.termux.app.TermuxService;
import com.termux.shared.shell.TermuxSession;
import com.termux.shared.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalSession;

/**
 * The {@link com.termux.terminal.TerminalSessionClient} that {@link com.termux.app.TermuxService}
 * gives sessions while no {@link com.termux.app.TermuxActivity} is bound to it. Like
 * {@link TermuxTerminalSessionClientBase} it does nothing, except recording output and bells in
 * {@link TerminalTabStates}, so that the tabs show them when the activity is opened again, and
 * notifying about them with {@link TerminalSessionNotifications}.
 */
public class TrackingTerminalSessionClient extends TermuxTerminalSessionClientBase {

    private final TermuxService mService;

    public TrackingTerminalSessionClient(@NonNull TermuxService service) {
        mService = service;
    }

    @Override
    public void onTextChanged(TerminalSession changedSession) {
        TerminalTabStates.markOutput(changedSession);
        TerminalTabTitles.onTextChanged(changedSession);
    }

    @Override
    public void onBell(TerminalSession session) {
        TerminalTabStates.markBell(session);
        TerminalSessionNotifications.onBell(mService, mService, session);
    }

    @Override
    public void onSessionFinished(TerminalSession finishedSession) {
        // Sessions are killed when the user exits from the service notification.
        if (mService.wantsToStop()) return;

        // Sessions of plugin commands that expect the result back are closed when they finish.
        TermuxSession termuxSession = mService.getTermuxSession(mService.getIndexOfSession(finishedSession));
        if (termuxSession != null && termuxSession.getExecutionCommand().isPluginExecutionCommandWithPendingResult()) return;

        TerminalSessionNotifications.onSessionFinished(mService, mService, finishedSession);
    }

}
