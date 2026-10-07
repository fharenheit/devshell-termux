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
    /** More `ssh_config` options, one per line, like `ProxyJump bastion` or `LocalForward 8080 localhost:80`. */
    @NonNull public String extraOptions = "";
    /** A command to run on the host after connecting, before the login shell. */
    @NonNull public String startupCommand = "";

    public static boolean isValidName(@Nullable String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
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
        // Keep connections alive while the app is in the background.
        block.append("    ServerAliveInterval 30\n");
        if (!TextUtils.isEmpty(startupCommand)) {
            block.append("    RequestTTY yes\n");
            // Run the login shell afterwards so that the session stays open.
            // `%` starts a token in ssh_config, so it is escaped.
            block.append("    RemoteCommand ").append(startupCommand.replace('\n', ' ').replace("%", "%%")).append("; exec \"$SHELL\" -l\n");
        }
        for (String line : extraOptions.split("\n")) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#")) block.append("    ").append(line).append('\n');
        }
        return block.toString();
    }

}
