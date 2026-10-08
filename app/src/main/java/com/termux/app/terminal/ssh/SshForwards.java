package com.termux.app.terminal.ssh;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.shell.TermuxShellUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The port forwards of ssh tabs: the ones of their host, and ones added or stopped while connected.
 * Forwards are added and stopped on the connection of a tab through its connection sharing socket
 * with `ssh -O forward` and `ssh -O cancel`, without connecting again.
 */
public final class SshForwards {

    /** A forward of a tab, and whether it was added while connected. */
    public static final class Item {
        @NonNull public final SshForward forward;
        public final boolean added;

        Item(@NonNull SshForward forward, boolean added) {
            this.forward = forward;
            this.added = added;
        }
    }

    /** The forwards added and stopped on a connection of a tab. */
    private static final class State {
        /** The time the socket was created, which changes when the tab connects again and the changes are gone. */
        long socketTime;
        final List<SshForward> added = new ArrayList<>();
        final List<SshForward> stopped = new ArrayList<>();
    }

    private static final Map<TerminalSession, State> sStates = new WeakHashMap<>();

    private SshForwards() {}

    /** The state of the current connection of a tab, forgetting changes made on an earlier connection. */
    @NonNull
    private static State getState(@NonNull TerminalSession session, @Nullable String controlPath) {
        long socketTime = controlPath == null ? 0 : new File(controlPath).lastModified();
        State state = sStates.get(session);
        if (state == null || state.socketTime != socketTime) {
            state = new State();
            state.socketTime = socketTime;
            sStates.put(session, state);
        }
        return state;
    }

    /** The forwards of a tab: those of its host that were not stopped, then the added ones. */
    @NonNull
    public static synchronized List<Item> getForwards(@NonNull TerminalSession session, @Nullable SshHost host, @Nullable String controlPath) {
        State state = getState(session, controlPath);
        List<Item> items = new ArrayList<>();
        if (host != null)
            for (SshForward forward : SshForward.fromHost(host))
                if (!state.stopped.contains(forward)) items.add(new Item(forward, false));
        for (SshForward forward : state.added)
            if (!state.stopped.contains(forward)) items.add(new Item(forward, true));
        return items;
    }

    /** Add a forward to the connection of a tab. Not on the main thread. Throws with the error of ssh. */
    public static void add(@NonNull Context context, @NonNull TerminalSession session, @NonNull String hostName,
                           @NonNull String controlPath, @NonNull SshForward forward) throws IOException {
        runControlCommand(context, hostName, controlPath, "forward", forward);
        synchronized (SshForwards.class) {
            State state = getState(session, controlPath);
            state.stopped.remove(forward);
            if (!state.added.contains(forward)) state.added.add(forward);
        }
    }

    /** Stop a forward of the connection of a tab. Not on the main thread. Throws with the error of ssh. */
    public static void stop(@NonNull Context context, @NonNull TerminalSession session, @NonNull String hostName,
                            @NonNull String controlPath, @NonNull SshForward forward) throws IOException {
        runControlCommand(context, hostName, controlPath, "cancel", forward);
        synchronized (SshForwards.class) {
            State state = getState(session, controlPath);
            if (!state.added.remove(forward)) state.stopped.add(forward);
        }
    }

    /** Run `ssh -S <socket> -O <operation> -L|-R|-D <spec> <host>`. */
    private static void runControlCommand(@NonNull Context context, @NonNull String hostName, @NonNull String controlPath,
                                          @NonNull String operation, @NonNull SshForward forward) throws IOException {
        File ssh = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "ssh");
        List<String> command = new ArrayList<>();
        command.add(ssh.getAbsolutePath());
        command.add("-S");
        command.add(controlPath);
        command.add("-O");
        command.add(operation);
        command.add(forward.getOption());
        command.add(forward.toSpec());
        command.add(hostName);
        ProcessBuilder builder = new ProcessBuilder(command).directory(TermuxConstants.TERMUX_HOME_DIR).redirectErrorStream(true);
        builder.environment().clear();
        for (String variable : TermuxShellUtils.buildEnvironment(context, false, TermuxConstants.TERMUX_HOME_DIR_PATH)) {
            int equals = variable.indexOf('=');
            if (equals > 0) builder.environment().put(variable.substring(0, equals), variable.substring(equals + 1));
        }
        Process process = builder.start();
        process.getOutputStream().close();
        String output = readAll(process.getInputStream()).trim();
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            process.destroy();
            throw new IOException("Interrupted");
        }
        // All of the output, since the reason is not on the last line, like "Port forwarding failed".
        if (exitCode != 0) throw new IOException(output.isEmpty() ? "ssh exited with " + exitCode : output);
    }

    @NonNull
    private static String readAll(@NonNull InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1 && out.size() < 64 * 1024) out.write(buffer, 0, read);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Whether a port of a forward accepts connections on this device. Not on the main thread. */
    public static boolean isListening(@NonNull SshForward forward) {
        String address = forward.getDeviceAddress();
        String host = address.substring(0, address.lastIndexOf(':'));
        if (host.equals("localhost")) host = "127.0.0.1";
        if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, forward.port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

}
