package com.termux.app.terminal.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** The settings of a database connection. The password is saved apart, encrypted, see {@link DbConnectionStore}. */
public class DbConnectionConfig {

    public static final int SSL_OFF = 0;
    /** Encrypt, without checking the certificate. */
    public static final int SSL_REQUIRE = 1;
    /** Encrypt and check the certificate of the server. */
    public static final int SSL_VERIFY = 2;

    /** The colors to tell connections apart, like red for production. 0 is none. */
    public static final int[] COLORS = {0, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF1E88E5, 0xFF8E24AA};

    @NonNull public String id = UUID.randomUUID().toString();
    @NonNull public String name = "";
    @NonNull public DbType type = DbType.POSTGRESQL;
    @NonNull public String host = "";
    public int port;
    /** The database name, or the file path for SQLite. */
    @NonNull public String database = "";
    @NonNull public String user = "";
    public int ssl = SSL_OFF;
    /** More driver parameters, one `name=value` per line. */
    @NonNull public String parameters = "";
    /** The SSH host registered in the app to connect through, or empty to connect directly. */
    @NonNull public String sshHost = "";
    /** The index in {@link #COLORS}. */
    public int color;
    /** Whether statements that write are refused. */
    public boolean readOnly;

    /** The port, or the default of the type if none is set. */
    public int getPort() {
        return port > 0 ? port : type.defaultPort;
    }

    /** The name, or a description like `tester@db.example.com/shop` if it has none. */
    @NonNull
    public String getTitle() {
        if (!name.trim().isEmpty()) return name.trim();
        return getAddress();
    }

    /** Like `tester@db.example.com:5433/shop`, or the file of SQLite. */
    @NonNull
    public String getAddress() {
        if (type == DbType.SQLITE) return database;
        String address = (user.isEmpty() ? "" : user + "@") + host;
        if (port > 0 && port != type.defaultPort) address += ":" + port;
        return database.isEmpty() ? address : address + "/" + database;
    }

    /** The parsed {@link #parameters}, skipping lines without `=`. */
    @NonNull
    public Map<String, String> getParameters() {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : parameters.split("\n")) {
            line = line.trim();
            int equals = line.indexOf('=');
            if (equals <= 0 || line.startsWith("#")) continue;
            map.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
        }
        return map;
    }

    /** Check the settings, and return the resource of the problem, or 0 if they are valid. */
    public int validate() {
        if (type == DbType.SQLITE) return database.trim().isEmpty() ? com.termux.R.string.msg_db_file_required : 0;
        if (host.trim().isEmpty()) return com.termux.R.string.msg_db_host_required;
        if (port < 0 || port > 65535) return com.termux.R.string.msg_db_port_invalid;
        return 0;
    }

    @NonNull
    public DbConnectionConfig copy() {
        try {
            DbConnectionConfig copy = fromJson(toJson());
            copy.id = UUID.randomUUID().toString();
            return copy;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("type", type.id);
        json.put("host", host);
        if (port > 0) json.put("port", port);
        json.put("database", database);
        json.put("user", user);
        if (ssl != SSL_OFF) json.put("ssl", ssl);
        if (!parameters.isEmpty()) json.put("parameters", parameters);
        if (!sshHost.isEmpty()) json.put("sshHost", sshHost);
        if (color != 0) json.put("color", color);
        if (readOnly) json.put("readOnly", true);
        return json;
    }

    @NonNull
    public static DbConnectionConfig fromJson(@NonNull JSONObject json) {
        DbConnectionConfig config = new DbConnectionConfig();
        String id = json.optString("id", "");
        if (!id.isEmpty()) config.id = id;
        config.name = json.optString("name", "");
        config.type = DbType.fromId(json.optString("type", null));
        config.host = json.optString("host", "");
        config.port = json.optInt("port", 0);
        config.database = json.optString("database", "");
        config.user = json.optString("user", "");
        config.ssl = json.optInt("ssl", SSL_OFF);
        config.parameters = json.optString("parameters", "");
        config.sshHost = json.optString("sshHost", "");
        int color = json.optInt("color", 0);
        config.color = color >= 0 && color < COLORS.length ? color : 0;
        config.readOnly = json.optBoolean("readOnly", false);
        return config;
    }

    /** A copy with the same id, to change without changing this one. */
    @NonNull
    static DbConnectionConfig fromJsonQuietly(@NonNull DbConnectionConfig config) {
        try {
            return fromJson(config.toJson());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    @Nullable
    public static DbConnectionConfig find(@NonNull Iterable<DbConnectionConfig> configs, @Nullable String id) {
        for (DbConnectionConfig config : configs) if (config.id.equals(id)) return config;
        return null;
    }

}
