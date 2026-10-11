package com.termux.app.terminal.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** A session of PostgreSQL or MySQL/MariaDB, with the JDBC driver of the database. */
public final class JdbcSession extends DbSession {

    private final Connection mConnection;
    @Nullable private volatile Statement mRunning;
    private boolean mChanged;

    private JdbcSession(@NonNull DbConnectionConfig config, @NonNull Connection connection) {
        super(config);
        mConnection = connection;
    }

    /**
     * Connect to the server of a connection, at {@code host} and {@code port}, which differ from
     * the ones of the connection when connecting through an SSH tunnel.
     */
    @NonNull
    public static JdbcSession connect(@NonNull DbConnectionConfig config, @Nullable String password,
                                      @NonNull String host, int port, int timeoutSeconds) throws SQLException {
        String url = getUrl(config, host, port);
        Properties properties = getProperties(config, password, timeoutSeconds);
        Driver driver;
        try {
            driver = (Driver) Class.forName(config.type.driverClass).newInstance();
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new SQLException("No driver for " + config.type.label, e);
        }
        Connection connection = driver.connect(url, properties);
        if (connection == null) throw new SQLException("The driver does not accept " + url);
        if (config.readOnly) {
            try {
                connection.setReadOnly(true);
            } catch (SQLException ignored) {
                // The statements are checked anyway.
            }
        }
        return new JdbcSession(config, connection);
    }

    /** The JDBC URL, like `jdbc:postgresql://host:5432/shop`. */
    @NonNull
    static String getUrl(@NonNull DbConnectionConfig config, @NonNull String host, int port) {
        String address = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        String scheme = config.type == DbType.POSTGRESQL ? "jdbc:postgresql://" : "jdbc:mariadb://";
        return scheme + address + ":" + port + "/" + config.database;
    }

    /** The driver properties: the user and password, timeouts, SSL, and the parameters of the connection, which win. */
    @NonNull
    static Properties getProperties(@NonNull DbConnectionConfig config, @Nullable String password, int timeoutSeconds) {
        Map<String, String> map = new LinkedHashMap<>();
        if (!config.user.isEmpty()) map.put("user", config.user);
        if (password != null) map.put("password", password);
        if (config.type == DbType.POSTGRESQL) {
            map.put("connectTimeout", String.valueOf(timeoutSeconds));
            map.put("ApplicationName", "DevShell");
            if (config.ssl == DbConnectionConfig.SSL_REQUIRE) {
                map.put("ssl", "true");
                map.put("sslmode", "require");
            } else if (config.ssl == DbConnectionConfig.SSL_VERIFY) {
                map.put("ssl", "true");
                map.put("sslmode", "verify-full");
                // The certificates trusted by Android, instead of the files of libpq.
                map.put("sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory");
                map.put("sslhostnameverifier", AndroidHostnameVerifier.class.getName());
            } else {
                map.put("sslmode", "disable");
            }
        } else {
            map.put("connectTimeout", String.valueOf(timeoutSeconds * 1000));
            // MySQL 8 sends its key to check the password without SSL.
            map.put("allowPublicKeyRetrieval", "true");
            if (config.ssl == DbConnectionConfig.SSL_REQUIRE) {
                map.put("useSsl", "true");
                map.put("trustServerCertificate", "true");
            } else if (config.ssl == DbConnectionConfig.SSL_VERIFY) {
                map.put("useSsl", "true");
                // Its host name check uses javax.naming.ldap, which Android does not have.
                map.put("disableSslHostnameVerification", "true");
            }
        }
        map.putAll(config.getParameters());
        Properties properties = new Properties();
        properties.putAll(map);
        return properties;
    }

    @NonNull
    @Override
    public String getServerVersion() {
        try {
            DatabaseMetaData metaData = mConnection.getMetaData();
            return metaData.getDatabaseProductName() + " " + metaData.getDatabaseProductVersion();
        } catch (SQLException e) {
            return getType().label;
        }
    }

