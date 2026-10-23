package com.termux.app.terminal.snippets;

import android.app.AlertDialog;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.terminal.TerminalSession;

/**
 * The menu of the `GIT_PUSH` extra key: push to main, to the current branch or to a new branch.
 * The chosen request is typed into the current tab and sent with Enter, like a prompt to a coding
 * agent running there.
 */
public final class PushMenu {

    /** The extra key that opens the menu. */
    public static final String EXTRA_KEY = "GIT_PUSH";

    /** How long to wait after the text before Enter, so that programs that tell typing from pasting take it as a key. */
    private static final long ENTER_DELAY_MS = 100;

    private static final int[] REQUESTS = {
        R.string.push_request_main, R.string.push_request_current_branch, R.string.push_request_new_branch};

    private PushMenu() {}

    public static void show(@NonNull TermuxActivity activity) {
        CharSequence[] labels = new CharSequence[REQUESTS.length];
        for (int i = 0; i < REQUESTS.length; i++) labels[i] = activity.getString(REQUESTS[i]);
        new AlertDialog.Builder(activity)
            .setTitle(R.string.title_push)
            .setItems(labels, (d, which) -> send(activity, labels[which].toString()))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private static void send(@NonNull TermuxActivity activity, @NonNull String text) {
        TerminalSession session = activity.getCurrentSession();
        if (session == null || !session.isRunning()) {
            Toast.makeText(activity, R.string.msg_snippet_no_session, Toast.LENGTH_SHORT).show();
            return;
        }
        activity.getTerminalView().prepareForExternalInput();
        session.write(text);
        activity.getTerminalView().postDelayed(() -> {
            if (session.isRunning()) session.write("\r");
        }, ENTER_DELAY_MS);
    }

}
