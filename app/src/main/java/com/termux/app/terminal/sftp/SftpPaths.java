package com.termux.app.terminal.sftp;

/** Remote paths, which always use `/`. */
public final class SftpPaths {

    private SftpPaths() {}

    public static String join(String dir, String name) {
        if (dir.isEmpty()) return name;
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    /** The parent directory, or the path itself for `/`. */
    public static String parent(String path) {
        if (path.equals("/")) return "/";
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = trimmed.lastIndexOf('/');
        if (slash < 0) return ".";
        return slash == 0 ? "/" : trimmed.substring(0, slash);
    }

    public static String name(String path) {
        String trimmed = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = trimmed.lastIndexOf('/');
        return slash < 0 ? trimmed : trimmed.substring(slash + 1);
    }

    /** Whether a file name is valid to create: not empty, `.` or `..`, and without `/` or NUL. */
    public static boolean isValidName(String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..") &&
            name.indexOf('/') < 0 && name.indexOf('\0') < 0;
    }

    /** The `rwxr-xr-x` like string of permission bits, with the file type first. */
    public static String formatPermissions(int permissions) {
        if (permissions == -1) return "?";
        char type;
        switch (permissions & SftpClient.Attributes.S_IFMT) {
            case SftpClient.Attributes.S_IFDIR: type = 'd'; break;
            case SftpClient.Attributes.S_IFLNK: type = 'l'; break;
            case SftpClient.Attributes.S_IFREG: type = '-'; break;
            default: type = '?'; break;
        }
        StringBuilder out = new StringBuilder().append(type);
        String rwx = "rwxrwxrwx";
        for (int i = 0; i < 9; i++) out.append((permissions & (0400 >> i)) != 0 ? rwx.charAt(i) : '-');
        return out.toString();
    }

}
