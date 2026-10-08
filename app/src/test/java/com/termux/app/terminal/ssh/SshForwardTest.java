package com.termux.app.terminal.ssh;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class SshForwardTest {

    @Test
    public void testParse() {
        SshForward local = SshForward.parse(SshForward.LOCAL, "8080:localhost:80");
        Assert.assertNotNull(local);
        Assert.assertEquals(8080, local.port);
        Assert.assertEquals("localhost", local.host);
        Assert.assertEquals(80, local.hostPort);
        Assert.assertEquals("8080:localhost:80", local.toSpec());
        Assert.assertEquals("-L", local.getOption());
        Assert.assertEquals("http://localhost:8080", local.getBrowserUrl());
        Assert.assertEquals("localhost:8080 → localhost:80", local.getLabel("web"));

        SshForward bound = SshForward.parse(SshForward.LOCAL, "0.0.0.0:8443:[::1]:443");
        Assert.assertNotNull(bound);
        Assert.assertEquals("0.0.0.0", bound.bindAddress);
        Assert.assertEquals("::1", bound.host);
        Assert.assertEquals("0.0.0.0:8443:[::1]:443", bound.toSpec());
        Assert.assertEquals("https://localhost:8443", bound.getBrowserUrl());

        SshForward remote = SshForward.parse(SshForward.REMOTE, "9000:localhost:3000");
        Assert.assertNotNull(remote);
        Assert.assertFalse(remote.listensOnDevice());
        Assert.assertEquals("web:9000 → localhost:3000", remote.getLabel("web"));

        SshForward socks = SshForward.parse(SshForward.SOCKS, "1080");
        Assert.assertNotNull(socks);
        Assert.assertEquals("1080", socks.toSpec());
        Assert.assertEquals("localhost:1080 (SOCKS)", socks.getLabel("web"));

        Assert.assertNull(SshForward.parse(SshForward.LOCAL, "8080"));
        Assert.assertNull(SshForward.parse(SshForward.LOCAL, "99999:localhost:80"));
        Assert.assertNull(SshForward.parse(SshForward.SOCKS, "socks"));
    }

    @Test
    public void testFromHost() {
        SshHost host = new SshHost();
        host.localForwards = "8080:localhost:80\n\n5432:db:5432";
        host.remoteForwards = "9000:localhost:3000";
        host.socksPort = "1080";
        List<SshForward> forwards = SshForward.fromHost(host);
        Assert.assertEquals(4, forwards.size());
        Assert.assertEquals(SshForward.LOCAL, forwards.get(0).type);
        Assert.assertEquals(SshForward.REMOTE, forwards.get(2).type);
        Assert.assertEquals(SshForward.SOCKS, forwards.get(3).type);
        Assert.assertEquals(forwards.get(0), SshForward.parse(SshForward.LOCAL, "8080:localhost:80"));
    }

}
