package com.termux.app.terminal.sftp;

/** Turns the error output of ssh into a message for the user. */
public final class SftpErrors {

    public static final int NOT_INSTALLED = 1;
    public static final int HOST_KEY = 2;
    public static final int LOGIN = 3;
    public static final int NO_SFTP = 4;
    public static final int OTHER = 5;

    private SftpErrors() {}

    /** The kind of the error of a failed ssh connection, from the error output of ssh. */
    public static int classify(String stderr) {
        if (stderr == null) return OTHER;
        if (stderr.contains("Host key verification failed") || stderr.contains("REMOTE HOST IDENTIFICATION HAS CHANGED"))
            return HOST_KEY;
        if (stderr.contains("Permission denied") || stderr.contains("Too many authentication failures"))
            return LOGIN;
        if (stderr.contains("subsystem request failed")) return NO_SFTP;
        return OTHER;
    }

    /** The last non-empty line of the error output, which is usually the reason. */
    public static String lastLine(String stderr) {
        if (stderr == null) return "";
        String[] lines = stderr.trim().split("\n");
        for (int i = lines.length - 1; i >= 0; i--)
            if (!lines[i].trim().isEmpty()) return lines[i].trim();
        return "";
    }

}
