package com.termux.app.terminal.ssh;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class SshHostKeysTest {

    private static final String ED25519 = "[127.0.0.1]:2224 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGY7SZEdVXOTpBc3";
    private static final String ED25519_OTHER = "[127.0.0.1]:2224 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOTHERKEY";
    private static final String RSA = "[127.0.0.1]:2224 ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQ";

    @Test
    public void testCompare() {
        Assert.assertEquals(SshHostKeys.UNKNOWN, SshHostKeys.compare(ED25519, Collections.emptyList()));
        Assert.assertEquals(SshHostKeys.TRUSTED, SshHostKeys.compare(ED25519, Arrays.asList(RSA, ED25519)));
        // Hashed names match by key too.
        Assert.assertEquals(SshHostKeys.TRUSTED, SshHostKeys.compare(ED25519,
            Collections.singletonList("|1|abc=|def= ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGY7SZEdVXOTpBc3")));
        // Another key of the same type: it changed.
        Assert.assertEquals(SshHostKeys.CHANGED, SshHostKeys.compare(ED25519, Collections.singletonList(ED25519_OTHER)));
        // Only a key of another type is known: this one is new, not changed.
        Assert.assertEquals(SshHostKeys.UNKNOWN, SshHostKeys.compare(ED25519, Collections.singletonList(RSA)));
        // Revoked and CA lines are not trusted keys.
        Assert.assertEquals(SshHostKeys.UNKNOWN, SshHostKeys.compare(ED25519,
            Collections.singletonList("@revoked " + ED25519)));
    }

    @Test
    public void testConfig() {
        String config = "user tester\nhostname example.com\nport 2222\nuserknownhostsfile ~/.ssh/known_hosts ~/.ssh/known_hosts2\n";
        Assert.assertEquals("[example.com]:2222", SshHostKeys.getKnownHostsName(config));
        Assert.assertTrue(SshHostKeys.getKnownHostsFile(config).getPath().endsWith("/.ssh/known_hosts"));
        Assert.assertEquals("example.com", SshHostKeys.getKnownHostsName("hostname example.com\nport 22\n"));
        Assert.assertEquals("[alias]:2222", SshHostKeys.getKnownHostsName("hostname 10.0.0.1\nhostkeyalias alias\nport 2222\n"));
    }

    @Test
    public void testFingerprint() {
        Assert.assertEquals("ED25519 SHA256:1mYyjqfM/k1GNvCxXPdsDLTd1X61M7PtqFAdTQYzUsE",
            SshHostKeys.formatFingerprint("256 SHA256:1mYyjqfM/k1GNvCxXPdsDLTd1X61M7PtqFAdTQYzUsE [127.0.0.1]:2224 (ED25519)\n"));
    }

}
