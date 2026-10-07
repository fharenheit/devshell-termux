package com.termux.app.terminal.ssh;

import org.json.JSONException;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28})
public class SshHostTest {

    private static SshHost newHost() {
        SshHost host = new SshHost();
        host.name = "my-server";
        host.hostName = "example.com";
        host.port = 2222;
        host.user = "me";
        host.identityFile = "~/.ssh/id_ed25519";
        host.extraOptions = "ProxyJump bastion\n# a comment\n\n  LocalForward 8080 localhost:80  ";
        host.startupCommand = "echo 100%";
        host.jumpHost = "me@gateway:2200";
        host.localForwards = "8080:localhost:80\n\n127.0.0.1:5432:db.lan:5432";
        host.remoteForwards = "9000:[::1]:3000";
        host.socksPort = "1080";
        host.autoReconnect = true;
        host.useMosh = true;
        return host;
    }

    @Test
    public void testConfigBlock() {
        Assert.assertEquals(
            "Host my-server\n" +
            "    HostName example.com\n" +
            "    Port 2222\n" +
            "    User me\n" +
            "    IdentityFile \"~/.ssh/id_ed25519\"\n" +
            "    RequestTTY yes\n" +
            "    RemoteCommand echo 100%%; exec \"$SHELL\" -l\n" +
            "    ProxyJump me@gateway:2200\n" +
            "    LocalForward 8080 localhost:80\n" +
            "    LocalForward 127.0.0.1:5432 db.lan:5432\n" +
            "    RemoteForward 9000 [::1]:3000\n" +
            "    DynamicForward 1080\n" +
            "    ProxyJump bastion\n" +
            "    LocalForward 8080 localhost:80\n" +
            "    ServerAliveInterval 30\n",
            newHost().toConfigBlock());
    }

    @Test
    public void testConfigBlockWithDefaults() {
        SshHost host = new SshHost();
        host.name = "plain";
        host.hostName = "10.0.0.1";
        Assert.assertEquals(
            "Host plain\n" +
            "    HostName 10.0.0.1\n" +
            "    ServerAliveInterval 30\n",
            host.toConfigBlock());
        Assert.assertEquals("10.0.0.1", host.getAddress());
    }

    @Test
    public void testJson() throws JSONException {
        SshHost host = SshHost.fromJson(newHost().toJson());
        Assert.assertEquals(newHost().toConfigBlock(), host.toConfigBlock());
        Assert.assertEquals("me@example.com:2222", host.getAddress());
        Assert.assertTrue(host.autoReconnect);
        Assert.assertTrue(host.useMosh);
    }

    @Test
    public void testOldJsonDefaults() throws JSONException {
        SshHost host = SshHost.fromJson(new org.json.JSONObject("{\"name\":\"old\",\"hostName\":\"h\",\"port\":22}"));
        Assert.assertEquals("", host.jumpHost);
        Assert.assertEquals("", host.localForwards);
        Assert.assertFalse(host.autoReconnect);
        Assert.assertFalse(host.useMosh);
    }

    @Test
    public void testForwards() {
        Assert.assertEquals("8080 localhost:80", SshHost.toForwardArguments("8080:localhost:80"));
        Assert.assertEquals("0.0.0.0:8080 10.0.0.2:80", SshHost.toForwardArguments("0.0.0.0:8080:10.0.0.2:80"));
        Assert.assertEquals("8080 [::1]:80", SshHost.toForwardArguments("8080:[::1]:80"));
        Assert.assertEquals("[::1]:8080 [fe80::2]:80", SshHost.toForwardArguments("[::1]:8080:[fe80::2]:80"));
        Assert.assertNull(SshHost.toForwardArguments("8080"));
        Assert.assertNull(SshHost.toForwardArguments("8080:localhost"));
        Assert.assertNull(SshHost.toForwardArguments("http:localhost:80"));
        Assert.assertNull(SshHost.toForwardArguments("8080:localhost:99999"));
        Assert.assertNull(SshHost.toForwardArguments(":localhost:80"));
        Assert.assertNull(SshHost.getInvalidForward("8080:localhost:80\n\n 9090:h:90 "));
        Assert.assertEquals("bad", SshHost.getInvalidForward("8080:localhost:80\nbad"));
    }

    @Test
    public void testIsValidName() {
        Assert.assertTrue(SshHost.isValidName("my-server.lan_2"));
        Assert.assertTrue(SshHost.isValidName("me@host"));
        Assert.assertFalse(SshHost.isValidName(""));
        Assert.assertFalse(SshHost.isValidName(null));
        Assert.assertFalse(SshHost.isValidName("two words"));
        Assert.assertFalse(SshHost.isValidName("web*"));
    }

}
