package com.termux.app.terminal.sftp;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.terminal.ssh.SshConnector;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.TermuxShellUtils;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The SFTP connection of the file browser to a host: an `ssh -s <host> sftp` process of the
 * openssh package, run with the Termux environment like in a terminal.
 *
 * If the host has a connected ssh tab, the connection sharing socket of the tab is used, so that
 * no login is needed. Otherwise ssh logs in itself with the keys and the ssh config, and with the
 * saved password of the host if any, given through the askpass script. Since there is no terminal,
 * the askpass script fails other prompts, like an unknown host key or a password that is not saved,
 * and the error output of ssh is turned into a message.
 *
 * The connection is opened when first used and can be closed while the browser is not visible, so
 * that it does not keep the ssh tab it shares from exiting, and opened again when used.
 */
public final class SftpConnection {

    private final Context mContext;
    @NonNull public final String hostName;
    @Nullable private final String mControlPath;

    @Nullable private Process mProcess;
    @Nullable private SftpClient mClient;
    private final StringBuilder mStderr = new StringBuilder();

    private static final String LOG_TAG = "SftpConnection";

    /**
     * @param controlPath The connection sharing socket of an ssh tab of the host, or {@code null}.
     */
    public SftpConnection(@NonNull Context context, @NonNull String hostName, @Nullable String controlPath) {
        mContext = context.getApplicationContext();
        this.hostName = hostName;
        mControlPath = controlPath;
    }

    /** Get the connected client, connecting if needed. Must not be called on the main thread. */
    @NonNull
    public synchronized SftpClient getClient() throws IOException {
        if (mClient != null && mProcess != null && isAlive(mProcess)) return mClient;
        disconnect();
        connect();
        return mClient;
    }

    /** Close the connection, which is opened again when used. */
    public synchronized void disconnect() {
        if (mClient != null) {
            try {
                mClient.close();
            } catch (IOException ignored) {
            }
        }
        if (mProcess != null) mProcess.destroy();
        mClient = null;
        mProcess = null;
    }

    private static boolean isAlive(@NonNull Process process) {
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    private void connect() throws IOException {
        File ssh = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "ssh");
        if (!ssh.canExecute()) throw new IOException(mContext.getString(R.string.msg_sftp_ssh_not_installed));

        List<String> command = new ArrayList<>();
        command.add(ssh.getAbsolutePath());
        // Use the socket of a connected ssh tab, but never become a master that tabs would depend on.
        command.add("-o");
        command.add("ControlMaster=no");
        command.add("-o");
        command.add("ControlPath=" + (mControlPath != null && new File(mControlPath).exists() ? mControlPath : "none"));
        command.add("-o");
        command.add("ServerAliveInterval=30");
        command.add("-s");
        command.add(hostName);
        command.add("sftp");

        Map<String, String> environment = new HashMap<>();
        for (String variable : TermuxShellUtils.buildEnvironment(mContext, false, TermuxConstants.TERMUX_HOME_DIR_PATH)) {
            int equals = variable.indexOf('=');
            if (equals > 0) environment.put(variable.substring(0, equals), variable.substring(equals + 1));
        }
        SshConnector.PasswordPipe pipe = SshConnector.PasswordPipe.create(mContext, hostName);
        environment.put("SSH_ASKPASS", pipe != null ? pipe.askpassPath : SshConnector.writeAskpassScript(mContext).getAbsolutePath());
        environment.put("SSH_ASKPASS_REQUIRE", "force");
        environment.put("TERMUX_SSH_NO_TTY", "1");
        if (pipe != null) environment.put("TERMUX_SSH_PASSWORD_PIPE", pipe.path);

        ProcessBuilder builder = new ProcessBuilder(command).directory(TermuxConstants.TERMUX_HOME_DIR);
        builder.environment().clear();
        builder.environment().putAll(environment);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            if (pipe != null) pipe.discard();
            throw e;
        }
        if (pipe != null) pipe.start(() -> isAlive(process), false);
        readStderr(process.getErrorStream());

        SftpClient client = new SftpClient(process.getInputStream(), process.getOutputStream(), process::destroy);
        try {
            client.init();
        } catch (IOException e) {
            // Let ssh write its error before reading it. Process.waitFor(timeout) needs API 26.
            for (int i = 0; i < 30 && isAlive(process); i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
            process.destroy();
            throw new IOException(describeError(), e);
        }
        mProcess = process;
        mClient = client;
    }

    private void readStderr(@NonNull InputStream stderr) {
        synchronized (mStderr) {
            mStderr.setLength(0);
        }
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[4096];
            int read;
            try {
                while ((read = stderr.read(buffer)) != -1) {
                    synchronized (mStderr) {
                        mStderr.append(new String(buffer, 0, read));
                        // Keep only the end, which has the reason of a failure.
                        if (mStderr.length() > 8192) mStderr.delete(0, mStderr.length() - 8192);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "SftpStderr");
        thread.setDaemon(true);
        thread.start();
    }

    @NonNull
    private String describeError() {
        String stderr;
        synchronized (mStderr) {
            stderr = mStderr.toString();
        }
        Logger.logError(LOG_TAG, "Failed to connect to " + hostName + ": " + stderr);
        switch (SftpErrors.classify(stderr)) {
            case SftpErrors.HOST_KEY:
                return mContext.getString(R.string.msg_sftp_host_key, hostName);
            case SftpErrors.LOGIN:
                return mContext.getString(R.string.msg_sftp_login_failed, hostName);
            case SftpErrors.NO_SFTP:
                return mContext.getString(R.string.msg_sftp_no_sftp, hostName);
            default:
                String reason = SftpErrors.lastLine(stderr);
                return mContext.getString(R.string.msg_sftp_connect_failed, hostName, reason.isEmpty() ? "?" : reason);
        }
    }

}
