package com.termux.app.terminal.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** A kind of database the client connects to. */
public enum DbType {

    POSTGRESQL("postgresql", "PostgreSQL", 5432, "org.postgresql.Driver"),
    /** MySQL and MariaDB, with the MariaDB driver which supports both. */
    MARIADB("mariadb", "MySQL / MariaDB", 3306, "org.mariadb.jdbc.Driver"),
    /** A database file on the device, with the SQLite of Android. */
    SQLITE("sqlite", "SQLite", 0, null);

    /** The name saved in the connection settings. */
    @NonNull public final String id;
    @NonNull public final String label;
    public final int defaultPort;
    @Nullable public final String driverClass;

    DbType(@NonNull String id, @NonNull String label, int defaultPort, @Nullable String driverClass) {
        this.id = id;
        this.label = label;
        this.defaultPort = defaultPort;
        this.driverClass = driverClass;
    }

    @NonNull
    public static DbType fromId(@Nullable String id) {
        for (DbType type : values()) if (type.id.equals(id)) return type;
        return POSTGRESQL;
    }

    /** Whether it connects over the network, unlike SQLite. */
    public boolean isNetwork() {
        return this != SQLITE;
    }

    /** Whether `#` starts a comment and `\` escapes in strings, like in MySQL. */
    public boolean isMysqlSyntax() {
        return this == MARIADB;
    }

    /** Whether `$tag$ ... $tag$` quotes a string, like in PostgreSQL functions. */
    public boolean hasDollarQuotes() {
        return this == POSTGRESQL;
    }

    /** Quote an identifier, like a table or column name. */
    @NonNull
    public String quote(@NonNull String identifier) {
        if (this == MARIADB) return "`" + identifier.replace("`", "``") + "`";
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** Quote a schema and a name, or the name alone if there is no schema. */
    @NonNull
    public String quote(@Nullable String schema, @NonNull String name) {
        return schema == null || schema.isEmpty() ? quote(name) : quote(schema) + "." + quote(name);
    }

}
