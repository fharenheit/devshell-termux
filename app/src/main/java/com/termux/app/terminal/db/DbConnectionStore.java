package com.termux.app.terminal.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.terminal.ssh.SshHostStore;
import com.termux.app.terminal.ssh.SshPasswords;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads and saves the database connections in {@link #CONNECTIONS_FILE}, which is included in the
 * settings export. The passwords are saved apart, encrypted with a key of the Android Keystore
 * like the SSH passwords, and are not exported.
 */
public final class DbConnectionStore {

    public static final File CONNECTIONS_FILE = new File(TermuxConstants.TERMUX_DATA_HOME_DIR_PATH, "db_connections.json");

    /** The prefix of the keys of the passwords in {@link SshPasswords}, apart from the SSH hosts. */
    private static final String PASSWORD_PREFIX = "db-connection:";

    private static final String LOG_TAG = "DbConnectionStore";

    /** The file of {@link #load()} and {@link #save(List)}, which tests change. */
    static File sFile = CONNECTIONS_FILE;

    private DbConnectionStore() {}

    @NonNull
    public static List<DbConnectionConfig> load() {
        return load(sFile);
    }

    @NonNull
    static synchronized List<DbConnectionConfig> load(@NonNull File file) {
        List<DbConnectionConfig> configs = new ArrayList<>();
        if (!file.isFile()) return configs;
        try {
            configs = parse(SshHostStore.readFile(file));
        } catch (IOException | JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read \"" + file.getAbsolutePath() + "\"", e);
        }
        return configs;
    }

    @NonNull
    static List<DbConnectionConfig> parse(@NonNull String content) throws JSONException {
        List<DbConnectionConfig> configs = new ArrayList<>();
        JSONArray array = new JSONArray(content);
        for (int i = 0; i < array.length(); i++) configs.add(DbConnectionConfig.fromJson(array.getJSONObject(i)));
        return configs;
    }

    public static void save(@NonNull List<DbConnectionConfig> configs) throws IOException {
        save(sFile, configs);
    }

    static synchronized void save(@NonNull File file, @NonNull List<DbConnectionConfig> configs) throws IOException {
        JSONArray array = new JSONArray();
        try {
            for (DbConnectionConfig config : configs) array.put(config.toJson());
            SshHostStore.writeFile(file, array.toString(2), true);
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    public static boolean hasPassword(@NonNull Context context, @NonNull DbConnectionConfig config) {
        return SshPasswords.has(context, PASSWORD_PREFIX + config.id);
    }

    /** Save the password, or delete it if empty, returning whether it worked. */
    public static boolean savePassword(@NonNull Context context, @NonNull DbConnectionConfig config, @NonNull char[] password) {
        if (password.length == 0) {
            deletePassword(context, config);
            return true;
        }
        return SshPasswords.save(context, PASSWORD_PREFIX + config.id, password);
    }

    /** The saved password, or {@code null} if none is saved. */
    @Nullable
    public static String loadPassword(@NonNull Context context, @NonNull DbConnectionConfig config) {
        byte[] bytes = SshPasswords.load(context, PASSWORD_PREFIX + config.id);
        if (bytes == null) return null;
        try {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    public static void deletePassword(@NonNull Context context, @NonNull DbConnectionConfig config) {
        SshPasswords.delete(context, PASSWORD_PREFIX + config.id);
    }

}
