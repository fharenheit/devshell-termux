package com.termux.app.terminal.backup;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The zip file of exported settings: a flat list of known entries, read back with limits, so
 * that an imported file can't write anywhere else or exhaust memory.
 */
final class SettingsBackupArchive {

    static final String ENTRY_MANIFEST = "manifest.json";
    static final String ENTRY_PROPERTIES = "termux.properties";
    static final String ENTRY_COLORS = "colors.properties";
    static final String ENTRY_SSH_HOSTS = "ssh_hosts.json";
    static final String ENTRY_SNIPPETS = "snippets.json";
    static final String ENTRY_SPECIAL_KEYS_FAVORITES = "special_keys_favorites.json";
    static final String ENTRY_PREFERENCES = "preferences.json";
    static final String ENTRY_FONT = "font.json";
    static final String ENTRY_FONT_FILE = "font.ttf";

    static final Set<String> ENTRIES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        ENTRY_MANIFEST, ENTRY_PROPERTIES, ENTRY_COLORS, ENTRY_SSH_HOSTS, ENTRY_SNIPPETS, ENTRY_SPECIAL_KEYS_FAVORITES, ENTRY_PREFERENCES, ENTRY_FONT, ENTRY_FONT_FILE)));

    /** The size limit of a text entry. */
    static final int MAX_TEXT_ENTRY_SIZE = 1024 * 1024;
    /** The size limit of the font file entry. */
    static final int MAX_FONT_ENTRY_SIZE = 64 * 1024 * 1024;

    private SettingsBackupArchive() {}

    static void write(@NonNull Map<String, byte[]> entries, @NonNull OutputStream out) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                if (!ENTRIES.contains(entry.getKey())) throw new IllegalArgumentException(entry.getKey());
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
    }

    /**
     * Read the known entries of a settings zip file. Unknown entries, like ones with directories,
     * are skipped, and a file without a manifest or with too large entries is rejected.
     */
    @NonNull
    static Map<String, byte[]> read(@NonNull InputStream in) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !ENTRIES.contains(name) || entries.containsKey(name)) continue;
                int limit = ENTRY_FONT_FILE.equals(name) ? MAX_FONT_ENTRY_SIZE : MAX_TEXT_ENTRY_SIZE;
                entries.put(name, readLimited(zip, limit, name));
            }
        }
        if (!entries.containsKey(ENTRY_MANIFEST)) throw new IOException("Not a Termux settings file: it has no " + ENTRY_MANIFEST);
        return entries;
    }

    @NonNull
    private static byte[] readLimited(@NonNull InputStream in, int limit, @NonNull String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > limit) throw new IOException("The " + name + " entry is larger than " + limit + " bytes");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

}
