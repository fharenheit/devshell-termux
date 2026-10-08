package com.termux.app.language;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Resources;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.termux.R;
import com.termux.shared.locale.AppLocale;

/** Choosing the language of the app, from the tab bar settings menu or the settings. */
public final class LanguageDialog {

    private LanguageDialog() {}

    /**
     * Show the languages, and apply the chosen one: the activity is created again in it, and
     * {@code extraResources}, like the service's, are updated.
     */
    public static void show(@NonNull Activity activity, @Nullable Resources... extraResources) {
        String current = AppLocale.get(activity);
        String[] languages = new String[AppLocale.LANGUAGES.length + 1];
        CharSequence[] names = new CharSequence[languages.length];
        languages[0] = AppLocale.SYSTEM;
        names[0] = activity.getString(R.string.language_system_default);
        int checked = 0;
        for (int i = 0; i < AppLocale.LANGUAGES.length; i++) {
            languages[i + 1] = AppLocale.LANGUAGES[i];
            names[i + 1] = AppLocale.getDisplayName(AppLocale.LANGUAGES[i]);
            if (AppLocale.LANGUAGES[i].equals(current)) checked = i + 1;
        }
        new AlertDialog.Builder(activity)
            .setTitle(R.string.title_language)
            .setSingleChoiceItems(names, checked, (d, which) -> {
                d.dismiss();
                if (languages[which].equals(current)) return;
                AppLocale.set(activity, languages[which]);
                AppLocale.update(activity.getApplicationContext().getResources(), activity);
                if (extraResources != null)
                    for (Resources resources : extraResources)
                        if (resources != null) AppLocale.update(resources, activity);
                activity.recreate();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Set up a settings preference that shows the dialog and the chosen language as its summary. */
    public static void setupPreference(@Nullable Preference preference, @NonNull Activity activity) {
        if (preference == null) return;
        String language = AppLocale.get(activity);
        preference.setSummary(language.isEmpty() ? activity.getString(R.string.language_system_default) : AppLocale.getDisplayName(language));
        preference.setOnPreferenceClickListener(p -> {
            show(activity);
            return true;
        });
    }

}
