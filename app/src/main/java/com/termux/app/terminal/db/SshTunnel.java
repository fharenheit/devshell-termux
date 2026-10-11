package com.termux.app.terminal.db;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.app.terminal.sftp.SftpErrors;
import com.termux.app.terminal.ssh.SshConnector;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.TermuxShellUtils;
import com.termux.shared.termux.TermuxConstants;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A local port forwarded to a database server through an SSH host registered in the app: an
 * `ssh -N -L 127.0.0.1:<port>:<host>:<port> <ssh host>` process of the openssh package, with the
 * Termux environment, the ssh config and the saved password of the host like the file browser.
 * Since there is no terminal, ssh can't ask about unknown host keys, which are checked with the
 * host key dialog first.
 */
public final class SshTunnel implements Closeable {

    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final String LOG_TAG = "SshTunnel";

    private final Process mProcess;
    public final int localPort;

    private SshTunnel(@NonNull Process process, int localPort) {
        mProcess = process;
        this.localPort = localPort;
    }

    /** Start the tunnel and wait until its port accepts connections. Must not be called on the main thread. */
    @NonNull
    public static SshTunnel open(@NonNull Context context, @NonNull String sshHost, @NonNull String targetHost, int targetPort) throws IOException {
        File ssh = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "ssh");
        if (!ssh.canExecute()) throw new IOException(context.getString(R.string.msg_sftp_ssh_not_installed));
        int localPort = findFreePort();

        List<String> command = new ArrayList<>();
        command.add(ssh.getAbsolutePath());
        command.add("-N");
        command.add("-o");
        command.add("ExitOnForwardFailure=yes");
        command.add("-o");
        command.add("ControlMaster=no");
        command.add("-o");
        command.add("ControlPath=none");
        command.add("-o");
        command.add("ServerAliveInterval=30");
        // The host of the database is resolved by the SSH server, like `localhost` for itself.
        String target = targetHost.contains(":") && !targetHost.startsWith("[") ? "[" + targetHost + "]" : targetHost;
        command.add("-L");
        command.add("127.0.0.1:" + localPort + ":" + target + ":" + targetPort);
        command.add(sshHost);

        Map<String, String> environment = new HashMap<>();
        for (String variable : TermuxShellUtils.buildEnvironment(context, false, TermuxConstants.TERMUX_HOME_DIR_PATH)) {
            int equals = variable.indexOf('=');
            if (equals > 0) environment.put(variable.substring(0, equals), variable.substring(equals + 1));
        }
        SshConnector.PasswordPipe pipe = SshConnector.PasswordPipe.create(context, sshHost);
        environment.put("SSH_ASKPASS", pipe != null ? pipe.askpassPath : SshConnector.writeAskpassScript(context).getAbsolutePath());
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
        StringBuilder stderr = new StringBuilder();
        readStderr(process.getErrorStream(), stderr);
        process.getOutputStream().close();

        long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive(process)) break;
            if (canConnect(localPort)) return new SshTunnel(process, localPort);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                break;
            }
        }
        process.destroy();
        String error;
        synchronized (stderr) {
            error = stderr.toString();
        }
        Logger.logError(LOG_TAG, "Failed to forward a port through " + sshHost + ": " + error);
        switch (SftpErrors.classify(error)) {
            case SftpErrors.HOST_KEY:
                throw new HostKeyException(context.getString(R.string.msg_sftp_host_key, sshHost));
            case SftpErrors.LOGIN:
                throw new IOException(context.getString(R.string.msg_sftp_login_failed, sshHost));
            default:
                String reason = SftpErrors.lastLine(error);
                throw new IOException(context.getString(R.string.msg_db_tunnel_failed, sshHost, reason.isEmpty() ? "timeout" : reason));
        }
    }

    /** Connecting failed because the host key of the SSH host is not known or changed. */
    public static final class HostKeyException extends IOException {
        HostKeyException(String message) {
            super(message);
        }
    }

    static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static boolean canConnect(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isAlive(@NonNull Process process) {
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    private static void readStderr(@NonNull InputStream in, @NonNull StringBuilder stderr) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[4096];
            int read;
            try {
                while ((read = in.read(buffer)) != -1) {
                    synchronized (stderr) {
                        stderr.append(new String(buffer, 0, read));
                        if (stderr.length() > 8192) stderr.delete(0, stderr.length() - 8192);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "SshTunnelStderr");
        thread.setDaemon(true);
        thread.start();
    }

    public boolean isOpen() {
        return isAlive(mProcess);
    }

    @Override
    public void close() {
        mProcess.destroy();
    }

}
