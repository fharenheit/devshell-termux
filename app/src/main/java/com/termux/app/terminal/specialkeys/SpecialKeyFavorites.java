package com.termux.app.terminal.specialkeys;

import androidx.annotation.NonNull;

import com.termux.app.terminal.ssh.SshHostStore;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The favorite special keys, by their macros, in {@link #FAVORITES_FILE}. */
public final class SpecialKeyFavorites {

    public static final File FAVORITES_FILE = new File(TermuxConstants.TERMUX_DATA_HOME_DIR_PATH, "special_keys_favorites.json");

    private static final String LOG_TAG = "SpecialKeyFavorites";

    private SpecialKeyFavorites() {}

    @NonNull
    public static List<String> load() {
        return load(FAVORITES_FILE);
    }

    @NonNull
    static synchronized List<String> load(@NonNull File file) {
        List<String> macros = new ArrayList<>();
        if (!file.isFile()) return macros;
        try {
            JSONArray array = new JSONArray(SshHostStore.readFile(file));
            for (int i = 0; i < array.length(); i++) {
                String macro = array.optString(i, "");
                if (!macro.isEmpty() && !macros.contains(macro)) macros.add(macro);
            }
        } catch (IOException | JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read \"" + file.getAbsolutePath() + "\"", e);
        }
        return macros;
    }

    /** Add a key if it is not a favorite, or remove it if it is, and return whether it is now. */
    public static boolean toggle(@NonNull String macro) throws IOException {
        return toggle(FAVORITES_FILE, macro);
    }

    static synchronized boolean toggle(@NonNull File file, @NonNull String macro) throws IOException {
        List<String> macros = load(file);
        boolean favorite = !macros.remove(macro);
        if (favorite) macros.add(macro);
        SshHostStore.writeFile(file, new JSONArray(macros).toString(), false);
        return favorite;
    }

}
