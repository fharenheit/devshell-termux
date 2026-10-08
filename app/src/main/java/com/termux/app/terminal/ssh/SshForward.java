package com.termux.app.terminal.ssh;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A port forward of an ssh connection: local (`-L`), remote (`-R`) or a SOCKS proxy (`-D`). Local
 * and remote forwards are written like `[bind:]port:host:hostport`, SOCKS ones `[bind:]port`.
 */
public final class SshForward {

    public static final int LOCAL = 0;
    public static final int REMOTE = 1;
    public static final int SOCKS = 2;

    public final int type;
    /** The address the port listens on, or empty for the default, which is localhost. */
    @NonNull public final String bindAddress;
    public final int port;
    /** Where connections go, for local and remote forwards. */
    @NonNull public final String host;
    public final int hostPort;

    private SshForward(int type, @NonNull String bindAddress, int port, @NonNull String host, int hostPort) {
        this.type = type;
        this.bindAddress = bindAddress;
        this.port = port;
        this.host = host;
        this.hostPort = hostPort;
    }

    /** Parse a forward, or return {@code null} if it is invalid. */
    @Nullable
    public static SshForward parse(int type, @NonNull String spec) {
        spec = spec.trim();
        if (type == SOCKS) {
            int colon = spec.lastIndexOf(':');
            String port = spec.substring(colon + 1);
            if (!SshHost.isPort(port)) return null;
            return new SshForward(SOCKS, colon < 0 ? "" : unbracket(spec.substring(0, colon)), Integer.parseInt(port), "", 0);
        }
        String arguments = SshHost.toForwardArguments(spec);
        if (arguments == null) return null;
        // `[bind:]port host:hostport`
        int space = arguments.indexOf(' ');
        String listen = arguments.substring(0, space);
        String target = arguments.substring(space + 1);
        int listenColon = listen.lastIndexOf(':');
        int targetColon = target.lastIndexOf(':');
        return new SshForward(type, listenColon < 0 ? "" : unbracket(listen.substring(0, listenColon)),
            Integer.parseInt(listen.substring(listenColon + 1)), unbracket(target.substring(0, targetColon)),
            Integer.parseInt(target.substring(targetColon + 1)));
    }

    private static String unbracket(@NonNull String address) {
        return address.startsWith("[") && address.endsWith("]") ? address.substring(1, address.length() - 1) : address;
    }

    /** The forwards of a host, local ones first. */
    @NonNull
    public static List<SshForward> fromHost(@NonNull SshHost host) {
        List<SshForward> forwards = new ArrayList<>();
        addAll(forwards, LOCAL, host.localForwards);
        addAll(forwards, REMOTE, host.remoteForwards);
        if (!host.socksPort.trim().isEmpty()) {
            SshForward socks = parse(SOCKS, host.socksPort);
            if (socks != null) forwards.add(socks);
        }
        return forwards;
    }

    private static void addAll(@NonNull List<SshForward> forwards, int type, @NonNull String lines) {
        for (String line : lines.split("\n")) {
            if (line.trim().isEmpty()) continue;
            SshForward forward = parse(type, line);
            if (forward != null) forwards.add(forward);
        }
    }

    private static String bracket(@NonNull String address) {
        return address.contains(":") ? "[" + address + "]" : address;
    }

    /** The argument of `-L`, `-R` or `-D`. */
    @NonNull
    public String toSpec() {
        String listen = (bindAddress.isEmpty() ? "" : bracket(bindAddress) + ":") + port;
        return type == SOCKS ? listen : listen + ":" + bracket(host) + ":" + hostPort;
    }

    @NonNull
    public String getOption() {
        return type == LOCAL ? "-L" : type == REMOTE ? "-R" : "-D";
    }

    /** Whether the port listens on this device, where it can be checked and opened. */
    public boolean listensOnDevice() {
        return type != REMOTE;
    }

    /** The address to connect to the port from this device, like `localhost:8080`. */
    @NonNull
    public String getDeviceAddress() {
        boolean any = bindAddress.isEmpty() || bindAddress.equals("*") || bindAddress.equals("0.0.0.0") || bindAddress.equals("::");
        return (any ? "localhost" : bracket(bindAddress)) + ":" + port;
    }

    /** The URL to open a local forward in a browser, like `http://localhost:8080`, using https for port 443 and 8443. */
    @NonNull
    public String getBrowserUrl() {
        String scheme = hostPort == 443 || hostPort == 8443 || port == 443 ? "https" : "http";
        return scheme + "://" + getDeviceAddress();
    }

    /** A description like `localhost:8080 → db:5432`, `server:9000 → localhost:3000` or `localhost:1080 (SOCKS)`. */
    @NonNull
    public String getLabel(@NonNull String serverName) {
        switch (type) {
            case LOCAL:
                return getDeviceAddress() + " → " + bracket(host) + ":" + hostPort;
            case REMOTE:
                return (bindAddress.isEmpty() ? serverName : bracket(bindAddress)) + ":" + port + " → " + bracket(host) + ":" + hostPort;
            default:
                return getDeviceAddress() + " (SOCKS)";
        }
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SshForward)) return false;
        SshForward forward = (SshForward) other;
        return type == forward.type && toSpec().equals(forward.toSpec());
    }

    @Override
    public int hashCode() {
        return type * 31 + toSpec().hashCode();
    }

}
