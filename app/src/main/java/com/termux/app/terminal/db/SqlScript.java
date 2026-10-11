package com.termux.app.terminal.db;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Splits a script into its statements at the `;` outside strings and comments. */
public final class SqlScript {

    /** A statement of a script. */
    public static final class Statement {
        /** The range of the statement in the script, without the white space around and the `;`. */
        public final int start;
        public final int end;
        @NonNull public final String text;

        Statement(int start, int end, @NonNull String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    private SqlScript() {}

    /** The statements of a script, skipping the ones with only comments. */
    @NonNull
    public static List<Statement> split(@NonNull String script, @NonNull DbType type) {
        List<Statement> statements = new ArrayList<>();
        int start = -1;
        int end = -1;
        boolean hasCode = false;
        for (SqlLexer.Token token : SqlLexer.tokenize(script, type)) {
            if (token.type == SqlLexer.SEMICOLON) {
                if (hasCode) statements.add(new Statement(start, end, script.substring(start, end)));
                start = -1;
                hasCode = false;
                continue;
            }
            if (token.type == SqlLexer.COMMENT) {
                // Comments before a statement are left out of it.
                if (start >= 0) end = token.end;
                continue;
            }
            if (start < 0) start = token.start;
            end = token.end;
            hasCode = true;
        }
        if (hasCode) statements.add(new Statement(start, end, script.substring(start, end)));
        return statements;
    }

    /**
     * The statement at a cursor position: the one it is in, or between statements, the one before
     * it on its line, or the next one if it is on an empty line or there is none before.
     */
    @Nullable
    public static Statement at(@NonNull String script, int cursor, @NonNull DbType type) {
        List<Statement> statements = split(script, type);
        Statement before = null;
        for (Statement statement : statements) {
            if (cursor < statement.start) {
                if (before == null || isBlankLine(script, cursor)) return statement;
                return before;
            }
            if (cursor <= statement.end) return statement;
            before = statement;
        }
        return before;
    }

    /** Whether the line of the cursor has only white space. */
    private static boolean isBlankLine(@NonNull String script, int cursor) {
        int lineStart = script.lastIndexOf('\n', Math.max(0, cursor - 1)) + 1;
        if (cursor > 0 && cursor <= script.length() && script.charAt(cursor - 1) == '\n') lineStart = cursor;
        int lineEnd = script.indexOf('\n', cursor);
        if (lineEnd < 0) lineEnd = script.length();
        return script.substring(lineStart, lineEnd).trim().isEmpty();
    }

}
