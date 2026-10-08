package com.termux.app.terminal.snippets;

import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SnippetPlaceholdersTest {

    @Test
    public void testFind() {
        List<SnippetPlaceholders.Placeholder> placeholders =
            SnippetPlaceholders.find("lsof -u <USERNAME> || grep \"pid=($(pgrep -u <USERNAME>))\" <DIR=.> <A_1=x y>");
        Assert.assertEquals(3, placeholders.size());
        Assert.assertEquals("USERNAME", placeholders.get(0).name);
        Assert.assertEquals("", placeholders.get(0).defaultValue);
        Assert.assertEquals("DIR", placeholders.get(1).name);
        Assert.assertEquals(".", placeholders.get(1).defaultValue);
        Assert.assertEquals("x y", placeholders.get(2).defaultValue);
    }

    @Test
    public void testShellSyntaxIsNotPlaceholder() {
        Assert.assertTrue(SnippetPlaceholders.find("sort < file > out; cat <<EOF; diff <(ls) <(ls -a); echo <name>").isEmpty());
    }

    @Test
    public void testFill() {
        Map<String, String> values = new HashMap<>();
        values.put("USERNAME", "alice");
        values.put("PATTERN", "*log*");
        Assert.assertEquals("ps -u alice; ps -u alice", SnippetPlaceholders.fill("ps -u <USERNAME>; ps -u <USERNAME>", values));
        // A missing value takes the default.
        Assert.assertEquals("find . -iname '*log*'", SnippetPlaceholders.fill("find <DIR=.> -iname '<PATTERN>'", values));
        Assert.assertEquals("a $1 \\ b", SnippetPlaceholders.fill("a <X> b", java.util.Collections.singletonMap("X", "$1 \\")));
    }

}
