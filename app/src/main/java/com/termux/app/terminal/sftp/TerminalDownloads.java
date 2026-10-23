package com.termux.app.terminal.sftp;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.terminal.backup.DownloadsDirectory;
import com.termux.app.terminal.ssh.SshConnector;
import com.termux.terminal.TerminalSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloading the files whose names are selected in an ssh tab, like in the output of `ls` or
 * `ls -l`, from the "More" context menu. The names are resolved against the current directory of
 * the remote login shell when the transfer runs.
 */
public final class TerminalDownloads {

    /** Selections longer than this are not offered for download. */
    private static final int MAX_SELECTION_LENGTH = 8192;
    private static final int MAX_LINES = 200;

    /**
     * The name of an `ls -l` line: the permissions, then anything up to the date (`Oct 23`, `23 Oct`
     * `10월 23` or `2026-10-23`) and the time or year, then the name.
     */
    private static final Pattern LS_LONG_LINE = Pattern.compile(
        "^[-dlbcpsD][-rwxsStT]{9}\\S*\\s.*?\\s(?:\\d{0,2}\\D\\S*\\s+\\d{1,2}|\\d{1,2}\\s+\\D\\S*|\\d{4}-\\d{2}-\\d{2})" +
            "\\s+(?:\\d{1,2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:\\s+[+-]\\d{4})?|\\d{4})\\s+(.+)$");

    /** Looks up a remote path, like {@link SftpClient#stat}. */
    interface PathLookup {
        @NonNull
        SftpClient.Attributes stat(@NonNull String path) throws IOException;
    }

    private TerminalDownloads() {}

    /** Whether the More menu offers to download the selected text of a session. */
    public static boolean canDownload(@Nullable TerminalSession session, @Nullable String selection) {
        return SshConnector.isSshSession(session) && !names(selection).isEmpty();
    }

    /** Queue the download of the files named in the selected text of an ssh tab. */
    public static void download(@NonNull Activity activity, @NonNull TerminalSession session, @Nullable String selection) {
        String hostName = SshConnector.getHostName(session);
        List<String> names = names(selection);
        if (hostName == null || names.isEmpty()) return;
        if (DownloadsDirectory.needsStoragePermission() &&
            activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0);
            Toast.makeText(activity, R.string.sftp_storage_permission, Toast.LENGTH_LONG).show();
            return;
        }

