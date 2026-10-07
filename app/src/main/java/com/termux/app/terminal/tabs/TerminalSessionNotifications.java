package com.termux.app.terminal.tabs;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.app.terminal.TermuxTerminalSessionClient;
import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.notification.TermuxNotificationUtils;
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Notifications for sessions that exit or ring the bell while the activity is not visible, so the
 * user knows when something in a background session needs attention. Tapping a notification opens
 * the activity on that session.
 *
 * A session has at most one notification, which is updated, and only one bell notification is
 * posted for a session until it is displayed again, so programs that ring the bell often do not
 * flood the notifications. The notification is removed when the session is displayed.
 *
 * All methods are called on the main thread, where {@link TerminalSession} callbacks are called.
 */
public final class TerminalSessionNotifications {

    private static final String CHANNEL_ID = "termux_session_events_notification_channel";
    private static final String CHANNEL_NAME = TermuxConstants.TERMUX_APP_NAME + " Sessions";

    /** The {@link TerminalSession#mHandle} of the session to show when the activity is opened. */
    private static final String EXTRA_SESSION_HANDLE = "com.termux.app.terminal.tabs.extra.SESSION_HANDLE";

    /** The id of the notification of each session that has one. */
    private static final Map<TerminalSession, Integer> sNotificationIds = new WeakHashMap<>();

    /** Sessions that already have a bell notification since they were last displayed. */
    private static final Set<TerminalSession> sBellNotifiedSessions = Collections.newSetFromMap(new WeakHashMap<>());

    private TerminalSessionNotifications() {}

    /** Called when a session rang the bell while the activity is not visible. */
    public static void onBell(@NonNull Context context, @Nullable TermuxService service, @NonNull TerminalSession session) {
        if (!TerminalTabsSettings.areBellNotificationsEnabled()) return;
        if (!sBellNotifiedSessions.add(session)) return;

        notify(context, service, session, context.getString(R.string.notification_terminal_session_bell));
    }

    /** Called when a session exited while the activity is not visible. */
    public static void onSessionFinished(@NonNull Context context, @Nullable TermuxService service, @NonNull TerminalSession session) {
        if (!TerminalTabsSettings.areExitNotificationsEnabled()) return;

        int exitStatus = session.getExitStatus();
        String text;
        if (exitStatus > 0)
            text = context.getString(R.string.notification_terminal_session_exited_code, exitStatus);
        else if (exitStatus < 0)
            // Negated signal, like in the "[Process completed (signal 9)]" message of TerminalSession.
            text = context.getString(R.string.notification_terminal_session_exited_signal, -exitStatus);
        else
            text = context.getString(R.string.notification_terminal_session_exited);

        notify(context, service, session, text);
    }

    /** Called when a session is displayed while the activity is visible, to remove its notification. */
    public static void onSessionSeen(@NonNull Context context, @Nullable TerminalSession session) {
        if (session == null) return;
        sBellNotifiedSessions.remove(session);

        Integer notificationId = sNotificationIds.remove(session);
        if (notificationId == null) return;
        NotificationManager notificationManager = NotificationUtils.getNotificationManager(context);
        if (notificationManager != null) notificationManager.cancel(notificationId);
    }

    private static void notify(@NonNull Context context, @Nullable TermuxService service, @NonNull TerminalSession session, @NonNull String text) {
        NotificationManager notificationManager = NotificationUtils.getNotificationManager(context);
        if (notificationManager == null) return;

        Integer notificationId = sNotificationIds.get(session);
        if (notificationId == null) {
            notificationId = TermuxNotificationUtils.getNextNotificationId(context);
            sNotificationIds.put(session, notificationId);
        }

        Intent intent = TermuxActivity.newInstance(context);
        intent.putExtra(EXTRA_SESSION_HANDLE, session.mHandle);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(context, notificationId, intent, flags);

        NotificationUtils.setupNotificationChannel(context, CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
        Notification.Builder builder = NotificationUtils.geNotificationBuilder(context, CHANNEL_ID,
            Notification.PRIORITY_DEFAULT, getTitle(service, session), text, null, contentIntent, null,
            NotificationUtils.NOTIFICATION_MODE_SOUND_AND_VIBRATE);
        if (builder == null) return;
        builder.setSmallIcon(R.drawable.ic_service_notification);
        builder.setColor(0xFF607D8B);
        builder.setAutoCancel(true);
        builder.setShowWhen(true);

        notificationManager.notify(notificationId, builder.build());
    }

    /** The session title like on its tab: the number and the session name or the title set by the program. */
    @NonNull
    private static String getTitle(@Nullable TermuxService service, @NonNull TerminalSession session) {
        int index = service == null ? -1 : service.getIndexOfSession(session);
        String label = TerminalTabTitles.getLabel(session);
        String number = index < 0 ? "" : "[" + (index + 1) + "]";
        if (TextUtils.isEmpty(label)) return number.isEmpty() ? TermuxConstants.TERMUX_APP_NAME : number;
        return number.isEmpty() ? label : number + " " + label;
    }

    /**
     * Show the session of the notification the activity was opened from, if any. Called with the
     * intent the activity was created or brought to front with.
     */
    public static void handleIntent(@NonNull TermuxActivity activity, @Nullable Intent intent) {
        if (intent == null) return;
        String sessionHandle = intent.getStringExtra(EXTRA_SESSION_HANDLE);
        if (sessionHandle == null) return;
        intent.removeExtra(EXTRA_SESSION_HANDLE);

        // Store it as the current session, which is shown when the activity is started or when it
        // connects to the service.
        TermuxAppSharedPreferences preferences = activity.getPreferences();
        if (preferences != null) preferences.setCurrentSession(sessionHandle);

        // Switch to it now if the activity is already showing a session.
        TermuxService service = activity.getTermuxService();
        TermuxTerminalSessionClient sessionClient = activity.getTermuxTerminalSessionClient();
        if (service == null || sessionClient == null) return;
        TerminalSession session = service.getTerminalSessionForHandle(sessionHandle);
        if (session != null) sessionClient.setCurrentSession(session);
    }

}
