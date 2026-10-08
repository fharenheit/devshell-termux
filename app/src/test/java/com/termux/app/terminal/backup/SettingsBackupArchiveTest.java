package com.termux.app.terminal.backup;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SettingsBackupArchiveTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    public void testRoundTrip() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(SettingsBackupArchive.ENTRY_MANIFEST, bytes("{\"format\":1}"));
        entries.put(SettingsBackupArchive.ENTRY_PROPERTIES, bytes("terminal-tabs = bottom\n"));
        entries.put(SettingsBackupArchive.ENTRY_FONT_FILE, new byte[]{0, 1, 2, (byte) 255});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SettingsBackupArchive.write(entries, out);

        Map<String, byte[]> read = SettingsBackupArchive.read(new ByteArrayInputStream(out.toByteArray()));
        Assert.assertEquals(entries.keySet(), read.keySet());
        for (String name : entries.keySet()) Assert.assertArrayEquals(entries.get(name), read.get(name));
    }

    @Test
    public void testUnknownEntriesAreSkipped() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(SettingsBackupArchive.ENTRY_MANIFEST, bytes("{}"));
        entries.put("../../.bashrc", bytes("rm -rf ~"));
        entries.put("dir/termux.properties", bytes("x"));
        entries.put("other.txt", bytes("x"));
        Map<String, byte[]> read = SettingsBackupArchive.read(new ByteArrayInputStream(zip(entries)));
        Assert.assertEquals(1, read.size());
        Assert.assertTrue(read.containsKey(SettingsBackupArchive.ENTRY_MANIFEST));
    }

    @Test(expected = IOException.class)
    public void testWithoutManifestIsRejected() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(SettingsBackupArchive.ENTRY_PROPERTIES, bytes("x"));
        SettingsBackupArchive.read(new ByteArrayInputStream(zip(entries)));
    }

    @Test(expected = IOException.class)
    public void testNotZipIsRejected() throws IOException {
        SettingsBackupArchive.read(new ByteArrayInputStream(bytes("not a zip file")));
    }

    @Test(expected = IOException.class)
    public void testTooLargeEntryIsRejected() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(SettingsBackupArchive.ENTRY_MANIFEST, bytes("{}"));
        entries.put(SettingsBackupArchive.ENTRY_COLORS, new byte[SettingsBackupArchive.MAX_TEXT_ENTRY_SIZE + 1]);
        SettingsBackupArchive.read(new ByteArrayInputStream(zip(entries)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testWritingUnknownEntryFails() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("other.txt", bytes("x"));
        SettingsBackupArchive.write(entries, new ByteArrayOutputStream());
    }

}