        // Read the shell's directory when the transfer runs, but keep its last OSC 7 report for when /proc is not available.
        String reportedDirectory = null;
        int remoteShellPid = 0;
        if (session.getEmulator() != null) {
            reportedDirectory = session.getEmulator().getCurrentWorkingDirectory();
            remoteShellPid = session.getEmulator().getRemoteShellPid();
        }
        String title = names.size() == 1 ? activity.getString(R.string.sftp_downloading, names.get(0))
            : activity.getString(R.string.sftp_downloading_items, names.size());
        SftpTransfers.get(activity).enqueue(hostName, SshConnector.getControlPath(session), title, -1, null,
            new ArrayList<>(), job(names, reportedDirectory, remoteShellPid));
        Toast.makeText(activity, activity.getString(R.string.sftp_transfer_queued_toast, title), Toast.LENGTH_SHORT).show();
    }

    @NonNull
    private static SftpTransfers.Job job(@NonNull List<String> names, @Nullable String reportedDirectory, int remoteShellPid) {
        return (context, client, transfer) -> {
            String directory = currentDirectory(client, reportedDirectory, remoteShellPid);
            List<SftpTransfers.DownloadItem> items = resolve(directory, names, client::stat);
            if (items.isEmpty())
                throw new IOException(context.getString(R.string.terminal_download_not_found, directory));
            return SftpTransfers.download(items).run(context, client, transfer);
        };
    }

    /** The directory of the remote login shell, else the one it last reported, else the home directory. */
    @NonNull
    static String currentDirectory(@NonNull SftpClient client, @Nullable String reportedDirectory, int remoteShellPid) throws IOException {
        if (remoteShellPid > 0) {
            try {
                return client.realPath("/proc/" + remoteShellPid + "/cwd");
            } catch (SftpClient.SftpException e) {
                if (!isMissing(e)) throw e;
            }
        }
        if (reportedDirectory != null && reportedDirectory.startsWith("/")) {
            try {
                return client.realPath(reportedDirectory);
            } catch (SftpClient.SftpException e) {
                if (!isMissing(e)) throw e;
            }
        }
        return client.realPath(".");
    }

    private static boolean isMissing(@NonNull SftpClient.SftpException e) {
        return e.status == SftpClient.SSH_FX_NO_SUCH_FILE || e.status == SftpClient.SSH_FX_PERMISSION_DENIED
            || e.status == SftpClient.SSH_FX_FAILURE;
    }

    /**
     * The names in a selection, one for each line that is not empty: the name of an `ls -l` line,
     * or else the line without quotes. Empty if the selection is too long to be file names.
     */
    @NonNull
    static List<String> names(@Nullable String selection) {
        List<String> names = new ArrayList<>();
        if (selection == null || selection.length() > MAX_SELECTION_LENGTH) return names;
        String[] lines = selection.split("\r?\n");
        if (lines.length > MAX_LINES) return names;
        for (String line : lines) {
            String name = lineName(line.trim());
            if (!name.isEmpty() && !names.contains(name)) names.add(name);
        }
        return names;
    }

    @NonNull
    static String lineName(@NonNull String line) {
        Matcher matcher = LS_LONG_LINE.matcher(line);
        if (matcher.matches()) {
            line = matcher.group(1);
            // A symlink is listed as `name -> target`.
            int arrow = line.indexOf(" -> ");
            if (arrow > 0 && matcher.group(0).startsWith("l")) line = line.substring(0, arrow);
        }
        return unquote(line.trim());
    }

    /** Remove the quotes and backslash escapes that `ls` adds around names with spaces or special characters. */
    @NonNull
    static String unquote(@NonNull String name) {
        if (name.length() >= 2) {
            char first = name.charAt(0);
            if ((first == '\'' || first == '"') && name.charAt(name.length() - 1) == first)
                return name.substring(1, name.length() - 1);
        }
        return name.replaceAll("\\\\([ '\"\\\\()\\[\\]&;$!])", "$1");
    }

    /**
     * The files and folders to download for the names. A name that is not found is tried without a
     * trailing `ls -F` indicator, and then as names separated by spaces, like a row of `ls`.
     */
    @NonNull
    static List<SftpTransfers.DownloadItem> resolve(@NonNull String directory, @NonNull List<String> names,
                                                   @NonNull PathLookup lookup) throws IOException {
        Map<String, SftpTransfers.DownloadItem> items = new LinkedHashMap<>();
        for (String name : names) {
            if (add(directory, name, lookup, items)) continue;
            if (name.length() > 1 && "*@=|".indexOf(name.charAt(name.length() - 1)) >= 0
                && add(directory, name.substring(0, name.length() - 1), lookup, items)) continue;
            String[] words = name.split("\\s+");
            if (words.length < 2) continue;
            for (String word : words) {
                word = unquote(word);
                if (add(directory, word, lookup, items)) continue;
                if (word.length() > 1 && "*@=|".indexOf(word.charAt(word.length() - 1)) >= 0)
                    add(directory, word.substring(0, word.length() - 1), lookup, items);
            }
        }
        return new ArrayList<>(items.values());
    }

    /** Add the file or folder of a name if it exists. */
    private static boolean add(@NonNull String directory, @NonNull String name, @NonNull PathLookup lookup,
                               @NonNull Map<String, SftpTransfers.DownloadItem> items) throws IOException {
        if (name.isEmpty() || name.indexOf('\0') >= 0) return false;
        String path = path(directory, name);
        SftpClient.Attributes attributes;
        try {
            attributes = lookup.stat(path);
        } catch (SftpClient.SftpException e) {
            if (isMissing(e)) return false;
            throw e;
        }
        if (!attributes.isDirectory() && !attributes.isRegularFile()) return false;
        String fileName = SftpPaths.name(path);
        if (fileName.isEmpty() || fileName.equals(".") || fileName.equals("..") || fileName.equals("/")) return false;
        if (!items.containsKey(path))
            items.put(path, new SftpTransfers.DownloadItem(path, fileName, attributes.isDirectory(), attributes.size));
        return true;
    }

    /** The remote path of a name: absolute, relative to the home directory with `~/`, or else to the directory. */
    @NonNull
    static String path(@NonNull String directory, @NonNull String name) {
        while (name.length() > 1 && name.endsWith("/")) name = name.substring(0, name.length() - 1);
        if (name.startsWith("/")) return name;
        if (name.equals("~")) return ".";
        if (name.startsWith("~/")) return name.substring(2);
        return SftpPaths.join(directory, name);
    }

}
