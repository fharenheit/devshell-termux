package com.termux.app.terminal.sftp;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Deciding how to open a remote file, and the text of the editor. Has no Android dependencies. */
public final class SftpText {

    /** Files up to this size can be edited, larger ones are shown read-only up to it. */
    public static final int MAX_EDIT_SIZE = 2 * 1024 * 1024;
    /** Files up to this size can be shown in hex. */
    public static final int MAX_HEX_SIZE = 16 * 1024 * 1024;
    /** Images up to this size are shown. */
    public static final int MAX_IMAGE_SIZE = 32 * 1024 * 1024;

    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>(Arrays.asList(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif"));

    private static final byte[] BOM_UTF_8 = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] BOM_UTF_16LE = {(byte) 0xFF, (byte) 0xFE};
    private static final byte[] BOM_UTF_16BE = {(byte) 0xFE, (byte) 0xFF};

    /** An encoding that files can be opened and saved with. */
    public static final class Encoding {
        /** The name shown in the editor. */
        public final String label;
        public final Charset charset;

        Encoding(String label, Charset charset) {
            this.label = label;
            this.charset = charset;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final List<Encoding> ENCODINGS = new ArrayList<>();
    public static final Encoding UTF_8;
    public static final Encoding ISO_8859_1;
    /** The Korean Windows encoding, a superset of EUC-KR, or EUC-KR if it is not available. */
    public static final Encoding CP949;

    static {
        UTF_8 = addEncoding("UTF-8", "UTF-8");
        addEncoding("UTF-16LE", "UTF-16LE");
        addEncoding("UTF-16BE", "UTF-16BE");
        CP949 = addEncoding("CP949 (EUC-KR)", "x-windows-949", "MS949", "windows-949", "CP949", "EUC-KR");
        addEncoding("Shift_JIS", "Shift_JIS");
        addEncoding("EUC-JP", "EUC-JP");
        addEncoding("GB18030", "GB18030");
        addEncoding("Big5", "Big5");
        addEncoding("Windows-1252", "windows-1252");
        addEncoding("Windows-1251", "windows-1251");
        addEncoding("KOI8-R", "KOI8-R");
        ISO_8859_1 = addEncoding("ISO-8859-1", "ISO-8859-1");
    }

    /** Add the first of the charset names that is supported, if any. */
    private static Encoding addEncoding(String label, String... charsetNames) {
        for (String name : charsetNames) {
            try {
                if (Charset.isSupported(name)) {
                    Encoding encoding = new Encoding(label, Charset.forName(name));
                    ENCODINGS.add(encoding);
                    return encoding;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    private SftpText() {}

    /** The encodings that can be chosen. */
    public static List<Encoding> getEncodings() {
        return new ArrayList<>(ENCODINGS);
    }

    public static String getExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isImage(String name) {
        return IMAGE_EXTENSIONS.contains(getExtension(name));
    }

    /** Whether the start of a file looks like text: it has no NUL bytes. */
    public static boolean isProbablyText(byte[] content, int length) {
        // UTF-16 text has NUL bytes, but starts with a byte order mark.
        if (getBomEncoding(content, length) != null) return true;
        int checked = Math.min(length, 8192);
        for (int i = 0; i < checked; i++)
            if (content[i] == 0) return false;
        return true;
    }

    /** The decoded text of a file, and how to encode it back. */
    public static final class Text {
        /** The text with `\n` line endings if {@link #crlf}. */
        public final String text;
        /** Whether the file used `\r\n` line endings, which are written back. */
        public final boolean crlf;
        public final Encoding encoding;
        /** Whether the file starts with a byte order mark, which is written back. */
        public final boolean bom;
        /**
         * Whether encoding the text gives back the content exactly. If not, some bytes were not
         * valid in the encoding, and saving the text would change them.
         */
        public final boolean lossless;

        Text(String text, boolean crlf, Encoding encoding, boolean bom, boolean lossless) {
            this.text = text;
            this.crlf = crlf;
            this.encoding = encoding;
            this.bom = bom;
            this.lossless = lossless;
        }
    }

    /**
     * Decode file content for the editor, detecting the encoding: from a byte order mark, else
     * UTF-8, CP949 or ISO-8859-1, the first that decodes it without errors. A file truncated at
     * {@code length} may end in the middle of a character, which is dropped.
     */
    public static Text decode(byte[] content, int length, boolean truncated) {
        Encoding bomEncoding = getBomEncoding(content, length);
        if (bomEncoding != null) return decode(content, length, truncated, bomEncoding);
        if (isValidUtf8(content, truncated ? utf8Boundary(content, length) : length)) return decode(content, length, truncated, UTF_8);
        if (CP949 != null) {
            Text korean = decode(content, length, truncated, CP949);
            if (korean.lossless) return korean;
        }
        return decode(content, length, truncated, ISO_8859_1);
    }

    /** Decode file content with an encoding. A byte order mark of the encoding is skipped. */
    public static Text decode(byte[] content, int length, boolean truncated, Encoding encoding) {
        boolean bom = encoding == getBomEncoding(content, length);
        int start = bom ? getBomLength(encoding) : 0;
        int end = truncated && encoding == UTF_8 ? Math.max(start, utf8Boundary(content, length)) : length;
        String text;
        boolean lossless;
        try {
            text = newDecoder(encoding.charset, CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content, start, end - start)).toString();
            // Some encodings decode different bytes to the same characters.
            lossless = !truncated && Arrays.equals(Arrays.copyOfRange(content, start, end), toBytes(encoding.charset, text));
        } catch (CharacterCodingException e) {
            try {
                text = newDecoder(encoding.charset, CodingErrorAction.REPLACE).decode(ByteBuffer.wrap(content, start, end - start)).toString();
            } catch (CharacterCodingException impossible) {
                text = new String(content, start, end - start, encoding.charset);
            }
            lossless = false;
        }
        boolean crlf = isCrlf(text);
        return new Text(crlf ? text.replace("\r\n", "\n") : text, crlf, encoding, bom, lossless);
    }

    private static CharsetDecoder newDecoder(Charset charset, CodingErrorAction action) {
        return charset.newDecoder().onMalformedInput(action).onUnmappableCharacter(action);
    }

    private static byte[] toBytes(Charset charset, String text) throws CharacterCodingException {
        ByteBuffer buffer = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(text));
        return Arrays.copyOfRange(buffer.array(), buffer.position(), buffer.limit());
    }

    private static boolean isValidUtf8(byte[] content, int length) {
        try {
            newDecoder(StandardCharsets.UTF_8, CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content, 0, length));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /** Whether every line ending is CRLF, so that files with mixed endings are not changed. */
    private static boolean isCrlf(String text) {
        int lf = 0;
        int crlf = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lf++;
                if (i > 0 && text.charAt(i - 1) == '\r') crlf++;
            }
        }
        return lf > 0 && crlf == lf;
    }

    private static Encoding getBomEncoding(byte[] content, int length) {
        if (startsWith(content, length, BOM_UTF_8)) return UTF_8;
        if (startsWith(content, length, BOM_UTF_16LE)) return find("UTF-16LE");
        if (startsWith(content, length, BOM_UTF_16BE)) return find("UTF-16BE");
        return null;
    }

    private static byte[] getBom(Encoding encoding) {
        if (encoding == UTF_8) return BOM_UTF_8;
        if (encoding != null && encoding.label.equals("UTF-16LE")) return BOM_UTF_16LE;
        if (encoding != null && encoding.label.equals("UTF-16BE")) return BOM_UTF_16BE;
        return new byte[0];
    }

    private static int getBomLength(Encoding encoding) {
        return getBom(encoding).length;
    }

    /** Whether files in an encoding can start with a byte order mark. */
    public static boolean hasBom(Encoding encoding) {
        return getBomLength(encoding) > 0;
    }

    public static Encoding find(String label) {
        for (Encoding encoding : ENCODINGS)
            if (encoding.label.equals(label)) return encoding;
        return null;
    }

    private static boolean startsWith(byte[] content, int length, byte[] prefix) {
        if (length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++)
            if (content[i] != prefix[i]) return false;
        return true;
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

    /**
     * Encode editor text to write back, with the line endings, encoding and byte order mark of the
     * file. Throws if a character of the text can't be encoded.
     */
    public static byte[] encode(String text, boolean crlf, Encoding encoding, boolean bom) throws CharacterCodingException {
        byte[] bytes = toBytes(encoding.charset, crlf ? text.replace("\n", "\r\n") : text);
        if (!bom || !hasBom(encoding)) return bytes;
        byte[] mark = getBom(encoding);
        byte[] result = new byte[mark.length + bytes.length];
        System.arraycopy(mark, 0, result, 0, mark.length);
        System.arraycopy(bytes, 0, result, mark.length, bytes.length);
        return result;
    }

    /** Encode editor text as UTF-8. */
    public static byte[] encode(String text, boolean crlf) {
        try {
            return encode(text, crlf, UTF_8, false);
        } catch (CharacterCodingException e) {
            // Unpaired surrogates, which can't be typed.
            return (crlf ? text.replace("\n", "\r\n") : text).getBytes(StandardCharsets.UTF_8);
        }
    }

    /** The number of characters of a hex line of {@link #formatHexLine}. */
    public static int getHexLineLength(int bytesPerLine) {
        return 8 + 2 + bytesPerLine * 3 + (bytesPerLine > 8 ? 1 : 0) + 1 + bytesPerLine;
    }

    /**
     * A line of a hex dump, like `00000010  48 65 6c 6c 6f 0a ...  Hello.`, with the offset, the
     * bytes in hex, and the bytes as ASCII characters, or `.` for others.
     */
    public static String formatHexLine(byte[] content, int length, int offset, int bytesPerLine) {
        StringBuilder line = new StringBuilder(getHexLineLength(bytesPerLine));
        String address = Integer.toHexString(offset);
        for (int i = address.length(); i < 8; i++) line.append('0');
        line.append(address).append("  ");
        for (int i = 0; i < bytesPerLine; i++) {
            if (i == 8) line.append(' ');
            if (offset + i < length) {
                int b = content[offset + i] & 0xff;
                line.append(Character.forDigit(b >> 4, 16)).append(Character.forDigit(b & 0xf, 16)).append(' ');
            } else {
                line.append("   ");
            }
        }
        line.append(' ');
        for (int i = 0; i < bytesPerLine && offset + i < length; i++) {
            int b = content[offset + i] & 0xff;
            line.append(b >= 0x20 && b < 0x7f ? (char) b : '.');
        }
        return line.toString();
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
