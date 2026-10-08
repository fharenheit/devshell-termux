package com.termux.app.terminal.fonts;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.termux.R;
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.TermuxConstants;

/**
 * A dialog to set the saved font size of the terminal, with a sample of the text at that size.
 * Unlike the A+ and A− extra keys and pinch zoom, which only change the size on the screen until
 * the app is opened again, the size set here is kept.
 */
public final class TerminalFontSizeDialog {

    /** Sizes change by this many pixels, like with pinch zoom. */
    private static final int STEP = 2;

    private TerminalFontSizeDialog() {}

    /**
     * Show the dialog.
     *
     * @param onSaved Called on the main thread after a size was saved.
     */
    public static void show(@NonNull Activity activity, @Nullable Runnable onSaved) {
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(activity, false);
        if (preferences == null) return;
        int[] sizes = TermuxAppSharedPreferences.getDefaultFontSizes(activity);
        final int defaultSize = sizes[0];
        final int min = sizes[1];
        // The saved maximum is very large, so the slider stops earlier.
        final int max = Math.min(sizes[2], defaultSize * 4);
        final int[] size = {Math.max(min, Math.min(max, preferences.getFontSize()))};

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(activity, 20);
        layout.setPadding(padding, dp(activity, 8), padding, 0);

        TextView sample = new TextView(activity);
        sample.setText("~$ ls -la  한글 日本語\n{ [ ( 0O 1lI ) ] }");
        sample.setTypeface(getTerminalTypeface(activity));
        sample.setTextColor(Color.WHITE);
        sample.setBackgroundColor(Color.BLACK);
        sample.setSingleLine(false);
        sample.setHorizontallyScrolling(true);
        sample.setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8));
        layout.addView(sample, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Button smaller = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        smaller.setText("A−");
        smaller.setAllCaps(false);
        TextView value = new TextView(activity);
        value.setGravity(Gravity.CENTER);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        Button larger = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        larger.setText("A+");
        larger.setAllCaps(false);
        row.addView(smaller);
        row.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(larger);
        layout.addView(row);

        SeekBar seekBar = new SeekBar(activity);
        seekBar.setMax((max - min) / STEP);
        layout.addView(seekBar);

        Runnable update = () -> {
            sample.setTextSize(TypedValue.COMPLEX_UNIT_PX, size[0]);
            value.setText(formatSize(activity, size[0], defaultSize));
            seekBar.setProgress((size[0] - min) / STEP);
            smaller.setEnabled(size[0] > min);
            larger.setEnabled(size[0] < max);
        };
        smaller.setOnClickListener(v -> {
            size[0] = Math.max(min, size[0] - STEP);
            update.run();
        });
        larger.setOnClickListener(v -> {
            size[0] = Math.min(max, size[0] + STEP);
            update.run();
        });
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                size[0] = min + progress * STEP;
                update.run();
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        update.run();

        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(R.string.terminal_font_size_title)
            .setView(layout)
            .setPositiveButton(R.string.action_terminal_font_size_save, (d, which) -> {
                preferences.setFontSize(size[0]);
                if (onSaved != null) onSaved.run();
            })
            .setNeutralButton(R.string.action_terminal_font_size_default, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.show();
        // Going back to the default size does not close the dialog, so that it can be saved or not.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            size[0] = Math.max(min, Math.min(max, defaultSize));
            update.run();
        });
    }

    /** A size in pixels, with its size in dp, like `24 px (12 dp)`, and if it is the default. */
    @NonNull
    static String formatSize(@NonNull Activity activity, int size, int defaultSize) {
        float density = activity.getResources().getDisplayMetrics().density;
        String text = activity.getString(R.string.terminal_font_size_value, size, Math.round(size / density));
        return size == defaultSize ? text + " " + activity.getString(R.string.terminal_font_default_suffix) : text;
    }

    /**
     * Set up a settings preference that shows the dialog when clicked and the saved size as its
     * summary.
     */
    public static void setupPreference(@Nullable Preference preference, @NonNull Activity activity) {
        if (preference == null) return;
        updatePreferenceSummary(preference, activity);
        preference.setOnPreferenceClickListener(p -> {
            show(activity, () -> updatePreferenceSummary(preference, activity));
            return true;
        });
    }

    private static void updatePreferenceSummary(@NonNull Preference preference, @NonNull Activity activity) {
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(activity, false);
        if (preferences == null) return;
        preference.setSummary(formatSize(activity, preferences.getFontSize(), TermuxAppSharedPreferences.getDefaultFontSizes(activity)[0]));
    }

    @NonNull
    private static Typeface getTerminalTypeface(@NonNull Activity activity) {
        try {
            if (TermuxConstants.TERMUX_FONT_FILE.isFile()) return Typeface.createFromFile(TermuxConstants.TERMUX_FONT_FILE);
        } catch (RuntimeException ignored) {
        }
        Typeface typeface = TerminalFonts.createTypeface(activity, TerminalFonts.DEFAULT_FONT);
        return typeface != null ? typeface : Typeface.MONOSPACE;
    }

    private static int dp(@NonNull Activity activity, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, activity.getResources().getDisplayMetrics()));
    }

}
