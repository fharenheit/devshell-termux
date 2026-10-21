package com.termux.app.terminal.ssh;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SshConnectionScriptTest {

    private static final String PID_PREFIX = "sh -c 'printf \"\\033]1337;DevShellShellPid=%%s\\007\" \"$PPID\"'; ";

    private List<String> connect(String remoteCommand, String controlPath) throws Exception {
        Assume.assumeTrue("Local OpenSSH client is unavailable", new File("/usr/bin/ssh").canExecute());
        File folder = Files.createTempDirectory("devshell-ssh-script-").toFile();
        File args = new File(folder, "args");
        File ssh = new File(folder, "ssh");
        Files.write(ssh.toPath(), ("#!/bin/sh\n" +
            "if [ \"$1\" = -G ]; then exec /usr/bin/ssh -G -F /dev/null -o \"RemoteCommand=$DEVSHELL_TEST_REMOTE_COMMAND\" \"$2\"; fi\n" +
            "printf '%s\\0' \"$@\" > \"$DEVSHELL_TEST_ARGS\"\n").getBytes(StandardCharsets.UTF_8));
        Assert.assertTrue(ssh.setExecutable(true));
        Field field = SshConnector.class.getDeclaredField("CONNECT_SCRIPT");
        field.setAccessible(true);
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", (String) field.get(null), "devshell",
            "server", "", "", "ssh", "", controlPath, PID_PREFIX);
        builder.environment().put("PATH", folder.getAbsolutePath() + ":" + System.getenv("PATH"));
        builder.environment().put("DEVSHELL_TEST_REMOTE_COMMAND", remoteCommand);
        builder.environment().put("DEVSHELL_TEST_ARGS", args.getAbsolutePath());
        Process process = builder.redirectError(ProcessBuilder.Redirect.INHERIT).start();
        byte[] output = readAll(process.getInputStream());
        Assert.assertEquals(0, process.waitFor());
        Assert.assertTrue(new String(output, StandardCharsets.UTF_8).contains("\033]1337;DevShellShellPid=0\007"));
        return Arrays.asList(new String(Files.readAllBytes(args.toPath()), StandardCharsets.UTF_8).split("\0"));
    }

    private static byte[] readAll(java.io.InputStream input) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    @Test
    public void testDefaultLoginAutomaticallyReportsItsShellIdentity() throws Exception {
        List<String> args = connect("none", "");
        Assert.assertTrue(args.contains("-t"));
        Assert.assertTrue(args.contains("RemoteCommand=" + PID_PREFIX + "exec \"$SHELL\" -l"));
        Assert.assertEquals("server", args.get(args.size() - 1));
    }

    @Test
    public void testConfiguredRemoteCommandAndSharingSocketArePreserved() throws Exception {
        String command = "cd '/srv/한글 work'; printf '100%% %h'; exec \"$SHELL\" -l";
        List<String> args = connect(command, "/tmp/devshell-test-no-socket");
        Assert.assertTrue(args.contains("RemoteCommand=" + PID_PREFIX + "cd '/srv/한글 work'; printf '100%% server'; exec \"$SHELL\" -l"));
        Assert.assertTrue(args.contains("ControlMaster=yes"));
        Assert.assertTrue(args.contains("ControlPath=/tmp/devshell-test-no-socket"));
    }

    @Test
    public void testRemoteCommandPercentTokensAreExpandedOnlyOnce() throws Exception {
        List<String> args = connect("printf '100%% %h'; exec \"$SHELL\" -l", "");
        String option = null;
        for (String arg : args) if (arg.startsWith("RemoteCommand=")) option = arg;
        Assert.assertNotNull(option);
        Process process = new ProcessBuilder("/usr/bin/ssh", "-G", "-F", "/dev/null", "-o", option, "server")
            .redirectErrorStream(true).start();
        String config = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
        Assert.assertEquals(config, 0, process.waitFor());
        Assert.assertTrue(config, config.contains("DevShellShellPid=%s"));
        Assert.assertTrue(config, config.contains("printf '100% server'"));
    }

    @Test
    public void testBootstrapReportsTheProcessThatBecomesTheLoginShell() throws Exception {
        Field field = SshConnector.class.getDeclaredField("REMOTE_SHELL_PID_PREFIX");
        field.setAccessible(true);
        // OpenSSH resolves %% to % before sending RemoteCommand to the server.
        String prefix = ((String) field.get(null)).replace("%%", "%");
        File folder = Files.createTempDirectory("devshell-remote-login-").toFile();
        File loginShell = new File(folder, "login-shell");
        Files.write(loginShell.toPath(), ("#!/bin/sh\nprintf 'LOGIN:%s:%s\\n' \"$$\" \"$1\"\n")
            .getBytes(StandardCharsets.UTF_8));
        Assert.assertTrue(loginShell.setExecutable(true));
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", prefix + "exec \"$SHELL\" -l");
        builder.environment().put("SHELL", loginShell.getAbsolutePath());
        Process process = builder.start();
        String output = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
        Assert.assertEquals(0, process.waitFor());
        java.util.regex.Matcher match = java.util.regex.Pattern.compile(
            "\\x1b\\]1337;DevShellShellPid=([0-9]+)\\x07LOGIN:([0-9]+):-l\\n").matcher(output);
        Assert.assertTrue(output, match.matches());
        Assert.assertEquals(match.group(1), match.group(2));
    }
}
