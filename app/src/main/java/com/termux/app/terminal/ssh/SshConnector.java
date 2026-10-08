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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

/**
 * Connects to SSH hosts in new terminal tabs by running `ssh <name>` of the openssh package, and
 * disconnects and reconnects them.
 *
 * A saved password is given to ssh without putting it in a command line, environment variable or
 * file: ssh runs {@link #getAskpassFile(Context) an askpass script} with `SSH_ASKPASS_REQUIRE=force`
 * for its prompts, which reads a password prompt's answer once from a named pipe that the app
 * writes the password to, and asks other prompts like the host key confirmation on the terminal.
 *
 * Each ssh tab is the master of its own connection sharing socket (`ControlMaster=yes` with a
 * {@link #CONTROL_DIR per-tab ControlPath}), so that the SFTP file browser can open the files of
 * the host over the connection of the tab, without logging in again. Tabs of the same host do not
 * share connections, so that closing one tab does not wait for the others.
 */
public final class SshConnector {

    /** The directory of the connection sharing sockets of the ssh tabs. */
    public static final File CONTROL_DIR = new File(SshHostStore.SSH_DIR, "cm");

    /** The SSH sessions and the names of their hosts. */
    private static final Map<TerminalSession, String> sSshSessions = new WeakHashMap<>();

    /** The connection sharing sockets of the ssh sessions. */
    private static final Map<TerminalSession, String> sControlPaths = new WeakHashMap<>();

    /** How long to wait for ssh to ask for the password before removing the pipe. */
    private static final long PASSWORD_PIPE_TIMEOUT_MS = 120_000;

