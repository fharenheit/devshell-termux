package com.termux.app.terminal.fonts;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The terminal fonts bundled in the app assets, which can be chosen from a list.
 *
 * Choosing a font copies it to {@link TermuxConstants#TERMUX_FONT_FILE} (`~/.termux/font.ttf`),
 * which the terminal already uses if it exists, like the Termux:Styling app does. Choosing the
 * default font removes that file, since the default font is used if it does not exist. A
 * `font.ttf` that is not one of the bundled fonts, like one set with Termux:Styling, is backed up
 * before it is replaced.
 *
 * Which bundled font `font.ttf` is a copy of is recorded with the size and modification time of
 * the copy, so that the current font is known without comparing the file contents.
 */
public final class TerminalFonts {

    public static final class Font {
        /** The id stored when the font is chosen. */
        @NonNull public final String id;
        @NonNull public final String name;
        /** The path in the app assets. */
        @NonNull public final String assetPath;
        /** Whether it has Hangul, otherwise Hangul is drawn with a fallback font that may not fit the cells. */
        public final boolean hasHangul;

        Font(@NonNull String id, @NonNull String name, @NonNull String assetPath, boolean hasHangul) {
            this.id = id;
            this.name = name;
            this.assetPath = assetPath;
            this.hasHangul = hasHangul;
        }
    }

    /** The default font, used when `font.ttf` does not exist. */
    public static final Font DEFAULT_FONT = new Font("d2coding", "D2Coding ligature", "fonts/D2Codingligature-Regular.ttf", true);

    public static final List<Font> FONTS = Collections.unmodifiableList(Arrays.asList(
        DEFAULT_FONT,
        new Font("nanum-gothic-coding", "NanumGothicCoding", "fonts/NanumGothicCoding-Regular.ttf", true),
        new Font("sarasa-mono-k", "Sarasa Mono K", "fonts/SarasaMonoK-Regular.ttf", true),
        new Font("noto-sans-mono-cjk-kr", "Noto Sans Mono CJK KR", "fonts/NotoSansMonoCJKkr-Regular.otf", true),
        new Font("jetbrains-mono", "JetBrains Mono", "fonts/JetBrainsMono-Regular.ttf", false),
        new Font("fira-code", "Fira Code", "fonts/FiraCode-Regular.ttf", false),
        new Font("hack", "Hack", "fonts/Hack-Regular.ttf", false),
        new Font("source-code-pro", "Source Code Pro", "fonts/SourceCodePro-Regular.ttf", false)
    ));

    private static final String PREFERENCES_NAME = "terminal_fonts";
    private static final String KEY_FONT_ID = "font_id";
    private static final String KEY_FONT_FILE_SIZE = "font_file_size";
    private static final String KEY_FONT_FILE_MODIFIED = "font_file_modified";

    private static final String LOG_TAG = "TerminalFonts";

    /** Whether a font was applied since {@link #consumeFontChanged()} was last called. */
    private static volatile boolean sFontChanged;

    private TerminalFonts() {}

    public interface ApplyCallback {
        /**
         * Called on the main thread when applying a font finished.
         *
         * @param error The error message if it failed, otherwise {@code null}.
         * @param backupFile The file the previous custom `font.ttf` was moved to, if any.
         */
        void onFontApplied(@Nullable String error, @Nullable File backupFile);
    }

    @Nullable
    public static Font getFont(@Nullable String id) {
        for (Font font : FONTS)
            if (font.id.equals(id)) return font;
        return null;
    }

    /**
     * Get the bundled font the terminal uses, or {@code null} if it uses a custom `font.ttf`
     * that is not one of the bundled fonts.
     */
    @Nullable
    public static Font getCurrentFont(@NonNull Context context) {
        File fontFile = TermuxConstants.TERMUX_FONT_FILE;
        if (!fontFile.exists() || fontFile.length() == 0) return DEFAULT_FONT;
        return isBundledFontCopy(context, fontFile) ? getFont(getPreferences(context).getString(KEY_FONT_ID, null)) : null;
    }

    /** Whether `font.ttf` is a custom font that is not one of the bundled fonts. */
    public static boolean hasCustomFontFile(@NonNull Context context) {
        File fontFile = TermuxConstants.TERMUX_FONT_FILE;
        return fontFile.exists() && fontFile.length() > 0 && !isBundledFontCopy(context, fontFile);
    }

    private static boolean isBundledFontCopy(@NonNull Context context, @NonNull File fontFile) {
        SharedPreferences preferences = getPreferences(context);
        return getFont(preferences.getString(KEY_FONT_ID, null)) != null &&
            preferences.getLong(KEY_FONT_FILE_SIZE, -1) == fontFile.length() &&
            preferences.getLong(KEY_FONT_FILE_MODIFIED, -1) == fontFile.lastModified();
    }

    private static SharedPreferences getPreferences(@NonNull Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Whether a font was applied since the last call, so that the terminal should load the font
     * again, like when it was chosen in the settings while the terminal was not visible.
     */
    public static boolean consumeFontChanged() {
        boolean changed = sFontChanged;
        sFontChanged = false;
        return changed;
    }

    /** Apply a font in the background, copying it to `font.ttf`, and call the callback on the main thread. */
    public static void apply(@NonNull Context context, @NonNull Font font, @NonNull ApplyCallback callback) {
        final Context appContext = context.getApplicationContext();
        final Handler handler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String error = null;
            File backupFile = null;
            try {
                backupFile = applySync(appContext, font);
            } catch (IOException | SecurityException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to apply the \"" + font.name + "\" font", e);
                error = e.getMessage();
            }
            sFontChanged = true;
            final String finalError = error;
            final File finalBackupFile = backupFile;
            handler.post(() -> callback.onFontApplied(finalError, finalBackupFile));
        }, "TerminalFonts").start();
    }

    /** Apply a font, returning the file a custom `font.ttf` was backed up to, if any. */
    @Nullable
    private static synchronized File applySync(@NonNull Context context, @NonNull Font font) throws IOException {
        File fontFile = TermuxConstants.TERMUX_FONT_FILE;
        File dir = fontFile.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs())
            throw new IOException("Failed to create the \"" + dir.getAbsolutePath() + "\" directory");

        File backupFile = null;
        if (hasCustomFontFile(context)) {
            backupFile = getBackupFile(fontFile);
            if (!fontFile.renameTo(backupFile))
                throw new IOException("Failed to back up \"" + fontFile.getAbsolutePath() + "\" to \"" + backupFile.getAbsolutePath() + "\"");
            Logger.logInfo(LOG_TAG, "Backed up the custom font to \"" + backupFile.getAbsolutePath() + "\"");
        }

        SharedPreferences.Editor editor = getPreferences(context).edit();
        if (font == DEFAULT_FONT) {
            // The default font is used when font.ttf does not exist.
            if (fontFile.exists() && !fontFile.delete())
                throw new IOException("Failed to delete \"" + fontFile.getAbsolutePath() + "\"");
            editor.remove(KEY_FONT_ID).remove(KEY_FONT_FILE_SIZE).remove(KEY_FONT_FILE_MODIFIED).apply();
            return backupFile;
        }

        // Copy to a temporary file first so that font.ttf is never left partially written.
        File tempFile = new File(dir, fontFile.getName() + ".tmp");
        try (InputStream in = context.getAssets().open(font.assetPath);
             OutputStream out = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1)
                out.write(buffer, 0, read);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
            throw e;
        }
        if (!tempFile.renameTo(fontFile)) {
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
            throw new IOException("Failed to move \"" + tempFile.getAbsolutePath() + "\" to \"" + fontFile.getAbsolutePath() + "\"");
        }

        editor.putString(KEY_FONT_ID, font.id)
            .putLong(KEY_FONT_FILE_SIZE, fontFile.length())
            .putLong(KEY_FONT_FILE_MODIFIED, fontFile.lastModified())
            .apply();
        return backupFile;
    }

    /** `font.ttf.bak`, or `font.ttf.<n>.bak` if it exists. */
    @NonNull
    private static File getBackupFile(@NonNull File fontFile) {
        File backupFile = new File(fontFile.getParentFile(), fontFile.getName() + ".bak");
        for (int i = 1; backupFile.exists(); i++)
            backupFile = new File(fontFile.getParentFile(), fontFile.getName() + "." + i + ".bak");
        return backupFile;
    }

}
