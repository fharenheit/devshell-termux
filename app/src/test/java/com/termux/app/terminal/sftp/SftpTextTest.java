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
    public void testUtf8() {
        Assert.assertTrue(decode("가나다").utf8);
        byte[] latin1 = {'c', 'a', 'f', (byte) 0xe9};
        SftpText.Text text = SftpText.decode(latin1, latin1.length, false);
        Assert.assertFalse(text.utf8);
        Assert.assertEquals("café", text.text);
    }

    @Test
    public void testTruncatedInSequence() {
        byte[] bytes = "ab가".getBytes(StandardCharsets.UTF_8); // 가 is 3 bytes
        for (int length = 2; length <= bytes.length; length++) {
            SftpText.Text text = SftpText.decode(bytes, length, true);
            Assert.assertTrue("length " + length, text.utf8);
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