    /**
     * Connect to `$1` with ssh, or with mosh if `$4` is `mosh`. ssh is the master of the connection
     * sharing socket `$6` if it is set, removing a socket left by a master that did not exit
     * cleanly first. With `$5` set to `1`, reconnect
     * when ssh fails with 255, which it does when the connection is lost or can't be made, after
     * 5, 10, ... seconds, giving up after 5 failed attempts in a row. A connection that lasted a
     * minute starts the count again. Each attempt gets a new `TERMUX_SSH_ATTEMPT` marker so that
     * the askpass script gives the saved password once per attempt.
     */
    private static final String CONNECT_SCRIPT =
        "host=\"$1\"\n" +
        "control_path=\"$6\"\n" +
        "if [ -n \"$2\" ]; then\n" +
        "  export SSH_ASKPASS=\"$3\" SSH_ASKPASS_REQUIRE=force TERMUX_SSH_PASSWORD_PIPE=\"$2\"\n" +
        "fi\n" +
        "run_ssh() {\n" +
        "  if [ -n \"$control_path\" ]; then\n" +
        "    rm -f \"$control_path\"\n" +
        "    ssh -o ControlMaster=yes -o ControlPath=\"$control_path\" \"$host\"\n" +
        "  else\n" +
        "    ssh \"$host\"\n" +
        "  fi\n" +
        "}\n" +
        "if [ \"$4\" = mosh ]; then\n" +
        "  if ! command -v mosh >/dev/null 2>&1; then\n" +
        "    echo \"mosh is not installed. Install it with: pkg install mosh\"\n" +
        "    exit 1\n" +
        "  fi\n" +
        "  exec mosh \"$host\"\n" +
        "fi\n" +
        "if ! command -v ssh >/dev/null 2>&1; then\n" +
        "  echo \"ssh is not installed. Install it with: pkg install openssh\"\n" +
        "  exit 1\n" +
        "fi\n" +
        "if [ \"$5\" != 1 ]; then\n" +
        "  run_ssh\n" +
        "  exit $?\n" +
        "fi\n" +
        "failures=0\n" +
        "while :; do\n" +
        "  start=$(date +%s)\n" +
        "  export TERMUX_SSH_ATTEMPT=\"${TMPDIR:-/tmp}/termux-ssh-attempt-$$-$start\"\n" +
        "  run_ssh\n" +
        "  status=$?\n" +
        "  rm -f \"$TERMUX_SSH_ATTEMPT\"\n" +
        "  [ $status -eq 255 ] || exit $status\n" +
        "  [ $(($(date +%s) - start)) -ge 60 ] && failures=0\n" +
        "  failures=$((failures + 1))\n" +
        "  if [ $failures -gt 5 ]; then\n" +
        "    echo \"[Failed to reconnect to $host 5 times in a row. Giving up.]\"\n" +
        "    exit $status\n" +
        "  fi\n" +
        "  delay=$((failures * 5))\n" +
        "  echo\n" +
        "  echo \"[Not connected to $host. Reconnecting in ${delay}s. Press Ctrl+C to stop.]\"\n" +
        "  sleep $delay\n" +
        "done\n";

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
        "# Answers ssh prompts for the Termux app: a password prompt with the saved password from the\n" +
        "# pipe the app writes it to, once per connection attempt, and other prompts on the terminal.\n" +
        "case \"$1\" in\n" +
        "  *[Pp]assword*)\n" +
        "    if [ -n \"$TERMUX_SSH_PASSWORD_PIPE\" ] && [ -p \"$TERMUX_SSH_PASSWORD_PIPE\" ]; then\n" +
        "      if [ -z \"$TERMUX_SSH_ATTEMPT\" ]; then\n" +
        "        cat \"$TERMUX_SSH_PASSWORD_PIPE\"\n" +
        "        rm -f \"$TERMUX_SSH_PASSWORD_PIPE\"\n" +
        "        exit 0\n" +
        "      elif [ ! -e \"$TERMUX_SSH_ATTEMPT\" ]; then\n" +
        "        : > \"$TERMUX_SSH_ATTEMPT\"\n" +
        "        cat \"$TERMUX_SSH_PASSWORD_PIPE\"\n" +
        "        exit 0\n" +
        "      fi\n" +
        "    fi;;\n" +
        "esac\n" +
        "# Without a terminal, like for the file browser, fail other prompts instead of waiting.\n" +
        "[ -n \"$TERMUX_SSH_NO_TTY\" ] && exit 1\n" +
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
        // Hosts of ~/.ssh/config that are not registered in the app use ssh without reconnecting.
        SshHost host = SshHostStore.find(SshHostStore.load(), hostName);
        boolean useMosh = host != null && host.useMosh;
        boolean autoReconnect = host != null && host.autoReconnect && !useMosh;
        // mosh does not keep an ssh connection to share.
        String controlPath = useMosh ? "" : newControlPath();
        TerminalSession session = startSshScript(activity, hostName, hostName, CONNECT_SCRIPT, autoReconnect,
            useMosh ? "mosh" : "ssh", autoReconnect ? "1" : "", controlPath);
        if (session != null) {
            synchronized (sSshSessions) {
                sSshSessions.put(session, hostName);
                if (!controlPath.isEmpty()) sControlPaths.put(session, controlPath);
            }
        }
        return session;
    }

    /** A new socket path in {@link #CONTROL_DIR}, or empty if the directory can't be created. */
    @NonNull
    private static String newControlPath() {
        try {
            SshHostStore.ensureSshDir();
            if (!CONTROL_DIR.isDirectory() && !CONTROL_DIR.mkdirs()) return "";
            //noinspection ResultOfMethodCallIgnored
            CONTROL_DIR.setReadable(false, false);
            //noinspection ResultOfMethodCallIgnored
            CONTROL_DIR.setReadable(true, true);
            //noinspection ResultOfMethodCallIgnored
            CONTROL_DIR.setExecutable(false, false);
            //noinspection ResultOfMethodCallIgnored
            CONTROL_DIR.setExecutable(true, true);
        } catch (IOException e) {
            return "";
        }
        // Short, since a unix socket path is limited to 107 bytes and ssh adds a suffix while creating it.
        return new File(CONTROL_DIR, "tab-" + UUID.randomUUID().toString().substring(0, 8)).getAbsolutePath();
    }

    /**
     * Get the connection sharing socket of a connected ssh session, or {@code null} if it is not
     * an ssh session, is not running or its ssh is not connected, like while reconnecting.
     */
    @Nullable
    public static String getControlPath(@Nullable TerminalSession session) {
        if (session == null || !session.isRunning()) return null;
        String controlPath;
        synchronized (sSshSessions) {
            controlPath = sControlPaths.get(session);
        }
        return controlPath != null && new File(controlPath).exists() ? controlPath : null;
    }

    /** Get the connection sharing socket of a connected ssh tab of a host, or {@code null} if it has none. */
    @Nullable
    public static String findControlPath(@NonNull String hostName) {
        List<TerminalSession> sessions;
        synchronized (sSshSessions) {
            sessions = new ArrayList<>(sControlPaths.keySet());
        }
        for (TerminalSession session : sessions) {
            if (!hostName.equals(getHostName(session))) continue;
            String controlPath = getControlPath(session);
            if (controlPath != null) return controlPath;
        }
        return null;
    }

    /** Install a public key on a host in a new tab, so that the key is used to log in to it. */
    public static void copyId(@NonNull TermuxActivity activity, @NonNull String hostName, @NonNull File publicKeyFile) {
        startSshScript(activity, "ssh-copy-id " + hostName, hostName, COPY_ID_SCRIPT, false, publicKeyFile.getAbsolutePath());
    }

    /**
     * Start a script that runs ssh for a host in a new tab, giving it the saved password of the
     * host if any. The script gets the host as `$1`, the password pipe as `$2`, the askpass
     * script as `$3`, both empty without a saved password, and the extra arguments after them.
     *
     * @param reusePassword Whether to give the password every time it is asked for while the
     *                      session runs, like when reconnecting, instead of once.
     */
    @Nullable
    private static TerminalSession startSshScript(@NonNull TermuxActivity activity, @NonNull String sessionName,
                                                  @NonNull String hostName, @NonNull String script, boolean reusePassword,
                                                  @NonNull String... extraArguments) {
        TerminalTabsController tabsController = activity.getTerminalTabsController();
        if (tabsController == null) return null;

        PasswordPipe pipe = PasswordPipe.create(activity, hostName);

        String[] arguments = new String[3 + extraArguments.length];
        arguments[0] = hostName;
        arguments[1] = pipe != null ? pipe.path : "";
        arguments[2] = pipe != null ? pipe.askpassPath : "";
        System.arraycopy(extraArguments, 0, arguments, 3, extraArguments.length);

        TerminalSession session = tabsController.startScriptSession(sessionName, script, arguments);
        if (session == null) {
            if (pipe != null) pipe.discard();
            return null;
        }

        if (pipe != null) pipe.start(session::isRunning, reusePassword);
        return session;
    }

    /**
     * Disconnect an SSH session by asking ssh to exit, which leaves its tab to be reconnected or
     * closed. The signal is sent to the process group of the session, which the session process
     * leads, so that it also stops the reconnect loop of the script that runs ssh.
     */
    public static void disconnect(@NonNull TerminalSession session) {
        int pid = session.getPid();
        if (!session.isRunning() || pid < 1) return;
        try {
            Os.kill(-pid, OsConstants.SIGTERM);
        } catch (ErrnoException e) {
            try {
                Os.kill(pid, OsConstants.SIGTERM);
            } catch (ErrnoException e2) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to disconnect the ssh process " + pid, e2);
            }
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
    public static File writeAskpassScript(@NonNull Context context) throws IOException {
        File file = new File(context.getCacheDir(), "termux-ssh-askpass");
        SshHostStore.writeFile(file, ASKPASS_SCRIPT, true);
        if (!file.setExecutable(true, true))
            throw new IOException("Failed to make \"" + file.getAbsolutePath() + "\" executable");
        return file;
    }

    /**
     * A named pipe that the saved password of a host is written to for the askpass script, which
     * ssh runs with the `SSH_ASKPASS`, `SSH_ASKPASS_REQUIRE=force` and `TERMUX_SSH_PASSWORD_PIPE`
     * environment variables.
     */
    public static final class PasswordPipe {
        @NonNull public final String path;
        @NonNull public final String askpassPath;
        @NonNull private final byte[] mPassword;

        private PasswordPipe(@NonNull String path, @NonNull String askpassPath, @NonNull byte[] password) {
            this.path = path;
            this.askpassPath = askpassPath;
            mPassword = password;
        }

        /** Create the pipe for the saved password of a host, or return {@code null} if it has none or it fails. */
        @Nullable
        public static PasswordPipe create(@NonNull Context context, @NonNull String hostName) {
            byte[] password = SshPasswords.load(context, hostName);
            if (password == null) return null;
            try {
                String askpassPath = writeAskpassScript(context).getAbsolutePath();
                String path = new File(context.getCacheDir(), "ssh-password-" + UUID.randomUUID()).getAbsolutePath();
                Os.mkfifo(path, 0600);
                return new PasswordPipe(path, askpassPath, password);
            } catch (IOException | ErrnoException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to set up giving the saved password to ssh", e);
                Arrays.fill(password, (byte) 0);
                return null;
            }
        }

        /** Remove the pipe without using it. */
        public void discard() {
            Arrays.fill(mPassword, (byte) 0);
            //noinspection ResultOfMethodCallIgnored
            new File(path).delete();
        }

        /**
         * Write the password to the pipe when the askpass script opens it for reading, in the
         * background, and remove the pipe afterwards, or when ssh does not ask for the password in
         * time or {@code isRunning} becomes false after being true. With {@code reuse}, the
         * password is written each time the pipe is opened until then, for reconnecting, and is
         * kept in memory until then.
         */
        public void start(@NonNull BooleanSupplier isRunning, boolean reuse) {
            new Thread(() -> {
                long deadline = System.currentTimeMillis() + PASSWORD_PIPE_TIMEOUT_MS;
                byte[] line = Arrays.copyOf(mPassword, mPassword.length + 1);
                line[mPassword.length] = '\n';
                Arrays.fill(mPassword, (byte) 0);
                // The process of a session is started when the session is first displayed.
                boolean started = false;
                try {
                    while (new File(path).exists()) {
                        // Wait for the password to be asked for until the timeout, except for a
                        // running process that reuses it, which waits until the process ends.
                        if ((!reuse || !started) && System.currentTimeMillis() > deadline) break;
                        FileDescriptor fd;
                        try {
                            // Fails with ENXIO until there is a reader, so it never blocks.
                            fd = Os.open(path, OsConstants.O_WRONLY | OsConstants.O_NONBLOCK, 0);
                        } catch (ErrnoException e) {
                            if (e.errno != OsConstants.ENXIO) throw e;
                            // Stop waiting once ssh exits, like when it failed to connect.
                            if (isRunning.getAsBoolean()) started = true;
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
                        if (!reuse) break;
                        // Let the reader finish before waiting for the next one.
                        Thread.sleep(1000);
                    }
                } catch (ErrnoException | java.io.InterruptedIOException | InterruptedException e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to give the saved password to ssh", e);
                } finally {
                    Arrays.fill(line, (byte) 0);
                    //noinspection ResultOfMethodCallIgnored
                    new File(path).delete();
                }
            }, "SshPasswordPipe").start();
        }
    }

}
