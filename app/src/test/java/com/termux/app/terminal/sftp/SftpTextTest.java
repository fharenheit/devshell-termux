package com.termux.app.terminal.sftp;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class SftpTextTest {

    private static SftpText.Text decode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return SftpText.decode(bytes, bytes.length, false);
    }

    @Test
    public void testLineEndingsAreKept() {
        SftpText.Text crlf = decode("a\r\nb\r\n");
        Assert.assertTrue(crlf.crlf);
        Assert.assertEquals("a\nb\n", crlf.text);
        Assert.assertArrayEquals("a\r\nb\r\n".getBytes(StandardCharsets.UTF_8), SftpText.encode(crlf.text, crlf.crlf));

        // Mixed line endings are left as they are.
        SftpText.Text mixed = decode("a\r\nb\n");
        Assert.assertFalse(mixed.crlf);
        Assert.assertArrayEquals("a\r\nb\n".getBytes(StandardCharsets.UTF_8), SftpText.encode(mixed.text, mixed.crlf));

        // No trailing newline stays without one.
        SftpText.Text noNewline = decode("한글\nend");
        Assert.assertArrayEquals("한글\nend".getBytes(StandardCharsets.UTF_8), SftpText.encode(noNewline.text, noNewline.crlf));
    }

    @Test
    public void testEncodingDetection() throws Exception {
        SftpText.Text utf8 = decode("가나다");
        Assert.assertSame(SftpText.UTF_8, utf8.encoding);
        Assert.assertTrue(utf8.lossless);
        Assert.assertFalse(utf8.bom);

        // Korean text in CP949 (EUC-KR) is detected and can be saved back the same.
        byte[] korean = "안녕하세요 똠방각하\n".getBytes("x-windows-949");
        SftpText.Text text = SftpText.decode(korean, korean.length, false);
        Assert.assertSame(SftpText.CP949, text.encoding);
        Assert.assertTrue(text.lossless);
        Assert.assertEquals("안녕하세요 똠방각하\n", text.text);
        Assert.assertArrayEquals(korean, SftpText.encode(text.text, text.crlf, text.encoding, text.bom));

        // Other bytes are ISO-8859-1, which keeps every byte.
        byte[] latin1 = {'c', 'a', 'f', (byte) 0xe9, ' ', (byte) 0xff};
        text = SftpText.decode(latin1, latin1.length, false);
        Assert.assertSame(SftpText.ISO_8859_1, text.encoding);
        Assert.assertTrue(text.lossless);
        Assert.assertEquals("café ÿ", text.text);

        // Decoding with an encoding that does not fit is not lossless, so it is read only.
        Assert.assertFalse(SftpText.decode(latin1, latin1.length, false, SftpText.UTF_8).lossless);
    }

    @Test
    public void testByteOrderMarks() throws Exception {
        byte[] utf8Bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'};
        SftpText.Text text = SftpText.decode(utf8Bom, utf8Bom.length, false);
        Assert.assertSame(SftpText.UTF_8, text.encoding);
        Assert.assertTrue(text.bom);
        Assert.assertEquals("hi", text.text);
        Assert.assertArrayEquals(utf8Bom, SftpText.encode(text.text, text.crlf, text.encoding, text.bom));

        byte[] utf16 = {(byte) 0xFF, (byte) 0xFE, 'a', 0, '\r', 0, '\n', 0};
        Assert.assertTrue(SftpText.isProbablyText(utf16, utf16.length));
        text = SftpText.decode(utf16, utf16.length, false);
        Assert.assertEquals("UTF-16LE", text.encoding.label);
        Assert.assertTrue(text.crlf);
        Assert.assertEquals("a\n", text.text);
        Assert.assertArrayEquals(utf16, SftpText.encode(text.text, text.crlf, text.encoding, text.bom));
    }

    @Test(expected = java.nio.charset.CharacterCodingException.class)
    public void testUnencodableCharacters() throws Exception {
        SftpText.encode("한글 😀", false, SftpText.CP949, false);
    }

    @Test
    public void testHexLines() {
        byte[] bytes = "Hello, world!\n\u0000\u0001ab".getBytes(StandardCharsets.UTF_8);
        Assert.assertEquals("00000000  48 65 6c 6c 6f 2c 20 77  6f 72 6c 64 21 0a 00 01  Hello, world!...",
            SftpText.formatHexLine(bytes, bytes.length, 0, 16));
        Assert.assertEquals("00000010  61 62                                             ab",
            SftpText.formatHexLine(bytes, bytes.length, 16, 16));
        Assert.assertEquals(SftpText.getHexLineLength(16), SftpText.formatHexLine(bytes, bytes.length, 0, 16).length());
        Assert.assertEquals("00000008  6f 72 6c 64 21 0a 00 01  orld!...", SftpText.formatHexLine(bytes, bytes.length, 8, 8));
        Assert.assertEquals(SftpText.getHexLineLength(8), SftpText.formatHexLine(bytes, bytes.length, 8, 8).length());
    }

    @Test
    public void testTruncatedInSequence() {
        byte[] bytes = "ab가".getBytes(StandardCharsets.UTF_8); // 가 is 3 bytes
        for (int length = 2; length <= bytes.length; length++) {
            SftpText.Text text = SftpText.decode(bytes, length, true);
            Assert.assertSame("length " + length, SftpText.UTF_8, text.encoding);
            Assert.assertEquals(length == bytes.length ? "ab가" : "ab", text.text);
        }
    }

    @Test
    public void testDetection() {
        Assert.assertTrue(SftpText.isImage("photo.JPG"));
        Assert.assertTrue(SftpText.isImage("a.webp"));
        Assert.assertFalse(SftpText.isImage("jpg"));
        Assert.assertFalse(SftpText.isImage("notes.txt"));
        byte[] binary = {1, 2, 0, 3};
        Assert.assertFalse(SftpText.isProbablyText(binary, binary.length));
        byte[] text = "text".getBytes(StandardCharsets.UTF_8);
        Assert.assertTrue(SftpText.isProbablyText(text, text.length));
        Assert.assertEquals("512 B", SftpText.formatSize(512));
        Assert.assertEquals("1.5 KB", SftpText.formatSize(1536));
        Assert.assertEquals("24 MB", SftpText.formatSize(25524620));
    }

}