    @NonNull
    @Override
    public QueryResult execute(@NonNull String sql, int maxRows, int timeoutSeconds) throws SQLException {
        long startTime = System.nanoTime();
        try (Statement statement = mConnection.createStatement()) {
            mRunning = statement;
            if (timeoutSeconds > 0) statement.setQueryTimeout(timeoutSeconds);
            // One more row than shown tells whether there are more.
            if (maxRows > 0) statement.setMaxRows(maxRows + 1);
            boolean hasResultSet = statement.execute(sql);
            QueryResult result;
            if (hasResultSet) {
                try (ResultSet resultSet = statement.getResultSet()) {
                    result = readResultSet(sql, resultSet, maxRows);
                }
            } else {
                result = QueryResult.ofUpdate(sql, statement.getUpdateCount());
                if (!mConnection.getAutoCommit()) mChanged = true;
            }
            result.message = getWarnings(statement.getWarnings());
            result.elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
            trackTransaction(sql);
            return result;
        } finally {
            mRunning = null;
        }
    }

    /** Forget the changes after a COMMIT or ROLLBACK statement. */
    private void trackTransaction(@NonNull String sql) {
        String keyword = SqlSafety.firstKeyword(sql, getType());
        if (keyword.equals("COMMIT") || keyword.equals("ROLLBACK") || keyword.equals("END")) mChanged = false;
    }

