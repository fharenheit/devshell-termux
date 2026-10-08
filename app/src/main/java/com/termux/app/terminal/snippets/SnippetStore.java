package com.termux.app.terminal.snippets;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.terminal.ssh.SshHostStore;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Loads and saves the snippets in {@link #SNIPPETS_FILE}. */
public final class SnippetStore {

    public static final File SNIPPETS_FILE = new File(TermuxConstants.TERMUX_DATA_HOME_DIR_PATH, "snippets.json");

    private static final String LOG_TAG = "SnippetStore";

    private SnippetStore() {}

    @NonNull
    public static List<Snippet> load() {
        return load(SNIPPETS_FILE);
    }

    @NonNull
    static synchronized List<Snippet> load(@NonNull File file) {
        List<Snippet> snippets = new ArrayList<>();
        if (!file.isFile()) return snippets;
        try {
            snippets = parse(SshHostStore.readFile(file));
        } catch (IOException | JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read \"" + file.getAbsolutePath() + "\"", e);
        }
        return snippets;
    }

    @NonNull
    static List<Snippet> parse(@NonNull String content) throws JSONException {
        List<Snippet> snippets = new ArrayList<>();
        JSONArray array = new JSONArray(content);
        for (int i = 0; i < array.length(); i++) snippets.add(Snippet.fromJson(array.getJSONObject(i)));
        return snippets;
    }

    public static void save(@NonNull List<Snippet> snippets) throws IOException {
        save(SNIPPETS_FILE, snippets);
    }

    static synchronized void save(@NonNull File file, @NonNull List<Snippet> snippets) throws IOException {
        JSONArray array = new JSONArray();
        try {
            for (Snippet snippet : snippets) array.put(snippet.toJson());
            SshHostStore.writeFile(file, array.toString(2), true);
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** The snippets for a tab, which is an SSH tab of {@code hostName} if not null: the host's first, then the others. */
    @NonNull
    public static List<Snippet> getFor(@NonNull List<Snippet> snippets, @Nullable String hostName) {
        List<Snippet> result = new ArrayList<>();
        for (Snippet snippet : snippets)
            if (!snippet.host.isEmpty() && snippet.isFor(hostName)) result.add(snippet);
        for (Snippet snippet : snippets)
            if (snippet.host.isEmpty()) result.add(snippet);
        return result;
    }

}
