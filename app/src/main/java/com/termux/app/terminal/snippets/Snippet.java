package com.termux.app.terminal.snippets;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

/** A saved command that can be typed into a terminal tab. */
public class Snippet {

    @NonNull public String name = "";
    /** The text to type, which can have several lines. */
    @NonNull public String command = "";
    /** Whether Enter is pressed after the text, to run it. */
    public boolean run = true;
    /** The SSH host whose tabs the snippet is for, or empty for all tabs. */
    @NonNull public String host = "";

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("name", name);
        json.put("command", command);
        json.put("run", run);
        if (!host.isEmpty()) json.put("host", host);
        return json;
    }

    @NonNull
    public static Snippet fromJson(@NonNull JSONObject json) {
        Snippet snippet = new Snippet();
        snippet.name = json.optString("name", "");
        snippet.command = json.optString("command", "");
        snippet.run = json.optBoolean("run", true);
        snippet.host = json.optString("host", "");
        return snippet;
    }

    /** The name, or the first line of the command if it has none. */
    @NonNull
    public String getTitle() {
        if (!name.trim().isEmpty()) return name.trim();
        String firstLine = command.trim();
        int newline = firstLine.indexOf('\n');
        return newline < 0 ? firstLine : firstLine.substring(0, newline) + " …";
    }

    /** Whether the snippet is shown for a tab, which is an SSH tab of {@code hostName} if not null. */
    public boolean isFor(@Nullable String hostName) {
        return host.isEmpty() || host.equals(hostName);
    }

    /**
     * The text to write to the terminal: lines end with a carriage return like the Enter key, and
     * a final one is added if the snippet runs.
     */
    @NonNull
    public String getInput() {
        String text = command.replace("\r\n", "\n");
        if (run) {
            while (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
            text += "\n";
        }
        return text.replace('\n', '\r');
    }

}
