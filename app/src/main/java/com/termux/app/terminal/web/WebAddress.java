package com.termux.app.terminal.web;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Pattern;

/** Turns what is typed in the address bar of a web tab into a URL. */
public final class WebAddress {

    static final String SEARCH_URL = "https://www.google.com/search?q=";

    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://.*");
    /** A host, or a host and port, with an optional path, like `example.com`, `localhost:8080/x` or `[::1]:80`. */
    private static final Pattern HOST = Pattern.compile("^(\\[[0-9a-fA-F:]+]|[^\\s/:?#]+)(:\\d{1,5})?([/?#]\\S*)?$");
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private WebAddress() {}

    /**
     * The URL for the text: a URL as it is, a host or address with http for local and private
     * addresses or https otherwise, or a web search for other text. Returns {@code null} for empty text.
     */
    @Nullable
    public static String toUrl(@Nullable String text) {
        if (text == null) return null;
        text = text.trim();
        if (text.isEmpty()) return null;
        if (SCHEME.matcher(text).matches()) return text;
        if (text.startsWith("about:")) return text;
        java.util.regex.Matcher matcher = HOST.matcher(text);
        if (matcher.matches()) {
            String host = matcher.group(1).toLowerCase(Locale.ROOT);
            boolean hasPort = matcher.group(2) != null;
            if (isLocal(host)) return "http://" + text;
            if (host.contains(".") || hasPort || host.startsWith("[")) return "https://" + text;
        }
        return SEARCH_URL + Uri.encode(text);
    }

    /** Whether a host is this device or a private network address, which usually serve http. */
    static boolean isLocal(@NonNull String host) {
        if (host.equals("localhost") || host.endsWith(".local") || host.equals("[::1]")) return true;
        if (!IPV4.matcher(host).matches()) return false;
        String[] parts = host.split("\\.");
        int a = Integer.parseInt(parts[0]);
        int b = Integer.parseInt(parts[1]);
        return a == 127 || a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
    }

}
