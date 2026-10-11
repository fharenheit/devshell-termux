package com.termux.app.terminal.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** The objects of a database schema, as listed by {@link DbSession}. */
public final class DbObjects {

    private DbObjects() {}

    /** A table or view. */
    public static final class Table {
        /** The schema, or the database in MySQL, or {@code null} if none. */
        @Nullable public final String schema;
        @NonNull public final String name;
        /** Like `TABLE` or `VIEW`. */
        @NonNull public final String type;

        public Table(@Nullable String schema, @NonNull String name, @NonNull String type) {
            this.schema = schema;
            this.name = name;
            this.type = type;
        }

        public boolean isView() {
            return type.toUpperCase(java.util.Locale.ROOT).contains("VIEW");
        }
    }

    /** A column of a table. */
    public static final class Column {
        @NonNull public final String name;
        /** The full type, like `varchar(20)` or `numeric(10,2)`. */
        @NonNull public final String type;
        public final boolean nullable;
        /** The default expression, or {@code null}. */
        @Nullable public final String defaultValue;
        /** The position in the primary key starting at 1, or 0 if not in it. */
        public final int primaryKey;

        public Column(@NonNull String name, @NonNull String type, boolean nullable, @Nullable String defaultValue, int primaryKey) {
            this.name = name;
            this.type = type;
            this.nullable = nullable;
            this.defaultValue = defaultValue;
            this.primaryKey = primaryKey;
        }
    }

}
