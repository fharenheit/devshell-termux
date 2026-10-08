package com.termux.app.terminal.sftp;

import org.junit.Assert;
import org.junit.Test;

public class SftpPathsTest {

    @Test
    public void testPaths() {
        Assert.assertEquals("/a/b", SftpPaths.join("/a", "b"));
        Assert.assertEquals("/b", SftpPaths.join("/", "b"));
        Assert.assertEquals("/a", SftpPaths.parent("/a/b"));
        Assert.assertEquals("/", SftpPaths.parent("/a"));
        Assert.assertEquals("/", SftpPaths.parent("/"));
        Assert.assertEquals("/a", SftpPaths.parent("/a/b/"));
        Assert.assertEquals("b", SftpPaths.name("/a/b"));
        Assert.assertEquals("b", SftpPaths.name("/a/b/"));
        Assert.assertTrue(SftpPaths.isValidName("한글 파일.txt"));
        Assert.assertFalse(SftpPaths.isValidName(".."));
        Assert.assertFalse(SftpPaths.isValidName("a/b"));
        Assert.assertFalse(SftpPaths.isValidName(""));
        Assert.assertEquals("drwxr-xr-x", SftpPaths.formatPermissions(0040755));
        Assert.assertEquals("-rw-------", SftpPaths.formatPermissions(0100600));
        Assert.assertEquals("lrwxrwxrwx", SftpPaths.formatPermissions(0120777));
    }

}
