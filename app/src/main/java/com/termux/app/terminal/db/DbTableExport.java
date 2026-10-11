package com.termux.app.terminal.db;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.terminal.backup.DownloadsDirectory;
import com.termux.shared.logger.Logger;
import com.termux.shared.notification.NotificationUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exports all rows of a table to a file in the Download directory in the background, on a
 * connection of its own so that the tab stays usable, writing rows as they arrive instead of
 * keeping them. A notification shows the progress and can cancel it.
 */
final class DbTableExport {

    private static final String CHANNEL_ID = "devshell_db_export";
    private static final String ACTION_CANCEL = "com.termux.db.EXPORT_CANCEL";
    private static final String EXTRA_ID = "id";
    private static final int NOTIFICATION_BASE = 7300;
    private static final String LOG_TAG = "DbTableExport";
    private static final AtomicInteger sNextId = new AtomicInteger();
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private final Context mContext;
    private final DbConnectionConfig mConfig;
    @Nullable private final String mPassword;
    private final DbObjects.Table mTable;
    private final int mFormat;
    private final int mId = sNextId.incrementAndGet();
    private volatile boolean mCancelled;
    @Nullable private volatile DbSession mSession;
    private long mLastNotified;

    private DbTableExport(@NonNull Context context, @NonNull DbConnectionConfig config, @Nullable String password,
                          @NonNull DbObjects.Table table, int format) {
        mContext = context.getApplicationContext();
        mConfig = config;
        mPassword = password;
        mTable = table;
        mFormat = format;
    }

    /** Ask for the format and start exporting a table. */
    static void start(@NonNull DbTabView view, @NonNull DbObjects.Table table) {
        DbConnectionConfig config = view.getConfig();
        if (config == null) return;
        String password = view.getTab().password;
        String[] formats = {"CSV", "TSV", "JSON", "Markdown", "SQL INSERT"};
        new AlertDialog.Builder(view.getActivity())
            .setTitle(view.getContext().getString(R.string.title_db_export_table, table.name))
            .setItems(formats, (d, which) -> {
                DbTableExport export = new DbTableExport(view.getActivity(), config, password, table, which);
                new Thread(export::run, "DbTableExport").start();
                Toast.makeText(view.getContext(), R.string.msg_db_export_started, Toast.LENGTH_SHORT).show();
            })
            .show();
    }

    private void run() {
        NotificationUtils.setupNotificationChannel(mContext, CHANNEL_ID, mContext.getString(R.string.db_export_channel),
            NotificationManager.IMPORTANCE_LOW);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getIntExtra(EXTRA_ID, -1) != mId) return;
                mCancelled = true;
                DbSession session = mSession;
                if (session != null) session.cancel();
            }
        };
        mContext.registerReceiver(receiver, new IntentFilter(ACTION_CANCEL));
        notifyProgress(0, true);
        DbConnector.Connection connection = null;
        DownloadsDirectory.NewFile file = null;
        String message;
        boolean failed = true;
        try {
            connection = DbConnector.connect(mContext, mConfig, mPassword);
            DbSession session = connection.session;
            mSession = session;
            String fileName = mTable.name.replaceAll("[^\\p{L}\\p{N}._-]", "_") + "." + ResultExport.EXTENSIONS[mFormat];
            file = DownloadsDirectory.createNewFile(mContext, fileName, ResultExport.MIME_TYPES[mFormat]);
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(file.out, StandardCharsets.UTF_8), 65536);
            DbType type = session.getType();
            String name = type.quote(mTable.schema, mTable.name);
            ResultExport.Writer[] writer = new ResultExport.Writer[1];
            long rows = session.stream("SELECT * FROM " + name, new DbSession.RowSink() {
                @Override
                public void columns(@NonNull List<QueryResult.Column> columns) throws IOException {
                    writer[0] = new ResultExport.Writer(mFormat, out, columns, type, name);
                }

                @Override
                public boolean row(@NonNull Object[] row) throws IOException {
                    writer[0].row(row);
                    notifyProgress(writer[0].getRowCount(), false);
                    return !mCancelled;
                }
            });
            if (mCancelled) throw new IOException(mContext.getString(R.string.msg_db_export_cancelled));
            if (writer[0] != null) writer[0].finish();
            out.close();
            message = mContext.getString(R.string.msg_db_export_done, rows, file.displayPath);
            failed = false;
        } catch (Exception | LinkageError e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to export " + mTable.name, e);
            if (file != null) file.delete();
            message = mCancelled ? mContext.getString(R.string.msg_db_export_cancelled)
                : mContext.getString(R.string.msg_db_export_failed, DbTabView.describe(mContext, e));
        } finally {
            mSession = null;
            if (connection != null) connection.close();
            mContext.unregisterReceiver(receiver);
        }
        notifyDone(message, failed);
        String toast = message;
        HANDLER.post(() -> Toast.makeText(mContext, toast, Toast.LENGTH_LONG).show());
    }

    private void notifyProgress(long rows, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - mLastNotified < 500) return;
        mLastNotified = now;
        NotificationManager manager = NotificationUtils.getNotificationManager(mContext);
        if (manager == null) return;
        Intent cancel = new Intent(ACTION_CANCEL).setPackage(mContext.getPackageName()).putExtra(EXTRA_ID, mId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification notification = builder()
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(mContext.getString(R.string.msg_db_exporting, mTable.name))
            .setContentText(mContext.getString(R.string.db_rows, rows))
            .setProgress(0, 0, true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, mContext.getString(R.string.action_db_cancel),
                PendingIntent.getBroadcast(mContext, mId, cancel, flags))
            .build();
        manager.notify(NOTIFICATION_BASE + mId, notification);
    }

    private void notifyDone(@NonNull String message, boolean failed) {
        NotificationManager manager = NotificationUtils.getNotificationManager(mContext);
        if (manager == null) return;
        manager.notify(NOTIFICATION_BASE + mId, builder()
            .setSmallIcon(failed ? android.R.drawable.stat_notify_error : android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentTitle(mTable.name)
            .setContentText(message)
            .setStyle(new Notification.BigTextStyle().bigText(message))
            .build());
    }

    @NonNull
    private Notification.Builder builder() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new Notification.Builder(mContext, CHANNEL_ID) : new Notification.Builder(mContext);
    }

}
