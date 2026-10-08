package com.termux.app.terminal.snippets;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28})
public class SnippetDefaultsTest {

    @Test
    public void testDefaults() {
        List<Snippet> defaults = SnippetDefaults.create(RuntimeEnvironment.application);
        Set<String> commands = new HashSet<>();
        for (Snippet snippet : defaults) {
            Assert.assertFalse(snippet.name.isEmpty());
            Assert.assertTrue(snippet.host.isEmpty());
            Assert.assertTrue("duplicate " + snippet.command, commands.add(snippet.command));
        }
        Assert.assertTrue(commands.contains("git status -sb"));
        Assert.assertTrue(commands.contains("df -h"));
        // A command to finish types the text without running it.
        for (Snippet snippet : defaults)
            if (snippet.command.equals("git commit -am ")) Assert.assertEquals("git commit -am ", snippet.getInput());
    }

    @Test
    public void testAddMissingSkipsSavedCommands() {
        List<Snippet> snippets = new ArrayList<>();
        Snippet own = new Snippet();
        own.name = "mine";
        own.command = "git status -sb";
        snippets.add(own);
        List<Snippet> git = SnippetDefaults.create(RuntimeEnvironment.application,
            Collections.singletonList(SnippetDefaults.GROUPS.get(0)));
        int added = SnippetDefaults.addMissing(snippets, git);
        Assert.assertEquals(git.size() - 1, added);
        Assert.assertEquals("mine", snippets.get(0).name);
        Assert.assertEquals(0, SnippetDefaults.addMissing(snippets, git));
    }

}
