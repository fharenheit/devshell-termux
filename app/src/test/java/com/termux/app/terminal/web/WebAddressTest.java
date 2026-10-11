package com.termux.app.terminal.web;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28})
public class WebAddressTest {

    @Test
    public void testUrls() {
        Assert.assertNull(WebAddress.toUrl("  "));
        Assert.assertEquals("https://example.com/a?b=1", WebAddress.toUrl("https://example.com/a?b=1"));
        Assert.assertEquals("ftp://host/file", WebAddress.toUrl("ftp://host/file"));
        Assert.assertEquals("about:blank", WebAddress.toUrl("about:blank"));
    }

    @Test
    public void testHosts() {
        Assert.assertEquals("https://example.com", WebAddress.toUrl("example.com"));
        Assert.assertEquals("https://github.com/fharenheit", WebAddress.toUrl(" github.com/fharenheit "));
        // Local and private addresses usually serve http, like a forwarded port.
        Assert.assertEquals("http://localhost:8080", WebAddress.toUrl("localhost:8080"));
        Assert.assertEquals("http://localhost", WebAddress.toUrl("localhost"));
        Assert.assertEquals("http://192.168.0.10/admin", WebAddress.toUrl("192.168.0.10/admin"));
        Assert.assertEquals("http://10.1.2.3:3000", WebAddress.toUrl("10.1.2.3:3000"));
        Assert.assertEquals("http://nas.local", WebAddress.toUrl("nas.local"));
        Assert.assertEquals("http://[::1]:8080", WebAddress.toUrl("[::1]:8080"));
        Assert.assertEquals("https://8.8.8.8", WebAddress.toUrl("8.8.8.8"));
        Assert.assertEquals("https://devbox:9000", WebAddress.toUrl("devbox:9000"));
    }

    @Test
    public void testSearch() {
        Assert.assertEquals(WebAddress.SEARCH_URL + "termux%20ssh", WebAddress.toUrl("termux ssh"));
        Assert.assertEquals(WebAddress.SEARCH_URL + "kotlin", WebAddress.toUrl("kotlin"));
        Assert.assertEquals(WebAddress.SEARCH_URL + "%ED%95%9C%EA%B8%80", WebAddress.toUrl("한글"));
    }

    @Test
    public void testIsLocal() {
        Assert.assertTrue(WebAddress.isLocal("127.0.0.1"));
        Assert.assertTrue(WebAddress.isLocal("172.16.0.1"));
        Assert.assertFalse(WebAddress.isLocal("172.32.0.1"));
        Assert.assertFalse(WebAddress.isLocal("example.com"));
    }

}
