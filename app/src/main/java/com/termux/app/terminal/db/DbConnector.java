package com.termux.app.terminal.db;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;

import java.io.IOException;
import java.sql.SQLException;

/** Opens a session of a connection, through an SSH tunnel if it has an SSH host. */
public final class DbConnector {

    /** The database drivers use APIs of Android 8.0 (API 26), like java.time and java.nio.file. */
    public static final int MIN_SDK = 26;

    static final int CONNECT_TIMEOUT_SECONDS = 15;

    /** An open session and its tunnel, if any. */
    public static final class Connection {
        @NonNull public final DbSession session;
        @Nullable private final SshTunnel tunnel;

        Connection(@NonNull DbSession session, @Nullable SshTunnel tunnel) {
            this.session = session;
            this.tunnel = tunnel;
        }

        public void close() {
            session.close();
            if (tunnel != null) tunnel.close();
        }

        public boolean isTunnelOpen() {
            return tunnel == null || tunnel.isOpen();
        }
    }

    private DbConnector() {}

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= MIN_SDK;
    }

    /** Connect, which blocks, so not on the main thread. */
    @NonNull
    public static Connection connect(@NonNull Context context, @NonNull DbConnectionConfig config, @Nullable String password)
        throws IOException, SQLException {
        if (config.type == DbType.SQLITE) return new Connection(SqliteSession.open(config), null);
        if (!isSupported()) throw new SQLException(context.getString(R.string.msg_db_android_version));
        SshTunnel tunnel = null;
        String host = config.host.trim();
        int port = config.getPort();
        if (!config.sshHost.isEmpty()) {
            tunnel = SshTunnel.open(context, config.sshHost, host, port);
            host = "127.0.0.1";
            port = tunnel.localPort;
        }
        try {
            return new Connection(JdbcSession.connect(config, password, host, port, CONNECT_TIMEOUT_SECONDS), tunnel);
        } catch (SQLException | RuntimeException e) {
            if (tunnel != null) tunnel.close();
            throw e;
        }
    }

}
