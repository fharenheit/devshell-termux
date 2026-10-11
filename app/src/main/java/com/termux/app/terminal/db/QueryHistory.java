package com.termux.app.terminal.db;

import androidx.annotation.NonNull;

import com.termux.app.terminal.ssh.SshHostStore;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The statements run on each connection, newest first, in {@link #HISTORY_FILE}, which is not
 * exported with the settings, and the queries saved by the user in {@link #SAVED_FILE}, which is.
 */
public final class QueryHistory {

    public static final File HISTORY_FILE = new File(TermuxConstants.TERMUX_DATA_HOME_DIR_PATH, "db_history.json");
    public static final File SAVED_FILE = new File(TermuxConstants.TERMUX_DATA_HOME_DIR_PATH, "db_queries.json");

    static final int MAX_HISTORY = 200;
    private static final int MAX_SQL_LENGTH = 20000;

    private static final String LOG_TAG = "QueryHistory";

    /** A statement that was run or saved. */
    public static final class Entry {
        @NonNull public final String connectionId;
        @NonNull public final String sql;
        /** The name of a saved query, or empty. */
        @NonNull public final String name;
        public final long time;
        public final long elapsedMs;
        /** The rows returned or changed, or -1 if it failed. */
        public final long rows;

        public Entry(@NonNull String connectionId, @NonNull String sql, @NonNull String name, long time, long elapsedMs, long rows) {
            this.connectionId = connectionId;
            this.sql = sql;
            this.name = name;
            this.time = time;
            this.elapsedMs = elapsedMs;
            this.rows = rows;
        }

        @NonNull
        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("connection", connectionId);
            json.put("sql", sql);
            if (!name.isEmpty()) json.put("name", name);
            json.put("time", time);
            if (elapsedMs > 0) json.put("elapsed", elapsedMs);
            json.put("rows", rows);
            return json;
        }

        @NonNull
        static Entry fromJson(@NonNull JSONObject json) {
            return new Entry(json.optString("connection", ""), json.optString("sql", ""), json.optString("name", ""),
                json.optLong("time", 0), json.optLong("elapsed", 0), json.optLong("rows", -1));
        }
    }

    private QueryHistory() {}

    @NonNull
    public static synchronized List<Entry> load(@NonNull File file) {
        List<Entry> entries = new ArrayList<>();
        if (!file.isFile()) return entries;
        try {
            JSONArray array = new JSONArray(SshHostStore.readFile(file));
            for (int i = 0; i < array.length(); i++) entries.add(Entry.fromJson(array.getJSONObject(i)));
        } catch (IOException | JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read \"" + file + "\"", e);
        }
        return entries;
    }

    public static synchronized void save(@NonNull File file, @NonNull List<Entry> entries) {
        JSONArray array = new JSONArray();
        try {
            for (Entry entry : entries) array.put(entry.toJson());
            SshHostStore.writeFile(file, array.toString(1), true);
        } catch (IOException | JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write \"" + file + "\"", e);
        }
    }

    /** Add a statement that was run, dropping an older run of the same statement and the oldest beyond the limit. */
    public static synchronized void add(@NonNull Entry entry) {
        if (entry.sql.length() > MAX_SQL_LENGTH) return;
        List<Entry> entries = load(HISTORY_FILE);
        addTo(entries, entry);
        save(HISTORY_FILE, entries);
    }

    static void addTo(@NonNull List<Entry> entries, @NonNull Entry entry) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry old = entries.get(i);
            if (old.connectionId.equals(entry.connectionId) && old.sql.equals(entry.sql)) entries.remove(i);
        }
        entries.add(0, entry);
        while (entries.size() > MAX_HISTORY) entries.remove(entries.size() - 1);
    }

    /** The entries of a connection, or of all connections for saved queries without one. */
    @NonNull
    public static List<Entry> filter(@NonNull List<Entry> entries, @NonNull String connectionId) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries) if (entry.connectionId.isEmpty() || entry.connectionId.equals(connectionId)) result.add(entry);
        return result;
    }

}
