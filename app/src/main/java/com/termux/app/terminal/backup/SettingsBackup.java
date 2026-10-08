package com.termux.app.terminal.backup;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminal.fonts.TerminalFontDialog;
import com.termux.app.terminal.fonts.TerminalFonts;
import com.termux.app.terminal.ssh.SshHostStore;
import com.termux.shared.logger.Logger;
import com.termux.shared.packages.PackageUtils;
import com.termux.shared.settings.preferences.TermuxPreferenceConstants.TERMUX_APP;
import com.termux.shared.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exports the settings to a zip file in the Download directory, and imports them from one chosen
 * with the system file picker, like after reinstalling the app or on another device.
 *
 * The settings are `termux.properties`, `colors.properties`, the SSH hosts, the main app
 * preferences like the font size, and the font: the id of a font of the font list, or a custom
 * `font.ttf`. The saved SSH passwords are not exported, since they are encrypted with an Android
 * Keystore key that can't leave the device.
 *
 * Before importing, the current files are copied to `~/.termux/backup/settings-<time>/`.
 */
public final class SettingsBackup {

    /** The request code of the file picker, handled in {@link #onActivityResult}. */
    public static final int REQUEST_CODE_IMPORT = 0x5E7B;

    private static final int FORMAT_VERSION = 1;

    /** The app preferences that are exported, leaving out state like the current session. */
    private static final List<String> EXPORTED_PREFERENCES = Collections.unmodifiableList(Arrays.asList(
        TERMUX_APP.KEY_TERMINAL_MARGIN_ADJUSTMENT,
        TERMUX_APP.KEY_SHOW_TERMINAL_TOOLBAR,
        TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED,
        TERMUX_APP.KEY_SOFT_KEYBOARD_ENABLED_ONLY_IF_NO_HARDWARE,
        TERMUX_APP.KEY_KEEP_SCREEN_ON,
        TERMUX_APP.KEY_FONTSIZE,
        TERMUX_APP.KEY_LOG_LEVEL,
        TERMUX_APP.KEY_TERMINAL_VIEW_KEY_LOGGING_ENABLED,
        TERMUX_APP.KEY_PLUGIN_ERROR_NOTIFICATIONS_ENABLED,
        TERMUX_APP.KEY_CRASH_REPORT_NOTIFICATIONS_ENABLED
    ));

    private static final File BACKUP_DIR = new File(TermuxConstants.TERMUX_DATA_HOME_DIR_PATH, "backup");

    private static final String LOG_TAG = "SettingsBackup";

    private SettingsBackup() {}

    // Export

    /** Export the settings to a new zip file in the Download directory, in the background. */
    public static void export(@NonNull TermuxActivity activity) {
        // Before Android 10 the Download directory is written directly, which needs the storage permission.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !activity.ensureStoragePermissionGranted()) {
            Toast.makeText(activity, R.string.msg_settings_export_permission, Toast.LENGTH_LONG).show();
            return;
        }

        final Context context = activity.getApplicationContext();
        final Handler handler = new Handler(Looper.getMainLooper());
        final String fileName = "termux-settings-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".zip";
        new Thread(() -> {
            String error = null;
            try {
                Map<String, byte[]> entries = collect(context);
                try (OutputStream out = DownloadsDirectory.createNewFile(context, fileName, "application/zip").out) {
                    SettingsBackupArchive.write(entries, out);
                }
            } catch (IOException | JSONException | SecurityException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to export the settings", e);
                error = e.getMessage();
            }
            final String finalError = error;
            handler.post(() -> {
                if (finalError != null)
                    Toast.makeText(activity, activity.getString(R.string.msg_settings_export_failed, finalError), Toast.LENGTH_LONG).show();
                else
                    new AlertDialog.Builder(activity)
                        .setTitle(R.string.title_settings_exported)
                        .setMessage(activity.getString(R.string.msg_settings_exported, Environment.DIRECTORY_DOWNLOADS + "/" + fileName))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        }, "SettingsExport").start();
    }

    /** Collect the zip entries of the current settings. */
    @NonNull
    private static Map<String, byte[]> collect(@NonNull Context context) throws IOException, JSONException {
        Map<String, byte[]> entries = new LinkedHashMap<>();

        JSONObject manifest = new JSONObject();
        manifest.put("format", FORMAT_VERSION);
        manifest.put("app", TermuxConstants.TERMUX_PACKAGE_NAME);
        manifest.put("version", String.valueOf(PackageUtils.getVersionNameForPackage(context)));
        manifest.put("created", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).format(new Date()));
        entries.put(SettingsBackupArchive.ENTRY_MANIFEST, manifest.toString(2).getBytes(StandardCharsets.UTF_8));

        putFile(entries, SettingsBackupArchive.ENTRY_PROPERTIES, TermuxPropertyConstants.getTermuxPropertiesFile());
        putFile(entries, SettingsBackupArchive.ENTRY_COLORS, TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE);
        putFile(entries, SettingsBackupArchive.ENTRY_SSH_HOSTS, SshHostStore.HOSTS_FILE);

        JSONObject preferences = new JSONObject();
        Map<String, ?> values = getAppPreferences(context).getAll();
        for (String key : EXPORTED_PREFERENCES) {
            Object value = values.get(key);
            if (value == null) continue;
            JSONObject typed = new JSONObject();
            typed.put("type", value.getClass().getSimpleName());
            typed.put("value", value instanceof Float ? ((Float) value).doubleValue() : value);
            preferences.put(key, typed);
        }
        entries.put(SettingsBackupArchive.ENTRY_PREFERENCES, preferences.toString(2).getBytes(StandardCharsets.UTF_8));

        TerminalFonts.Font font = TerminalFonts.getCurrentFont(context);
        if (font != null) {
            JSONObject fontJson = new JSONObject();
            fontJson.put("id", font.id);
            entries.put(SettingsBackupArchive.ENTRY_FONT, fontJson.toString(2).getBytes(StandardCharsets.UTF_8));
        } else {
            putFile(entries, SettingsBackupArchive.ENTRY_FONT_FILE, TermuxConstants.TERMUX_FONT_FILE);
        }
        return entries;
    }