    @NonNull
    private QueryResult readResultSet(@NonNull String sql, @NonNull ResultSet resultSet, int maxRows) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int count = metaData.getColumnCount();
        List<QueryResult.Column> columns = new ArrayList<>(count);
        boolean[] binary = new boolean[count];
        for (int i = 0; i < count; i++) {
            int sqlType = metaData.getColumnType(i + 1);
            String typeName = metaData.getColumnTypeName(i + 1);
            columns.add(new QueryResult.Column(metaData.getColumnLabel(i + 1), typeName != null ? typeName : "", sqlType));
            binary[i] = sqlType == Types.BINARY || sqlType == Types.VARBINARY || sqlType == Types.LONGVARBINARY || sqlType == Types.BLOB;
        }
        List<Object[]> rows = new ArrayList<>();
        QueryResult result = new QueryResult(sql, columns, rows, -1);
        long chars = 0;
        while (resultSet.next()) {
            if (maxRows > 0 && rows.size() >= maxRows) {
                result.hasMore = true;
                break;
            }
            Object[] row = new Object[count];
            for (int i = 0; i < count; i++) {
                Object value;
                if (binary[i]) {
                    byte[] bytes = resultSet.getBytes(i + 1);
                    if (bytes != null && bytes.length > MAX_VALUE_CHARS) bytes = java.util.Arrays.copyOf(bytes, MAX_VALUE_CHARS);
                    value = bytes;
                    chars += bytes == null ? 0 : bytes.length * 2L;
                } else {
                    String text = resultSet.getString(i + 1);
                    if (text != null && text.length() > MAX_VALUE_CHARS) text = text.substring(0, MAX_VALUE_CHARS);
                    value = text;
                    chars += text == null ? 0 : text.length();
                }
                row[i] = value;
            }
            rows.add(row);
            if (chars > MAX_RESULT_CHARS) {
                result.truncated = true;
                result.hasMore = true;
                break;
            }
        }
        return result;
    }

    @Override
    public long stream(@NonNull String sql, @NonNull RowSink sink) throws SQLException, java.io.IOException {
        // PostgreSQL fetches rows in parts only in a transaction.
        boolean autoCommit = mConnection.getAutoCommit();
        if (autoCommit && getType() == DbType.POSTGRESQL) mConnection.setAutoCommit(false);
        try (Statement statement = mConnection.createStatement()) {
            mRunning = statement;
            statement.setFetchSize(1000);
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                ResultSetMetaData metaData = resultSet.getMetaData();
                int count = metaData.getColumnCount();
                List<QueryResult.Column> columns = new ArrayList<>(count);
                boolean[] binary = new boolean[count];
                for (int i = 0; i < count; i++) {
                    int sqlType = metaData.getColumnType(i + 1);
                    String typeName = metaData.getColumnTypeName(i + 1);
                    columns.add(new QueryResult.Column(metaData.getColumnLabel(i + 1), typeName != null ? typeName : "", sqlType));
                    binary[i] = sqlType == Types.BINARY || sqlType == Types.VARBINARY || sqlType == Types.LONGVARBINARY || sqlType == Types.BLOB;
                }
                sink.columns(columns);
                Object[] row = new Object[count];
                long rows = 0;
                while (resultSet.next()) {
                    for (int i = 0; i < count; i++) row[i] = binary[i] ? resultSet.getBytes(i + 1) : resultSet.getString(i + 1);
                    rows++;
                    if (!sink.row(row)) break;
                }
                return rows;
            }
        } finally {
            mRunning = null;
            if (autoCommit && getType() == DbType.POSTGRESQL) {
                mConnection.rollback();
                mConnection.setAutoCommit(true);
            }
        }
    }

    @Nullable
    private static String getWarnings(@Nullable SQLWarning warning) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; warning != null && i < 20; i++, warning = warning.getNextWarning()) {
            if (text.length() > 0) text.append('\n');
            text.append(warning.getMessage());
        }
        return text.length() > 0 ? text.toString() : null;
    }

    @Override
    public int executeUpdate(@NonNull String sql, @NonNull List<Object> parameters) throws SQLException {
        try (PreparedStatement statement = mConnection.prepareStatement(sql)) {
            mRunning = statement;
            for (int i = 0; i < parameters.size(); i++) {
                Object value = parameters.get(i);
                if (value == null) statement.setNull(i + 1, Types.NULL);
                else if (value instanceof byte[]) statement.setBytes(i + 1, (byte[]) value);
                else statement.setObject(i + 1, value.toString(), getParameterType());
            }
            int count = statement.executeUpdate();
            if (!mConnection.getAutoCommit()) mChanged = true;
            return count;
        } finally {
            mRunning = null;
        }
    }

    /**
     * Text values are sent as an unknown type in PostgreSQL, so that it converts them to the
     * column type, like a string literal, and as strings in MySQL, which converts them anyway.
     */
    private int getParameterType() {
        return getType() == DbType.POSTGRESQL ? Types.OTHER : Types.VARCHAR;
    }

    @Override
    public void cancel() {
        Statement statement = mRunning;
        if (statement == null) return;
        try {
            statement.cancel();
        } catch (SQLException | RuntimeException ignored) {
        }
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        return mConnection.getAutoCommit();
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        mConnection.setAutoCommit(autoCommit);
        if (autoCommit) mChanged = false;
    }

    @Override
    public void commit() throws SQLException {
        mConnection.commit();
        mChanged = false;
    }

    @Override
    public void rollback() throws SQLException {
        mConnection.rollback();
        mChanged = false;
    }

    @Override
    public boolean isInTransaction() {
        return mChanged;
    }

    @Override
    public boolean isValid() {
        try {
            return mConnection.isValid(5);
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public void close() {
        try {
            mConnection.close();
        } catch (SQLException ignored) {
        }
    }

    // Metadata

    private boolean isMysql() {
        return getType() == DbType.MARIADB;
    }

    @NonNull
    @Override
    public List<String> listSchemas() throws SQLException {
        List<String> schemas = new ArrayList<>();
        DatabaseMetaData metaData = mConnection.getMetaData();
        try (ResultSet resultSet = isMysql() ? metaData.getCatalogs() : metaData.getSchemas()) {
            while (resultSet.next()) schemas.add(resultSet.getString(1));
        }
        // The schemas of the database itself last.
        List<String> system = new ArrayList<>();
        for (String schema : schemas) if (isSystemSchema(schema)) system.add(schema);
        schemas.removeAll(system);
        schemas.addAll(system);
        return schemas;
    }

    static boolean isSystemSchema(@NonNull String schema) {
        String lower = schema.toLowerCase(Locale.ROOT);
        return lower.equals("information_schema") || lower.equals("pg_catalog") || lower.startsWith("pg_toast")
            || lower.startsWith("pg_temp") || lower.equals("mysql") || lower.equals("performance_schema") || lower.equals("sys");
    }

    @Nullable
    @Override
    public String getDefaultSchema() throws SQLException {
        if (isMysql()) return mConnection.getCatalog();
        // Connection.getSchema is JDBC 4.1, which Android does not have.
        try (Statement statement = mConnection.createStatement(); ResultSet resultSet = statement.executeQuery("SELECT current_schema()")) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    @NonNull
    @Override
    public List<DbObjects.Table> listTables(@Nullable String schema) throws SQLException {
        List<DbObjects.Table> tables = new ArrayList<>();
        DatabaseMetaData metaData = mConnection.getMetaData();
        String[] types = isMysql() ? new String[]{"TABLE", "VIEW", "BASE TABLE", "SYSTEM VIEW"}
            : new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW", "FOREIGN TABLE", "PARTITIONED TABLE"};
        try (ResultSet resultSet = isMysql() ? metaData.getTables(schema, null, "%", types) : metaData.getTables(null, schema, "%", types)) {
            while (resultSet.next()) {
                String type = resultSet.getString("TABLE_TYPE");
                tables.add(new DbObjects.Table(schema, resultSet.getString("TABLE_NAME"), type != null ? type : "TABLE"));
            }
        }
        return tables;
    }

    @NonNull
    @Override
    public List<DbObjects.Column> listColumns(@Nullable String schema, @NonNull String table) throws SQLException {
        Map<String, Integer> primaryKey = getPrimaryKey(schema, table);
        List<DbObjects.Column> columns = new ArrayList<>();
        if (getType() == DbType.POSTGRESQL) {
            // format_type gives the full type, like varchar(20), which the JDBC metadata does not.
            String sql = "SELECT a.attname, format_type(a.atttypid, a.atttypmod), NOT a.attnotnull, pg_get_expr(d.adbin, d.adrelid) " +
                "FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum " +
                "WHERE a.attrelid = ?::regclass AND a.attnum > 0 AND NOT a.attisdropped ORDER BY a.attnum";
            try (PreparedStatement statement = mConnection.prepareStatement(sql)) {
                statement.setString(1, getType().quote(schema, table));
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        String name = resultSet.getString(1);
                        Integer position = primaryKey.get(name);
                        columns.add(new DbObjects.Column(name, resultSet.getString(2), resultSet.getBoolean(3),
                            resultSet.getString(4), position != null ? position : 0));
                    }
                }
            }
            return columns;
        }
        String sql = "SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS " +
            "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
        try (PreparedStatement statement = mConnection.prepareStatement(sql)) {
            statement.setString(1, schema != null ? schema : mConnection.getCatalog());
            statement.setString(2, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String name = resultSet.getString(1);
                    Integer position = primaryKey.get(name);
                    columns.add(new DbObjects.Column(name, resultSet.getString(2), "YES".equals(resultSet.getString(3)),
                        resultSet.getString(4), position != null ? position : 0));
                }
            }
        }
        return columns;
    }

    @NonNull
    private Map<String, Integer> getPrimaryKey(@Nullable String schema, @NonNull String table) throws SQLException {
        Map<String, Integer> columns = new LinkedHashMap<>();
        DatabaseMetaData metaData = mConnection.getMetaData();
        try (ResultSet resultSet = isMysql() ? metaData.getPrimaryKeys(schema, null, table) : metaData.getPrimaryKeys(null, schema, table)) {
            while (resultSet.next()) columns.put(resultSet.getString("COLUMN_NAME"), resultSet.getInt("KEY_SEQ"));
        }
        return columns;
    }

    @NonNull
    @Override
    public List<String> listIndexes(@Nullable String schema, @NonNull String table) throws SQLException {
        List<String> indexes = new ArrayList<>();
        if (getType() == DbType.POSTGRESQL) {
            try (PreparedStatement statement = mConnection.prepareStatement(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND tablename = ? ORDER BY indexname")) {
                statement.setString(1, schema != null ? schema : getDefaultSchema());
                statement.setString(2, table);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) indexes.add(resultSet.getString(1));
                }
            }
            return indexes;
        }
        Map<String, StringBuilder> byName = new LinkedHashMap<>();
        Map<String, Boolean> unique = new LinkedHashMap<>();
        try (ResultSet resultSet = mConnection.getMetaData().getIndexInfo(schema, null, table, false, false)) {
            while (resultSet.next()) {
                String name = resultSet.getString("INDEX_NAME");
                String column = resultSet.getString("COLUMN_NAME");
                if (name == null || column == null) continue;
                StringBuilder columns = byName.get(name);
                if (columns == null) {
                    byName.put(name, columns = new StringBuilder(column));
                    unique.put(name, !resultSet.getBoolean("NON_UNIQUE"));
                } else {
                    columns.append(", ").append(column);
                }
            }
        }
        for (Map.Entry<String, StringBuilder> entry : byName.entrySet())
            indexes.add((unique.get(entry.getKey()) ? "UNIQUE " : "") + entry.getKey() + " (" + entry.getValue() + ")");
        return indexes;
    }

    @NonNull
    @Override
    public List<String> listForeignKeys(@Nullable String schema, @NonNull String table) throws SQLException {
        Map<String, String[]> keys = new LinkedHashMap<>();
        DatabaseMetaData metaData = mConnection.getMetaData();
        try (ResultSet resultSet = isMysql() ? metaData.getImportedKeys(schema, null, table) : metaData.getImportedKeys(null, schema, table)) {
            while (resultSet.next()) {
                String name = resultSet.getString("FK_NAME");
                if (name == null) name = resultSet.getString("FKCOLUMN_NAME");
                String[] key = keys.get(name);
                String fkColumn = resultSet.getString("FKCOLUMN_NAME");
                String pkColumn = resultSet.getString("PKCOLUMN_NAME");
                if (key == null) {
                    keys.put(name, new String[]{fkColumn, resultSet.getString("PKTABLE_NAME"), pkColumn});
                } else {
                    key[0] += ", " + fkColumn;
                    key[2] += ", " + pkColumn;
                }
            }
        }
        List<String> descriptions = new ArrayList<>();
        for (String[] key : keys.values()) descriptions.add(key[0] + " → " + key[1] + "(" + key[2] + ")");
        return descriptions;
    }

    @NonNull
    @Override
    public String getDdl(@NonNull DbObjects.Table table) throws SQLException {
        String name = getType().quote(table.schema, table.name);
        if (isMysql()) {
            try (Statement statement = mConnection.createStatement();
                 ResultSet resultSet = statement.executeQuery((table.isView() ? "SHOW CREATE VIEW " : "SHOW CREATE TABLE ") + name)) {
                return resultSet.next() ? resultSet.getString(2) + ";" : "";
            }
        }
        if (table.isView()) {
            try (PreparedStatement statement = mConnection.prepareStatement("SELECT pg_get_viewdef(?::regclass, true)")) {
                statement.setString(1, name);
                try (ResultSet resultSet = statement.executeQuery()) {
                    String keyword = table.type.toUpperCase(Locale.ROOT).contains("MATERIALIZED") ? "MATERIALIZED VIEW" : "VIEW";
                    return resultSet.next() ? "CREATE " + keyword + " " + name + " AS\n" + resultSet.getString(1).trim() : "";
                }
            }
        }
        return getPostgresTableDdl(table, name);
    }

    @NonNull
    private String getPostgresTableDdl(@NonNull DbObjects.Table table, @NonNull String name) throws SQLException {
        List<DbObjects.Column> columns = listColumns(table.schema, table.name);
        StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(name).append(" (");
        List<String> lines = new ArrayList<>();
        List<String> primaryKey = new ArrayList<>();
        for (DbObjects.Column column : columns) {
            StringBuilder line = new StringBuilder(getType().quote(column.name)).append(' ').append(column.type);
            if (column.defaultValue != null) line.append(" DEFAULT ").append(column.defaultValue);
            if (!column.nullable) line.append(" NOT NULL");
            lines.add(line.toString());
        }
        for (DbObjects.Column column : columns) if (column.primaryKey > 0) primaryKey.add(column.name);
        // Constraints other than the columns, like the primary, unique and foreign keys and checks.
        try (PreparedStatement statement = mConnection.prepareStatement(
            "SELECT conname, pg_get_constraintdef(oid, true) FROM pg_constraint WHERE conrelid = ?::regclass ORDER BY contype, conname")) {
            statement.setString(1, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next())
                    lines.add("CONSTRAINT " + getType().quote(resultSet.getString(1)) + " " + resultSet.getString(2));
            }
        }
        for (int i = 0; i < lines.size(); i++) ddl.append(i == 0 ? "\n    " : ",\n    ").append(lines.get(i));
        ddl.append("\n);");
        for (String index : listIndexes(table.schema, table.name)) {
            // The indexes of constraints are created with them.
            if (!index.toUpperCase(Locale.ROOT).contains("UNIQUE INDEX") || !containsConstraintIndex(index, lines))
                ddl.append("\n").append(index).append(";");
        }
        return ddl.toString();
    }

    private static boolean containsConstraintIndex(@NonNull String index, @NonNull List<String> lines) {
        int on = index.indexOf(" INDEX ");
        int end = index.indexOf(" ON ");
        if (on < 0 || end < 0) return false;
        String indexName = index.substring(on + 7, end).trim().replace("\"", "");
        for (String line : lines) if (line.startsWith("CONSTRAINT ") && line.replace("\"", "").startsWith("CONSTRAINT " + indexName + " ")) return true;
        return false;
    }

}
