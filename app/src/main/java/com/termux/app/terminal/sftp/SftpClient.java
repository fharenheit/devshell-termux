package com.termux.app.terminal.sftp;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A client of the SFTP protocol version 3, which OpenSSH implements, over the input and output
 * of an `ssh -s <host> sftp` process. It has no Android dependencies, so that it can be tested
 * against a real server on the JVM.
 *
 * Reads and writes keep several requests in flight, since each request otherwise waits for a
 * round trip to the server. The methods are synchronized, so one operation runs at a time.
 *
 * https://datatracker.ietf.org/doc/html/draft-ietf-secsh-filexfer-02
 */
public class SftpClient implements Closeable {

    // Packet types
    private static final int SSH_FXP_INIT = 1;
    private static final int SSH_FXP_VERSION = 2;
    private static final int SSH_FXP_OPEN = 3;
    private static final int SSH_FXP_CLOSE = 4;
    private static final int SSH_FXP_READ = 5;
    private static final int SSH_FXP_WRITE = 6;
    private static final int SSH_FXP_LSTAT = 7;
    private static final int SSH_FXP_SETSTAT = 9;
    private static final int SSH_FXP_OPENDIR = 11;
    private static final int SSH_FXP_READDIR = 12;
    private static final int SSH_FXP_REMOVE = 13;
    private static final int SSH_FXP_MKDIR = 14;
    private static final int SSH_FXP_RMDIR = 15;
    private static final int SSH_FXP_REALPATH = 16;
    private static final int SSH_FXP_STAT = 17;
    private static final int SSH_FXP_RENAME = 18;
    private static final int SSH_FXP_STATUS = 101;
    private static final int SSH_FXP_HANDLE = 102;
    private static final int SSH_FXP_DATA = 103;
    private static final int SSH_FXP_NAME = 104;
    private static final int SSH_FXP_ATTRS = 105;
    private static final int SSH_FXP_EXTENDED = 200;

    // Open flags
    private static final int SSH_FXF_READ = 0x01;
    private static final int SSH_FXF_WRITE = 0x02;
    private static final int SSH_FXF_CREAT = 0x08;
    private static final int SSH_FXF_TRUNC = 0x10;
    private static final int SSH_FXF_EXCL = 0x20;

    // Status codes
    public static final int SSH_FX_OK = 0;
    public static final int SSH_FX_EOF = 1;
    public static final int SSH_FX_NO_SUCH_FILE = 2;
    public static final int SSH_FX_PERMISSION_DENIED = 3;
    public static final int SSH_FX_FAILURE = 4;

    /** The OpenSSH extension that renames over an existing file, like rename(2). */
    static final String EXTENSION_POSIX_RENAME = "posix-rename@openssh.com";

    /** The data size of a read or write request, which OpenSSH allows up to 256 KiB of. */
    private static final int CHUNK_SIZE = 32 * 1024;
    /** The number of read or write requests in flight. */
    private static final int MAX_REQUESTS_IN_FLIGHT = 16;

    private static final int MAX_PACKET_SIZE = 1024 * 1024;

    private final DataInputStream mIn;
    private final OutputStream mOut;
    private final Closeable mCloseable;
    private int mNextRequestId = 1;
    /** Responses read while waiting for another request. */
    private final Map<Integer, Packet> mPendingResponses = new HashMap<>();
    private final Map<String, String> mExtensions = new HashMap<>();

    /** File attributes. Fields are -1 when the server did not send them. */
    public static final class Attributes {
        public static final int FLAG_SIZE = 0x01;
        public static final int FLAG_UIDGID = 0x02;
        public static final int FLAG_PERMISSIONS = 0x04;
        public static final int FLAG_ACMODTIME = 0x08;
        public static final int FLAG_EXTENDED = 0x80000000;

        public static final int S_IFMT = 0170000;
        public static final int S_IFDIR = 0040000;
        public static final int S_IFREG = 0100000;
        public static final int S_IFLNK = 0120000;

        public long size = -1;
        public int uid = -1;
        public int gid = -1;
        public int permissions = -1;
        public long atime = -1;
        public long mtime = -1;

        public boolean isDirectory() {
            return permissions != -1 && (permissions & S_IFMT) == S_IFDIR;
        }

