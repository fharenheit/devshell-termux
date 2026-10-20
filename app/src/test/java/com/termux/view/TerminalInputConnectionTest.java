package com.termux.view;

import android.content.res.Configuration;
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
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

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
    private final StringBuilder mWire = new StringBuilder();

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
            mWire.append(text);
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

    @Test
    public void testRecomposingThePreviousWordKeepsTheExpectedLine() throws Exception {
        InputConnection connection = connect();
        connection.commitText("안녕", 1);
        Assert.assertEquals("안녕", line());

        // The same word is marked as composing when switching back to Korean.
        connection.setComposingRegion(0, 2);
        connection.setComposingText("안녕하", 1);
        connection.commitText("안녕하", 1);

        Assert.assertEquals("안녕하", line());
    }

    @Test
    public void testActionMultipleTextDoesNotLeaveThePreviousKoreanWordEditable() throws Exception {
        InputConnection connection = connect();
        connection.commitText("안녕", 1);
        Assert.assertEquals("안녕", line());

        // ACTION_MULTIPLE/KEYCODE_UNKNOWN carries text rather than a single key code.
        mView.onKeyDown(KeyEvent.KEYCODE_UNKNOWN, new KeyEvent(0L, "abc", -1, 0));
        Assert.assertEquals("안녕abc", line());

        // A late commit on the old connection must not replay the word after direct input.
        connection.setComposingRegion(0, 2);
        connection.setComposingText("안녕하", 1);
        connection.commitText("안녕하", 1);
        String actual = line();
        Assert.assertEquals("안녕abc", actual);
    }

    @Test
    public void testReplacingInputConnectionFinishesTheOldCompositionBeforeNewInput() throws Exception {
        InputConnection oldConnection = connect();
        oldConnection.setComposingText("이전입력", 1);
        Assert.assertEquals("", line());

        InputConnection newConnection = connect();
        Assert.assertEquals("이전입력", line());
        newConnection.commitText("new", 1);
        Assert.assertEquals("이전입력new", line());

        // BaseInputConnection.closeConnection() finishes the old composition. A delayed close
        // must not send its text after a new connection has already started writing.
        oldConnection.closeConnection();
        String actual = line();
        Assert.assertEquals("이전입력new", actual);
    }

    @Test
    public void testActionMultipleSendsThePendingSyllableBeforeEnglishText() throws Exception {
        InputConnection connection = connect();
        connection.commitText("안녕", 1);
        connection.setComposingText("하", 1);
        mView.onKeyDown(KeyEvent.KEYCODE_UNKNOWN, new KeyEvent(0L, "abc", -1, 0));
        Assert.assertEquals("안녕하abc", line());
        Assert.assertEquals("", mView.mComposingText.toString());

        connection.commitText("안녕하세요", 1);
        InputConnection newConnection = connect();
        newConnection.commitText("요", 1);
        Assert.assertEquals("안녕하abc요", line());
    }

    @Test
    public void testRepeatedKoreanAndEnglishInputDoesNotAccumulateOldText() throws Exception {
        StringBuilder expected = new StringBuilder();
        InputConnection connection = connect();
        for (int i = 0; i < 100; i++) {
            connection.commitText("가", 1);
            connection.setComposingText("나", 1);
            if (i % 2 == 0) {
                mView.onKeyDown(KeyEvent.KEYCODE_UNKNOWN, new KeyEvent(0L, "a", -1, 0));
            } else {
                mView.onKeyDown(KeyEvent.KEYCODE_A, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A));
            }
            InputConnection oldConnection = connection;
            connection = connect();
            connection.commitText("다", 1);
            oldConnection.setComposingRegion(0, 2);
            oldConnection.commitText("가나다", 1);
            oldConnection.deleteSurroundingText(3, 0);
            oldConnection.closeConnection();
            expected.append("가나a다");
            Assert.assertEquals("input cycle " + i, expected.toString(), line());
        }
    }

    @Test
    public void testEnterFinishesCompositionAndRejectsTheLateCommit() throws Exception {
        InputConnection connection = connect();
        connection.commitText("명령", 1);
        connection.setComposingText("어", 1);
        mView.onKeyDown(KeyEvent.KEYCODE_ENTER, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        connection.commitText("명령어", 1);
        Assert.assertEquals("명령어\r", line());
        Assert.assertEquals("명령어\r", mWire.toString());

        connect().commitText("다음", 1);
        Assert.assertEquals("명령어\r다음", line());
    }

    @Test
    public void testArrowFinishesCompositionBeforeMovingTheCursor() throws Exception {
        InputConnection connection = connect();
        connection.setComposingText("한", 1);
        mView.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
        connection.finishComposingText();
        line();
        Assert.assertEquals("한\u001b[D", mWire.toString());
        Assert.assertEquals("", mView.mComposingText.toString());
    }

    @Test
    public void testOldConnectionCannotDeleteTextWrittenByTheNewConnection() throws Exception {
        InputConnection oldConnection = connect();
        oldConnection.commitText("기존", 1);
        InputConnection newConnection = connect();
        newConnection.commitText("new", 1);
        oldConnection.deleteSurroundingText(2, 0);
        Assert.assertEquals("기존new", line());
    }

    @Test
    public void testOldConnectionCannotReplaceTheNewComposingPreview() throws Exception {
        InputConnection oldConnection = connect();
        oldConnection.setComposingText("이전", 1);
        InputConnection newConnection = connect();
        Assert.assertEquals("", mView.mComposingText.toString());
        Assert.assertEquals("이전", line());
        newConnection.setComposingText("새", 1);
        oldConnection.closeConnection();
        Assert.assertEquals("이전", line());
        Assert.assertEquals("새", mView.mComposingText.toString());
        newConnection.finishComposingText();
        Assert.assertEquals("이전새", line());
    }

    @Test
    public void testClosingTheCurrentConnectionCommitsOnceAndRejectsLaterInput() throws Exception {
        InputConnection connection = connect();
        connection.setComposingText("한", 1);
        connection.closeConnection();
        Assert.assertEquals("한", line());
        connection.commitText("이전입력", 1);
        connection.setComposingText("ㄱ", 1);
        connection.deleteSurroundingText(1, 0);
        connection.closeConnection();
        Assert.assertEquals("한", line());
        Assert.assertEquals("", mView.mComposingText.toString());
        connect().commitText("new", 1);
        Assert.assertEquals("한new", line());
    }

    @Test
    public void testKeyboardConfigurationChangeImmediatelyRejectsOldInput() throws Exception {
        InputConnection connection = connect();
        connection.setComposingText("이전", 1);
        Configuration config = new Configuration(mView.getResources().getConfiguration());
        config.hardKeyboardHidden = config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_YES
            ? Configuration.HARDKEYBOARDHIDDEN_NO : Configuration.HARDKEYBOARDHIDDEN_YES;
        mView.onConfigurationChanged(config);
        // Restarting input is asynchronous; an old commit may arrive before onCreateInputConnection.
        connection.commitText("이전입력", 1);
        connection.closeConnection();
        Assert.assertEquals("이전", line());
        Assert.assertEquals("", mView.mComposingText.toString());
        connect().commitText("new", 1);
        Assert.assertEquals("이전new", line());
    }

    @Test
    @LooperMode(LooperMode.Mode.LEGACY)
    public void testDelayedNewlineResetDoesNotFinishANewConnectionsComposition() throws Exception {
        Robolectric.getForegroundThreadScheduler().pause();
        try {
            InputConnection oldConnection = connect();
            oldConnection.commitText("명령\n", 1);
            Assert.assertEquals("명령\r", line());

            InputConnection newConnection = connect();
            newConnection.setComposingText("새", 1);
            Robolectric.getForegroundThreadScheduler().advanceToLastPostedRunnable();
            Assert.assertEquals("명령\r", line());
            Assert.assertEquals("새", mView.mComposingText.toString());
            newConnection.commitText("새글", 1);
            Assert.assertEquals("명령\r새글", line());
        } finally {
            Robolectric.getForegroundThreadScheduler().unPause();
        }
    }

    @Test
    @LooperMode(LooperMode.Mode.LEGACY)
    public void testNewlineCommittedByTheImeStartsFreshInput() throws Exception {
        Robolectric.getForegroundThreadScheduler().pause();
        try {
            InputConnection connection = connect();
            connection.commitText("명령\n", 1);
            Robolectric.getForegroundThreadScheduler().advanceToLastPostedRunnable();
            connection.commitText("이전입력", 1);
            Assert.assertEquals("명령\r", line());
            connect().commitText("다음", 1);
            Assert.assertEquals("명령\r다음", line());
        } finally {
            Robolectric.getForegroundThreadScheduler().unPause();
        }
    }

    @Test
    public void testSwitchingSessionsDiscardsTheOldCompositionInsteadOfFlushingIt() throws Exception {
        InputConnection oldConnection = connect();
        oldConnection.commitText("기존", 1);
        oldConnection.setComposingText("보류", 1);
        Assert.assertEquals("기존", line());

        TerminalSessionClient client = stub(TerminalSessionClient.class);
        TerminalSession nextSession = new TerminalSession("/system/bin/sh", "/", new String[0], new String[0], 100, client);
        TerminalEmulator nextEmulator = new TerminalEmulator(nextSession, 80, 24, 10, 20, 100, client);
        setField(nextSession, "mEmulator", nextEmulator);
        setField(nextSession, "mShellPid", 1);
        mView.attachSession(nextSession);
        // This view has no layout; supply the emulator normally installed by updateSize().
        mView.mEmulator = nextEmulator;
        mSent = getField(nextSession, "mTerminalToProcessIOQueue");
        mLine.setLength(0);

        InputConnection newConnection = connect();
        newConnection.commitText("새", 1);
        oldConnection.closeConnection();
        oldConnection.commitText("보류", 1);
        Assert.assertEquals("새", line());
        Assert.assertEquals("", mView.mComposingText.toString());
    }

}
