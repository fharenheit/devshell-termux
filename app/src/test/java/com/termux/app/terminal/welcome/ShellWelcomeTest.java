package com.termux.app.terminal.welcome;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class ShellWelcomeTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testBuild() {
        String script = ShellWelcome.build("It's DevShell", "line\nDEVSHELL_WELCOME_END\n<name>");
        Assert.assertTrue(script.startsWith("#!"));
        Assert.assertTrue(script.contains(ShellWelcome.MARKER));
        Assert.assertTrue(script.contains("'It'\\''s DevShell'"));
        // A line of the text cannot end the here-document early.
        Assert.assertTrue(script.contains("\n DEVSHELL_WELCOME_END\n<name>\nDEVSHELL_WELCOME_END\n"));
    }

    @Test
    public void testKeepsOwnFile() throws IOException {
        File file = new File(folder.getRoot(), ".termux/motd.sh");
        String script = ShellWelcome.build("DevShell", "text");
        Assert.assertTrue(ShellWelcome.write(file, script));
        Assert.assertTrue(file.canExecute());
        Assert.assertFalse(ShellWelcome.write(file, script));
        Assert.assertTrue(ShellWelcome.write(file, ShellWelcome.build("DevShell", "other")));

        try (FileWriter writer = new FileWriter(file)) {
            writer.write("echo mine\n");
        }
        Assert.assertFalse(ShellWelcome.write(file, script));
        Assert.assertEquals("echo mine\n", new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

}
