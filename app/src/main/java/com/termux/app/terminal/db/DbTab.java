package com.termux.app.terminal.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A database tab: a connection, the SQL being edited and the results. It lives as long as the app
 * process, apart from its view, so that the connection is kept when the activity is made again.
 * Its database operations run one after another on its own thread.
 */
public final class DbTab {

    public static final int DISCONNECTED = 0;
    public static final int CONNECTING = 1;
    public static final int READY = 2;
    public static final int RUNNING = 3;
    public static final int FAILED = 4;

    public static final int PANEL_SCHEMA = 0;
    public static final int PANEL_SQL = 1;
    public static final int PANEL_RESULTS = 2;

    private static long sNextId = 1;

    public final long id = sNextId++;
    @NonNull public String connectionId;
    @NonNull public String sql = "";

    // While connected.
    @Nullable public DbConnector.Connection connection;
    /** The password typed or loaded for this tab, kept in memory to connect again. */
    @Nullable public String password;
    public int state = DISCONNECTED;
    @Nullable public String error;
    @Nullable public String serverVersion;
    public boolean autoCommit = true;

    // Results.
    @NonNull public final List<QueryResult> results = new ArrayList<>();
    public int resultIndex;
    /** The statements of the last run, for their progress. */
    public int runDone;
    public int runTotal;

    // Schema tree.
    @NonNull public final List<String> schemas = new ArrayList<>();
    @Nullable public String defaultSchema;
    @NonNull public final Map<String, List<DbObjects.Table>> tables = new HashMap<>();
    @NonNull public final Map<String, List<DbObjects.Column>> columns = new HashMap<>();
    @NonNull public final java.util.Set<String> expandedSchemas = new java.util.HashSet<>();
    @NonNull public final java.util.Set<String> expandedTables = new java.util.HashSet<>();

    public int panel = PANEL_SQL;
    /** Whether the results pane is collapsed to give the editor more room. Starts collapsed. */
    public boolean resultsCollapsed = true;
    public int timeoutSeconds;
    public int pageSize = 200;

    /** The table whose rows are being edited, or {@code null}. */
    @Nullable public DbObjects.Table editTable;
    /** The editor of the rows of {@link #editTable}, with its changes. */
    @Nullable DbDataEditor dataEditor;

    @NonNull final ExecutorService executor = Executors.newSingleThreadExecutor();

    public DbTab(@NonNull String connectionId) {
        this.connectionId = connectionId;
    }

    @Nullable
    public DbSession getSession() {
        DbConnector.Connection connection = this.connection;
        return connection != null ? connection.session : null;
    }

    /** Whether the connection of the tab refuses writes. */
    public boolean connectionReadOnly() {
        DbConnector.Connection connection = this.connection;
        return connection != null && connection.session.getConfig().readOnly;
    }

    public boolean isConnected() {
        return connection != null && (state == READY || state == RUNNING);
    }

    @NonNull
    static String key(@Nullable String schema, @NonNull String table) {
        return (schema != null ? schema : "") + "." + table;
    }

    /** Forget the connection and what was read from it, after it is closed. */
    void reset() {
        connection = null;
        state = DISCONNECTED;
        serverVersion = null;
        schemas.clear();
        tables.clear();
        columns.clear();
        expandedSchemas.clear();
        expandedTables.clear();
        autoCommit = true;
        editTable = null;
        dataEditor = null;
    }

}
