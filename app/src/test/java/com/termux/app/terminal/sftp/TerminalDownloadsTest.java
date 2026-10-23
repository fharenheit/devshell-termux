package com.termux.app.terminal.sftp;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TerminalDownloadsTest {

    @Test
    public void testLsLongLines() {
        Assert.assertEquals("a.txt", TerminalDownloads.lineName("-rw-r--r-- 1 user group 1234 Oct 23 10:00 a.txt"));
        Assert.assertEquals("my file.txt", TerminalDownloads.lineName("-rw-r--r--  1 user group 12 Oct 23 10:00 'my file.txt'"));
        Assert.assertEquals("old.tar", TerminalDownloads.lineName("-rw-r--r--. 1 user group 5 Jan  3  2024 old.tar"));
        Assert.assertEquals("dir", TerminalDownloads.lineName("drwxr-xr-x+ 2 1000 1000 4.0K 2026-10-23 10:00 dir"));
        Assert.assertEquals("link", TerminalDownloads.lineName("lrwxrwxrwx 1 user group 7 Oct 23 10:00 link -> target"));
        Assert.assertEquals("f", TerminalDownloads.lineName("-rw-r--r-- 1 user group 9 10월 23 10:00 f"));
        Assert.assertEquals("f", TerminalDownloads.lineName(
            "-rw-r--r-- 1 user group 9 2026-10-23 10:00:00.123456789 +0900 f"));
        Assert.assertEquals("a -> b", TerminalDownloads.lineName("-rw-r--r-- 1 user group 9 Oct 23 10:00 a -> b"));
    }

    @Test
    public void testNames() {
        Assert.assertEquals(Arrays.asList("a.txt", "my file"), TerminalDownloads.names("a.txt\n my\\ file \n\n"));
        Assert.assertEquals(Arrays.asList("it's"), TerminalDownloads.names("\"it's\""));
        Assert.assertTrue(TerminalDownloads.names("   ").isEmpty());
        Assert.assertTrue(TerminalDownloads.names(null).isEmpty());
    }

    @Test
    public void testPaths() {
        Assert.assertEquals("/home/u/a", TerminalDownloads.path("/home/u", "a"));
        Assert.assertEquals("/etc/hosts", TerminalDownloads.path("/home/u", "/etc/hosts"));
        Assert.assertEquals("a/b", TerminalDownloads.path("/home/u", "~/a/b"));
    }

    @Test
    public void testResolve() throws IOException {
        Map<String, SftpClient.Attributes> files = new HashMap<>();
        files.put("/d/a.txt", attributes(SftpClient.Attributes.S_IFREG, 3));
        files.put("/d/b.sh", attributes(SftpClient.Attributes.S_IFREG, 5));
        files.put("/d/my file", attributes(SftpClient.Attributes.S_IFREG, 7));
        files.put("/d/sub", attributes(SftpClient.Attributes.S_IFDIR, 4096));
        TerminalDownloads.PathLookup lookup = path -> {
            SftpClient.Attributes attributes = files.get(path);
            if (attributes == null) throw new SftpClient.SftpException(SftpClient.SSH_FX_NO_SUCH_FILE, "No such file");
            return attributes;
        };

        List<SftpTransfers.DownloadItem> items = TerminalDownloads.resolve("/d",
            Arrays.asList("my file", "a.txt  b.sh*  missing", "sub/", "a.txt", "nothing"), lookup);
        List<String> paths = new ArrayList<>();
        for (SftpTransfers.DownloadItem item : items) paths.add(item.path + (item.isDirectory ? "/" : "") + ":" + item.name);
        Assert.assertEquals(Arrays.asList("/d/my file:my file", "/d/a.txt:a.txt", "/d/b.sh:b.sh", "/d/sub/:sub"), paths);
    }

    private static SftpClient.Attributes attributes(int type, long size) {
        SftpClient.Attributes attributes = new SftpClient.Attributes();
        attributes.permissions = type | 0644;
        attributes.size = size;
        return attributes;
    }

}
