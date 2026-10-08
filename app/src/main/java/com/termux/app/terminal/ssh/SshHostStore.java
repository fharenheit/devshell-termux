package com.termux.app.terminal.ssh;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Stores the SSH hosts registered in the app.
 *
 * The hosts are kept in {@link #HOSTS_FILE} and written as `Host` blocks to {@link #TERMUX_HOSTS_FILE},
 * which is included from the start of `~/.ssh/config`, so that `ssh <name>` works in the shell
 * too and the user's own `~/.ssh/config` is otherwise left as is. Both are in the home directory,
 * so they are kept if the app data is cleared, unlike the saved passwords.
 */
public final class SshHostStore {

    public static final File SSH_DIR = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".ssh");
    public static final File SSH_CONFIG_FILE = new File(SSH_DIR, "config");
    public static final File TERMUX_HOSTS_FILE = new File(SSH_DIR, "termux_hosts");
    public static final File HOSTS_FILE = new File(TermuxConstants.TERMUX_DATA_HOME_DIR_PATH, "ssh_hosts.json");

    private static final String INCLUDE_LINE = "Include " + TERMUX_HOSTS_FILE.getName();

    private static final String LOG_TAG = "SshHostStore";

    private SshHostStore() {}

    @NonNull
    public static synchronized List<SshHost> load() {
        List<SshHost> hosts = new ArrayList<>();
        if (!HOSTS_FILE.isFile()) return hosts;
        try {
            JSONArray array = new JSONArray(readFile(HOSTS_FILE));
            for (int i = 0; i < array.length(); i++)
                hosts.add(SshHost.fromJson(array.getJSONObject(i)));
        } catch (IOException | JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read \"" + HOSTS_FILE.getAbsolutePath() + "\"", e);
        }
        return hosts;
    }

    /** Save the hosts and write them to {@link #TERMUX_HOSTS_FILE}, including it from `~/.ssh/config`. */
    public static synchronized void save(@NonNull List<SshHost> hosts) throws IOException {
        JSONArray array = new JSONArray();
        StringBuilder config = new StringBuilder();
        config.append("# Written by the Termux app from ").append(HOSTS_FILE.getAbsolutePath()).append(".\n");
        config.append("# Changes here are overwritten. Edit the hosts in the app, or add your own to ~/.ssh/config.\n");
        try {
            for (SshHost host : hosts) {
                array.put(host.toJson());
                config.append('\n').append(host.toConfigBlock());
            }
            writeFile(HOSTS_FILE, array.toString(2), true);
        } catch (JSONException e) {
            throw new IOException(e);
        }

        ensureSshDir();
        writeFile(TERMUX_HOSTS_FILE, config.toString(), true);
        ensureIncluded();
    }

    @Nullable
    public static SshHost find(@NonNull List<SshHost> hosts, @Nullable String name) {
        for (SshHost host : hosts)
            if (host.name.equals(name)) return host;
        return null;
    }

    static void ensureSshDir() throws IOException {
        if (!SSH_DIR.isDirectory() && !SSH_DIR.mkdirs())
            throw new IOException("Failed to create \"" + SSH_DIR.getAbsolutePath() + "\"");
        // ssh refuses keys and config that others can read.
        setOwnerOnly(SSH_DIR, true);
    }

    /** Add the `Include` line at the start of `~/.ssh/config`, since it only applies to hosts after it. */
    private static void ensureIncluded() throws IOException {
        String config = SSH_CONFIG_FILE.isFile() ? readFile(SSH_CONFIG_FILE) : "";
        for (String line : config.split("\n")) {
            if (line.trim().equalsIgnoreCase(INCLUDE_LINE)) return;
        }
        writeFile(SSH_CONFIG_FILE, "# Hosts added in the Termux app\n" + INCLUDE_LINE + "\n\n" + config, true);
    }

    /**
     * Get the names of the hosts in `~/.ssh/config` that are not registered in the app, so that
     * they can be connected to too. Patterns with wildcards are left out.
     */
    @NonNull
    public static List<String> loadConfigHostNames(@NonNull List<SshHost> registeredHosts) {
        Set<String> names = new LinkedHashSet<>();
        if (!SSH_CONFIG_FILE.isFile()) return new ArrayList<>();
        try {
            for (String line : readFile(SSH_CONFIG_FILE).split("\n")) {
                String[] words = line.trim().split("[\\s=]+");
                if (words.length < 2 || !words[0].equalsIgnoreCase("Host")) continue;
                for (int i = 1; i < words.length; i++) {
                    String name = words[i];
                    if (name.startsWith("#")) break;
                    if (name.contains("*") || name.contains("?") || name.startsWith("!")) continue;
                    names.add(name);
                }
            }
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read \"" + SSH_CONFIG_FILE.getAbsolutePath() + "\"", e);
        }
        for (SshHost host : registeredHosts) names.remove(host.name);
        return new ArrayList<>(names);
    }

    @NonNull
    public static String readFile(@NonNull File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            int offset = 0;
            int read;
            while (offset < buffer.length && (read = in.read(buffer, offset, buffer.length - offset)) != -1)
                offset += read;
            return new String(buffer, 0, offset, StandardCharsets.UTF_8);
        }
    }

    /** Write a file through a temporary file, so that it is never left partially written. */
    public static void writeFile(@NonNull File file, @NonNull String content, boolean ownerOnly) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs())
            throw new IOException("Failed to create \"" + dir.getAbsolutePath() + "\"");
        File tempFile = new File(dir, file.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tempFile)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        if (ownerOnly) setOwnerOnly(tempFile, false);
        if (!tempFile.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
            throw new IOException("Failed to write \"" + file.getAbsolutePath() + "\"");
        }
    }

    private static void setOwnerOnly(@NonNull File file, boolean executable) {
        //noinspection ResultOfMethodCallIgnored
        file.setReadable(false, false);
        //noinspection ResultOfMethodCallIgnored
        file.setReadable(true, true);
        //noinspection ResultOfMethodCallIgnored
        file.setWritable(false, false);
        //noinspection ResultOfMethodCallIgnored
        file.setWritable(true, true);
        //noinspection ResultOfMethodCallIgnored
        file.setExecutable(false, false);
        if (executable) {
            //noinspection ResultOfMethodCallIgnored
            file.setExecutable(true, true);
        }
    }

}
