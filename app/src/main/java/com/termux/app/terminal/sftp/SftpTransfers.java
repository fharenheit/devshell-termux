package com.termux.app.terminal.sftp;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.terminal.backup.DownloadsDirectory;
import com.termux.shared.logger.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The queue of SFTP downloads and uploads, which run one after another in the background with
 * their own connections, so that they go on after leaving the file browser. While transfers are
 * queued, {@link SftpTransferService} shows their progress in a notification and keeps the app
 * running.
 */
public final class SftpTransfers {

    public static final int QUEUED = 0;
    public static final int RUNNING = 1;
    public static final int DONE = 2;
    public static final int FAILED = 3;
    public static final int CANCELLED = 4;

    /** Finished transfers beyond this many are forgotten. */
    private static final int MAX_FINISHED = 50;
    /** Progress is reported at most this often. */
    private static final long PROGRESS_INTERVAL_MS = 300;

    private static final String LOG_TAG = "SftpTransfers";

    /** The work of a transfer. */
    interface Job {
        /** Run the transfer, and return a message to show when it is done, or null. */
        @Nullable
        String run(@NonNull Context context, @NonNull SftpClient client, @NonNull Transfer transfer) throws IOException;
    }

    public static final class Transfer {
        public final int id;
        @NonNull public final String hostName;
        @NonNull public final String title;
        /** The remote folder an upload changes, to refresh a browser showing it, or null for a download. */
        @Nullable public final String remoteDir;
        @Nullable final String controlPath;
        @NonNull final Job job;
        /** Document uris whose read permission is kept until the transfer ends. */
        @NonNull final List<Uri> grantedUris = new ArrayList<>();
        final AtomicBoolean cancelled = new AtomicBoolean();

        volatile int state = QUEUED;
        volatile long done;
        volatile long total;
        /** The message when done, or the error when failed. */
        @Nullable volatile String message;

        Transfer(int id, @NonNull String hostName, @Nullable String controlPath, @NonNull String title,
                 long total, @Nullable String remoteDir, @NonNull Job job) {
            this.id = id;
            this.hostName = hostName;
            this.controlPath = controlPath;
            this.title = title;
            this.total = total;
            this.remoteDir = remoteDir;
            this.job = job;
        }

        public int getState() {
            return state;
        }

        public long getDone() {
            return done;
        }

        public long getTotal() {
            return total;
        }

        @Nullable
        public String getMessage() {
            return message;
        }

        public boolean isFinished() {
            return state >= DONE;
        }

        /** The progress as done and total sizes, or the message of a finished transfer. */
        @NonNull
        public String describe(@NonNull Context context) {
            switch (state) {
                case QUEUED:
                    return context.getString(R.string.sftp_transfer_queued);
                case RUNNING:
                    return SftpText.formatSize(done) + (total > 0 ? " / " + SftpText.formatSize(total) : "");
                case DONE:
                    return message != null ? message : context.getString(R.string.sftp_transfer_done);
                case FAILED:
                    return context.getString(R.string.sftp_transfer_failed, message);
                default:
                    return context.getString(R.string.sftp_cancelled);
            }
        }
    }

    interface ClientFactory {
        @NonNull
        SftpClient getClient(@NonNull Transfer transfer) throws IOException;
    }

    /** Listens for changes of the transfers, on the main thread. */
    public interface Listener {
        /** A transfer was added, progressed, or was removed. */
        void onTransfersChanged();

        /** A transfer finished, cancelled or not. */
        default void onTransferFinished(@NonNull Transfer transfer) {}
    }

    private static SftpTransfers sInstance;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final List<Transfer> mTransfers = new CopyOnWriteArrayList<>();
    private final List<Listener> mListeners = new CopyOnWriteArrayList<>();
    /** The connections of the transfer thread, by host. */
    private final Map<String, SftpConnection> mConnections = new HashMap<>();
    /** Connects transfers instead of ssh in tests. */
    @Nullable private ClientFactory mClientFactory;
    private int mNextId = 1;
    private long mLastProgress;
    private boolean mProgressPosted;

    private SftpTransfers(@NonNull Context context) {
        mContext = context.getApplicationContext();
    }

    @NonNull
    public static synchronized SftpTransfers get(@NonNull Context context) {
        if (sInstance == null) sInstance = new SftpTransfers(context);
        return sInstance;
    }

    public void addListener(@NonNull Listener listener) {
        mListeners.add(listener);
    }

    public void removeListener(@NonNull Listener listener) {
        mListeners.remove(listener);
    }

