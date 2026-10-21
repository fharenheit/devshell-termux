package com.termux.app.terminal.sftp;

import android.content.Intent;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SftpBrowserDirectoryTest {

    private TerminalSession session(String directory) throws Exception {
        TerminalSessionClient client = (TerminalSessionClient) Proxy.newProxyInstance(
            TerminalSessionClient.class.getClassLoader(), new Class<?>[]{TerminalSessionClient.class}, (proxy, method, args) -> {
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == float.class) return 1f;
                return null;
            });
        TerminalSession session = new TerminalSession("/bin/sh", "/", new String[0], new String[0], 100, client);
        TerminalEmulator emulator = new TerminalEmulator(session, 80, 24, 10, 20, 100, client);
        Field field = TerminalSession.class.getDeclaredField("mEmulator");
        field.setAccessible(true);
        field.set(session, emulator);
        report(session, directory);
        return session;
    }

    private void report(TerminalSession session, String directory) {
        byte[] bytes = ("\033]7;file://server" + directory + "\007").getBytes(StandardCharsets.UTF_8);
        session.getEmulator().append(bytes, bytes.length);
    }

    @Test
    public void testBrowserIntentUsesTheSelectedTerminalAndItsLatestDirectory() throws Exception {
        TerminalSession first = session("/srv/first");
        TerminalSession second = session("/srv/second");
        byte[] identity = "\033]1337;DevShellShellPid=12345\007".getBytes(StandardCharsets.UTF_8);
        first.getEmulator().append(identity, identity.length);
        Intent intent = SftpBrowserActivity.newIntentForSession(RuntimeEnvironment.application, "server", first);
        Assert.assertEquals("/srv/first", intent.getStringExtra(SftpBrowserActivity.EXTRA_INITIAL_DIRECTORY));
        Assert.assertEquals("server", intent.getStringExtra(SftpBrowserActivity.EXTRA_HOST));
        Assert.assertEquals(12345, intent.getIntExtra(SftpBrowserActivity.EXTRA_REMOTE_SHELL_PID, 0));

        report(first, "/srv/next");
        intent = SftpBrowserActivity.newIntentForSession(RuntimeEnvironment.application, "server", first);
        Assert.assertEquals("/srv/next", intent.getStringExtra(SftpBrowserActivity.EXTRA_INITIAL_DIRECTORY));
        intent = SftpBrowserActivity.newIntentForSession(RuntimeEnvironment.application, "server", second);
        Assert.assertEquals("/srv/second", intent.getStringExtra(SftpBrowserActivity.EXTRA_INITIAL_DIRECTORY));
        Assert.assertEquals(0, intent.getIntExtra(SftpBrowserActivity.EXTRA_REMOTE_SHELL_PID, 0));
    }

    @Test
    public void testBrowserWithoutATerminalKeepsTheHomeDirectoryDefault() {
        Intent intent = SftpBrowserActivity.newIntent(RuntimeEnvironment.application, "server", null, false);
        Assert.assertNull(intent.getStringExtra(SftpBrowserActivity.EXTRA_INITIAL_DIRECTORY));
    }

    /** Substitute only the remote server operations, retaining the browser's path/fallback logic. */
    private static final class Directories extends SftpClient {
        final List<String> requested = new ArrayList<>();
        IOException failure;
        IOException procFailure;
        String procDirectory;
        boolean failDuringList;

        Directories() {
            super(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), null);
        }

        @Override
        public synchronized String realPath(String path) throws IOException {
            requested.add(path);
            if (path.startsWith("/proc/")) {
                if (procFailure != null) throw procFailure;
                if (procDirectory != null) return procDirectory;
            }
            if (failure != null && !path.equals(".") && !failDuringList) throw failure;
            return path.equals(".") ? "/home/user" : path;
        }

        @Override
        public synchronized List<Entry> list(String path) throws IOException {
            if (failure != null && !path.equals("/home/user") && failDuringList) throw failure;
            return Collections.emptyList();
        }
    }

    @Test
    public void testOpeningBrowserQueriesTheLiveShellInsteadOfItsOldReportedDirectory() throws Exception {
        Directories client = new Directories();
        client.procDirectory = "/srv/current";
        Object[] result = SftpBrowserActivity.readInitialDirectory(client, "/srv/old", 12345);
        Assert.assertEquals("/srv/current", result[0]);
        Assert.assertEquals(Arrays.asList("/proc/12345/cwd", "/srv/current"), client.requested);
    }

    @Test
    public void testUnavailableProcUsesReportedDirectoryOrHome() throws Exception {
        for (String fallback : new String[]{"/srv/reported", "."}) {
            Directories client = new Directories();
            client.procFailure = new SftpClient.SftpException(SftpClient.SSH_FX_NO_SUCH_FILE, "No procfs");
            Object[] result = SftpBrowserActivity.readInitialDirectory(client, fallback, 12345);
            Assert.assertEquals(fallback.equals(".") ? "/home/user" : fallback, result[0]);
            Assert.assertEquals(Arrays.asList("/proc/12345/cwd", fallback), client.requested);
        }
    }

    @Test
    public void testLiveDirectoryQueryDoesNotHideConnectionErrors() throws Exception {
        Directories client = new Directories();
        client.procFailure = new IOException("Connection lost");
        try {
            SftpBrowserActivity.readInitialDirectory(client, "/srv/reported", 12345);
            Assert.fail("Connection errors must reach the user");
        } catch (IOException expected) {
            Assert.assertSame(client.procFailure, expected);
        }
        Assert.assertEquals(Collections.singletonList("/proc/12345/cwd"), client.requested);
    }

    @Test
    public void testRealSftpServerFindsTheShellDirectoryAfterCdAndWhileBusy() throws Exception {
        File server = new File("/usr/lib/openssh/sftp-server");
        Assume.assumeTrue("Local OpenSSH SFTP server is not installed", server.canExecute());
        File root = Files.createTempDirectory("devshell-live-cwd-").toFile();
        File first = new File(root, "first folder");
        File second = new File(root, "한글 second");
        Assert.assertTrue(first.mkdir());
        Assert.assertTrue(second.mkdir());
        Process shell = new ProcessBuilder("/bin/sh").start();
        Process sftp = new ProcessBuilder(server.getAbsolutePath()).start();
        try (SftpClient client = new SftpClient(sftp.getInputStream(), sftp.getOutputStream(), sftp::destroy)) {
            client.init();
            OutputStreamWriter commands = new OutputStreamWriter(shell.getOutputStream(), StandardCharsets.UTF_8);
            BufferedReader output = new BufferedReader(new InputStreamReader(shell.getInputStream(), StandardCharsets.UTF_8));
            commands.write("printf '%s\\n' \"$$\"\n");
            commands.flush();
            int pid = Integer.parseInt(output.readLine());
            for (File folder : new File[]{first, second}) {
                commands.write("cd '" + folder.getAbsolutePath().replace("'", "'\\''") + "'; printf 'READY\\n'\n");
                commands.flush();
                Assert.assertEquals("READY", output.readLine());
                Assert.assertEquals(folder.getCanonicalPath(), SftpBrowserActivity.readInitialDirectory(client, ".", pid)[0]);
            }
            // The shell is occupied by a foreground builtin; no command can be injected to query it.
            commands.write("printf 'BUSY\\n'; read task\n");
            commands.flush();
            Assert.assertEquals("BUSY", output.readLine());
            Assert.assertEquals(second.getCanonicalPath(), SftpBrowserActivity.readInitialDirectory(client, ".", pid)[0]);
        } finally {
            shell.destroy();
            sftp.destroy();
        }
    }

    @Test
    public void testInitialListingUsesTheReportedDirectory() throws Exception {
        Directories client = new Directories();
        Object[] result = SftpBrowserActivity.readDirectory(client, "/srv/project", true);
        Assert.assertEquals("/srv/project", result[0]);
        Assert.assertEquals(Collections.singletonList("/srv/project"), client.requested);
    }

    @Test
    public void testUnavailableInitialDirectoryFallsBackToHome() throws Exception {
        for (int status : new int[]{SftpClient.SSH_FX_NO_SUCH_FILE, SftpClient.SSH_FX_PERMISSION_DENIED,
            SftpClient.SSH_FX_FAILURE}) {
            for (boolean failDuringList : new boolean[]{false, true}) {
                Directories client = new Directories();
                client.failure = new SftpClient.SftpException(status, "unavailable");
                client.failDuringList = failDuringList;
                Object[] result = SftpBrowserActivity.readDirectory(client, "/srv/missing", true);
                Assert.assertEquals("/home/user", result[0]);
                Assert.assertEquals(Arrays.asList("/srv/missing", "."), client.requested);
            }
        }
    }

    @Test
    public void testNavigationAndConnectionErrorsAreNotHiddenByFallback() throws Exception {
        Directories client = new Directories();
        client.failure = new SftpClient.SftpException(SftpClient.SSH_FX_PERMISSION_DENIED, "denied");
        try {
            SftpBrowserActivity.readDirectory(client, "/private", false);
            Assert.fail("Navigation must report permission errors");
        } catch (IOException expected) {
            Assert.assertSame(client.failure, expected);
        }
        client.requested.clear();
        client.failure = new IOException("Connection lost");
        try {
            SftpBrowserActivity.readDirectory(client, "/srv/project", true);
            Assert.fail("Connection errors must reach the user");
        } catch (IOException expected) {
            Assert.assertSame(client.failure, expected);
        }
        Assert.assertEquals(Collections.singletonList("/srv/project"), client.requested);
    }
}
