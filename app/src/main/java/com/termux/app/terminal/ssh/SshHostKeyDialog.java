package com.termux.app.terminal.ssh;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shows whether the host key of an SSH host is trusted, with its fingerprint, to trust a new key,
 * replace a changed one or remove the trusted one. See {@link SshHostKeys}.
 */
public final class SshHostKeyDialog {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private SshHostKeyDialog() {}

    private interface Task<T> {
        T run() throws IOException;
    }

    private interface Done<T> {
        void done(T result);
    }

    /** Run a task with a progress dialog, then {@code done} with its result, or show its error. */
    private static <T> void run(@NonNull Activity activity, int messageId, @NonNull Task<T> task, @NonNull Done<T> done) {
        ProgressBar progress = new ProgressBar(activity);
        progress.setPadding(0, 32, 0, 32);
        AlertDialog progressDialog = new AlertDialog.Builder(activity)
            .setMessage(messageId)
            .setView(progress)
            .setCancelable(false)
            .show();
        EXECUTOR.execute(() -> {
            T result = null;
            IOException error = null;
            try {
                result = task.run();
            } catch (IOException e) {
                error = e;
            }
            T finalResult = result;
            IOException finalError = error;
            HANDLER.post(() -> {
                if (progressDialog.isShowing()) progressDialog.dismiss();
                if (activity.isFinishing()) return;
                if (finalError != null) {
                    new AlertDialog.Builder(activity)
                        .setTitle(R.string.title_ssh_host_key)
                        .setMessage(activity.getString(R.string.msg_ssh_host_key_failed, finalError.getMessage()))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                    return;
                }
                done.done(finalResult);
            });
        });
    }

    /**
     * Check the host key of a host and show the result.
     *
     * @param onTrusted Called after a key was trusted, like to connect again.
     */
    public static void show(@NonNull Activity activity, @NonNull String hostName, @Nullable Runnable onTrusted) {
        run(activity, R.string.msg_ssh_host_key_checking, () -> SshHostKeys.check(activity, hostName),
            check -> showCheck(activity, hostName, check, onTrusted));
    }

    private static void showCheck(@NonNull Activity activity, @NonNull String hostName, @NonNull SshHostKeys.Check check,
                                  @Nullable Runnable onTrusted) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(R.string.title_ssh_host_key);
        switch (check.state) {
            case SshHostKeys.TRUSTED:
                builder.setMessage(activity.getString(R.string.msg_ssh_host_key_trusted, hostName, check.fingerprint))
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.action_ssh_host_key_remove, (d, which) -> confirmRemove(activity, hostName, check));
                break;
            case SshHostKeys.UNKNOWN:
                builder.setMessage(activity.getString(R.string.msg_ssh_host_key_unknown, hostName, check.fingerprint))
                    .setPositiveButton(R.string.action_ssh_host_key_trust, (d, which) -> trust(activity, check, onTrusted))
                    .setNegativeButton(android.R.string.cancel, null);
                break;
            default:
                builder.setIcon(android.R.drawable.ic_dialog_alert)
                    .setMessage(activity.getString(R.string.msg_ssh_host_key_changed, hostName, check.fingerprint,
                        String.join("\n", check.knownFingerprints)))
                    .setPositiveButton(R.string.action_ssh_host_key_replace, (d, which) -> confirmReplace(activity, hostName, check, onTrusted))
                    .setNegativeButton(android.R.string.cancel, null);
                break;
        }
        builder.show();
    }

    private static void trust(@NonNull Activity activity, @NonNull SshHostKeys.Check check, @Nullable Runnable onTrusted) {
        run(activity, R.string.msg_ssh_host_key_saving, () -> {
            SshHostKeys.trust(activity, check);
            return true;
        }, result -> {
            Toast.makeText(activity, R.string.msg_ssh_host_key_saved, Toast.LENGTH_SHORT).show();
            if (onTrusted != null) onTrusted.run();
        });
    }

    /** Replacing a changed key needs a second confirmation, since it could be an attack. */
    private static void confirmReplace(@NonNull Activity activity, @NonNull String hostName, @NonNull SshHostKeys.Check check,
                                       @Nullable Runnable onTrusted) {
        new AlertDialog.Builder(activity)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setMessage(activity.getString(R.string.msg_ssh_host_key_replace_confirm, hostName))
            .setPositiveButton(R.string.action_ssh_host_key_replace, (d, which) -> trust(activity, check, onTrusted))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private static void confirmRemove(@NonNull Activity activity, @NonNull String hostName, @NonNull SshHostKeys.Check check) {
        new AlertDialog.Builder(activity)
            .setMessage(activity.getString(R.string.msg_ssh_host_key_remove_confirm, hostName))
            .setPositiveButton(R.string.action_ssh_host_key_remove, (d, which) -> run(activity, R.string.msg_ssh_host_key_saving, () -> {
                SshHostKeys.remove(activity, check);
                return true;
            }, result -> Toast.makeText(activity, R.string.msg_ssh_host_key_removed, Toast.LENGTH_SHORT).show()))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

}
