package com.termux.app.terminal.db;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Puts the main clauses of statements on lines of their own, like {@code FROM} and
 * {@code WHERE}, with the conditions of {@code WHERE} indented. Other white space becomes one
 * space, and the text of tokens, like strings and comments, is kept as it is.
 */
public final class SqlFormatter {

    private SqlFormatter() {}

    /** Words that start a statement, and whose clauses are put on lines at their depth. */
    private static final Set<String> STATEMENTS = new HashSet<>(Arrays.asList(
        "SELECT", "INSERT", "UPDATE", "DELETE", "WITH", "MERGE", "REPLACE"));
    private static final Set<String> CLAUSES = new HashSet<>(Arrays.asList(
        "SELECT", "FROM", "WHERE", "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET", "UNION", "EXCEPT", "INTERSECT",
        "JOIN", "LEFT", "RIGHT", "INNER", "FULL", "CROSS", "NATURAL", "SET", "VALUES", "RETURNING", "WINDOW", "ON"));
    /** Words before JOIN, after which JOIN stays on the line. */
    private static final Set<String> JOIN_PREFIXES = new HashSet<>(Arrays.asList(
        "LEFT", "RIGHT", "INNER", "FULL", "CROSS", "NATURAL", "OUTER"));
    /** Clauses after which the next clause word stays on the line, like the ALL of UNION ALL. */
    private static final Set<String> KEEP_AFTER = new HashSet<>(Arrays.asList("UNION", "EXCEPT", "INTERSECT"));

    @NonNull
    public static String format(@NonNull String sql, @NonNull DbType type) {
        List<SqlLexer.Token> tokens = SqlLexer.tokenize(sql, type);
        StringBuilder out = new StringBuilder();
        // The depths of the statements seen, as bits, where clauses are put on lines.
        long statementDepths = 0;
        // The depths in a WHERE, HAVING or ON clause, where AND and OR start lines.
        long whereDepths = 0;
        boolean inBetween = false;
        SqlLexer.Token previous = null;
        String previousWord = "";
        for (SqlLexer.Token token : tokens) {
            int depth = Math.min(token.depth, 62);
            if (token.type == SqlLexer.CLOSE_PAREN) {
                statementDepths &= (1L << (depth + 1)) - 1;
                whereDepths &= (1L << (depth + 1)) - 1;
            }
            String upper = token.type == SqlLexer.WORD ? token.upper() : "";
            boolean statementDepth = (statementDepths & (1L << depth)) != 0;
            boolean newLine = false;
            String indent = indent(depth);
            if (token.type == SqlLexer.WORD) {
                if (STATEMENTS.contains(upper) && !(upper.equals("SELECT") && statementDepth)) {
                    newLine = previous != null && previous.type != SqlLexer.OPEN_PAREN && !KEEP_AFTER.contains(previousWord);
                    statementDepths |= 1L << depth;
                    whereDepths &= ~(1L << depth);
                } else if (statementDepth && CLAUSES.contains(upper)) {
                    boolean afterJoinPrefix = upper.equals("JOIN") && JOIN_PREFIXES.contains(previousWord);
                    boolean secondWord = JOIN_PREFIXES.contains(upper) && JOIN_PREFIXES.contains(previousWord);
                    newLine = !afterJoinPrefix && !secondWord && !KEEP_AFTER.contains(previousWord);
                    if (upper.equals("ON")) indent += "  ";
                    if (upper.equals("SELECT") && previous != null && previous.type == SqlLexer.OPEN_PAREN) newLine = false;
                    if (upper.equals("WHERE") || upper.equals("HAVING") || upper.equals("ON")) whereDepths |= 1L << depth;
                    else whereDepths &= ~(1L << depth);
                } else if (statementDepth && (whereDepths & (1L << depth)) != 0 && (upper.equals("AND") || upper.equals("OR"))) {
                    if (inBetween && upper.equals("AND")) inBetween = false;
                    else {
                        newLine = true;
                        indent += "  ";
                    }
                }
                if (upper.equals("BETWEEN")) inBetween = true;
            }
            if (previous != null) {
                if (previous.type == SqlLexer.SEMICOLON) {
                    out.append("\n\n");
                } else if (newLine) {
                    trimEnd(out);
                    out.append('\n').append(indent);
                } else if (previous.type == SqlLexer.COMMENT && isLineComment(previous)) {
                    out.append('\n').append(indent(depth));
                } else if (previous.end != token.start || previous.type == SqlLexer.SYMBOL && previous.text.equals(",")) {
                    boolean noSpace = token.type == SqlLexer.SEMICOLON || token.type == SqlLexer.CLOSE_PAREN
                        || previous.type == SqlLexer.OPEN_PAREN || token.type == SqlLexer.SYMBOL && token.text.equals(",");
                    if (!noSpace) out.append(' ');
                }
            }
            out.append(token.text);
            if (token.type == SqlLexer.SEMICOLON) {
                statementDepths = 0;
                whereDepths = 0;
            }
            previous = token;
            if (token.type == SqlLexer.WORD) previousWord = upper;
            else if (token.type != SqlLexer.COMMENT) previousWord = "";
        }
        return out.toString();
    }

    private static boolean isLineComment(@NonNull SqlLexer.Token token) {
        return token.text.startsWith("--") || token.text.startsWith("#");
    }

    @NonNull
    private static String indent(int depth) {
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < depth; i++) indent.append("  ");
        return indent.toString();
    }

    private static void trimEnd(@NonNull StringBuilder out) {
        int length = out.length();
        while (length > 0 && out.charAt(length - 1) == ' ') length--;
        out.setLength(length);
    }

}
