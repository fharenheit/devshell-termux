package com.termux.app.terminal.sftp;

import org.junit.Assert;
import org.junit.Test;

public class SftpPermissionsTest {

    @Test
    public void testParseOctal() {
        Assert.assertEquals(0755, SftpPermissionsDialog.parseOctal("755"));
        Assert.assertEquals(0644, SftpPermissionsDialog.parseOctal(" 0644 "));
        Assert.assertEquals(04755, SftpPermissionsDialog.parseOctal("4755"));
        Assert.assertEquals(-1, SftpPermissionsDialog.parseOctal("789"));
        Assert.assertEquals(-1, SftpPermissionsDialog.parseOctal("17777"));
        Assert.assertEquals(-1, SftpPermissionsDialog.parseOctal(""));
    }

}
