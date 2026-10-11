package com.termux.app.terminal.links;

import android.app.Activity;
import android.app.AlertDialog;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.data.UrlUtils;
import com.termux.shared.interact.ShareUtils;

import java.util.ArrayList;
import java.util.List;

/** Opening the URLs of the text selected in the terminal, from the "More" context menu. */
public final class TerminalLinks {

    /** What to do with a chosen URL, like opening it in the browser or in a web tab. */
    public interface UrlAction {
        void open(@NonNull String url);
    }

    private TerminalLinks() {}

    /** The URLs in a text, in order and without duplicates. */
    @NonNull
    public static List<String> findUrls(@Nullable String text) {
        List<String> urls = new ArrayList<>();
        if (text == null || text.isEmpty()) return urls;
        for (CharSequence match : UrlUtils.extractUrls(text)) {
            String url = trimUrl(match.toString());
            if (!url.isEmpty() && !urls.contains(url)) urls.add(url);
        }
        return urls;
    }

    /**
     * Remove punctuation that ends the sentence around a URL rather than the URL, like the `.` of
     * `see https://example.com.` or the `)` of `(https://example.com)`.
     */
    @NonNull
    static String trimUrl(@NonNull String url) {
        int end = url.length();
        while (end > 0) {
            char c = url.charAt(end - 1);
            if (".,;:!?'\"".indexOf(c) >= 0) {
                end--;
            } else if (c == ')' && count(url, end, '(') < count(url, end, ')')) {
                end--;
            } else {
                break;
            }
        }
        return url.substring(0, end);
    }

    private static int count(String text, int end, char c) {
        int count = 0;
        for (int i = 0; i < end; i++) if (text.charAt(i) == c) count++;
        return count;
    }

    /** Open a URL in the external browser, or let the user choose one of several first. */
    public static void open(@NonNull Activity activity, @NonNull List<String> urls) {
        choose(activity, urls, R.string.action_open_url, url -> ShareUtils.openURL(activity, url));
    }

    /** Open a URL in a web tab, or let the user choose one of several first. */
    public static void openInTab(@NonNull Activity activity, @NonNull List<String> urls,
                                 @NonNull UrlAction action) {
        choose(activity, urls, R.string.action_open_url_tab, action);
    }

    /** Run {@code action} on the single URL, or let the user choose one of several first. */
    private static void choose(@NonNull Activity activity, @NonNull List<String> urls,
                               int titleRes, @NonNull UrlAction action) {
        if (urls.isEmpty()) return;
        if (urls.size() == 1) {
            action.open(urls.get(0));
            return;
        }
        String[] items = urls.toArray(new String[0]);
        new AlertDialog.Builder(activity)
            .setTitle(titleRes)
            .setItems(items, (d, which) -> action.open(items[which]))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

}
