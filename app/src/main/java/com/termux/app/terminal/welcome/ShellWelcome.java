package com.termux.app.terminal.welcome;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.BuildConfig;
import com.termux.R;
import com.termux.shared.fork.DevShellInfo;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The welcome message of the local shell, about this app instead of Termux. The login script of
 * Termux shows `~/.termux/motd.sh` if it exists instead of the `$PREFIX/etc/motd` of the
 * termux-tools package, so writing it there keeps it after package upgrades. It is written in the
 * language of the app, and again when that or the version changes. A file without the
 * {@link #MARKER} line is the user's own and is never changed. `~/.hushlogin` still hides it.
 */
public final class ShellWelcome {

    /** The line that marks the file as written by the app. Deleting it keeps the user's changes. */
    static final String MARKER = "# devshell-managed: delete this line to keep your changes";
    private static final String END = "DEVSHELL_WELCOME_END";

    private static final String LOG_TAG = "ShellWelcome";

    private ShellWelcome() {}

    /** Write the welcome message in a background thread, unless the user has their own. */
    public static void install(@NonNull Context context) {
        String script = build(context.getString(R.string.shell_welcome_title, BuildConfig.VERSION_NAME),
            context.getString(R.string.shell_welcome, DevShellInfo.getManualUrl(context), DevShellInfo.RELEASES_URL,
                DevShellInfo.ISSUES_URL, DevShellInfo.TERMUX_PACKAGES_ISSUES_URL));
        File file = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".termux/motd.sh");
        new Thread(() -> {
            try {
                write(file, script);
            } catch (IOException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write " + file, e);
            }
        }).start();
    }

    /** Write the script to the file if it is missing or written by the app with other content. */
    static boolean write(@NonNull File file, @NonNull String script) throws IOException {
        if (file.exists()) {
            String content = read(file);
            if (!content.contains(MARKER) || content.equals(script)) return false;
        }
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Failed to create " + dir);
        File temp = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(temp)) {
            out.write(script.getBytes(StandardCharsets.UTF_8));
        }
        if (!temp.setExecutable(true, true) || !temp.renameTo(file)) {
            temp.delete();
            throw new IOException("Failed to write " + file);
        }
        return true;
    }

    @NonNull
    private static String read(@NonNull File file) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) bytes.write(buffer, 0, read);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    /** A shell script that prints the title in bold, then the text. */
    @NonNull
    static String build(@NonNull String title, @NonNull String text) {
        return "#!" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh\n" +
            MARKER + "\n" +
            "# The welcome message of " + DevShellInfo.NAME + ", in the language of the app.\n" +
            "printf '\\n \\033[1m%s\\033[0m\\n' '" + title.replace("'", "'\\''") + "'\n" +
            "cat <<'" + END + "'\n" +
            text.replace("\n" + END, "\n " + END) + "\n" +
            END + "\n";
    }

}
