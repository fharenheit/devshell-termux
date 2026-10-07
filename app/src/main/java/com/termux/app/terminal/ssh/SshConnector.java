package com.termux.app.terminal.ssh;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.app.terminal.tabs.TerminalTabsController;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.TermuxSession;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Connects to SSH hosts in new terminal tabs by running `ssh <name>` of the openssh package, and
 * disconnects and reconnects them.
 *
 * A saved password is given to ssh without putting it in a command line, environment variable or
 * file: ssh runs {@link #getAskpassFile(Context) an askpass script} with `SSH_ASKPASS_REQUIRE=force`
 * for its prompts, which reads a password prompt's answer once from a named pipe that the app
 * writes the password to, and asks other prompts like the host key confirmation on the terminal.
 */
public final class SshConnector {

    /** The SSH sessions and the names of their hosts. */
    private static final Map<TerminalSession, String> sSshSessions = new WeakHashMap<>();

    /** How long to wait for ssh to ask for the password before removing the pipe. */
    private static final long PASSWORD_PIPE_TIMEOUT_MS = 120_000;

    private static final String CONNECT_SCRIPT =
        "if ! command -v ssh >/dev/null 2>&1; then\n" +
        "  echo \"ssh is not installed. Install it with: pkg install openssh\"\n" +
        "  exit 1\n" +
        "fi\n" +
        "if [ -n \"$2\" ]; then\n" +
        "  export SSH_ASKPASS=\"$3\" SSH_ASKPASS_REQUIRE=force TERMUX_SSH_PASSWORD_PIPE=\"$2\"\n" +
        "fi\n" +
        "exec ssh \"$1\"\n";

    /** Install a public key on a host, like `ssh-copy-id`, which is used if it is installed. */
    private static final String COPY_ID_SCRIPT =
        "if ! command -v ssh >/dev/null 2>&1; then\n" +
        "  echo \"ssh is not installed. Install it with: pkg install openssh\"\n" +
        "  exit 1\n" +
        "fi\n" +
        "if [ -n \"$2\" ]; then\n" +
        "  export SSH_ASKPASS=\"$3\" SSH_ASKPASS_REQUIRE=force TERMUX_SSH_PASSWORD_PIPE=\"$2\"\n" +
        "fi\n" +
        "echo \"Installing $4 on $1\"\n" +
        "if command -v ssh-copy-id >/dev/null 2>&1; then\n" +
        "  ssh-copy-id -i \"$4\" \"$1\"\n" +
        "else\n" +
        "  ssh \"$1\" 'umask 077; mkdir -p ~/.ssh && cat >> ~/.ssh/authorized_keys' < \"$4\"\n" +
        "fi\n" +
        "status=$?\n" +
        "[ $status -eq 0 ] && echo \"Done. Key authentication will be used for $1 from now on.\"\n" +
        "echo \"Press Enter to close.\"\n" +
        "read -r _\n" +
        "exit $status\n";

    private static final String ASKPASS_SCRIPT =
        "#!" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh\n" +
        "# Answers ssh prompts for the Termux app: a password prompt once with the saved password\n" +
        "# from the pipe the app writes it to, and other prompts on the terminal.\n" +
        "case \"$1\" in\n" +
        "  *[Pp]assword*)\n" +
        "    if [ -n \"$TERMUX_SSH_PASSWORD_PIPE\" ] && [ -p \"$TERMUX_SSH_PASSWORD_PIPE\" ]; then\n" +
        "      cat \"$TERMUX_SSH_PASSWORD_PIPE\"\n" +
        "      rm -f \"$TERMUX_SSH_PASSWORD_PIPE\"\n" +
        "      exit 0\n" +
        "    fi;;\n" +
        "esac\n" +
        "case \"$1\" in\n" +
        "  *[Pp]assword*|*[Pp]assphrase*|*PIN*)\n" +
        "    # Turn off echo before showing the prompt, so that nothing typed after it is shown.\n" +
        "    stty -echo < /dev/tty\n" +
        "    printf '%s' \"$1\" > /dev/tty\n" +
        "    IFS= read -r answer < /dev/tty\n" +
        "    stty echo < /dev/tty\n" +
        "    echo > /dev/tty;;\n" +
        "  *)\n" +
        "    printf '%s' \"$1\" > /dev/tty\n" +
        "    IFS= read -r answer < /dev/tty;;\n" +
        "esac\n" +
        "printf '%s\\n' \"$answer\"\n";

    private static final String LOG_TAG = "SshConnector";

    private SshConnector() {}

    /** Whether the session was started by {@link #connect}. */
    public static boolean isSshSession(@Nullable TerminalSession session) {
        return getHostName(session) != null;
    }

    @Nullable
    public static String getHostName(@Nullable TerminalSession session) {
        if (session == null) return null;
        synchronized (sSshSessions) {
            return sSshSessions.get(session);
        }
    }

    /** Connect to a host in a new tab, which is named after it. */
    @Nullable
    public static TerminalSession connect(@NonNull TermuxActivity activity, @NonNull String hostName) {
        TerminalSession session = startSshScript(activity, hostName, hostName, CONNECT_SCRIPT);
        if (session != null) {
            synchronized (sSshSessions) {
                sSshSessions.put(session, hostName);
            }
        }
        return session;
    }

    /** Install a public key on a host in a new tab, so that the key is used to log in to it. */
    public static void copyId(@NonNull TermuxActivity activity, @NonNull String hostName, @NonNull File publicKeyFile) {
        startSshScript(activity, "ssh-copy-id " + hostName, hostName, COPY_ID_SCRIPT, publicKeyFile.getAbsolutePath());
    }

    /**
     * Start a script that runs ssh for a host in a new tab, giving it the saved password of the
     * host if any. The script gets the host as `$1`, the password pipe as `$2`, the askpass
     * script as `$3`, both empty without a saved password, and the extra arguments after them.
     */
    @Nullable
    private static TerminalSession startSshScript(@NonNull TermuxActivity activity, @NonNull String sessionName,
                                                  @NonNull String hostName, @NonNull String script, @NonNull String... extraArguments) {
        TerminalTabsController tabsController = activity.getTerminalTabsController();
        if (tabsController == null) return null;

        String pipePath = "";
        String askpassPath = "";
        byte[] password = SshPasswords.load(activity, hostName);
        if (password != null) {
            try {
                askpassPath = writeAskpassScript(activity).getAbsolutePath();
                pipePath = new File(activity.getCacheDir(), "ssh-password-" + UUID.randomUUID()).getAbsolutePath();
                Os.mkfifo(pipePath, 0600);
            } catch (IOException | ErrnoException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to set up giving the saved password to ssh", e);
                Arrays.fill(password, (byte) 0);
                password = null;
                pipePath = "";
                askpassPath = "";
            }
        }

        String[] arguments = new String[3 + extraArguments.length];
        arguments[0] = hostName;
        arguments[1] = pipePath;
        arguments[2] = askpassPath;
        System.arraycopy(extraArguments, 0, arguments, 3, extraArguments.length);

        TerminalSession session = tabsController.startScriptSession(sessionName, script, arguments);
        if (session == null) {
            if (password != null) Arrays.fill(password, (byte) 0);
            if (!pipePath.isEmpty()) //noinspection ResultOfMethodCallIgnored
                new File(pipePath).delete();
            return null;
        }

        if (password != null) writePasswordToPipe(session, pipePath, password);
        return session;
    }

    /** Disconnect an SSH session by asking ssh to exit, which leaves its tab to be reconnected or closed. */
    public static void disconnect(@NonNull TerminalSession session) {
        int pid = session.getPid();
        if (!session.isRunning() || pid < 1) return;
        try {
            Os.kill(pid, OsConstants.SIGTERM);
        } catch (ErrnoException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to disconnect the ssh process " + pid, e);
        }
    }

    /** Reconnect a disconnected SSH session in a new tab at the position of its tab, which is closed. */
    public static void reconnect(@NonNull TermuxActivity activity, @NonNull TerminalSession session) {
        String hostName = getHostName(session);
        TermuxService service = activity.getTermuxService();
        if (hostName == null || service == null || session.isRunning()) return;

        int index = service.getIndexOfSession(session);
        activity.getTermuxTerminalSessionClient().removeFinishedSession(session);

        TerminalSession newSession = connect(activity, hostName);
        if (newSession == null || index < 0) return;

        synchronized (service) {
            List<TermuxSession> sessions = service.getTermuxSessions();
            int newIndex = service.getIndexOfSession(newSession);
            if (newIndex < 0 || newIndex == index || index >= sessions.size()) return;
            sessions.add(index, sessions.remove(newIndex));
        }
        activity.termuxSessionListNotifyUpdated();
        activity.getTermuxTerminalSessionClient().checkAndScrollToSession(newSession);
    }

    /** Write the askpass script, which has no secrets, to the app cache directory. */
    @NonNull
    private static File writeAskpassScript(@NonNull Context context) throws IOException {
        File file = new File(context.getCacheDir(), "termux-ssh-askpass");
        SshHostStore.writeFile(file, ASKPASS_SCRIPT, true);
        if (!file.setExecutable(true, true))
            throw new IOException("Failed to make \"" + file.getAbsolutePath() + "\" executable");
        return file;
    }

    /**
     * Write the password to the pipe once the askpass script opens it for reading, in the
     * background, and remove the pipe afterwards, or when ssh does not ask for the password in
     * time or exits.
     */
    private static void writePasswordToPipe(@NonNull TerminalSession session, @NonNull String pipePath, @NonNull byte[] password) {
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + PASSWORD_PIPE_TIMEOUT_MS;
            byte[] line = Arrays.copyOf(password, password.length + 1);
            line[password.length] = '\n';
            Arrays.fill(password, (byte) 0);
            // The process is started when the session is first displayed.
            boolean started = false;
            try {
                while (System.currentTimeMillis() < deadline && new File(pipePath).exists()) {
                    FileDescriptor fd;
                    try {
                        // Fails with ENXIO until there is a reader, so it never blocks.
                        fd = Os.open(pipePath, OsConstants.O_WRONLY | OsConstants.O_NONBLOCK, 0);
                    } catch (ErrnoException e) {
                        if (e.errno != OsConstants.ENXIO) throw e;
                        // Stop waiting once ssh exits, like when it failed to connect.
                        if (session.isRunning()) started = true;
                        else if (started) break;
                        Thread.sleep(200);
                        continue;
                    }
                    try {
                        int written = 0;
                        while (written < line.length)
                            written += Os.write(fd, line, written, line.length - written);
                    } finally {
                        Os.close(fd);
                    }
                    break;
                }
            } catch (ErrnoException | java.io.InterruptedIOException | InterruptedException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to give the saved password to ssh", e);
            } finally {
                Arrays.fill(line, (byte) 0);
                //noinspection ResultOfMethodCallIgnored
                new File(pipePath).delete();
            }
        }, "SshPasswordPipe").start();
    }

}
