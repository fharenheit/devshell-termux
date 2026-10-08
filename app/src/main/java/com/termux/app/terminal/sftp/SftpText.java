package com.termux.app.terminal.sftp;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Deciding how to open a remote file, and the text of the editor. Has no Android dependencies. */
public final class SftpText {

    /** Files up to this size can be edited, larger ones are shown read-only up to it. */
    public static final int MAX_EDIT_SIZE = 2 * 1024 * 1024;
    /** Images up to this size are shown. */
    public static final int MAX_IMAGE_SIZE = 32 * 1024 * 1024;

    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>(Arrays.asList(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif"));

    private SftpText() {}

    public static String getExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isImage(String name) {
        return IMAGE_EXTENSIONS.contains(getExtension(name));
    }

    /** Whether the start of a file looks like text: it has no NUL bytes. */
    public static boolean isProbablyText(byte[] content, int length) {
        int checked = Math.min(length, 8192);
        for (int i = 0; i < checked; i++)
            if (content[i] == 0) return false;
        return true;
    }

    /** The decoded text of a file, and how to encode it back. */
    public static final class Text {
        /** The text with `\n` line endings. */
        public final String text;
        /** Whether the file used `\r\n` line endings, which are written back. */
        public final boolean crlf;
        /** Whether the content is valid UTF-8, otherwise it was decoded as ISO-8859-1 and must not be saved. */
        public final boolean utf8;

        Text(String text, boolean crlf, boolean utf8) {
            this.text = text;
            this.crlf = crlf;
            this.utf8 = utf8;
        }
    }

    /**
     * Decode file content for the editor. A file truncated at {@code length} may end in the
     * middle of a UTF-8 sequence, which is dropped.
     */
    public static Text decode(byte[] content, int length, boolean truncated) {
        String text;
        boolean utf8 = true;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content, 0, truncated ? utf8Boundary(content, length) : length))
                .toString();
        } catch (CharacterCodingException e) {
            text = new String(content, 0, length, StandardCharsets.ISO_8859_1);
            utf8 = false;
        }
        // CRLF if every line ending is CRLF, so that files with mixed endings are not changed.
        int lf = 0;
        int crlf = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lf++;
                if (i > 0 && text.charAt(i - 1) == '\r') crlf++;
            }
        }
        boolean isCrlf = lf > 0 && crlf == lf;
        return new Text(isCrlf ? text.replace("\r\n", "\n") : text, isCrlf, utf8);
    }

    /** The length up to the last complete UTF-8 sequence. */
    private static int utf8Boundary(byte[] content, int length) {
        int start = length;
        // Go back over continuation bytes to the start of the last sequence.
        while (start > 0 && start > length - 4 && (content[start - 1] & 0xc0) == 0x80) start--;
        if (start == 0) return length;
        int lead = content[start - 1] & 0xff;
        int needed = lead >= 0xf0 ? 4 : lead >= 0xe0 ? 3 : lead >= 0xc0 ? 2 : 1;
        return length - (start - 1) >= needed ? length : start - 1;
    }

    /** Encode editor text to write back, with the line endings of the file. */
    public static byte[] encode(String text, boolean crlf) {
        String out = crlf ? text.replace("\n", "\r\n") : text;
        ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(out));
        return Arrays.copyOfRange(buffer.array(), buffer.position(), buffer.limit());
    }

    /** A size like `1.2 MB`. */
    public static String formatSize(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, value < 10 ? "%.1f %s" : "%.0f %s", value, units[unit]);
    }

}
