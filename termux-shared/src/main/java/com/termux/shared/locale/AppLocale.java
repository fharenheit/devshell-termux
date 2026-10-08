package com.termux.shared.locale;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * The language of the app's user interface, chosen in the app instead of following the system.
 * Each activity, the service and the application wrap their base context with {@link #wrap} in
 * {@code attachBaseContext}, so that their resources are in the chosen language.
 */
public final class AppLocale {

    /** Follow the language of the system. */
    public static final String SYSTEM = "";
    /** The languages that can be chosen, as language tags, after {@link #SYSTEM}. */
    public static final String[] LANGUAGES = {"en", "ko", "ja", "zh"};

    private static final String PREFERENCES = "termux_app_language";
    private static final String KEY_LANGUAGE = "language";

    private AppLocale() {}

    private static SharedPreferences getPreferences(@NonNull Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    /** The chosen language tag, or {@link #SYSTEM}. */
    @NonNull
    public static String get(@NonNull Context context) {
        String language = getPreferences(context).getString(KEY_LANGUAGE, SYSTEM);
        return language == null ? SYSTEM : language;
    }

    /** Choose a language tag, or {@link #SYSTEM}. Activities show it when they are created again. */
    public static void set(@NonNull Context context, @NonNull String language) {
        getPreferences(context).edit().putString(KEY_LANGUAGE, language).commit();
    }

    /** The name of a language in itself, like `한국어`. */
    @NonNull
    public static String getDisplayName(@NonNull String language) {
        switch (language) {
            case "en": return "English";
            case "ko": return "한국어";
            case "ja": return "日本語";
            case "zh": return "中文 (简体)";
            default: return language;
        }
    }

    /** The locale to use: the chosen one, or the system's. */
    @NonNull
    private static Locale getLocale(@NonNull Context context) {
        String language = get(context);
        if (!language.isEmpty()) return Locale.forLanguageTag(language);
        return Resources.getSystem().getConfiguration().getLocales().get(0);
    }

    /** A context with the chosen language, for {@code attachBaseContext}. */
    @NonNull
    public static Context wrap(@NonNull Context base) {
        if (get(base).isEmpty()) return base;
        return base.createConfigurationContext(getConfiguration(base.getResources().getConfiguration(), getLocale(base)));
    }

    @NonNull
    private static Configuration getConfiguration(@NonNull Configuration current, @NonNull Locale locale) {
        Configuration configuration = new Configuration(current);
        configuration.setLocale(locale);
        configuration.setLocales(new LocaleList(locale));
        return configuration;
    }

    /**
     * Update resources that live longer than activities, like those of the application and the
     * service, to the chosen language, so that their text changes without restarting the app.
     */
    @SuppressWarnings("deprecation")
    public static void update(@NonNull Resources resources, @NonNull Context context) {
        resources.updateConfiguration(getConfiguration(resources.getConfiguration(), getLocale(context)), resources.getDisplayMetrics());
    }

    /** Whether an activity shows another language than the chosen one, so it should be created again. */
    public static boolean isOutdated(@NonNull Activity activity) {
        Locale shown = activity.getResources().getConfiguration().getLocales().get(0);
        return !shown.getLanguage().equals(getLocale(activity).getLanguage());
    }

    private static long sLastRecreateTime;

    /**
     * Create an activity again if the language was changed while it was in the background, and
     * return whether it is. Not more than once in a few seconds, so that it can't loop if the
     * language could not be applied.
     */
    public static boolean recreateIfOutdated(@NonNull Activity activity) {
        if (!isOutdated(activity)) return false;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - sLastRecreateTime < 5000) return false;
        sLastRecreateTime = now;
        activity.recreate();
        return true;
    }

}
