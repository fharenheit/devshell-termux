package com.termux.app.terminal.db;

import androidx.annotation.NonNull;

import com.termux.R;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Tells statements that change data from ones that read, and finds the ones to confirm before running. */
public final class SqlSafety {

    private static final Set<String> WRITE_WORDS = new HashSet<>(Arrays.asList(
        "INSERT", "UPDATE", "DELETE", "MERGE", "REPLACE", "UPSERT", "CREATE", "ALTER", "DROP", "TRUNCATE",
        "GRANT", "REVOKE", "RENAME", "COPY", "LOAD", "CALL", "DO", "VACUUM", "REINDEX", "CLUSTER", "COMMENT",
        "LOCK", "REFRESH", "IMPORT", "ATTACH", "DETACH"));

    private SqlSafety() {}

    /** The first keyword, in upper case, or empty. */
    @NonNull
    public static String firstKeyword(@NonNull String sql, @NonNull DbType type) {
        for (SqlLexer.Token token : SqlLexer.tokenize(sql, type)) {
            if (token.type == SqlLexer.WORD) return token.upper();
            if (token.type != SqlLexer.COMMENT && token.type != SqlLexer.OPEN_PAREN) return "";
        }
        return "";
    }

    /** Statements that only read or control the session or transaction. */
    private static final Set<String> SESSION_KEYWORDS = new HashSet<>(Arrays.asList(
        "SHOW", "DESCRIBE", "DESC", "HELP", "USE", "SET", "BEGIN", "START", "COMMIT", "ROLLBACK", "END",
        "SAVEPOINT", "RELEASE", "CHECKSUM", "ANALYZE"));

    /** Whether the statement may change data or the schema, which read-only connections refuse. */
    public static boolean isWrite(@NonNull String sql, @NonNull DbType type) {
        String first = firstKeyword(sql, type);
        if (first.isEmpty() || SESSION_KEYWORDS.contains(first)) return false;
        List<SqlLexer.Token> tokens = SqlLexer.tokenize(sql, type);
        switch (first) {
            case "PRAGMA":
                // PRAGMA name = value sets, PRAGMA name reads.
                for (SqlLexer.Token token : tokens)
                    if (token.type == SqlLexer.SYMBOL && token.text.equals("=")) return true;
                return false;
            case "EXPLAIN":
                // EXPLAIN ANALYZE runs the statement.
                boolean analyze = false;
                for (SqlLexer.Token token : tokens)
                    if (token.type == SqlLexer.WORD && token.upper().equals("ANALYZE")) analyze = true;
                return analyze && containsWriteWord(tokens, false);
            case "SELECT":
            case "VALUES":
            case "TABLE":
            case "WITH":
                // Like WITH ... INSERT, or SELECT ... INTO a new table.
                return containsWriteWord(tokens, true);
            default:
                return true;
        }
    }

    private static boolean containsWriteWord(@NonNull List<SqlLexer.Token> tokens, boolean into) {
        for (int i = 0; i < tokens.size(); i++) {
            SqlLexer.Token token = tokens.get(i);
            if (token.type != SqlLexer.WORD) continue;
            String word = token.upper();
            // SELECT ... FOR UPDATE only locks rows.
            if (word.equals("UPDATE") && i > 0 && tokens.get(i - 1).upper().equals("FOR")) continue;
            if (WRITE_WORDS.contains(word) || into && word.equals("INTO")) return true;
        }
        return false;
    }

    /**
     * The resource of the warning to confirm before running the statement, or 0: dropping or
     * truncating, altering, or updating or deleting without a WHERE, which changes all the rows.
     */
    public static int getWarning(@NonNull String sql, @NonNull DbType type) {
        List<SqlLexer.Token> tokens = SqlLexer.tokenize(sql, type);
        String first = firstKeyword(sql, type);
        switch (first) {
            case "DROP":
                return R.string.msg_db_confirm_drop;
            case "TRUNCATE":
                return R.string.msg_db_confirm_truncate;
            case "ALTER":
                return R.string.msg_db_confirm_alter;
            case "DELETE":
            case "UPDATE":
                for (SqlLexer.Token token : tokens)
                    if (token.type == SqlLexer.WORD && token.depth == 0 && token.upper().equals("WHERE")) return 0;
                return first.equals("DELETE") ? R.string.msg_db_confirm_delete_all : R.string.msg_db_confirm_update_all;
            default:
                return 0;
        }
    }

}
