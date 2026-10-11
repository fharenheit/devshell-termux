package com.termux.shared.fork;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * The name and links of this app, DevShell, a fork of Termux. The Termux links stay for what
 * still comes from Termux, like the packages.
 */
public final class DevShellInfo {

    public static final String NAME = "DevShell";
    public static final String REPO_URL = "https://github.com/fharenheit/termux-app";
    public static final String RELEASES_URL = REPO_URL + "/releases";
    public static final String ISSUES_URL = REPO_URL + "/issues";
    public static final String MANUAL_URL = REPO_URL + "/blob/master/custom/docs/manual";
    public static final String LICENSE_URL = REPO_URL + "/blob/master/LICENSE.md";
    public static final String TERMUX_URL = "https://github.com/termux/termux-app";
    public static final String TERMUX_PACKAGES_ISSUES_URL = "https://github.com/termux/termux-packages/issues";

    private DevShellInfo() {}

    /** The user manual in the language of the app, Korean or else English. */
    @NonNull
    public static String getManualUrl(@NonNull Context context) {
        Locale locale = context.getResources().getConfiguration().locale;
        String language = locale != null && "ko".equals(locale.getLanguage()) ? "ko" : "en";
        return MANUAL_URL + "/" + language + "/README.md";
    }

    /** A markdown section about this app and where to report its issues, for the about and issue reports. */
    @NonNull
    public static String getMarkdownString(@NonNull Context context) {
        return "## " + NAME + "\n\n" +
            NAME + " is based on [Termux](" + TERMUX_URL + "), with input for languages composed by the keyboard, " +
            "SSH management, a file browser, snippets and more. Its packages come from the Termux repositories.\n\n" +
            "### Links\n\n" +
            "[Repository](" + REPO_URL + ")  \n" +
            "[Releases](" + RELEASES_URL + ")  \n" +
            "[Manual](" + getManualUrl(context) + ")  \n" +
            "[Report an issue of " + NAME + "](" + ISSUES_URL + ")  \n" +
            "[Report an issue of a package](" + TERMUX_PACKAGES_ISSUES_URL + ")  \n" +
            "[License: GPLv3](" + LICENSE_URL + ")\n";
    }

}
