package com.termux.app.terminal.ssh;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.shell.TermuxShellUtils;
import com.termux.shared.termux.TermuxConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Checking and trusting the host key of an SSH host, for connections that have no terminal to
 * ask in, like the SFTP file browser.
 *
 * The key is fetched with ssh itself into a temporary known hosts file with
 * `StrictHostKeyChecking=accept-new`, without logging in, so that the host's ssh settings, like
 * its port and jump host, apply. It is then compared with the entries of the host in the user's
 * known hosts file with `ssh-keygen -F`.
 */
public final class SshHostKeys {

    /** The key is in the known hosts file. */
    public static final int TRUSTED = 0;
    /** No key of the host, or none of the key's type, is in the known hosts file. */
    public static final int UNKNOWN = 1;
    /** Another key of the same type is in the known hosts file: the server changed or it is an attack. */
    public static final int CHANGED = 2;

    /** The result of checking the host key of a host. */
    public static final class Check {
        public final int state;
        /** The fingerprint of the server's key, like `ED25519 SHA256:…`. */
        @NonNull public final String fingerprint;
        /** The fingerprints of the known keys of the host that differ from the server's. */
        @NonNull public final List<String> knownFingerprints;
        /** The known hosts lines of the server's key. */
        @NonNull final String keyLines;
        /** The name of the host in the known hosts file, like `[example.com]:2222`. */
        @NonNull final String knownHostsName;
        @NonNull final File knownHostsFile;

        Check(int state, @NonNull String fingerprint, @NonNull List<String> knownFingerprints, @NonNull String keyLines,
              @NonNull String knownHostsName, @NonNull File knownHostsFile) {
            this.state = state;
            this.fingerprint = fingerprint;
            this.knownFingerprints = knownFingerprints;
            this.keyLines = keyLines;
            this.knownHostsName = knownHostsName;
            this.knownHostsFile = knownHostsFile;
        }
    }

    private static final class Result {
        final int exitCode;
        final String output;
        final String error;

        Result(int exitCode, String output, String error) {
            this.exitCode = exitCode;
            this.output = output;
            this.error = error;
        }
    }

    private SshHostKeys() {}

