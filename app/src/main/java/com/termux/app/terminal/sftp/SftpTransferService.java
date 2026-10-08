package com.termux.app.terminal.sftp;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.locale.AppLocale;
import com.termux.shared.notification.NotificationUtils;

/**
 * Shows the progress of the SFTP transfer queue of {@link SftpTransfers} in a notification, and
 * keeps the app running in the foreground while transfers are queued. When the queue is done, it
 * shows how many transfers finished or failed, and stops.
 */
public final class SftpTransferService extends Service implements SftpTransfers.Listener {

    private static final String ACTION_CANCEL_ALL = "com.termux.app.terminal.sftp.action.CANCEL_ALL_TRANSFERS";
    private static final String CHANNEL_ID = "termux_sftp_transfers";
    private static final int NOTIFICATION_ID = 1400;
    private static final int SUMMARY_NOTIFICATION_ID = 1401;

    private SftpTransfers mTransfers;
    private int mDone;
    private int mFailed;
    @Nullable private String mLastHost;
    @Nullable private String mLastControlPath;

    static void start(@NonNull Context context) {
        context.startService(new Intent(context, SftpTransferService.class));
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocale.wrap(newBase));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationUtils.setupNotificationChannel(this, CHANNEL_ID, getString(R.string.sftp_transfers_channel),
            NotificationManager.IMPORTANCE_LOW);
        mTransfers = SftpTransfers.get(this);
        mTransfers.addListener(this);
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL_ALL.equals(intent.getAction())) mTransfers.cancelAll();
        onTransfersChanged();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        mTransfers.removeListener(this);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTransfersChanged() {
        if (mTransfers.getActiveCount() == 0) {
            stopForeground(true);
            showSummary();
            stopSelf();
            return;
        }
        NotificationManager manager = NotificationUtils.getNotificationManager(this);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    @Override
    public void onTransferFinished(@NonNull SftpTransfers.Transfer transfer) {
        if (transfer.getState() == SftpTransfers.DONE) mDone++;
        else if (transfer.getState() == SftpTransfers.FAILED) mFailed++;
        mLastHost = transfer.hostName;
        mLastControlPath = transfer.controlPath;
    }

    /** Open the file browser of the host with the transfers. */
    @Nullable
    private PendingIntent getContentIntent(@Nullable String hostName, @Nullable String controlPath) {
        if (hostName == null) return null;
        Intent intent = SftpBrowserActivity.newIntent(this, hostName, controlPath, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT |
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    @NonNull
    private Notification buildNotification() {
        SftpTransfers.Transfer running = mTransfers.getRunning();
        int active = mTransfers.getActiveCount();

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentTitle(running != null ? running.title : getString(R.string.sftp_transfers_waiting))
            .setSubText(getResources().getQuantityString(R.plurals.sftp_transfers_active, active, active));
        if (running != null) {
            long total = running.getTotal();
            builder.setContentText(running.describe(this));
            if (total > 0) builder.setProgress(1000, (int) Math.min(1000, running.getDone() * 1000 / total), false);
            else builder.setProgress(0, 0, true);
            builder.setContentIntent(getContentIntent(running.hostName, running.controlPath));
            builder.setSmallIcon(running.remoteDir != null ? android.R.drawable.stat_sys_upload : android.R.drawable.stat_sys_download);
        }
        Intent cancel = new Intent(this, SftpTransferService.class).setAction(ACTION_CANCEL_ALL);
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.sftp_transfers_cancel_all),
            PendingIntent.getService(this, 0, cancel, Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));
        return builder.build();
    }

    /** Show how many transfers finished or failed since the queue started. */
    private void showSummary() {
        if (mDone + mFailed == 0) return;
        NotificationManager manager = NotificationUtils.getNotificationManager(this);
        if (manager == null) return;
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        builder.setSmallIcon(mFailed > 0 ? android.R.drawable.stat_notify_error : android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentTitle(getString(R.string.sftp_transfers_finished))
            .setContentText(mFailed > 0 ? getString(R.string.sftp_transfers_summary_failed, mDone, mFailed)
                : getString(R.string.sftp_transfers_summary, mDone))
            .setContentIntent(getContentIntent(mLastHost, mLastControlPath));
        manager.notify(SUMMARY_NOTIFICATION_ID, builder.build());
        mDone = 0;
        mFailed = 0;
    }

}