        public boolean isRegularFile() {
            return permissions != -1 && (permissions & S_IFMT) == S_IFREG;
        }

        public boolean isSymlink() {
            return permissions != -1 && (permissions & S_IFMT) == S_IFLNK;
        }
    }

    /** A directory entry. */
    public static final class Entry {
        public final String name;
        /** The `ls -l` like line of the server. */
        public final String longName;
        public final Attributes attributes;

        Entry(String name, String longName, Attributes attributes) {
            this.name = name;
            this.longName = longName;
            this.attributes = attributes;
        }
    }

    /** An error status of the server. */
    public static class SftpException extends IOException {
        public final int status;

        SftpException(int status, String message) {
            super(message.isEmpty() ? "SFTP error " + status : message);
            this.status = status;
        }
    }

    public interface ProgressListener {
        void onProgress(long done, long total);
    }

    /**
     * @param in The output of the sftp subsystem.
     * @param out The input of the sftp subsystem.
     * @param closeable Closed with the client, like the ssh process.
     */
    public SftpClient(InputStream in, OutputStream out, Closeable closeable) {
        mIn = new DataInputStream(in);
        mOut = out;
        mCloseable = closeable;
    }

    /** Start the session. Must be called first. */
    public synchronized void init() throws IOException {
        Packet init = new Packet(SSH_FXP_INIT);
        init.putInt(3);
        send(init);
        Packet version = readPacket();
        if (version.type != SSH_FXP_VERSION) throw new IOException("Unexpected SFTP packet " + version.type + " instead of the version");
        int serverVersion = version.getInt();
        if (serverVersion < 3) throw new IOException("Unsupported SFTP version " + serverVersion);
        while (version.remaining() > 0) mExtensions.put(version.getString(), version.getString());
    }

    public synchronized boolean hasExtension(String name) {
        return mExtensions.containsKey(name);
    }

    @Override
    public void close() throws IOException {
        try {
            mOut.close();
        } catch (IOException ignored) {
        }
        if (mCloseable != null) mCloseable.close();
    }

    /** Get the absolute canonical path, like `.` for the home directory. */
    public synchronized String realPath(String path) throws IOException {
        Packet response = request(newRequest(SSH_FXP_REALPATH).putString(path));
        checkNameCount(response);
        return response.getString();
    }

    /** Get the attributes of a file, following symlinks. */
    public synchronized Attributes stat(String path) throws IOException {
        return expectAttributes(request(newRequest(SSH_FXP_STAT).putString(path)));
    }

    /** Get the attributes of a file without following symlinks. */
    public synchronized Attributes lstat(String path) throws IOException {
        return expectAttributes(request(newRequest(SSH_FXP_LSTAT).putString(path)));
    }

    /** List a directory, without `.` and `..`. */
    public synchronized List<Entry> list(String path) throws IOException {
        byte[] handle = expectHandle(request(newRequest(SSH_FXP_OPENDIR).putString(path)));
        List<Entry> entries = new ArrayList<>();
        try {
            while (true) {
                Packet response = request(newRequest(SSH_FXP_READDIR).putBytes(handle));
                if (response.type == SSH_FXP_STATUS) {
                    int status = response.getInt();
                    if (status == SSH_FX_EOF) break;
                    throw new SftpException(status, response.getString());
                }
                int count = checkNameCount(response);
                for (int i = 0; i < count; i++) {
                    String name = response.getString();
                    String longName = response.getString();
                    Attributes attributes = response.getAttributes();
                    if (!name.equals(".") && !name.equals("..")) entries.add(new Entry(name, longName, attributes));
                }
            }
        } finally {
            closeHandle(handle);
        }
        return entries;
    }

