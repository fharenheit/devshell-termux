package com.termux.app.terminal.fonts;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.TextView;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28})
public class TerminalFontsTest {

    @Rule public TemporaryFolder mTemp = new TemporaryFolder();

    /** The repository root, where the unit tests of the app module run in app/. */
    private static File repoFile(String path) {
        File file = new File("../" + path);
        return file.exists() ? file : new File(path);
    }

    /** The sizes and checksums in the font list match the font files in the repository. */
    @Test
    public void testFontFilesMatch() throws Exception {
        for (TerminalFonts.Font font : TerminalFonts.FONTS) {
            File file = font.assetPath != null ? repoFile("app/src/main/assets/" + font.assetPath)
                : repoFile("custom/fonts/" + font.downloadFileName);
            Assert.assertTrue(file.getPath(), file.isFile());
            Assert.assertEquals(font.name, font.size, file.length());
            if (font.sha256 != null) Assert.assertEquals(font.name, font.sha256, sha256(file));
        }
    }

    private static String sha256(File file) throws Exception {
        return TerminalFonts.toHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())));
    }

    /** A minimal HTTP server that answers one request with the content, since com.sun.net.httpserver is not available here. */
    private static ServerSocket serve(byte[] content) throws IOException {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        Thread thread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) { /* Skip the request. */ }
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + content.length + "\r\nConnection: close\r\n\r\n").getBytes());
                out.write(content);
                out.flush();
            } catch (IOException ignored) {
            }
        });
        thread.setDaemon(true);
        thread.start();
        return server;
    }

    private static String url(ServerSocket server) {
        return "http://127.0.0.1:" + server.getLocalPort() + "/";
    }

    private static TerminalFonts.Font nanum() {
        return TerminalFonts.getFont("nanum-gothic-coding");
    }

    @Test
    public void testDownload() throws Exception {
        TerminalFonts.Font font = nanum();
        byte[] content = Files.readAllBytes(repoFile("custom/fonts/" + font.downloadFileName).toPath());
        ServerSocket server = serve(content);
        try {
            File dir = mTemp.newFolder();
            long[] lastProgress = {0};
            TerminalFonts.download(font, url(server), dir, new AtomicBoolean(),
                (downloaded, total) -> lastProgress[0] = downloaded);
            File file = new File(dir, font.downloadFileName);
            Assert.assertEquals(font.size, file.length());
            Assert.assertEquals(font.size, lastProgress[0]);
            Assert.assertFalse(new File(dir, font.downloadFileName + ".download").exists());
        } finally {
            server.close();
        }
    }

    @Test
    public void testDownloadWithWrongChecksumIsRejected() throws Exception {
        TerminalFonts.Font font = nanum();
        byte[] content = Files.readAllBytes(repoFile("custom/fonts/" + font.downloadFileName).toPath());
        content[1000] ^= 1;
        ServerSocket server = serve(content);
        File dir = mTemp.newFolder();
        try {
            TerminalFonts.download(font, url(server), dir, new AtomicBoolean(), null);
            Assert.fail("A download that does not match the checksum was accepted");
        } catch (IOException e) {
            Assert.assertTrue(e.getMessage(), e.getMessage().contains("checksum"));
        } finally {
            server.close();
        }
        Assert.assertFalse(new File(dir, font.downloadFileName).exists());
        Assert.assertFalse(new File(dir, font.downloadFileName + ".download").exists());
    }

    @Test
    public void testDownloadCancelled() throws Exception {
        TerminalFonts.Font font = nanum();
        ServerSocket server = serve(new byte[(int) font.size]);
        File dir = mTemp.newFolder();
        try {
            TerminalFonts.download(font, url(server), dir, new AtomicBoolean(true), null);
            Assert.fail("A cancelled download finished");
        } catch (InterruptedIOException expected) {
        } finally {
            server.close();
        }
        Assert.assertFalse(new File(dir, font.downloadFileName).exists());
    }

    @Test
    public void testPreviewHasAllSampleLines() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TerminalFontPreviewView preview = new TerminalFontPreviewView(activity);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.measure(View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        preview.layout(0, 0, 2000, preview.getMeasuredHeight());
        Assert.assertTrue(preview.getMeasuredHeight() > 0);
        preview.draw(new Canvas(Bitmap.createBitmap(2000, preview.getMeasuredHeight(), Bitmap.Config.ARGB_8888)));

        // Every sample line is on the screen of the emulator that is drawn.
        com.termux.terminal.TerminalEmulator emulator;
        try {
            java.lang.reflect.Field field = TerminalFontPreviewView.class.getDeclaredField("mEmulator");
            field.setAccessible(true);
            emulator = (com.termux.terminal.TerminalEmulator) field.get(preview);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        String screen = emulator.getScreen().getTranscriptText();
        for (String line : TerminalFontPreviewView.SAMPLE_LINES)
            Assert.assertTrue(line + " in " + screen, screen.contains(line));
    }

    @Test
    public void testDialogLists() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TerminalFontDialog.show(activity, null);
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        ListAdapter adapter = dialog.getListView().getAdapter();
        Assert.assertEquals(TerminalFonts.FONTS.size(), adapter.getCount());
        int sarasa = TerminalFonts.FONTS.indexOf(TerminalFonts.getFont("sarasa-mono-k"));
        LinearLayout row = (LinearLayout) adapter.getView(sarasa, null, dialog.getListView());
        Assert.assertEquals("Sarasa Mono K", ((TextView) row.getChildAt(0)).getText().toString());
        Assert.assertEquals("EN 한 日 中 Кир · ↓ 24.3 MB", ((TextView) row.getChildAt(1)).getText().toString());
        int cascadia = TerminalFonts.FONTS.indexOf(TerminalFonts.getFont("cascadia-mono"));
        row = (LinearLayout) adapter.getView(cascadia, null, dialog.getListView());
        Assert.assertEquals("EN Кир ع ש", ((TextView) row.getChildAt(1)).getText().toString());
    }

}
