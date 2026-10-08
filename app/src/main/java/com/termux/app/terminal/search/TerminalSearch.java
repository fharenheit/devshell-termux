package com.termux.app.terminal.search;

import androidx.annotation.NonNull;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.WcWidth;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds text in the terminal output: the screen and the rows scrolled above it. Rows that a line
 * wrapped over are searched as one line, and each match knows the rows and columns it covers,
 * with wide characters like Korean taking two columns.
 */
public final class TerminalSearch {

    /** A match, from a column of a row to before a column of a row. */
    public static final class Match {
        public final int startRow;
        public final int startColumn;
        public final int endRow;
        /** The column after the last one of the match, on {@link #endRow}. */
        public final int endColumn;

        Match(int startRow, int startColumn, int endRow, int endColumn) {
            this.startRow = startRow;
            this.startColumn = startColumn;
            this.endRow = endRow;
            this.endColumn = endColumn;
        }

        /** Compare by where matches start. */
        public int compareTo(@NonNull Match other) {
            if (startRow != other.startRow) return Integer.compare(startRow, other.startRow);
            return Integer.compare(startColumn, other.startColumn);
        }

        public boolean sameStart(@NonNull Match other) {
            return startRow == other.startRow && startColumn == other.startColumn;
        }

        @NonNull
        @Override
        public String toString() {
            return startRow + ":" + startColumn + "-" + endRow + ":" + endColumn;
        }
    }

    /** The text of rows, with the row, column and width of each of its chars. */
    private static final class Text {
        final StringBuilder text = new StringBuilder();
        int[] rows = new int[256];
        int[] columns = new int[256];
        int[] widths = new int[256];

        void append(char c, int row, int column, int width) {
            int index = text.length();
            if (index == rows.length) {
                int size = rows.length * 2;
                rows = java.util.Arrays.copyOf(rows, size);
                columns = java.util.Arrays.copyOf(columns, size);
                widths = java.util.Arrays.copyOf(widths, size);
            }
            text.append(c);
            rows[index] = row;
            columns[index] = column;
            widths[index] = width;
        }
    }

    private TerminalSearch() {}

    /** Find all matches in the screen and the rows above it, from the oldest. */
    @NonNull
    public static List<Match> findAll(@NonNull TerminalEmulator emulator, @NonNull String query) {
        TerminalBuffer screen = emulator.getScreen();
        return find(screen, emulator.mColumns, -screen.getActiveTranscriptRows(), emulator.mRows - 1, query);
    }

    /** Find the matches on {@code rows} rows from {@code topRow}, including lines that wrap into or out of them. */
    @NonNull
    public static List<Match> findInRows(@NonNull TerminalEmulator emulator, int topRow, int rows, @NonNull String query) {
        TerminalBuffer screen = emulator.getScreen();
        int first = Math.max(-screen.getActiveTranscriptRows(), topRow);
        int last = Math.min(emulator.mRows - 1, topRow + rows - 1);
        while (first > -screen.getActiveTranscriptRows() && screen.getLineWrap(first - 1)) first--;
        while (last < emulator.mRows - 1 && screen.getLineWrap(last)) last++;
        return find(screen, emulator.mColumns, first, last, query);
    }

    @NonNull
    static List<Match> find(@NonNull TerminalBuffer screen, int columns, int firstRow, int lastRow, @NonNull String query) {
        List<Match> matches = new ArrayList<>();
        if (query.isEmpty() || firstRow > lastRow) return matches;
        Text text = getText(screen, columns, firstRow, lastRow);
        boolean ignoreCase = isIgnoreCase(query);
        String haystack = ignoreCase ? toLowerCase(text.text) : text.text.toString();
        String needle = ignoreCase ? toLowerCase(query) : query;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            int last = index + needle.length() - 1;
            if (text.rows[index] != Integer.MIN_VALUE && text.rows[last] != Integer.MIN_VALUE) {
                matches.add(new Match(text.rows[index], text.columns[index], text.rows[last],
                    text.columns[last] + Math.max(1, text.widths[last])));
            }
            index = haystack.indexOf(needle, index + needle.length());
        }
        return matches;
    }

    /** Ignore case unless the query has capitals, like the smart case of editors. */
    static boolean isIgnoreCase(@NonNull String query) {
        return query.equals(toLowerCase(query));
    }

    /** Lower case each char, which keeps the length unlike {@link String#toLowerCase()}. */
    @NonNull
    private static String toLowerCase(@NonNull CharSequence text) {
        char[] chars = new char[text.length()];
        for (int i = 0; i < chars.length; i++) chars[i] = Character.toLowerCase(text.charAt(i));
        return new String(chars);
    }

    @NonNull
    private static Text getText(@NonNull TerminalBuffer screen, int columns, int firstRow, int lastRow) {
        Text text = new Text();
        for (int row = firstRow; row <= lastRow; row++) {
            TerminalRow line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            char[] chars = line.mText;
            int used = line.getSpaceUsed();
            int column = 0;
            int i = 0;
            while (i < used && column < columns) {
                char c = chars[i];
                boolean pair = Character.isHighSurrogate(c) && i + 1 < used;
                int codePoint = pair ? Character.toCodePoint(c, chars[i + 1]) : c;
                int width = WcWidth.width(codePoint);
                // Combining characters belong to the column before them.
                int charColumn = width > 0 ? column : Math.max(0, column - 1);
                text.append(c, row, charColumn, Math.max(0, width));
                if (pair) text.append(chars[i + 1], row, charColumn, Math.max(0, width));
                if (width > 0) column += width;
                i += pair ? 2 : 1;
            }
            // A line that does not wrap into the next row ends here.
            if (row == lastRow || !screen.getLineWrap(row)) text.append('\n', Integer.MIN_VALUE, -1, 0);
        }
        return text;
    }

}
