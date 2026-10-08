package com.termux.app.terminal.sftp;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** The bookmarked folders of each host, kept in the app's shared preferences. */
public final class SftpBookmarks {

    private static final String PREFERENCES = "sftp_bookmarks";

    private SftpBookmarks() {}

    private static SharedPreferences getPreferences(@NonNull Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    @NonNull
    public static List<String> load(@NonNull Context context, @NonNull String hostName) {
        List<String> paths = new ArrayList<>();
        String json = getPreferences(context).getString(hostName, null);
        if (json == null) return paths;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) paths.add(array.getString(i));
        } catch (JSONException ignored) {
        }
        return paths;
    }

    private static void save(@NonNull Context context, @NonNull String hostName, @NonNull List<String> paths) {
        SharedPreferences.Editor editor = getPreferences(context).edit();
        if (paths.isEmpty()) editor.remove(hostName);
        else editor.putString(hostName, new JSONArray(paths).toString());
        editor.apply();
    }

    /** Add a folder at the end, if it is not bookmarked yet. */
    public static void add(@NonNull Context context, @NonNull String hostName, @NonNull String path) {
        List<String> paths = load(context, hostName);
        if (paths.contains(path)) return;
        paths.add(path);
        save(context, hostName, paths);
    }

    public static void remove(@NonNull Context context, @NonNull String hostName, @NonNull String path) {
        List<String> paths = load(context, hostName);
        if (paths.remove(path)) save(context, hostName, paths);
    }

}
