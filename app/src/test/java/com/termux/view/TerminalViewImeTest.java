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

/** What {@link TerminalView} sends to the terminal for what an IME does, and for keys pressed while the IME is composing. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.P)
public class TerminalViewImeTest {

    private static final String DEL = "\u007f";

    private TerminalView mView;
    /** The queue of bytes to the process of the session, and its read(byte[], boolean). */
    private Object mQueue;
    private Method mRead;
    private final StringBuilder mWire = new StringBuilder();

    @Before
    public void setUp() throws Exception {
        mView = new TerminalView(RuntimeEnvironment.application, null);
        mView.mClient = stub(TerminalViewClient.class);
        attach(newSession());
    }

    /** A session without a process, whose writes are kept in its queue to the process. */
    private static TerminalSession newSession() throws Exception {
        TerminalSessionClient sessionClient = stub(TerminalSessionClient.class);
        TerminalSession session = new TerminalSession("/system/bin/sh", "/", new String[0], new String[0], 100, sessionClient);
        TerminalEmulator emulator = new TerminalEmulator(session, 80, 24, 10, 20, 100, sessionClient);
        setField(session, "mEmulator", emulator);
        setField(session, "mShellPid", 1);
        return session;
    }

    private void attach(TerminalSession session) throws Exception {
        mView.attachSession(session);
        mView.mEmulator = session.getEmulator();
        mQueue = getField(session, "mTerminalToProcessIOQueue");
        mRead = mQueue.getClass().getDeclaredMethod("read", byte[].class, boolean.class);
        mRead.setAccessible(true);
        mWire.setLength(0);
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

    private void pressKey(int keyCode) {
        mView.onKeyDown(keyCode, new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
    }

    /** Everything sent to the process of the current session so far. */
    private String wire() throws Exception {
        byte[] buffer = new byte[4096];
        int read = (int) mRead.invoke(mQueue, buffer, false);
        if (read > 0) mWire.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        return mWire.toString();
    }

    @Test
    public void testComposingTextIsSentWhenCommitted() throws Exception {
        InputConnection connection = connect();
        connection.commitText("안녕", 1);
        connection.setComposingText("ㅎ", 1);
        connection.setComposingText("하", 1);
        Assert.assertEquals("안녕", wire());
        Assert.assertEquals("하", mView.mComposingText.toString());

        connection.commitText("하", 1);
        Assert.assertEquals("안녕하", wire());
        Assert.assertEquals("", mView.mComposingText.toString());
    }

    @Test
    public void testEnterSendsTheComposingSyllableBeforeTheNewLine() throws Exception {
        InputConnection connection = connect();
        connection.commitText("안녕", 1);
        connection.setComposingText("하", 1);

        // Enter from a hardware keyboard reaches the view before the IME commits the syllable.
        pressKey(KeyEvent.KEYCODE_ENTER);
        Assert.assertEquals("안녕하\r", wire());
        Assert.assertEquals("", mView.mComposingText.toString());

        // The IME then commits the syllable, which is not sent again.
        connection.commitText("하", 1);
        Assert.assertEquals("안녕하\r", wire());

        connection.commitText("다음", 1);
        Assert.assertEquals("안녕하\r다음", wire());
    }

    @Test
    public void testFinishingTheCompositionAfterEnterSendsNothingAgain() throws Exception {
        InputConnection connection = connect();
        connection.setComposingText("하", 1);
        pressKey(KeyEvent.KEYCODE_ENTER);
        connection.finishComposingText();
        Assert.assertEquals("하\r", wire());

        connection.setComposingText("ㄱ", 1);
        Assert.assertEquals("ㄱ", mView.mComposingText.toString());
        connection.commitText("가", 1);
        Assert.assertEquals("하\r가", wire());
    }

    @Test
    public void testCompositionChangedAfterItWasSentIsCorrected() throws Exception {
        InputConnection connection = connect();
        connection.setComposingText("하", 1);
        pressKey(KeyEvent.KEYCODE_DPAD_LEFT);
        Assert.assertEquals("하\u001b[D", wire());

        // The IME keeps composing the syllable it already got sent, so the terminal is corrected.
        connection.setComposingText("한", 1);
        Assert.assertEquals("하\u001b[D" + DEL + "한", wire());
        Assert.assertEquals("", mView.mComposingText.toString());
        connection.commitText("한", 1);
        Assert.assertEquals("하\u001b[D" + DEL + "한", wire());
    }

    @Test
    public void testCharacterKeySendsTheComposingTextFirst() throws Exception {
        InputConnection connection = connect();
        connection.setComposingText("하", 1);
        mView.inputCodePoint('a', false, false);
        Assert.assertEquals("하a", wire());
        connection.commitText("하", 1);
        Assert.assertEquals("하a", wire());
    }

    @Test
    public void testSwitchingSessionsDiscardsTheComposingTextSentAhead() throws Exception {
        InputConnection connection = connect();
        connection.setComposingText("하", 1);
        pressKey(KeyEvent.KEYCODE_ENTER);
        Assert.assertEquals("하\r", wire());

        attach(newSession());
        // The old connection is discarded, so the late commit is not sent to the new session.
        connection.commitText("하", 1);
        Assert.assertEquals("", wire());

        connect().commitText("새", 1);
        Assert.assertEquals("새", wire());
    }

}