    /**
     * Read a file to the output, up to {@code maxBytes}.
     *
     * @return The number of bytes read.
     */
    public synchronized long read(String path, OutputStream out, long maxBytes, ProgressListener listener,
                                  AtomicBoolean cancelled) throws IOException {
        byte[] handle = expectHandle(request(newRequest(SSH_FXP_OPEN).putString(path).putInt(SSH_FXF_READ).putAttributes(null)));
        long total = -1;
        try {
            try {
                total = fstatSize(path);
            } catch (IOException ignored) {
            }
            // Requests in flight by id, with their offsets, answered in order by OpenSSH but matched by id.
            Deque<long[]> inFlight = new ArrayDeque<>();
            long nextOffset = 0;
            long written = 0;
            boolean eof = false;
            while (!eof || !inFlight.isEmpty()) {
                while (!eof && inFlight.size() < MAX_REQUESTS_IN_FLIGHT && nextOffset < maxBytes) {
                    int length = (int) Math.min(CHUNK_SIZE, maxBytes - nextOffset);
                    Packet request = newRequest(SSH_FXP_READ).putBytes(handle).putLong(nextOffset).putInt(length);
                    send(request);
                    inFlight.add(new long[]{request.id, nextOffset, length});
                    nextOffset += length;
                }
                if (inFlight.isEmpty()) break;
                if (cancelled != null && cancelled.get()) throw new InterruptedIOException("Cancelled");
                long[] next = inFlight.poll();
                Packet response = readResponse((int) next[0]);
                if (response.type == SSH_FXP_STATUS) {
                    int status = response.getInt();
                    if (status != SSH_FX_EOF) throw new SftpException(status, response.getString());
                    eof = true;
                    continue;
                }
                if (response.type != SSH_FXP_DATA) throw new IOException("Unexpected SFTP packet " + response.type);
                byte[] data = response.getBytes();
                if (next[1] != written) throw new IOException("Out of order SFTP read at " + next[1]);
                out.write(data);
                written += data.length;
                if (listener != null) listener.onProgress(written, total);
                if (data.length < next[2]) {
                    // A short read, so the file ended or the server sends less at a time. Read the
                    // rest after the requests in flight, which are answered with EOF or data.
                    for (long[] request : inFlight) readResponse((int) request[0]);
                    inFlight.clear();
                    nextOffset = written;
                }
            }
            return written;
        } finally {
            closeHandle(handle);
        }
    }

    /** Read a whole file into memory, failing if it is larger than {@code maxBytes}. */
    public byte[] readAll(String path, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        read(path, out, maxBytes + 1L, null, null);
        if (out.size() > maxBytes) throw new IOException("The file is larger than " + maxBytes + " bytes");
        return out.toByteArray();
    }

    private long fstatSize(String path) throws IOException {
        Attributes attributes = stat(path);
        return attributes.size;
    }

