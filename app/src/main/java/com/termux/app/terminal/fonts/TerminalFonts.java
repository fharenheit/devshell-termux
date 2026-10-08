package com.termux.app.terminal.fonts;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
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
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The terminal fonts that can be chosen from a list: small fonts bundled in the app assets, and
 * large fonts downloaded when first chosen, to keep the APK small.
 *
 * Choosing a font copies it to {@link TermuxConstants#TERMUX_FONT_FILE} (`~/.termux/font.ttf`),
 * which the terminal already uses if it exists, like the Termux:Styling app does. Choosing the
 * default font removes that file, since the default font is used if it does not exist. A
 * `font.ttf` that is not one of these fonts, like one set with Termux:Styling, is backed up
 * before it is replaced.
 *
 * Downloaded fonts are kept in {@link #DOWNLOADED_FONTS_DIR} and are only used if their SHA-256
 * checksum matches the one here, so a changed or partial download is never used.
 *
 * Which font `font.ttf` is a copy of is recorded with the size and modification time of the copy,
 * so that the current font is known without comparing the file contents.
 */
public final class TerminalFonts {

    /** Where downloadable fonts are downloaded from: the `custom/fonts` directory of this fork. */
    static final String DOWNLOAD_BASE_URL = "https://raw.githubusercontent.com/fharenheit/termux-app/master/custom/fonts/";

    /** Where downloaded fonts are kept. */
    public static final File DOWNLOADED_FONTS_DIR = new File(TermuxConstants.TERMUX_DATA_HOME_DIR_PATH, "fonts");

    /** The scripts a font has glyphs for, shown as tags in the font list. */
    public enum Script {
        KOREAN("한"),
        JAPANESE("日"),
        CHINESE("中"),
        CYRILLIC("Кир"),
        ARABIC("ع"),
        HEBREW("ש"),
        THAI("ไทย"),
        NERD("Nerd");

        @NonNull public final String tag;

        Script(@NonNull String tag) {
            this.tag = tag;
        }
    }

    public static final class Font {
        /** The id stored when the font is chosen and in exported settings. */
        @NonNull public final String id;
        @NonNull public final String name;
        /** The path in the app assets for a bundled font, otherwise {@code null}. */
        @Nullable public final String assetPath;
        /** The file name to download for a downloadable font, otherwise {@code null}. */
        @Nullable public final String downloadFileName;
        /** The size of the font file. */
        public final long size;
        /** The SHA-256 checksum of a downloadable font file, as lowercase hex. */
        @Nullable public final String sha256;
        /** The scripts the font has glyphs for, checked with fontTools when the font was added. */
        @NonNull public final Set<Script> scripts;

        private Font(@NonNull String id, @NonNull String name, @Nullable String assetPath, @Nullable String downloadFileName,
                     long size, @Nullable String sha256, @NonNull Set<Script> scripts) {
            this.id = id;
            this.name = name;
            this.assetPath = assetPath;
            this.downloadFileName = downloadFileName;
            this.size = size;
            this.sha256 = sha256;
            this.scripts = Collections.unmodifiableSet(scripts);
        }

        static Font bundled(String id, String name, String assetPath, long size, Set<Script> scripts) {
            return new Font(id, name, assetPath, null, size, null, scripts);
        }

        static Font downloadable(String id, String name, String fileName, long size, String sha256, Set<Script> scripts) {
            return new Font(id, name, null, fileName, size, sha256, scripts);
        }

        public boolean isDownloadable() {
            return downloadFileName != null;
        }

        /** The file of a downloadable font in {@link #DOWNLOADED_FONTS_DIR}. */
        @Nullable
        public File getDownloadedFile() {
            return downloadFileName == null ? null : new File(DOWNLOADED_FONTS_DIR, downloadFileName);
        }

        /** Whether the font can be used now: it is bundled, or it was downloaded. */
        public boolean isAvailable() {
            if (!isDownloadable()) return true;
            File file = getDownloadedFile();
            // The checksum was verified when it was downloaded.
            return file != null && file.isFile() && file.length() == size;
        }
    }

    /** The default font, used when `font.ttf` does not exist. */
    public static final Font DEFAULT_FONT = Font.bundled("d2coding", "D2Coding ligature", "fonts/D2Codingligature-Regular.ttf", 4136352,
        EnumSet.of(Script.KOREAN, Script.JAPANESE, Script.CYRILLIC));

    public static final List<Font> FONTS = Collections.unmodifiableList(Arrays.asList(
        DEFAULT_FONT,
        Font.downloadable("d2coding-nerd-font", "D2Coding Nerd Font", "D2CodingLigatureNerdFontMono-Regular.ttf", 6450104,
            "0429ed7b44056de04adc073e11f2a6dde9507cd691fce8c851f8cec9a1065893",
            EnumSet.of(Script.KOREAN, Script.JAPANESE, Script.CYRILLIC, Script.NERD)),
        Font.downloadable("nanum-gothic-coding", "NanumGothicCoding", "NanumGothicCoding-Regular.ttf", 2315924,
            "787effd7efed2abca88ade231faa8191f4e9fcf85b1805a13ee1dc3724b72089",
            EnumSet.of(Script.KOREAN, Script.CYRILLIC)),
        Font.downloadable("sarasa-mono-k", "Sarasa Mono K", "SarasaMonoK-Regular.ttf", 25524620,
            "756bd56d3bf14a0a0bde5b7846d9275e1f213d52bc68230a30bc2abfb6820fe8",
            EnumSet.of(Script.KOREAN, Script.JAPANESE, Script.CHINESE, Script.CYRILLIC)),
        Font.downloadable("noto-sans-mono-cjk-kr", "Noto Sans Mono CJK KR", "NotoSansMonoCJKkr-Regular.otf", 16389532,
            "d5afed9988a28ae96afb0f4791754d3c9f4f08d08477eb1a7d6e2d905679a472",
            EnumSet.of(Script.KOREAN, Script.JAPANESE, Script.CHINESE, Script.CYRILLIC)),
        Font.bundled("cascadia-mono", "Cascadia Mono", "fonts/CascadiaMono-Regular.ttf", 714804,
            EnumSet.of(Script.CYRILLIC, Script.ARABIC, Script.HEBREW)),
        Font.bundled("dejavu-sans-mono", "DejaVu Sans Mono", "fonts/DejaVuSansMono.ttf", 340712,
            EnumSet.of(Script.CYRILLIC, Script.ARABIC)),
        Font.bundled("jetbrains-mono", "JetBrains Mono", "fonts/JetBrainsMono-Regular.ttf", 273900, EnumSet.of(Script.CYRILLIC)),
        Font.bundled("fira-code", "Fira Code", "fonts/FiraCode-Regular.ttf", 289624, EnumSet.of(Script.CYRILLIC)),
        Font.bundled("hack", "Hack", "fonts/Hack-Regular.ttf", 309408, EnumSet.of(Script.CYRILLIC)),
        Font.bundled("source-code-pro", "Source Code Pro", "fonts/SourceCodePro-Regular.ttf", 210312, EnumSet.of(Script.CYRILLIC))
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
         * Called on the main thread with the download progress of a downloadable font.
         *
         * @param downloaded The bytes downloaded.
         * @param total The size of the font.
         */
        default void onDownloadProgress(long downloaded, long total) {}

        /**
         * Called on the main thread when applying a font finished.
         *
         * @param error The error message if it failed or was cancelled, otherwise {@code null}.
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
     * Get the font the terminal uses, or {@code null} if it uses a custom `font.ttf` that is not
     * one of {@link #FONTS}.
     */
    @Nullable
    public static Font getCurrentFont(@NonNull Context context) {
        File fontFile = TermuxConstants.TERMUX_FONT_FILE;
        if (!fontFile.exists() || fontFile.length() == 0) return DEFAULT_FONT;
        return isFontCopy(context, fontFile) ? getFont(getPreferences(context).getString(KEY_FONT_ID, null)) : null;
    }

    /** Whether `font.ttf` is a custom font that is not one of {@link #FONTS}. */
    public static boolean hasCustomFontFile(@NonNull Context context) {
        File fontFile = TermuxConstants.TERMUX_FONT_FILE;
        return fontFile.exists() && fontFile.length() > 0 && !isFontCopy(context, fontFile);
    }

    private static boolean isFontCopy(@NonNull Context context, @NonNull File fontFile) {
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

    /** Make the terminal load the font again when it is started, like after `font.ttf` was replaced. */
    public static void markFontChanged() {
        sFontChanged = true;
    }

    /** Create the typeface of an available font, or {@code null} if it is not available or fails to load. */
    @Nullable
    public static Typeface createTypeface(@NonNull Context context, @NonNull Font font) {
        try {
            if (font.assetPath != null) return Typeface.createFromAsset(context.getAssets(), font.assetPath);
            if (font.isAvailable()) return Typeface.createFromFile(font.getDownloadedFile());
        } catch (RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load the \"" + font.name + "\" font", e);
        }
        return null;
    }

    /**
     * Apply a font in the background, downloading it first if needed, and call the callback on the
     * main thread.
     *
     * @return Set to {@code true} to cancel downloading.
     */
    @NonNull
    public static AtomicBoolean apply(@NonNull Context context, @NonNull Font font, @NonNull ApplyCallback callback) {
        final Context appContext = context.getApplicationContext();
        final Handler handler = new Handler(Looper.getMainLooper());
        final AtomicBoolean cancelled = new AtomicBoolean();
        new Thread(() -> {
            String error = null;
            File backupFile = null;
            try {
                if (!font.isAvailable())
                    download(font, DOWNLOAD_BASE_URL, DOWNLOADED_FONTS_DIR, cancelled, (downloaded, total) -> handler.post(() -> callback.onDownloadProgress(downloaded, total)));
                backupFile = applySync(appContext, font);
                sFontChanged = true;
            } catch (IOException | SecurityException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to apply the \"" + font.name + "\" font", e);
                error = e.getMessage();
            }
            final String finalError = error;
            final File finalBackupFile = backupFile;
            handler.post(() -> callback.onFontApplied(finalError, finalBackupFile));
        }, "TerminalFonts").start();
        return cancelled;
    }

    interface ProgressListener {
        void onProgress(long downloaded, long total);
    }

    /**
     * Download a downloadable font from {@code baseUrl} to {@code dir}, normally
     * {@link #DOWNLOAD_BASE_URL} and {@link #DOWNLOADED_FONTS_DIR}, through a temporary file that
     * is only moved in place if its checksum matches.
     */
    static void download(@NonNull Font font, @NonNull String baseUrl, @NonNull File dir, @NonNull AtomicBoolean cancelled,
                         @Nullable ProgressListener listener) throws IOException {
        if (font.downloadFileName == null || font.sha256 == null) throw new IOException("\"" + font.name + "\" is not downloadable");
        File file = new File(dir, font.downloadFileName);
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IOException("Failed to create \"" + dir.getAbsolutePath() + "\"");

        File tempFile = new File(dir, file.getName() + ".download");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }

        String url = baseUrl + font.downloadFileName;
        Logger.logInfo(LOG_TAG, "Downloading \"" + url + "\"");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("Download failed with HTTP " + status + ": " + url);
            long downloaded = 0;
            long lastReported = 0;
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (cancelled.get()) throw new InterruptedIOException("Cancelled");
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    downloaded += read;
                    if (downloaded > font.size) throw new IOException("Downloaded more than the expected " + font.size + " bytes");
                    if (listener != null && (downloaded - lastReported >= 256 * 1024 || downloaded == font.size)) {
                        lastReported = downloaded;
                        listener.onProgress(downloaded, font.size);
                    }
                }
            }
            String sha256 = toHex(digest.digest());
            if (downloaded != font.size || !sha256.equals(font.sha256))
                throw new IOException("The downloaded \"" + font.name + "\" font does not match its checksum");
            if (!tempFile.renameTo(file)) throw new IOException("Failed to move \"" + tempFile.getAbsolutePath() + "\" in place");
        } finally {
            connection.disconnect();
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
        }
    }

    @NonNull
    static String toHex(@NonNull byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) hex.append(String.format(Locale.ROOT, "%02x", b));
        return hex.toString();
    }

    /** Apply an available font, returning the file a custom `font.ttf` was backed up to, if any. */
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
        try (InputStream in = font.assetPath != null ? context.getAssets().open(font.assetPath)
                : new java.io.FileInputStream(font.getDownloadedFile());
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
    static File getBackupFile(@NonNull File file) {
        File backupFile = new File(file.getParentFile(), file.getName() + ".bak");
        for (int i = 1; backupFile.exists(); i++)
            backupFile = new File(file.getParentFile(), file.getName() + "." + i + ".bak");
        return backupFile;
    }

}
