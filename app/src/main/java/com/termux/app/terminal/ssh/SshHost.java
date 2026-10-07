package com.termux.app.terminal.ssh;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Pattern;

/** An SSH host registered in the app, written as a `Host` block of {@link SshHostStore#TERMUX_HOSTS_FILE}. */
public final class SshHost {

    /** The name is the `Host` alias, so `ssh <name>` works in the shell too. */
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9._@-]+");

    public static final int DEFAULT_PORT = 22;

    @NonNull public String name = "";
    @NonNull public String hostName = "";
    public int port = DEFAULT_PORT;
    @NonNull public String user = "";
    /** The private key file, like `~/.ssh/id_ed25519`, or empty for the ssh defaults. */
    @NonNull public String identityFile = "";
    /** More `ssh_config` options, one per line, like `Compression yes`. */
    @NonNull public String extraOptions = "";
    /** A command to run on the host after connecting, before the login shell. */
    @NonNull public String startupCommand = "";
    /** The `ProxyJump` hosts to connect through, like `bastion` or `user@gateway:2222`. */
    @NonNull public String jumpHost = "";
    /** Local port forwards, one per line as `[bind:]port:host:hostport`, like `ssh -L`. */
    @NonNull public String localForwards = "";
    /** Remote port forwards, one per line as `[bind:]port:host:hostport`, like `ssh -R`. */
    @NonNull public String remoteForwards = "";
    /** The local SOCKS proxy port, like `ssh -D`, or empty. */
    @NonNull public String socksPort = "";
    /** Whether to reconnect when the connection is lost, see {@link SshConnector}. */
    public boolean autoReconnect;
    /** Whether to connect with mosh instead of ssh, which keeps the session over network changes. */
    public boolean useMosh;

    public static boolean isValidName(@Nullable String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    /**
     * Whether a port forward is `[bind:]port:host:hostport`, where host may be an IPv6 address in
     * brackets. Returns the `ssh_config` arguments `[bind:]port host:hostport`, or {@code null}
     * if it is invalid.
     */
    @Nullable
    static String toForwardArguments(@NonNull String forward) {
        // Split off host:hostport from the end, since the bind address is optional.
        int hostPortStart = forward.lastIndexOf(':');
        if (hostPortStart <= 0) return null;
        String hostPort = forward.substring(hostPortStart + 1);
        String rest = forward.substring(0, hostPortStart);
        int hostStart;
        if (rest.endsWith("]")) {
            hostStart = rest.lastIndexOf('[');
            if (hostStart <= 0 || rest.charAt(hostStart - 1) != ':') return null;
            hostStart--;
        } else {
            hostStart = rest.lastIndexOf(':');
        }
        if (hostStart <= 0) return null;
        String host = rest.substring(hostStart + 1);
        String listen = rest.substring(0, hostStart);
        String listenPort = listen.substring(listen.lastIndexOf(':') + 1);
        if (!isPort(listenPort) || !isPort(hostPort) || host.isEmpty() || host.contains(" ")) return null;
        return listen + " " + host + ":" + hostPort;
    }

    static boolean isPort(@Nullable String value) {
        if (value == null || !value.matches("[0-9]{1,5}")) return false;
        int port = Integer.parseInt(value);
        return port >= 1 && port <= 65535;
    }

    /** Get the invalid line of the forwards, or {@code null} if all are valid. */
    @Nullable
    public static String getInvalidForward(@NonNull String forwards) {
        for (String line : forwards.split("\n")) {
            line = line.trim();
            if (!line.isEmpty() && toForwardArguments(line) == null) return line;
        }
        return null;
    }

    /** The `user@host:port` shown under the name. */
    @NonNull
    public String getAddress() {
        String address = (TextUtils.isEmpty(user) ? "" : user + "@") + hostName;
        return port == DEFAULT_PORT ? address : address + ":" + port;
    }

    @NonNull
    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("name", name);
        json.put("hostName", hostName);
        json.put("port", port);
        json.put("user", user);
        json.put("identityFile", identityFile);
        json.put("extraOptions", extraOptions);
        json.put("startupCommand", startupCommand);
        json.put("jumpHost", jumpHost);
        json.put("localForwards", localForwards);
        json.put("remoteForwards", remoteForwards);
        json.put("socksPort", socksPort);
        json.put("autoReconnect", autoReconnect);
        json.put("useMosh", useMosh);
        return json;
    }

    @NonNull
    static SshHost fromJson(@NonNull JSONObject json) {
        SshHost host = new SshHost();
        host.name = json.optString("name");
        host.hostName = json.optString("hostName");
        host.port = json.optInt("port", DEFAULT_PORT);
        host.user = json.optString("user");
        host.identityFile = json.optString("identityFile");
        host.extraOptions = json.optString("extraOptions");
        host.startupCommand = json.optString("startupCommand");
        host.jumpHost = json.optString("jumpHost");
        host.localForwards = json.optString("localForwards");
        host.remoteForwards = json.optString("remoteForwards");
        host.socksPort = json.optString("socksPort");
        host.autoReconnect = json.optBoolean("autoReconnect");
        host.useMosh = json.optBoolean("useMosh");
        return host;
    }

    /** The `Host` block for `ssh_config`. */
    @NonNull
    String toConfigBlock() {
        StringBuilder block = new StringBuilder();
        block.append("Host ").append(name).append('\n');
        block.append("    HostName ").append(hostName).append('\n');
        if (port != DEFAULT_PORT) block.append("    Port ").append(port).append('\n');
        if (!TextUtils.isEmpty(user)) block.append("    User ").append(user).append('\n');
        if (!TextUtils.isEmpty(identityFile)) block.append("    IdentityFile \"").append(identityFile).append("\"\n");
        if (!TextUtils.isEmpty(startupCommand)) {
            block.append("    RequestTTY yes\n");
            // Run the login shell afterwards so that the session stays open.
            // `%` starts a token in ssh_config, so it is escaped.
            block.append("    RemoteCommand ").append(startupCommand.replace('\n', ' ').replace("%", "%%")).append("; exec \"$SHELL\" -l\n");
        }
        if (!TextUtils.isEmpty(jumpHost)) block.append("    ProxyJump ").append(jumpHost).append('\n');
        appendForwards(block, "LocalForward", localForwards);
        appendForwards(block, "RemoteForward", remoteForwards);
        if (isPort(socksPort)) block.append("    DynamicForward ").append(socksPort).append('\n');
        for (String line : extraOptions.split("\n")) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#")) block.append("    ").append(line).append('\n');
        }
        // Keep connections alive while the app is in the background. It is last, since ssh uses
        // the first value of an option, so that the extra options can change it.
        block.append("    ServerAliveInterval 30\n");
        return block.toString();
    }

    private static void appendForwards(@NonNull StringBuilder block, @NonNull String keyword, @NonNull String forwards) {
        for (String line : forwards.split("\n")) {
            String arguments = toForwardArguments(line.trim());
            if (arguments != null) block.append("    ").append(keyword).append(' ').append(arguments).append('\n');
        }
    }

}