    /** Connect to a host to get its key and compare it with the known ones. Not on the main thread. */
    @NonNull
    public static Check check(@NonNull Context context, @NonNull String hostName) throws IOException {
        // Where the host's known keys are, and its name in them.
        Result config = run(context, Arrays.asList(bin("ssh"), "-G", hostName));
        if (config.exitCode != 0) throw new IOException(lastLine(config.error, "ssh -G failed"));
        String name = getKnownHostsName(config.output);
        File knownHostsFile = getKnownHostsFile(config.output);

        File temp = File.createTempFile("known_hosts", null, context.getCacheDir());
        try {
            // Logging in fails without authentication methods, after the key was written.
            Result fetch = run(context, Arrays.asList(bin("ssh"), "-o", "BatchMode=yes", "-o", "PreferredAuthentications=none",
                "-o", "StrictHostKeyChecking=accept-new", "-o", "UserKnownHostsFile=" + temp.getAbsolutePath(),
                "-o", "GlobalKnownHostsFile=/dev/null", "-o", "ControlPath=none", "-o", "ConnectTimeout=15",
                "-o", "LogLevel=ERROR", hostName, "true"));
            String keyLines = SshHostStore.readFile(temp).trim();
            if (keyLines.isEmpty()) throw new IOException(lastLine(fetch.error, "Failed to connect to " + hostName));
            Result fingerprint = run(context, Arrays.asList(bin("ssh-keygen"), "-l", "-f", temp.getAbsolutePath()));
            String serverFingerprint = formatFingerprint(fingerprint.output);

            List<String> known = new ArrayList<>();
            if (knownHostsFile.isFile()) {
                Result find = run(context, Arrays.asList(bin("ssh-keygen"), "-F", name, "-f", knownHostsFile.getAbsolutePath()));
                for (String line : find.output.split("\n"))
                    if (!line.trim().isEmpty() && !line.startsWith("#")) known.add(line.trim());
            }
            int state = compare(keyLines, known);
            List<String> knownFingerprints = new ArrayList<>();
            if (state == CHANGED) {
                File knownTemp = File.createTempFile("known_hosts", null, context.getCacheDir());
                try {
                    writeFile(knownTemp, String.join("\n", known) + "\n", false);
                    Result knownPrint = run(context, Arrays.asList(bin("ssh-keygen"), "-l", "-f", knownTemp.getAbsolutePath()));
                    for (String line : knownPrint.output.split("\n"))
                        if (!line.trim().isEmpty()) knownFingerprints.add(formatFingerprint(line));
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    knownTemp.delete();
                }
            }
            return new Check(state, serverFingerprint, knownFingerprints, keyLines, name, knownHostsFile);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    /**
     * Compare the server's key lines with the known lines of the host: trusted if a known line
     * has the same key, changed if one has another key of the same type, else unknown.
     */
    static int compare(@NonNull String keyLines, @NonNull List<String> knownLines) {
        boolean sameType = false;
        for (String keyLine : keyLines.split("\n")) {
            String[] key = getKey(keyLine);
            if (key == null) continue;
            for (String knownLine : knownLines) {
                String[] knownKey = getKey(knownLine);
                if (knownKey == null) continue;
                if (knownKey[0].equals(key[0])) {
                    if (knownKey[1].equals(key[1])) return TRUSTED;
                    sameType = true;
                }
            }
        }
        return sameType ? CHANGED : UNKNOWN;
    }

    /** The type and the base64 key of a known hosts line, or {@code null} for a marker line like `@revoked`. */
    @Nullable
    static String[] getKey(@NonNull String line) {
        String[] fields = line.trim().split("\\s+");
        if (fields.length < 3 || fields[0].startsWith("@") || fields[0].startsWith("#")) return null;
        return new String[]{fields[1], fields[2]};
    }

    /** The name of a host in known hosts files from `ssh -G`: its host key alias or name, with the port if not 22. */
    @NonNull
    static String getKnownHostsName(@NonNull String sshConfig) {
        String hostName = null;
        String alias = null;
        String port = "22";
        for (String line : sshConfig.split("\n")) {
            String[] fields = line.trim().split("\\s+", 2);
            if (fields.length < 2) continue;
            switch (fields[0]) {
                case "hostname": hostName = fields[1]; break;
                case "hostkeyalias": alias = fields[1]; break;
                case "port": port = fields[1]; break;
            }
        }
        String name = alias != null ? alias : String.valueOf(hostName);
        return port.equals("22") ? name : "[" + name + "]:" + port;
    }

    /** The first user known hosts file from `ssh -G`. */
    @NonNull
    static File getKnownHostsFile(@NonNull String sshConfig) {
        for (String line : sshConfig.split("\n")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length >= 2 && fields[0].equals("userknownhostsfile")) {
                String path = fields[1];
                if (path.startsWith("~/")) path = TermuxConstants.TERMUX_HOME_DIR_PATH + path.substring(1);
                return new File(path);
            }
        }
        return new File(SshHostStore.SSH_DIR, "known_hosts");
    }

    /** `ED25519 SHA256:…` from `256 SHA256:… host (ED25519)`. */
    @NonNull
    static String formatFingerprint(@NonNull String output) {
        String line = output.trim().split("\n")[0].trim();
        String[] fields = line.split("\\s+");
        if (fields.length < 2) return line;
        String type = fields[fields.length - 1];
        if (type.startsWith("(") && type.endsWith(")")) return type.substring(1, type.length() - 1) + " " + fields[1];
        return fields[1];
    }

    /** Trust the server's key: replace the known keys of the host if it changed, else add it. Not on the main thread. */
    public static void trust(@NonNull Context context, @NonNull Check check) throws IOException {
        if (check.state == CHANGED) remove(context, check);
        SshHostStore.ensureSshDir();
        boolean exists = check.knownHostsFile.isFile();
        String content = exists ? SshHostStore.readFile(check.knownHostsFile) : "";
        if (!content.isEmpty() && !content.endsWith("\n")) content += "\n";
        writeFile(check.knownHostsFile, content + check.keyLines + "\n", true);
    }

    /** Remove the known keys of the host, so that its key is asked again. Not on the main thread. */
    public static void remove(@NonNull Context context, @NonNull Check check) throws IOException {
        if (!check.knownHostsFile.isFile()) return;
        Result result = run(context, Arrays.asList(bin("ssh-keygen"), "-R", check.knownHostsName, "-f", check.knownHostsFile.getAbsolutePath()));
        if (result.exitCode != 0) throw new IOException(lastLine(result.error, "ssh-keygen -R failed"));
        // ssh-keygen keeps the previous file as known_hosts.old.
    }

    private static void writeFile(@NonNull File file, @NonNull String content, boolean ownerOnly) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Failed to create " + dir);
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        if (ownerOnly) {
            //noinspection ResultOfMethodCallIgnored
            file.setReadable(false, false);
            //noinspection ResultOfMethodCallIgnored
            file.setReadable(true, true);
        }
    }

    @NonNull
    private static String bin(@NonNull String name) throws IOException {
        File file = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, name);
        if (!file.canExecute()) throw new IOException(name + " is not installed. Run \"pkg install openssh\".");
        return file.getAbsolutePath();
    }

    @NonNull
    private static Result run(@NonNull Context context, @NonNull List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(TermuxConstants.TERMUX_HOME_DIR);
        builder.environment().clear();
        for (String variable : TermuxShellUtils.buildEnvironment(context, false, TermuxConstants.TERMUX_HOME_DIR_PATH)) {
            int equals = variable.indexOf('=');
            if (equals > 0) builder.environment().put(variable.substring(0, equals), variable.substring(equals + 1));
        }
        Process process = builder.start();
        process.getOutputStream().close();
        // Read stderr in another thread, so that neither stream fills up and blocks the process.
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        Thread errorReader = new Thread(() -> {
            try {
                copy(process.getErrorStream(), error);
            } catch (IOException ignored) {
            }
        });
        errorReader.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(process.getInputStream(), output);
        int exitCode;
        try {
            exitCode = process.waitFor();
            errorReader.join(5000);
        } catch (InterruptedException e) {
            process.destroy();
            throw new IOException("Interrupted");
        }
        return new Result(exitCode, new String(output.toByteArray(), StandardCharsets.UTF_8),
            new String(error.toByteArray(), StandardCharsets.UTF_8));
    }

    private static void copy(@NonNull InputStream in, @NonNull ByteArrayOutputStream out) throws IOException {
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) if (out.size() < 1024 * 1024) out.write(buffer, 0, read);
    }

    @NonNull
    private static String lastLine(@NonNull String text, @NonNull String fallback) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return fallback;
        return trimmed.substring(trimmed.lastIndexOf('\n') + 1).trim();
    }

}
