package com.termux.app.terminal.tabs;

import com.termux.app.terminal.ssh.SshHost;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28})
public class NewTabMenuTest {

    @Test
    public void testHostLabel() {
        SshHost host = new SshHost();
        host.name = "dev-server";
        host.hostName = "10.0.0.5";
        host.user = "alice";
        Assert.assertEquals("dev-server · alice@10.0.0.5", NewTabMenu.getHostLabel(host, false));
        host.port = 2222;
        host.useMosh = true;
        host.jumpHost = "bastion";
        Assert.assertEquals("dev-server · alice@10.0.0.5:2222 · mosh · ↪ bastion  ●", NewTabMenu.getHostLabel(host, true));
    }

}