    /**
     * Write the input to a file, creating it or replacing its content.
     *
     * @param permissions The permissions of a created file, or -1 for the server default.
     * @param exclusive Whether to fail if the file exists.
     * @return The number of bytes written.
     */
    public synchronized long write(String path, InputStream in, long total, int permissions, boolean exclusive,
                                   ProgressListener listener, AtomicBoolean cancelled) throws IOException {
        Attributes attributes = null;
        if (permissions != -1) {
            attributes = new Attributes();
            attributes.permissions = permissions & 07777;
        }
        int flags = SSH_FXF_WRITE | SSH_FXF_CREAT | (exclusive ? SSH_FXF_EXCL : SSH_FXF_TRUNC);
        byte[] handle = expectHandle(request(newRequest(SSH_FXP_OPEN).putString(path).putInt(flags).putAttributes(attributes)));
        try {
            // Requests in flight as {id, length}.
            Deque<int[]> inFlight = new ArrayDeque<>();
            byte[] buffer = new byte[CHUNK_SIZE];
            long offset = 0;
            long acknowledged = 0;
            boolean done = false;
            while (!done || !inFlight.isEmpty()) {
                while (!done && inFlight.size() < MAX_REQUESTS_IN_FLIGHT) {
                    int length = readFully(in, buffer);
                    if (length <= 0) {
                        done = true;
                        break;
                    }
                    Packet request = newRequest(SSH_FXP_WRITE).putBytes(handle).putLong(offset).putBytes(buffer, 0, length);
                    send(request);
                    inFlight.add(new int[]{request.id, length});
                    offset += length;
                    if (length < buffer.length) done = true;
                }
                if (inFlight.isEmpty()) break;
                if (cancelled != null && cancelled.get()) throw new InterruptedIOException("Cancelled");
                int[] next = inFlight.poll();
                expectOk(readResponse(next[0]));
                acknowledged += next[1];
                if (listener != null) listener.onProgress(acknowledged, total);
            }
            return offset;
        } finally {
            closeHandle(handle);
        }
    }

    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int length = 0;
        while (length < buffer.length) {
            int read = in.read(buffer, length, buffer.length - length);
            if (read == -1) break;
            length += read;
        }
        return length;
    }

    /**
     * Rename a file. With {@code overwrite}, an existing target is replaced atomically with the
     * OpenSSH `posix-rename` extension, which is required for it.
     */
    public synchronized void rename(String from, String to, boolean overwrite) throws IOException {
        if (overwrite) {
            if (!hasExtension(EXTENSION_POSIX_RENAME)) throw new IOException("The server can't replace files when renaming");
            expectOk(request(newRequest(SSH_FXP_EXTENDED).putString(EXTENSION_POSIX_RENAME).putString(from).putString(to)));
        } else {
            expectOk(request(newRequest(SSH_FXP_RENAME).putString(from).putString(to)));
        }
    }

    public synchronized void remove(String path) throws IOException {
        expectOk(request(newRequest(SSH_FXP_REMOVE).putString(path)));
    }

    public synchronized void rmdir(String path) throws IOException {
        expectOk(request(newRequest(SSH_FXP_RMDIR).putString(path)));
    }

    public synchronized void mkdir(String path) throws IOException {
        expectOk(request(newRequest(SSH_FXP_MKDIR).putString(path).putAttributes(null)));
    }

    /** Set the permission bits of a file. */
    public synchronized void chmod(String path, int permissions) throws IOException {
        Attributes attributes = new Attributes();
        attributes.permissions = permissions & 07777;
        expectOk(request(newRequest(SSH_FXP_SETSTAT).putString(path).putAttributes(attributes)));
    }

    /** Remove a directory and everything in it, without following symlinks. */
    public synchronized void removeRecursively(String path, AtomicBoolean cancelled) throws IOException {
        Attributes attributes = lstat(path);
        if (!attributes.isDirectory()) {
            remove(path);
            return;
        }
        for (Entry entry : list(path)) {
            if (cancelled != null && cancelled.get()) throw new InterruptedIOException("Cancelled");
            removeRecursively(SftpPaths.join(path, entry.name), cancelled);
        }
        rmdir(path);
    }

    private void closeHandle(byte[] handle) throws IOException {
        expectOk(request(newRequest(SSH_FXP_CLOSE).putBytes(handle)));
    }

    // Requests and responses

    private Packet newRequest(int type) {
        Packet packet = new Packet(type);
        packet.id = mNextRequestId++;
        packet.putInt(packet.id);
        return packet;
    }

    private Packet request(Packet request) throws IOException {
        send(request);
        return readResponse(request.id);
    }

    private void send(Packet packet) throws IOException {
        byte[] payload = packet.toByteArray();
        byte[] length = {(byte) (payload.length >>> 24), (byte) (payload.length >>> 16), (byte) (payload.length >>> 8), (byte) payload.length};
        mOut.write(length);
        mOut.write(payload);
        mOut.flush();
    }

    /** Read the response of a request, keeping responses of other requests for later. */
    private Packet readResponse(int id) throws IOException {
        Packet pending = mPendingResponses.remove(id);
        if (pending != null) return pending;
        while (true) {
            Packet packet = readPacket();
            int responseId = packet.getInt();
            if (responseId == id) return packet;
            mPendingResponses.put(responseId, packet);
        }
    }

    private Packet readPacket() throws IOException {
        int length;
        try {
            length = mIn.readInt();
        } catch (EOFException e) {
            throw new EOFException("The SFTP connection was closed");
        }
        if (length < 1 || length > MAX_PACKET_SIZE) throw new IOException("Invalid SFTP packet length " + length);
        byte[] payload = new byte[length];
        mIn.readFully(payload);
        return new Packet(payload);
    }

    private static void expectOk(Packet response) throws IOException {
        if (response.type != SSH_FXP_STATUS) throw new IOException("Unexpected SFTP packet " + response.type);
        int status = response.getInt();
        if (status != SSH_FX_OK) throw new SftpException(status, response.getString());
    }

    private static byte[] expectHandle(Packet response) throws IOException {
        throwIfStatus(response);
        if (response.type != SSH_FXP_HANDLE) throw new IOException("Unexpected SFTP packet " + response.type);
        return response.getBytes();
    }

    private static Attributes expectAttributes(Packet response) throws IOException {
        throwIfStatus(response);
        if (response.type != SSH_FXP_ATTRS) throw new IOException("Unexpected SFTP packet " + response.type);
        return response.getAttributes();
    }

    private static int checkNameCount(Packet response) throws IOException {
        throwIfStatus(response);
        if (response.type != SSH_FXP_NAME) throw new IOException("Unexpected SFTP packet " + response.type);
        return response.getInt();
    }

    private static void throwIfStatus(Packet response) throws IOException {
        if (response.type == SSH_FXP_STATUS) {
            int status = response.getInt();
            throw new SftpException(status, response.getString());
        }
    }

    /** A packet being built or read: the type, and for requests the id, followed by the fields. */
    private static final class Packet {
        final int type;
        int id;
        private final ByteArrayOutputStream mOut;
        private final byte[] mData;
        private int mPosition;

        /** A packet to send. */
        Packet(int type) {
            this.type = type;
            mOut = new ByteArrayOutputStream();
            mOut.write(type);
            mData = null;
        }

        /** A received packet, positioned after its type. */
        Packet(byte[] payload) {
            type = payload[0] & 0xff;
            mOut = null;
            mData = payload;
            mPosition = 1;
        }

        byte[] toByteArray() {
            return mOut.toByteArray();
        }

        Packet putInt(int value) {
            mOut.write(value >>> 24);
            mOut.write(value >>> 16);
            mOut.write(value >>> 8);
            mOut.write(value);
            return this;
        }

        Packet putLong(long value) {
            putInt((int) (value >>> 32));
            putInt((int) value);
            return this;
        }

        Packet putBytes(byte[] bytes) {
            return putBytes(bytes, 0, bytes.length);
        }

        Packet putBytes(byte[] bytes, int offset, int length) {
            putInt(length);
            mOut.write(bytes, offset, length);
            return this;
        }

        Packet putString(String value) {
            return putBytes(value.getBytes(StandardCharsets.UTF_8));
        }

        Packet putAttributes(Attributes attributes) {
            if (attributes == null || attributes.permissions == -1) return putInt(0);
            putInt(Attributes.FLAG_PERMISSIONS);
            return putInt(attributes.permissions);
        }

        int remaining() {
            return mData.length - mPosition;
        }

        int getInt() throws IOException {
            if (remaining() < 4) throw new IOException("Truncated SFTP packet");
            int value = ((mData[mPosition] & 0xff) << 24) | ((mData[mPosition + 1] & 0xff) << 16) |
                ((mData[mPosition + 2] & 0xff) << 8) | (mData[mPosition + 3] & 0xff);
            mPosition += 4;
            return value;
        }

        long getLong() throws IOException {
            return ((long) getInt() << 32) | (getInt() & 0xffffffffL);
        }

        byte[] getBytes() throws IOException {
            int length = getInt();
            if (length < 0 || length > remaining()) throw new IOException("Truncated SFTP packet");
            byte[] bytes = new byte[length];
            System.arraycopy(mData, mPosition, bytes, 0, length);
            mPosition += length;
            return bytes;
        }

        String getString() throws IOException {
            if (remaining() == 0) return "";
            return new String(getBytes(), StandardCharsets.UTF_8);
        }

        Attributes getAttributes() throws IOException {
            Attributes attributes = new Attributes();
            int flags = getInt();
            if ((flags & Attributes.FLAG_SIZE) != 0) attributes.size = getLong();
            if ((flags & Attributes.FLAG_UIDGID) != 0) {
                attributes.uid = getInt();
                attributes.gid = getInt();
            }
            if ((flags & Attributes.FLAG_PERMISSIONS) != 0) attributes.permissions = getInt();
            if ((flags & Attributes.FLAG_ACMODTIME) != 0) {
                attributes.atime = getInt() & 0xffffffffL;
                attributes.mtime = getInt() & 0xffffffffL;
            }
            if ((flags & Attributes.FLAG_EXTENDED) != 0) {
                int count = getInt();
                for (int i = 0; i < count; i++) {
                    getString();
                    getString();
                }
            }
            return attributes;
        }
    }

    /** The extensions the server announced. */
    synchronized Map<String, String> getExtensions() {
        return Collections.unmodifiableMap(mExtensions);
    }

}
