package com.termux.app.terminal.links;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class TerminalLinksTest {

    @Test
    public void testFindUrls() {
        Assert.assertEquals(Collections.singletonList("https://example.com/a?b=1#c"),
            TerminalLinks.findUrls("https://example.com/a?b=1#c"));
        Assert.assertEquals(Arrays.asList("https://a.example.com", "http://b.example.com:8080/x"),
            TerminalLinks.findUrls("go https://a.example.com and http://b.example.com:8080/x, or https://a.example.com"));
        Assert.assertEquals(Collections.emptyList(), TerminalLinks.findUrls("no links here"));
        Assert.assertEquals(Collections.emptyList(), TerminalLinks.findUrls(null));
    }

    @Test
    public void testTrimUrl() {
        Assert.assertEquals("https://example.com", TerminalLinks.trimUrl("https://example.com."));
        Assert.assertEquals("https://example.com/x", TerminalLinks.trimUrl("https://example.com/x),"));
        Assert.assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", TerminalLinks.trimUrl("https://en.wikipedia.org/wiki/Foo_(bar)"));
        Assert.assertEquals("https://example.com/?a=1", TerminalLinks.trimUrl("https://example.com/?a=1"));
    }

}
