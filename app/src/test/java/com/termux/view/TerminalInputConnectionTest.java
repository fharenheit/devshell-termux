package com.termux.view;

import android.os.Build;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;

/** What the input connection of {@link TerminalView} sends to the terminal for what an IME does. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.P)
public class TerminalInputConnectionTest {

    private TerminalView mView;
    /** The queue of bytes to the process of the session, and its read(byte[], boolean). */
    private Object mSent;
    private Method mRead;
    private final StringBuilder mLine = new StringBuilder();

    @Before
    public void setUp() throws Exception {
        mView = new TerminalView(RuntimeEnvironment.application, null);
        mView.mClient = stub(TerminalViewClient.class);

        // A session without a process, whose writes are kept in its queue to the process.
        TerminalSessionClient sessionClient = stub(TerminalSessionClient.class);
        TerminalSession session = new TerminalSession("/system/bin/sh", "/", new String[0], new String[0], 100, sessionClient);
        TerminalEmulator emulator = new TerminalEmulator(session, 80, 24, 10, 20, 100, sessionClient);
        setField(session, "mEmulator", emulator);
        setField(session, "mShellPid", 1);
        mSent = getField(session, "mTerminalToProcessIOQueue");
        mRead = mSent.getClass().getDeclaredMethod("read", byte[].class, boolean.class);
        mRead.setAccessible(true);
        mView.mTermSession = session;
        mView.mEmulator = emulator;
    }

    /** An implementation of {@code type} doing nothing. */
    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            Class<?> returnType = method.getReturnType();
            if (returnType == boolean.class) return false;
            if (returnType == float.class) return 1f;
            if (returnType == int.class) return 0;
            return null;
        });
    }

    private static Field field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void setField(Object object, String name, Object value) throws Exception {
        field(object, name).set(object, value);
    }

    private static Object getField(Object object, String name) throws Exception {
        return field(object, name).get(object);
    }

    private InputConnection connect() {
        return mView.onCreateInputConnection(new EditorInfo());
    }

    /** The line the terminal would show, with the backspaces sent applied to it. */
    private String line() throws Exception {
        byte[] buffer = new byte[4096];
        int read = (int) mRead.invoke(mSent, buffer, false);
        if (read > 0) {
            String text = new String(buffer, 0, read, StandardCharsets.UTF_8);
            text.codePoints().forEach(codePoint -> {
                if (codePoint == 127) {
                    if (mLine.length() > 0) mLine.setLength(mLine.offsetByCodePoints(mLine.length(), -1));
                } else {
                    mLine.appendCodePoint(codePoint);
                }
            });
        }
        return mLine.toString();
    }

    @Test
    public void testComposingTextIsSentWhenCommitted() throws Exception {
        InputConnection connection = connect();
        connection.setComposingText("ㅇ", 1);
        connection.setComposingText("아", 1);
        connection.setComposingText("안", 1);
        Assert.assertEquals("", line());
        Assert.assertEquals("안", mView.mComposingText.toString());

        connection.commitText("안", 1);
        connection.setComposingText("ㄴ", 1);
        connection.setComposingText("녀", 1);
        Assert.assertEquals("안", line());
        Assert.assertEquals("녀", mView.mComposingText.toString());

        connection.finishComposingText();
        Assert.assertEquals("안녀", line());
        Assert.assertEquals("", mView.mComposingText.toString());
    }

    @Test
    public void testComposingTheWordBeforeTheCursorAgainDoesNotSendItAgain() throws Exception {
        InputConnection connection = connect();
        connection.commitText("안녕", 1);
        Assert.assertEquals("안녕", line());

        // E.g. after switching from Korean to English and back, the IME composes the last word again.
        connection.setComposingRegion(0, 2);
        connection.setComposingText("안녕하", 1);
        connection.commitText("안녕하", 1);
        Assert.assertEquals("안녕하", line());
    }

    @Test
    public void testKeysSentToTheTerminalStartInputAgain() throws Exception {
        InputConnection connection = connect();
        connection.commitText("안녕", 1);
        connection.setComposingText("하", 1);

        // A key typed in English: the composing text is sent first, and the old connection is not used anymore.
        mView.onKeyDown(KeyEvent.KEYCODE_A, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A));
        Assert.assertEquals("안녕하a", line());
        connection.commitText("안녕하세요", 1);
        Assert.assertEquals("안녕하a", line());

        InputConnection newConnection = connect();
        newConnection.setComposingRegion(0, 3);
        newConnection.commitText("요", 1);
        Assert.assertEquals("안녕하a요", line());
    }

    @Test
    public void testKeysAfterOnlyKeysDoNotStartInputAgain() throws Exception {
        InputConnection connection = connect();
        mView.onKeyDown(KeyEvent.KEYCODE_A, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A));
        connection.commitText("가", 1);
        Assert.assertEquals("a가", line());
    }

    @Test
    public void testDeletingTextBeforeTheCommittedTextSendsBackspaces() throws Exception {
        InputConnection connection = connect();
        mView.onKeyDown(KeyEvent.KEYCODE_X, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_X));
        mView.onKeyDown(KeyEvent.KEYCODE_Y, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Y));
        connection.commitText("가나", 1);
        Assert.assertEquals("xy가나", line());

        // E.g. the stock Samsung keyboard with 'Auto check spelling' deletes more than it committed.
        connection.deleteSurroundingText(3, 0);
        Assert.assertEquals("x", line());
        connection.commitText("다", 1);
        Assert.assertEquals("x다", line());
    }

}