    private static void putFile(@NonNull Map<String, byte[]> entries, @NonNull String name, @Nullable File file) throws IOException {
        if (file == null || !file.isFile()) return;
        try (InputStream in = new FileInputStream(file)) {
            byte[] content = new byte[(int) file.length()];
            int offset = 0;
            int read;
            while (offset < content.length && (read = in.read(content, offset, content.length - offset)) != -1)
                offset += read;
            entries.put(name, offset == content.length ? content : Arrays.copyOf(content, offset));
        }
    }

    // Import

    /** Ask to import, then let the user choose the settings file with the system file picker. */
    public static void startImport(@NonNull Activity activity) {
        new AlertDialog.Builder(activity)
            .setTitle(R.string.title_settings_import)
            .setMessage(R.string.msg_settings_import)
            .setPositiveButton(R.string.action_settings_choose_file, (d, which) -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
                try {
                    activity.startActivityForResult(intent, REQUEST_CODE_IMPORT);
                } catch (android.content.ActivityNotFoundException e) {
                    Toast.makeText(activity, R.string.msg_settings_no_file_picker, Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /**
     * Handle the result of the file picker started by {@link #startImport}.
     *
     * @return Whether the result was for the file picker.
     */
    public static boolean onActivityResult(@NonNull Activity activity, int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode != REQUEST_CODE_IMPORT) return false;
        Uri uri = data != null ? data.getData() : null;
        if (resultCode != Activity.RESULT_OK || uri == null) return true;

        final Context context = activity.getApplicationContext();
        final Handler handler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String error = null;
            TerminalFonts.Font font = null;
            File backupDir = null;
            try {
                Map<String, byte[]> entries;
                try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("Failed to open " + uri);
                    entries = SettingsBackupArchive.read(in);
                }
                checkManifest(entries);
                backupDir = backupCurrentSettings();
                restoreFiles(entries);
                restorePreferences(context, entries);
                font = restoreFont(context, entries);
            } catch (IOException | JSONException | SecurityException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to import the settings", e);
                error = e.getMessage();
            }
            final String finalError = error;
            final TerminalFonts.Font finalFont = font;
            final File finalBackupDir = backupDir;
            handler.post(() -> {
                if (finalError != null) {
                    Toast.makeText(activity, activity.getString(R.string.msg_settings_import_failed, finalError), Toast.LENGTH_LONG).show();
                    return;
                }
                Runnable finish = () -> {
                    Toast.makeText(activity, activity.getString(R.string.msg_settings_imported, finalBackupDir.getAbsolutePath()), Toast.LENGTH_LONG).show();
                    // Load everything again, like the properties, colors, font and font size.
                    activity.recreate();
                };
                // A font of the font list is applied like when chosen, downloading it if needed.
                if (finalFont != null && finalFont != TerminalFonts.getCurrentFont(activity))
                    TerminalFontDialog.applyFont(activity, finalFont, finish);
                else
                    finish.run();
            });
        }, "SettingsImport").start();
        return true;
    }

    private static void checkManifest(@NonNull Map<String, byte[]> entries) throws IOException, JSONException {
        JSONObject manifest = new JSONObject(new String(entries.get(SettingsBackupArchive.ENTRY_MANIFEST), StandardCharsets.UTF_8));
        if (!TermuxConstants.TERMUX_PACKAGE_NAME.equals(manifest.optString("app")))
            throw new IOException("Not a Termux settings file");
        if (manifest.optInt("format", -1) > FORMAT_VERSION)
            throw new IOException("The settings file is from a newer version of the app");
    }

