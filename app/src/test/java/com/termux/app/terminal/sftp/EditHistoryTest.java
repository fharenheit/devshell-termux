package com.termux.app.terminal.sftp;

import android.text.Editable;
import android.widget.EditText;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28})
public class EditHistoryTest {

    private EditText mEditText;
    private EditHistory mHistory;

    @Before
    public void setUp() {
        mEditText = new EditText(RuntimeEnvironment.application);
        mEditText.setText("start\n");
        mHistory = new EditHistory(mEditText);
        mHistory.clear();
    }

    private String text() {
        return mEditText.getText().toString();
    }

    @Test
    public void testTypingIsOneStep() {
        Editable text = mEditText.getText();
        for (char c : "hello".toCharArray()) text.insert(text.length(), String.valueOf(c));
        Assert.assertEquals("start\nhello", text());
        Assert.assertFalse(mHistory.isAtSaved());
        mHistory.undo();
        Assert.assertEquals("start\n", text());
        Assert.assertTrue(mHistory.isAtSaved());
        Assert.assertFalse(mHistory.canUndo());
        mHistory.redo();
        Assert.assertEquals("start\nhello", text());
        Assert.assertFalse(mHistory.canRedo());
    }

    @Test
    public void testInputMethodComposing() {
        // An input method replaces the syllable it composes: ㅎ, 하, 한, then 한ㄱ, 한그, 한글.
        Editable text = mEditText.getText();
        int at = text.length();
        text.insert(at, "ㅎ");
        text.replace(at, at + 1, "하");
        text.replace(at, at + 1, "한");
        text.insert(at + 1, "ㄱ");
        text.replace(at + 1, at + 2, "그");
        text.replace(at + 1, at + 2, "글");
        Assert.assertEquals("start\n한글", text());
        mHistory.undo();
        Assert.assertEquals("start\n", text());
    }

    @Test
    public void testDeletingAndLinesAreSteps() {
        Editable text = mEditText.getText();
        // Backspace three times.
        text.delete(4, 5);
        text.delete(3, 4);
        text.delete(2, 3);
        Assert.assertEquals("st\n", text());
        // A new line is a step of its own.
        text.insert(3, "\n");
        mHistory.undo();
        Assert.assertEquals("st\n", text());
        mHistory.undo();
        Assert.assertEquals("start\n", text());
    }

    @Test
    public void testSlowChangesAreSteps() {
        mHistory.record(0, "", "a", 1000);
        mHistory.record(1, "", "b", 5000);
        Assert.assertTrue(mHistory.canUndo());
        mHistory.undo();
        Assert.assertTrue(mHistory.canUndo());
    }

    @Test
    public void testSavedPositionAndRedoDropped() {
        Editable text = mEditText.getText();
        text.insert(0, "a\n");
        mHistory.markSaved();
        text.insert(0, "b");
        Assert.assertFalse(mHistory.isAtSaved());
        mHistory.undo();
        Assert.assertTrue(mHistory.isAtSaved());
        mHistory.undo();
        Assert.assertEquals("start\n", text());
        Assert.assertFalse(mHistory.isAtSaved());

        // A change after undoing drops the steps that could be redone, and the saved text with them.
        text.insert(0, "c");
        Assert.assertFalse(mHistory.canRedo());
        mHistory.undo();
        Assert.assertEquals("start\n", text());
        Assert.assertFalse(mHistory.isAtSaved());
    }

    @Test
    public void testChangeAfterSaveIsNewStep() {
        Editable text = mEditText.getText();
        text.insert(0, "a");
        mHistory.markSaved();
        // Typing right after saving does not merge into the saved step.
        text.insert(1, "b");
        mHistory.undo();
        Assert.assertEquals("astart\n", text());
        Assert.assertTrue(mHistory.isAtSaved());
    }

}
