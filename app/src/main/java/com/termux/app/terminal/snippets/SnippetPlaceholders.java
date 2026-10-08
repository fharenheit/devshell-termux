package com.termux.app.terminal.snippets;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The values asked for when a snippet is typed: `<NAME>` in its command, of upper case letters,
 * digits and `_`, or `<NAME=default>` with a default value. A name used several times is asked
 * for once. Shell syntax like `< file`, `<<EOF` or `<(command)` is not a placeholder. The last
 * value of each name is remembered and offered the next time.
 */
public final class SnippetPlaceholders {

    private static final Pattern PLACEHOLDER = Pattern.compile("<([A-Z][A-Z0-9_]*)(?:=([^<>\\n]*))?>");

    private static final String PREFERENCES = "snippet_placeholders";

    /** A value to ask for. */
    public static final class Placeholder {
        @NonNull public final String name;
        /** The default value, or empty if none. */
        @NonNull public final String defaultValue;

        Placeholder(@NonNull String name, @NonNull String defaultValue) {
            this.name = name;
            this.defaultValue = defaultValue;
        }
    }

    private SnippetPlaceholders() {}

    /** The placeholders of a command, each name once, in the order they first appear. */
    @NonNull
    public static List<Placeholder> find(@NonNull String command) {
        List<Placeholder> placeholders = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(command);
        while (matcher.find()) {
            String name = matcher.group(1);
            boolean known = false;
            for (Placeholder placeholder : placeholders) if (placeholder.name.equals(name)) known = true;
            if (!known) placeholders.add(new Placeholder(name, matcher.group(2) != null ? matcher.group(2) : ""));
        }
        return placeholders;
    }

    /** The command with the placeholders replaced by their values, or their defaults if they have none. */
    @NonNull
    public static String fill(@NonNull String command, @NonNull Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(command);
        StringBuilder result = new StringBuilder();
        int end = 0;
        while (matcher.find()) {
            result.append(command, end, matcher.start());
            String value = values.get(matcher.group(1));
            if (value == null) value = matcher.group(2) != null ? matcher.group(2) : "";
            result.append(value);
            end = matcher.end();
        }
        result.append(command, end, command.length());
        return result.toString();
    }

    /** The last value used for a name, or {@code null}. */
    @Nullable
    static String getLastValue(@NonNull Context context, @NonNull String name) {
        return getPreferences(context).getString(name, null);
    }

    static void saveValues(@NonNull Context context, @NonNull Map<String, String> values) {
        SharedPreferences.Editor editor = getPreferences(context).edit();
        for (Map.Entry<String, String> entry : values.entrySet()) editor.putString(entry.getKey(), entry.getValue());
        editor.apply();
    }

    private static SharedPreferences getPreferences(@NonNull Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

}