    /** Copy the current settings files that an import replaces to a new backup directory. */
    @NonNull
    private static File backupCurrentSettings() throws IOException {
        File dir = new File(BACKUP_DIR, "settings-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()));
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Failed to create " + dir.getAbsolutePath());
        copyIfExists(TermuxPropertyConstants.getTermuxPropertiesFile(), new File(dir, SettingsBackupArchive.ENTRY_PROPERTIES));
        copyIfExists(TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE, new File(dir, SettingsBackupArchive.ENTRY_COLORS));
        copyIfExists(SshHostStore.HOSTS_FILE, new File(dir, SettingsBackupArchive.ENTRY_SSH_HOSTS));
        copyIfExists(TermuxConstants.TERMUX_FONT_FILE, new File(dir, SettingsBackupArchive.ENTRY_FONT_FILE));
        return dir;
    }

    private static void restoreFiles(@NonNull Map<String, byte[]> entries) throws IOException {
        byte[] properties = entries.get(SettingsBackupArchive.ENTRY_PROPERTIES);
        if (properties != null) {
            // Replace the properties file in use, or create the primary one.
            File propertiesFile = TermuxPropertyConstants.getTermuxPropertiesFile();
            if (propertiesFile == null) propertiesFile = new File(TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH);
            writeFile(propertiesFile, properties);
        }
        byte[] colors = entries.get(SettingsBackupArchive.ENTRY_COLORS);
        if (colors != null) writeFile(TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE, colors);
        byte[] sshHosts = entries.get(SettingsBackupArchive.ENTRY_SSH_HOSTS);
        if (sshHosts != null) {
            writeFile(SshHostStore.HOSTS_FILE, sshHosts);
            // Write ~/.ssh/termux_hosts and the include of it again from the imported hosts.
            SshHostStore.save(SshHostStore.load());
        }
        byte[] fontFile = entries.get(SettingsBackupArchive.ENTRY_FONT_FILE);
        if (fontFile != null && !entries.containsKey(SettingsBackupArchive.ENTRY_FONT))
            writeFile(TermuxConstants.TERMUX_FONT_FILE, fontFile);
    }

    private static void restorePreferences(@NonNull Context context, @NonNull Map<String, byte[]> entries) throws JSONException {
        byte[] content = entries.get(SettingsBackupArchive.ENTRY_PREFERENCES);
        if (content == null) return;
        JSONObject preferences = new JSONObject(new String(content, StandardCharsets.UTF_8));
        SharedPreferences.Editor editor = getAppPreferences(context).edit();
        for (Iterator<String> keys = preferences.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (!EXPORTED_PREFERENCES.contains(key)) continue;
            JSONObject typed = preferences.getJSONObject(key);
            switch (typed.optString("type")) {
                case "Boolean": editor.putBoolean(key, typed.getBoolean("value")); break;
                case "Integer": editor.putInt(key, typed.getInt("value")); break;
                case "Long": editor.putLong(key, typed.getLong("value")); break;
                case "Float": editor.putFloat(key, (float) typed.getDouble("value")); break;
                case "String": editor.putString(key, typed.getString("value")); break;
                default: break;
            }
        }
        editor.commit();
    }

    /** Get the font of the font list to apply, if the settings have one. */
    @Nullable
    private static TerminalFonts.Font restoreFont(@NonNull Context context, @NonNull Map<String, byte[]> entries) throws JSONException {
        byte[] content = entries.get(SettingsBackupArchive.ENTRY_FONT);
        if (content == null) {
            // A custom font.ttf was written by restoreFiles(), so the terminal loads it again.
            if (entries.containsKey(SettingsBackupArchive.ENTRY_FONT_FILE)) TerminalFonts.markFontChanged();
            return null;
        }
        return TerminalFonts.getFont(new JSONObject(new String(content, StandardCharsets.UTF_8)).optString("id"));
    }

    private static SharedPreferences getAppPreferences(@NonNull Context context) {
        return context.getSharedPreferences(TermuxConstants.TERMUX_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION, Context.MODE_PRIVATE);
    }

    private static void copyIfExists(@Nullable File from, @NonNull File to) throws IOException {
        if (from == null || !from.isFile()) return;
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        }
    }

    /** Write a file through a temporary file, so that it is never left partially written. */
    private static void writeFile(@NonNull File file, @NonNull byte[] content) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Failed to create " + dir.getAbsolutePath());
        File tempFile = new File(dir, file.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tempFile)) {
            out.write(content);
        }
        if (!tempFile.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
            throw new IOException("Failed to write " + file.getAbsolutePath());
        }
    }

}