    /** The transfers, oldest first. */
    @NonNull
    public List<Transfer> getTransfers() {
        return new ArrayList<>(mTransfers);
    }

    /** The number of transfers that are queued or running. */
    public int getActiveCount() {
        int count = 0;
        for (Transfer transfer : mTransfers) if (!transfer.isFinished()) count++;
        return count;
    }

    /** The running transfer, or null. */
    @Nullable
    public Transfer getRunning() {
        for (Transfer transfer : mTransfers) if (transfer.state == RUNNING) return transfer;
        return null;
    }

    /** Add a transfer to the queue. Must be called on the main thread. */
    @NonNull
    Transfer enqueue(@NonNull String hostName, @Nullable String controlPath, @NonNull String title, long total,
                     @Nullable String remoteDir, @NonNull List<Uri> grantedUris, @NonNull Job job) {
        Transfer transfer = new Transfer(mNextId++, hostName, controlPath, title, total, remoteDir, job);
        for (Uri uri : grantedUris) {
            // Keep reading the picked files after the browser is closed.
            try {
                mContext.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                transfer.grantedUris.add(uri);
            } catch (SecurityException e) {
                Logger.logDebug(LOG_TAG, "Failed to keep the permission of " + uri + ": " + e.getMessage());
            }
        }
        mTransfers.add(transfer);
        trimFinished();
        SftpTransferService.start(mContext);
        notifyChanged();
        mExecutor.execute(() -> run(transfer));
        return transfer;
    }

    /** Cancel a queued or running transfer. */
    public void cancel(@NonNull Transfer transfer) {
        transfer.cancelled.set(true);
        if (transfer.state == QUEUED) finish(transfer, CANCELLED, null);
    }

    public void cancelAll() {
        for (Transfer transfer : mTransfers) if (!transfer.isFinished()) cancel(transfer);
    }

    /** Forget the finished transfers. */
    public void clearFinished() {
        for (Transfer transfer : mTransfers) if (transfer.isFinished()) mTransfers.remove(transfer);
        notifyChanged();
    }

    private void trimFinished() {
        int finished = 0;
        for (Transfer transfer : mTransfers) if (transfer.isFinished()) finished++;
        for (Transfer transfer : mTransfers) {
            if (finished <= MAX_FINISHED) break;
            if (transfer.isFinished()) {
                mTransfers.remove(transfer);
                finished--;
            }
        }
    }

    /** Run a transfer on the transfer thread. */
    private void run(@NonNull Transfer transfer) {
        if (transfer.isFinished()) {
            disconnectIfIdle(transfer);
            return;
        }
        transfer.state = RUNNING;
        notifyChanged();
        int state;
        String message;
        try {
            if (transfer.cancelled.get()) throw new InterruptedIOException("Cancelled");
            message = transfer.job.run(mContext, getClient(transfer), transfer);
            state = DONE;
        } catch (InterruptedIOException e) {
            state = transfer.cancelled.get() ? CANCELLED : FAILED;
            message = e.getMessage();
        } catch (IOException | RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Transfer failed: " + transfer.title, e);
            state = transfer.cancelled.get() ? CANCELLED : FAILED;
            message = e.getMessage() != null ? e.getMessage() : e.toString();
        }
        for (Uri uri : transfer.grantedUris) {
            if (isGrantNeeded(transfer, uri)) continue;
            try {
                mContext.getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
            }
        }
        int finalState = state;
        String finalMessage = message;
        mHandler.post(() -> finish(transfer, finalState, finalMessage));
        disconnectIfIdle(transfer);
    }

    /** Whether another unfinished transfer still reads a document. */
    private boolean isGrantNeeded(@NonNull Transfer finished, @NonNull Uri uri) {
        for (Transfer transfer : mTransfers)
            if (transfer != finished && !transfer.isFinished() && transfer.grantedUris.contains(uri)) return true;
        return false;
    }

    @NonNull
    private SftpClient getClient(@NonNull Transfer transfer) throws IOException {
        if (mClientFactory != null) return mClientFactory.getClient(transfer);
        SftpConnection connection = mConnections.get(transfer.hostName);
        if (connection == null) {
            connection = new SftpConnection(mContext, transfer.hostName, transfer.controlPath);
            mConnections.put(transfer.hostName, connection);
        }
        return connection.getClient();
    }

    void setClientFactory(@Nullable ClientFactory factory) {
        mClientFactory = factory;
    }

    /** Close the connections when no other transfer is left, so that they do not keep ssh tabs from exiting. */
    private void disconnectIfIdle(@NonNull Transfer current) {
        for (Transfer transfer : mTransfers) if (transfer != current && !transfer.isFinished()) return;
        for (SftpConnection connection : mConnections.values()) connection.disconnect();
        mConnections.clear();
    }

