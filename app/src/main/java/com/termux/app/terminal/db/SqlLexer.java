package com.termux.app.terminal.db;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits SQL into tokens, knowing the strings, quoted names and comments of each database, so
 * that a `;` or a keyword inside them is not taken for one: `'...'` strings, `"..."` names,
 * `--` and `/* *&#47;` comments, and `` `...` `` names, `#` comments and `\` escapes of MySQL, and
 * `$tag$ ... $tag$` strings of PostgreSQL.
 */
public final class SqlLexer {

    public static final int WORD = 0;
    public static final int STRING = 1;
    /** A quoted name, like `"Order"` or `` `order` ``. */
    public static final int QUOTED_NAME = 2;
    public static final int COMMENT = 3;
    public static final int SEMICOLON = 4;
    public static final int OPEN_PAREN = 5;
    public static final int CLOSE_PAREN = 6;
    /** Any other character, like an operator or a `,`. */
    public static final int SYMBOL = 7;
    public static final int NUMBER = 8;

    /** A token of SQL. */
    public static final class Token {
        public final int type;
        public final int start;
        public final int end;
        /** The depth of parentheses it is in, 0 at the top level. */
        public final int depth;
        @NonNull public final String text;

        Token(int type, int start, int end, int depth, @NonNull String text) {
            this.type = type;
            this.start = start;
            this.end = end;
            this.depth = depth;
            this.text = text;
        }

        /** The word in upper case, for comparing with keywords. */
        @NonNull
        public String upper() {
            return text.toUpperCase(Locale.ROOT);
        }

        @NonNull
        @Override
        public String toString() {
            return type + ":" + text;
        }
    }

    private SqlLexer() {}

    /** The tokens of the text, without white space. An unclosed string or comment runs to the end. */
    @NonNull
    public static List<Token> tokenize(@NonNull String sql, @NonNull DbType type) {
        List<Token> tokens = new ArrayList<>();
        int length = sql.length();
        int depth = 0;
        int i = 0;
        while (i < length) {
            char c = sql.charAt(i);
            int start = i;
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '-' && i + 1 < length && sql.charAt(i + 1) == '-' || c == '#' && type.isMysqlSyntax()) {
                while (i < length && sql.charAt(i) != '\n') i++;
                tokens.add(new Token(COMMENT, start, i, depth, sql.substring(start, i)));
            } else if (c == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                int close = sql.indexOf("*/", i + 2);
                i = close < 0 ? length : close + 2;
                tokens.add(new Token(COMMENT, start, i, depth, sql.substring(start, i)));
            } else if (c == '\'') {
                i = skipQuoted(sql, i, '\'', type.isMysqlSyntax());
                tokens.add(new Token(STRING, start, i, depth, sql.substring(start, i)));
            } else if (c == '"') {
                i = skipQuoted(sql, i, '"', type.isMysqlSyntax());
                tokens.add(new Token(type.isMysqlSyntax() ? STRING : QUOTED_NAME, start, i, depth, sql.substring(start, i)));
            } else if (c == '`' && type.isMysqlSyntax()) {
                i = skipQuoted(sql, i, '`', false);
                tokens.add(new Token(QUOTED_NAME, start, i, depth, sql.substring(start, i)));
            } else if (c == '$' && type.hasDollarQuotes() && dollarTagEnd(sql, i) > 0) {
                int tagEnd = dollarTagEnd(sql, i);
                String tag = sql.substring(i, tagEnd);
                int close = sql.indexOf(tag, tagEnd);
                i = close < 0 ? length : close + tag.length();
                tokens.add(new Token(STRING, start, i, depth, sql.substring(start, i)));
            } else if (c == ';') {
                i++;
                tokens.add(new Token(SEMICOLON, start, i, depth, ";"));
            } else if (c == '(') {
                i++;
                tokens.add(new Token(OPEN_PAREN, start, i, depth, "("));
                depth++;
            } else if (c == ')') {
                i++;
                depth = Math.max(0, depth - 1);
                tokens.add(new Token(CLOSE_PAREN, start, i, depth, ")"));
            } else if (Character.isLetter(c) || c == '_') {
                while (i < length && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_' || sql.charAt(i) == '$')) i++;
                tokens.add(new Token(WORD, start, i, depth, sql.substring(start, i)));
            } else if (Character.isDigit(c)) {
                while (i < length && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '.')) i++;
                tokens.add(new Token(NUMBER, start, i, depth, sql.substring(start, i)));
            } else {
                i++;
                tokens.add(new Token(SYMBOL, start, i, depth, String.valueOf(c)));
            }
        }
        return tokens;
    }

    /** The end of a quoted string or name starting at {@code start}, where a doubled quote is a quote. */
    private static int skipQuoted(@NonNull String sql, int start, char quote, boolean backslashEscapes) {
        int i = start + 1;
        int length = sql.length();
        while (i < length) {
            char c = sql.charAt(i);
            if (backslashEscapes && c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < length && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return length;
    }

    /** The end of a `$tag$` at {@code start}, or -1 if it is not one, like `$1`. */
    private static int dollarTagEnd(@NonNull String sql, int start) {
        int i = start + 1;
        int length = sql.length();
        if (i < length && Character.isDigit(sql.charAt(i))) return -1;
        while (i < length && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) i++;
        return i < length && sql.charAt(i) == '$' ? i + 1 : -1;
    }

}
