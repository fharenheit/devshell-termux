package com.termux.app.terminal.snippets;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28})
public class SnippetStoreTest {

    @Rule
    public TemporaryFolder mFolder = new TemporaryFolder();

    private static Snippet snippet(String name, String command, boolean run, String host) {
        Snippet snippet = new Snippet();
        snippet.name = name;
        snippet.command = command;
        snippet.run = run;
        snippet.host = host;
        return snippet;
    }

    @Test
    public void testSaveAndLoad() throws Exception {
        File file = new File(mFolder.getRoot(), "snippets.json");
        Assert.assertTrue(SnippetStore.load(file).isEmpty());
        SnippetStore.save(file, Arrays.asList(snippet("Update", "pkg upgrade -y", true, ""),
            snippet("", "cd /var/log\ntail -f syslog", false, "web")));
        List<Snippet> loaded = SnippetStore.load(file);
        Assert.assertEquals(2, loaded.size());
        Assert.assertEquals("Update", loaded.get(0).getTitle());
        Assert.assertEquals("cd /var/log …", loaded.get(1).getTitle());
        Assert.assertFalse(loaded.get(1).run);
        Assert.assertEquals("web", loaded.get(1).host);
    }

    @Test
    public void testInput() {
        Assert.assertEquals("ls -la\r", snippet("", "ls -la", true, "").getInput());
        Assert.assertEquals("ls -la\r", snippet("", "ls -la\n\n", true, "").getInput());
        Assert.assertEquals("git commit -m \"", snippet("", "git commit -m \"", false, "").getInput());
        Assert.assertEquals("cd /tmp\rls\r", snippet("", "cd /tmp\r\nls", true, "").getInput());
    }

    @Test
    public void testForHost() {
        List<Snippet> snippets = Arrays.asList(snippet("all", "a", true, ""), snippet("web", "b", true, "web"),
            snippet("db", "c", true, "db"));
        List<Snippet> forWeb = SnippetStore.getFor(snippets, "web");
        Assert.assertEquals(2, forWeb.size());
        Assert.assertEquals("web", forWeb.get(0).name);
        Assert.assertEquals("all", forWeb.get(1).name);
        Assert.assertEquals(1, SnippetStore.getFor(snippets, null).size());
    }

}