    private void finish(@NonNull Transfer transfer, int state, @Nullable String message) {
        if (transfer.isFinished()) return;
        transfer.state = state;
        transfer.message = message;
        for (Listener listener : mListeners) listener.onTransferFinished(transfer);
        notifyChanged();
    }

    /** Report progress of the running transfer, at most every {@link #PROGRESS_INTERVAL_MS}. */
    void progress(@NonNull Transfer transfer, long done, long total) {
        transfer.done = done;
        if (total > 0) transfer.total = total;
        long now = SystemClock.uptimeMillis();
        synchronized (this) {
            if (mProgressPosted || now - mLastProgress < PROGRESS_INTERVAL_MS) return;
            mLastProgress = now;
            mProgressPosted = true;
        }
        mHandler.post(() -> {
            synchronized (this) {
                mProgressPosted = false;
            }
            notifyChanged();
        });
    }

    private void notifyChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mHandler.post(this::notifyChanged);
            return;
        }
        for (Listener listener : mListeners) listener.onTransfersChanged();
    }

    // Jobs

    /** A file or folder to download. */
    static final class DownloadItem {
        final String path;
        final String name;
        final boolean isDirectory;
        final long size;

        DownloadItem(String path, String name, boolean isDirectory, long size) {
            this.path = path;
            this.name = name;
            this.isDirectory = isDirectory;
            this.size = size;
        }
    }

    /** A file to download, and the folder in the Download directory it goes to, or null for the Download directory. */
    private static final class DownloadFile {
        final String path;
        @Nullable final String folder;
        final long size;

        DownloadFile(String path, @Nullable String folder, long size) {
            this.path = path;
            this.folder = folder;
            this.size = size;
        }
    }

    /** Download files, and folders with their contents into folders of the same names, to the Download directory. */
    static Job download(@NonNull List<DownloadItem> items) {
        return (context, client, transfer) -> {
            List<DownloadFile> files = new ArrayList<>();
            for (DownloadItem item : items) {
                if (item.isDirectory) collectFiles(client, item.path, item.name, files, transfer.cancelled);
                else files.add(new DownloadFile(item.path, null, item.size));
            }
            long total = 0;
            for (DownloadFile file : files) total += Math.max(0, file.size);
            final long totalSize = total;
            SftpTransfers transfers = get(context);
            transfers.progress(transfer, 0, totalSize);
            long done = 0;
            String savedPath = null;
            for (DownloadFile download : files) {
                String name = SftpPaths.name(download.path);
                String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(SftpText.getExtension(name));
                DownloadsDirectory.NewFile file = DownloadsDirectory.createNewFile(context, download.folder, name,
                    mimeType != null ? mimeType : "application/octet-stream");
                final long before = done;
                try {
                    done += client.read(download.path, file.out, Long.MAX_VALUE,
                        (read, size) -> transfers.progress(transfer, before + read, totalSize), transfer.cancelled);
                    file.out.close();
                } catch (IOException e) {
                    // Do not leave a partial file.
                    file.delete();
                    throw e;
                }
                savedPath = file.displayPath;
            }
            if (items.size() == 1 && !items.get(0).isDirectory && savedPath != null)
                return context.getString(R.string.sftp_downloaded, savedPath);
            return context.getString(R.string.sftp_downloaded_items, files.size(),
                items.size() == 1 ? "Download/" + items.get(0).name : "Download");
        };
    }

    /** Add the files of a folder and its subfolders. Symlinks to folders are not followed, so that loops end. */
    private static void collectFiles(@NonNull SftpClient client, @NonNull String path, @NonNull String folder,
                                     @NonNull List<DownloadFile> files, @NonNull AtomicBoolean cancelled) throws IOException {
        for (SftpClient.Entry entry : client.list(path)) {
            if (cancelled.get()) throw new InterruptedIOException("Cancelled");
            String childPath = SftpPaths.join(path, entry.name);
            if (entry.attributes.isDirectory()) {
                collectFiles(client, childPath, folder + "/" + entry.name, files, cancelled);
            } else if (entry.attributes.isRegularFile() || entry.attributes.isSymlink()) {
                long size = entry.attributes.size;
                if (entry.attributes.isSymlink()) {
                    try {
                        SftpClient.Attributes target = client.stat(childPath);
                        if (!target.isRegularFile()) continue;
                        size = target.size;
                    } catch (IOException e) {
                        continue; // A broken symlink.
                    }
                }
                files.add(new DownloadFile(childPath, folder, size));
            }
        }
    }

    /** A picked file to upload, with the name and size the document provider reports. */
    static final class Upload {
        final Uri uri;
        final String name;
        final long size;

        Upload(Uri uri, String name, long size) {
            this.uri = uri;
            this.name = name;
            this.size = size;
        }
    }

    /** Upload picked files into a remote folder. */
    static Job upload(@NonNull List<Upload> uploads, @NonNull String dir) {
        return (context, client, transfer) -> {
            long total = 0;
            for (Upload upload : uploads) total = upload.size >= 0 && total >= 0 ? total + upload.size : -1;
            final long totalSize = total;
            SftpTransfers transfers = get(context);
            long done = 0;
            for (Upload upload : uploads) {
                final long before = done;
                try (InputStream in = context.getContentResolver().openInputStream(upload.uri)) {
                    if (in == null) throw new IOException("Failed to open " + upload.uri);
                    done += client.write(SftpPaths.join(dir, upload.name), in, upload.size, 0644, false,
                        (written, size) -> transfers.progress(transfer, before + written, totalSize), transfer.cancelled);
                }
            }
            return context.getString(R.string.sftp_uploaded, uploads.size(), dir);
        };
    }

    /** A file or folder of a picked folder, with its path relative to the picked folder. */
    private static final class TreeEntry {
        final Uri uri;
        final String relativePath;
        final boolean isDirectory;
        final long size;

        TreeEntry(Uri uri, String relativePath, boolean isDirectory, long size) {
            this.uri = uri;
            this.relativePath = relativePath;
            this.isDirectory = isDirectory;
            this.size = size;
        }
    }

    /** Upload a picked folder with its contents to a remote folder, merging with it if it exists. */
    static Job uploadFolder(@NonNull Uri treeUri, @NonNull String rootId, @NonNull String target) {
        return (context, client, transfer) -> {
            List<TreeEntry> entries = new ArrayList<>();
            collectTree(context, treeUri, rootId, "", entries, transfer.cancelled);
            long total = 0;
            for (TreeEntry entry : entries) if (!entry.isDirectory) total += Math.max(0, entry.size);
            final long totalSize = total;
            SftpTransfers transfers = get(context);
            transfers.progress(transfer, 0, totalSize);
            mkdirIfMissing(client, target);
            long done = 0;
            int count = 0;
            for (TreeEntry entry : entries) {
                if (transfer.cancelled.get()) throw new InterruptedIOException("Cancelled");
                String path = target + entry.relativePath;
                if (entry.isDirectory) {
                    mkdirIfMissing(client, path);
                    continue;
                }
                final long before = done;
                try (InputStream in = context.getContentResolver().openInputStream(entry.uri)) {
                    if (in == null) throw new IOException("Failed to open " + entry.relativePath);
                    done += client.write(path, in, entry.size, 0644, false,
                        (written, size) -> transfers.progress(transfer, before + written, totalSize), transfer.cancelled);
                }
                count++;
            }
            return context.getString(R.string.sftp_uploaded, count, target);
        };
    }

    /** Add the files and folders under a document of a picked folder, folders before their contents. */
    private static void collectTree(@NonNull Context context, @NonNull Uri treeUri, @NonNull String documentId,
                                    @NonNull String relativePath, @NonNull List<TreeEntry> entries,
                                    @NonNull AtomicBoolean cancelled) throws IOException {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE};
        List<String[]> folders = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver().query(childrenUri, columns, null, null, null)) {
            if (cursor == null) throw new IOException("Failed to list " + relativePath);
            while (cursor.moveToNext()) {
                if (cancelled.get()) throw new InterruptedIOException("Cancelled");
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                if (name == null || !SftpPaths.isValidName(name)) continue;
                String path = relativePath + "/" + name;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                    entries.add(new TreeEntry(null, path, true, 0));
                    folders.add(new String[]{id, path});
                } else {
                    long size = cursor.isNull(3) ? -1 : cursor.getLong(3);
                    entries.add(new TreeEntry(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), path, false, size));
                }
            }
        }
        for (String[] folder : folders) collectTree(context, treeUri, folder[0], folder[1], entries, cancelled);
    }

    static void mkdirIfMissing(@NonNull SftpClient client, @NonNull String path) throws IOException {
        try {
            client.mkdir(path);
        } catch (SftpClient.SftpException e) {
            // It may exist already, which servers report as a failure.
            if (!client.stat(path).isDirectory()) throw e;
        }
    }

}
