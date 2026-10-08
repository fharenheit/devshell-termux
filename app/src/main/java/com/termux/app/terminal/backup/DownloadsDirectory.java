package com.termux.app.terminal.backup;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Writes new files to the shared Download directory. */
public final class DownloadsDirectory {

    private DownloadsDirectory() {}

    /** Whether writing needs the storage permission, which it does before Android 10. */
    public static boolean needsStoragePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q;
    }

    /** A new file in the Download directory being written. */
    public static final class NewFile {
        @NonNull public final OutputStream out;
        /** The path shown to the user, like `Download/name.txt`. */
        @NonNull public final String displayPath;
        private final Runnable mDelete;

        NewFile(@NonNull OutputStream out, @NonNull String displayPath, @NonNull Runnable delete) {
            this.out = out;
            this.displayPath = displayPath;
            mDelete = delete;
        }

        /** Close and delete the file, like when writing it failed or was cancelled. */
        public void delete() {
            try {
                out.close();
            } catch (IOException ignored) {
            }
            mDelete.run();
        }
    }

    /**
     * Create a new file in the Download directory. A file with the same name is not replaced:
     * MediaStore picks another name, and before Android 10 a number is added to the name.
     * The caller must close {@link NewFile#out}.
     */
    @NonNull
    public static NewFile createNewFile(@NonNull Context context, @NonNull String fileName, @NonNull String mimeType) throws IOException {
        return createNewFile(context, null, fileName, mimeType);
    }

    /**
     * Create a new file in a directory in the Download directory, like `Download/logs/a.txt` for
     * {@code subDirectory} `logs`, or in the Download directory itself if it is {@code null}.
     */
    @NonNull
    public static NewFile createNewFile(@NonNull Context context, @Nullable String subDirectory, @NonNull String fileName,
                                        @NonNull String mimeType) throws IOException {
        String relativePath = subDirectory == null || subDirectory.isEmpty() ? Environment.DIRECTORY_DOWNLOADS
            : Environment.DIRECTORY_DOWNLOADS + "/" + subDirectory;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // MediaStore needs no permission for files the app adds.
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
            values.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);
            ContentResolver resolver = context.getContentResolver();
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            OutputStream out = uri == null ? null : resolver.openOutputStream(uri);
            if (out == null) throw new IOException("Failed to create " + fileName + " in the Download directory");
            return new NewFile(out, relativePath + "/" + fileName, () -> resolver.delete(uri, null, null));
        }
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (subDirectory != null && !subDirectory.isEmpty()) dir = new File(dir, subDirectory);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Failed to create " + dir.getAbsolutePath());
        File file = new File(dir, fileName);
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        for (int i = 1; file.exists(); i++) file = new File(dir, base + " (" + i + ")" + extension);
        final File created = file;
        //noinspection ResultOfMethodCallIgnored
        return new NewFile(new FileOutputStream(file), relativePath + "/" + file.getName(), created::delete);
    }

}
