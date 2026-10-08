package com.termux.app.terminal.fonts;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckedTextView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.termux.R;
import com.termux.app.terminal.fonts.TerminalFonts.Font;
import com.termux.shared.logger.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The dialog to choose a terminal font from {@link TerminalFonts#FONTS}, showing each font with a
 * preview in that font. Used from the terminal tab bar menu and the Terminal View settings.
 */
public final class TerminalFontDialog {

    /**
     * The preview lines: Korean, Latin and code characters, and Japanese and Chinese, which are
     * drawn with a system fallback font if the font does not have them. Each line is a single
     * line view, so that a long line can't push the next one out of the preview.
     */
    private static final String[] PREVIEW_LINES = {
        "가나다 한글 abc ABC 0O 1lI {} -> != ==",
        "日本語 ひらがな カタカナ 中文 简体 繁體"
    };

    private static final String LOG_TAG = "TerminalFontDialog";

    private TerminalFontDialog() {}

    /**
     * Show the dialog.
     *
     * @param onApplied Called on the main thread after a font was applied successfully.
     */
    public static void show(@NonNull Activity activity, @Nullable Runnable onApplied) {
        final Font current = TerminalFonts.getCurrentFont(activity);
        final boolean hasCustomFont = current == null;

        final List<Object> items = new ArrayList<>();
        // A custom font.ttf is shown first and selected, but can't be chosen again once replaced.
        if (hasCustomFont) items.add(activity.getString(R.string.terminal_font_custom));
        items.addAll(TerminalFonts.FONTS);
        int checkedItem = hasCustomFont ? 0 : TerminalFonts.FONTS.indexOf(current);

        final FontAdapter adapter = new FontAdapter(activity, items);
        final AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(R.string.terminal_font_title)
            .setSingleChoiceItems(adapter, checkedItem, (d, which) -> {
                d.dismiss();
                Object item = items.get(which);
                if (!(item instanceof Font) || item == current) return;
                applyFont(activity, (Font) item, onApplied);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnDismissListener(d -> adapter.release());
        dialog.show();
    }

    /** Apply a font and report the result with a toast. */
    private static void applyFont(@NonNull Activity activity, @NonNull Font font, @Nullable Runnable onApplied) {
        Toast.makeText(activity, activity.getString(R.string.msg_terminal_font_applying, font.name), Toast.LENGTH_SHORT).show();
        TerminalFonts.apply(activity, font, (error, backupFile) -> {
            if (error != null) {
                Toast.makeText(activity, activity.getString(R.string.msg_terminal_font_failed, font.name, error), Toast.LENGTH_LONG).show();
                return;
            }
            String message = activity.getString(R.string.msg_terminal_font_applied, font.name);
            if (backupFile != null)
                message += "\n" + activity.getString(R.string.msg_terminal_font_backed_up, backupFile.getAbsolutePath());
            Toast.makeText(activity, message, backupFile != null ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            if (onApplied != null) onApplied.run();
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

    /**
     * The list items: the font name and a preview in that font. The fonts are loaded in the
     * background since some are large.
     */
    private static class FontAdapter extends ArrayAdapter<Object> {

        private final Activity mActivity;
        private final Map<Font, Typeface> mTypefaces = new HashMap<>();
        private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
        private final Handler mHandler = new Handler(Looper.getMainLooper());
        private boolean mReleased;

        FontAdapter(@NonNull Activity activity, @NonNull List<Object> items) {
            super(activity, android.R.layout.simple_list_item_single_choice, items);
            mActivity = activity;
            for (Object item : items)
                if (item instanceof Font) loadTypeface((Font) item);
        }

        private void loadTypeface(@NonNull Font font) {
            mExecutor.execute(() -> {
                Typeface typeface;
                try {
                    typeface = Typeface.createFromAsset(mActivity.getAssets(), font.assetPath);
                } catch (RuntimeException e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load the \"" + font.name + "\" font for its preview", e);
                    return;
                }
                mHandler.post(() -> {
                    if (mReleased) return;
                    mTypefaces.put(font, typeface);
                    notifyDataSetChanged();
                });
            });
        }

        /** Stop loading fonts and drop the loaded ones when the dialog is dismissed. */
        void release() {
            mReleased = true;
            mExecutor.shutdownNow();
            mTypefaces.clear();
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            // A single choice row with the name, and the preview lines below it for fonts.
            LinearLayout row;
            CheckedTextView nameView;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                nameView = (CheckedTextView) row.getChildAt(0);
            } else {
                row = new LinearLayout(mActivity);
                row.setOrientation(LinearLayout.VERTICAL);
                nameView = (CheckedTextView) super.getView(position, null, parent);
                row.addView(nameView);
                int padding = nameView.getPaddingStart();
                for (int i = 0; i < PREVIEW_LINES.length; i++) {
                    TextView lineView = new TextView(mActivity);
                    lineView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                    lineView.setSingleLine();
                    lineView.setEllipsize(TextUtils.TruncateAt.END);
                    lineView.setText(PREVIEW_LINES[i]);
                    boolean last = i == PREVIEW_LINES.length - 1;
                    lineView.setPadding(padding, 0, padding, last ? Math.round(dp(8)) : 0);
                    row.addView(lineView);
                }
            }

            Object item = getItem(position);
            if (item instanceof Font) {
                Font font = (Font) item;
                String name = font.name;
                if (font == TerminalFonts.DEFAULT_FONT) name += " " + mActivity.getString(R.string.terminal_font_default_suffix);
                if (!font.hasHangul) name += " " + mActivity.getString(R.string.terminal_font_english_only_suffix);
                nameView.setText(name);
                Typeface typeface = mTypefaces.get(font);
                setPreviewLines(row, typeface != null ? typeface : Typeface.MONOSPACE, View.VISIBLE);
            } else {
                nameView.setText(String.valueOf(item));
                setPreviewLines(row, null, View.GONE);
            }

            // The ListView of the dialog sets the checked state on the row, so pass it to the name.
            nameView.setChecked(parent instanceof android.widget.ListView &&
                ((android.widget.ListView) parent).isItemChecked(position));
            return row;
        }

        /** Set the typeface and visibility of the preview line views, which follow the name view. */
        private void setPreviewLines(@NonNull LinearLayout row, @Nullable Typeface typeface, int visibility) {
            for (int i = 1; i < row.getChildCount(); i++) {
                TextView lineView = (TextView) row.getChildAt(i);
                if (typeface != null) lineView.setTypeface(typeface);
                lineView.setVisibility(visibility);
            }
        }

        private float dp(float value) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, mActivity.getResources().getDisplayMetrics());
        }
    }

}
