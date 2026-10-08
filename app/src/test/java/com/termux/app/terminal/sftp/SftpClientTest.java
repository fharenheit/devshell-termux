package com.termux.app.terminal.sftp;

import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tests {@link SftpClient} against a real SFTP server through `ssh -s <host> sftp`. Runs only if
 * the TERMUX_SFTP_TEST_HOST environment variable names a host that ssh can log in to without a
 * prompt, like with a key, and is skipped otherwise.
 */
public class SftpClientTest {

    private Process mProcess;
    private SftpClient mClient;
    private String mDir;

    @Before
    public void setUp() throws IOException {
        String host = System.getenv("TERMUX_SFTP_TEST_HOST");
        Assume.assumeTrue("TERMUX_SFTP_TEST_HOST is not set", host != null && !host.isEmpty());
        mProcess = new ProcessBuilder("ssh", "-o", "BatchMode=yes", "-s", host, "sftp").redirectError(ProcessBuilder.Redirect.INHERIT).start();
        mClient = new SftpClient(mProcess.getInputStream(), mProcess.getOutputStream(), () -> mProcess.destroy());
        mClient.init();
        mDir = SftpPaths.join(mClient.realPath("."), "sftp-test-" + System.nanoTime());
        mClient.mkdir(mDir);
    }

    @After
    public void tearDown() throws IOException {
        if (mClient == null) return;
        try {
            mClient.removeRecursively(mDir, null);
        } finally {
            mClient.close();
        }
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        return bytes;
    }

    private void writeFile(String path, byte[] content) throws IOException {
        mClient.write(path, new ByteArrayInputStream(content), content.length, 0644, false, null, null);
    }

    @Test
    public void testWriteReadAndList() throws IOException {
        byte[] small = "hello\n".getBytes(StandardCharsets.UTF_8);
        byte[] large = randomBytes(3 * 1024 * 1024 + 123);
        String smallPath = SftpPaths.join(mDir, "한글 파일.txt");
        String largePath = SftpPaths.join(mDir, "large.bin");
        writeFile(smallPath, small);
        List<Long> progress = new ArrayList<>();
        mClient.write(largePath, new ByteArrayInputStream(large), large.length, -1, false, (done, total) -> progress.add(done), null);
        Assert.assertEquals(large.length, (long) progress.get(progress.size() - 1));

        Assert.assertArrayEquals(small, mClient.readAll(smallPath, 1024));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long read = mClient.read(largePath, out, Long.MAX_VALUE, null, null);
        Assert.assertEquals(large.length, read);
        Assert.assertArrayEquals(large, out.toByteArray());

        SftpClient.Attributes attributes = mClient.stat(largePath);
        Assert.assertEquals(large.length, attributes.size);
        Assert.assertTrue(attributes.isRegularFile());
        Assert.assertEquals(0644, mClient.stat(smallPath).permissions & 0777);

        mClient.mkdir(SftpPaths.join(mDir, "sub"));
        List<String> names = new ArrayList<>();
        for (SftpClient.Entry entry : mClient.list(mDir)) names.add(entry.name + (entry.attributes.isDirectory() ? "/" : ""));
        java.util.Collections.sort(names);
        Assert.assertEquals(java.util.Arrays.asList("large.bin", "sub/", "한글 파일.txt"), names);
    }

    @Test
    public void testReadLimit() throws IOException {
        String path = SftpPaths.join(mDir, "limit.txt");
        writeFile(path, randomBytes(100_000));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Assert.assertEquals(40_000, mClient.read(path, out, 40_000, null, null));
        try {
            mClient.readAll(path, 99_999);
            Assert.fail("Read a file larger than the limit");
        } catch (IOException expected) {
        }
        Assert.assertEquals(100_000, mClient.readAll(path, 100_000).length);
        // An empty file.
        String empty = SftpPaths.join(mDir, "empty");
        writeFile(empty, new byte[0]);
        Assert.assertEquals(0, mClient.readAll(empty, 10).length);
    }

    @Test
    public void testRenameRemoveAndErrors() throws IOException {
        String a = SftpPaths.join(mDir, "a.txt");
        String b = SftpPaths.join(mDir, "b.txt");
        writeFile(a, "A".getBytes(StandardCharsets.UTF_8));
        writeFile(b, "B".getBytes(StandardCharsets.UTF_8));
        try {
            mClient.rename(a, b, false);
            Assert.fail("Renamed over an existing file without overwrite");
        } catch (SftpClient.SftpException expected) {
        }
        Assert.assertTrue(mClient.hasExtension(SftpClient.EXTENSION_POSIX_RENAME));
        mClient.rename(a, b, true);
        Assert.assertEquals("A", new String(mClient.readAll(b, 10), StandardCharsets.UTF_8));
        try {
            mClient.stat(a);
            Assert.fail("The renamed file still exists");
        } catch (SftpClient.SftpException e) {
            Assert.assertEquals(SftpClient.SSH_FX_NO_SUCH_FILE, e.status);
        }
        try {
            writeFile(b, new byte[0]);
            mClient.write(b, new ByteArrayInputStream(new byte[1]), 1, -1, true, null, null);
            Assert.fail("Created an existing file exclusively");
        } catch (SftpClient.SftpException expected) {
        }

        mClient.chmod(b, 0600);
        Assert.assertEquals(0600, mClient.stat(b).permissions & 0777);
        mClient.remove(b);

        String nested = SftpPaths.join(SftpPaths.join(mDir, "x"), "y");
        mClient.mkdir(SftpPaths.parent(nested));
        mClient.mkdir(nested);
        writeFile(SftpPaths.join(nested, "f"), new byte[10]);
        mClient.removeRecursively(SftpPaths.parent(nested), null);
        Assert.assertTrue(mClient.list(mDir).isEmpty());
    }

    @Test
    public void testCancel() throws IOException {
        String path = SftpPaths.join(mDir, "cancel.bin");
        byte[] content = randomBytes(2 * 1024 * 1024);
        try {
            mClient.write(path, new ByteArrayInputStream(content), content.length, -1, false, null, new AtomicBoolean(true));
            Assert.fail("A cancelled write finished");
        } catch (java.io.InterruptedIOException expected) {
        }
        // The session still works after the cancelled write.
        writeFile(path, content);
        Assert.assertEquals(content.length, mClient.stat(path).size);
    }

}
