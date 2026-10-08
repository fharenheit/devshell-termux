package com.termux.app.terminal.fonts;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckedTextView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.termux.R;
import com.termux.app.terminal.fonts.TerminalFonts.Font;
import com.termux.app.terminal.fonts.TerminalFonts.Script;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The dialog to choose a terminal font from {@link TerminalFonts#FONTS}. The selected font is
 * previewed at the top the way the terminal draws it, see {@link TerminalFontPreviewView}, and
 * each font shows the scripts it has and its download size if it must be downloaded first.
 * Used from the terminal tab bar menu and the Terminal View settings.
 */
public final class TerminalFontDialog {

    private static final String LOG_TAG = "TerminalFontDialog";

    private TerminalFontDialog() {}

    /**
     * Show the dialog.
     *
     * @param onApplied Called on the main thread after a font was applied successfully.
     */
    public static void show(@NonNull Activity activity, @Nullable Runnable onApplied) {
        show(activity, onApplied, null);
    }

    /**
     * Show the dialog.
     *
     * @param onApplied Called on the main thread after a font was applied successfully.
     * @param onSizeSaved Called on the main thread after a font size was saved with the Size button.
     */
    public static void show(@NonNull Activity activity, @Nullable Runnable onApplied, @Nullable Runnable onSizeSaved) {
        final Font current = TerminalFonts.getCurrentFont(activity);
        final boolean hasCustomFont = current == null;

        // A custom font.ttf is shown first and selected, but can't be chosen again once replaced.
        final List<Font> items = new ArrayList<>();
        if (hasCustomFont) items.add(null);
        items.addAll(TerminalFonts.FONTS);
        final int[] selected = {hasCustomFont ? 0 : TerminalFonts.FONTS.indexOf(current)};

        // The title with the preview below it, so that the list scrolls under them.
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText(R.string.terminal_font_title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        int padding = dp(activity, 20);
        title.setPadding(padding, padding, padding, dp(activity, 8));
        header.addView(title);
        final TerminalFontPreviewView preview = new TerminalFontPreviewView(activity);
        header.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final PreviewLoader previewLoader = new PreviewLoader(activity, preview);
        final FontAdapter adapter = new FontAdapter(activity, items);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
            .setCustomTitle(header)
            .setSingleChoiceItems(adapter, selected[0], (d, which) -> {
                selected[0] = which;
                adapter.notifyDataSetChanged();
                previewLoader.load(items.get(which));
            })
            .setPositiveButton(R.string.action_terminal_font_apply, (d, which) -> {
                Font font = items.get(selected[0]);
                if (font == null || font == current) return;
                applyFont(activity, font, onApplied);
            })
            .setNeutralButton(R.string.action_terminal_font_size, (d, which) -> TerminalFontSizeDialog.show(activity, onSizeSaved))
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnDismissListener(d -> previewLoader.release());
        dialog.show();
        previewLoader.load(items.get(selected[0]));
    }

    /**
     * Apply a font, downloading it first with a progress dialog if needed, and report the result
     * with a toast.
     *
     * @param onApplied Called on the main thread after the font was applied successfully.
     */
    public static void applyFont(@NonNull Activity activity, @NonNull Font font, @Nullable Runnable onApplied) {
        AlertDialog progressDialog = null;
        ProgressBar progressBar = null;
        TextView progressText = null;
        final AtomicBoolean[] cancelled = new AtomicBoolean[1];
        if (!font.isAvailable()) {
            LinearLayout layout = new LinearLayout(activity);
            layout.setOrientation(LinearLayout.VERTICAL);
            int padding = dp(activity, 20);
            layout.setPadding(padding, dp(activity, 8), padding, 0);
            progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            progressBar.setMax(1000);
            layout.addView(progressBar);
            progressText = new TextView(activity);
            progressText.setText(formatSize(0) + " / " + formatSize(font.size));
            layout.addView(progressText);
            progressDialog = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.msg_terminal_font_downloading, font.name))
                .setView(layout)
                .setNegativeButton(android.R.string.cancel, (d, which) -> {
                    if (cancelled[0] != null) cancelled[0].set(true);
                })
                .setCancelable(false)
                .show();
        } else {
            Toast.makeText(activity, activity.getString(R.string.msg_terminal_font_applying, font.name), Toast.LENGTH_SHORT).show();
        }

        final AlertDialog finalProgressDialog = progressDialog;
        final ProgressBar finalProgressBar = progressBar;
        final TextView finalProgressText = progressText;
        cancelled[0] = TerminalFonts.apply(activity, font, new TerminalFonts.ApplyCallback() {
            @Override
            public void onDownloadProgress(long downloaded, long total) {
                if (finalProgressBar == null) return;
                finalProgressBar.setProgress((int) (downloaded * 1000 / Math.max(1, total)));
                finalProgressText.setText(formatSize(downloaded) + " / " + formatSize(total));
            }

            @Override
            public void onFontApplied(@Nullable String error, @Nullable java.io.File backupFile) {
                if (finalProgressDialog != null && finalProgressDialog.isShowing()) finalProgressDialog.dismiss();
                if (error != null) {
                    Toast.makeText(activity, activity.getString(R.string.msg_terminal_font_failed, font.name, error), Toast.LENGTH_LONG).show();
                    return;
                }
                String message = activity.getString(R.string.msg_terminal_font_applied, font.name);
                if (backupFile != null)
                    message += "\n" + activity.getString(R.string.msg_terminal_font_backed_up, backupFile.getAbsolutePath());
                Toast.makeText(activity, message, backupFile != null ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
                if (onApplied != null) onApplied.run();
            }
        });
    }

    /**
     * Set up a settings preference that shows the dialog when clicked and the current font as its
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
        Font current = TerminalFonts.getCurrentFont(activity);
        preference.setSummary(current != null ? current.name : activity.getString(R.string.terminal_font_custom));
    }

    @NonNull
    static String formatSize(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static int dp(@NonNull Activity activity, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, activity.getResources().getDisplayMetrics()));
    }

    /**
     * Loads the typeface of the selected font in the background, since some are large, and shows
     * it in the preview. Only one is loaded at a time, and a load that is overtaken by a newer
     * selection is dropped.
     */
    private static class PreviewLoader {
        private final Activity mActivity;
        private final TerminalFontPreviewView mPreview;
        private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
        private final Handler mHandler = new Handler(Looper.getMainLooper());
        private int mGeneration;
        private boolean mReleased;

        PreviewLoader(@NonNull Activity activity, @NonNull TerminalFontPreviewView preview) {
            mActivity = activity;
            mPreview = preview;
        }

        /** Load a font, or the custom `font.ttf` for {@code null}. */
        void load(@Nullable Font font) {
            final int generation = ++mGeneration;
            if (font != null && !font.isAvailable()) {
                mPreview.setMessage(mActivity.getString(R.string.msg_terminal_font_not_downloaded, formatSize(font.size)));
                return;
            }
            mPreview.setMessage(mActivity.getString(R.string.msg_terminal_font_loading));
            mExecutor.execute(() -> {
                Typeface typeface;
                try {
                    typeface = font != null ? TerminalFonts.createTypeface(mActivity, font)
                        : Typeface.createFromFile(TermuxConstants.TERMUX_FONT_FILE);
                } catch (RuntimeException e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load the font for its preview", e);
                    typeface = null;
                }
                final Typeface loaded = typeface;
                mHandler.post(() -> {
                    if (mReleased || generation != mGeneration) return;
                    if (loaded != null) mPreview.setTypeface(loaded);
                    else mPreview.setMessage(mActivity.getString(R.string.msg_terminal_font_preview_failed));
                });
            });
        }

        void release() {
            mReleased = true;
            mExecutor.shutdownNow();
        }
    }

    /** The list items: a single choice name and below it the scripts of the font and its download size. */
    private static class FontAdapter extends ArrayAdapter<Font> {

        private final Activity mActivity;

        FontAdapter(@NonNull Activity activity, @NonNull List<Font> items) {
            super(activity, android.R.layout.simple_list_item_single_choice, items);
            mActivity = activity;
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            LinearLayout row;
            CheckedTextView nameView;
            TextView detailsView;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                nameView = (CheckedTextView) row.getChildAt(0);
                detailsView = (TextView) row.getChildAt(1);
            } else {
                row = new LinearLayout(mActivity);
                row.setOrientation(LinearLayout.VERTICAL);
                // Not super.getView(), which would call toString() on the null item of a custom font.
                nameView = (CheckedTextView) LayoutInflater.from(mActivity).inflate(android.R.layout.simple_list_item_single_choice, parent, false);
                detailsView = new TextView(mActivity);
                detailsView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                detailsView.setSingleLine();
                int padding = nameView.getPaddingStart();
                detailsView.setPadding(padding, 0, padding, dp(mActivity, 8));
                row.addView(nameView);
                row.addView(detailsView);
            }

            Font font = getItem(position);
            if (font == null) {
                nameView.setText(R.string.terminal_font_custom);
                detailsView.setText(TermuxConstants.TERMUX_FONT_FILE.getAbsolutePath());
            } else {
                String name = font.name;
                if (font == TerminalFonts.DEFAULT_FONT) name += " " + mActivity.getString(R.string.terminal_font_default_suffix);
                nameView.setText(name);
                detailsView.setText(getDetails(font));
            }

            // The ListView of the dialog sets the checked state on the row, so pass it to the name.
            nameView.setChecked(parent instanceof ListView && ((ListView) parent).isItemChecked(position));
            return row;
        }

        /** Like `EN 한 日 Кир · ↓ 25.5 MB`: English, the other scripts it has, and the download size if not downloaded. */
        @NonNull
        private String getDetails(@NonNull Font font) {
            StringBuilder details = new StringBuilder("EN");
            for (Script script : Script.values())
                if (font.scripts.contains(script)) details.append(' ').append(script.tag);
            if (font.isDownloadable()) {
                details.append(" · ");
                if (font.isAvailable()) details.append(mActivity.getString(R.string.terminal_font_downloaded));
                else details.append("↓ ").append(formatSize(font.size));
            }
            return details.toString();
        }
    }

}
