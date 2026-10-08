package com.termux.app.terminal.sftp;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.Editable;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28})
public class SftpEditorViewTest {

    private SftpEditorView mEditor;
    private int mStateChanges;

    @Before
    public void setUp() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        mEditor = new SftpEditorView(activity, Typeface.MONOSPACE);
        mEditor.setListener(() -> mStateChanges++);
        activity.setContentView(mEditor);
    }

    private void load(byte[] bytes, boolean hex) {
        mEditor.load("file.txt", bytes, bytes.length, false, hex);
    }

    @Test
    public void testEditUndoAndSave() throws Exception {
        byte[] bytes = "a\r\nb\r\n".getBytes(StandardCharsets.UTF_8);
        load(bytes, false);
        Assert.assertFalse(mEditor.isDirty());
        Assert.assertFalse(mEditor.isReadOnly());
        Assert.assertEquals("a\nb\n", mEditor.getTextView().getText().toString());

        Editable text = mEditor.getTextView().getText();
        text.insert(1, "x");
        Assert.assertTrue(mEditor.isDirty());
        Assert.assertTrue(mStateChanges > 0);
        // Saved with the CRLF line endings of the file.
        byte[] content = mEditor.getContent();
        Assert.assertEquals("ax\r\nb\r\n", new String(content, StandardCharsets.UTF_8));
        mEditor.markSaved(content);
        Assert.assertFalse(mEditor.isDirty());
        Assert.assertEquals(3, mEditor.getTextView().getTextLineCount());
    }

    @Test
    public void testReadOnlyWhenNotLossless() {
        // Invalid UTF-8 detected as ISO-8859-1 is editable, since it keeps every byte.
        byte[] bytes = {'a', (byte) 0xff, '\n'};
        load(bytes, false);
        Assert.assertFalse(mEditor.isReadOnly());

        // Truncated files are read only.
        mEditor.load("big.txt", bytes, bytes.length, true, false);
        Assert.assertTrue(mEditor.isReadOnly());
    }

    @Test
    public void testHex() throws Exception {
        byte[] binary = {0, 1, 2, 'x'};
        load(binary, true);
        Assert.assertTrue(mEditor.isHex());
        Assert.assertFalse(mEditor.isDirty());
    }

    @Test
    public void testFontSize() {
        float defaultSize = mEditor.getTextView().getTextSize();
        mEditor.setFontSize(mEditor.getFontSize() + 4);
        Assert.assertEquals(18, mEditor.getFontSize());
        Assert.assertTrue(mEditor.getTextView().getTextSize() > defaultSize);

        // Kept within limits, and remembered for the next editor.
        mEditor.setFontSize(100);
        Assert.assertEquals(32, mEditor.getFontSize());
        mEditor.setFontSize(1);
        Assert.assertEquals(8, mEditor.getFontSize());
        mEditor.setFontSize(20);
        SftpEditorView next = new SftpEditorView(mEditor.getContext(), Typeface.MONOSPACE);
        Assert.assertEquals(20, next.getFontSize());
    }

    @Test
    public void testCutCopyPaste() throws Exception {
        load("one\ntwo\nthree\n".getBytes(StandardCharsets.UTF_8), false);
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
            mEditor.getContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        LineNumberEditText text = mEditor.getTextView();

        // Copy without a selection copies the line of the cursor.
        text.setSelection(5);
        invoke("copy");
        Assert.assertEquals("two\n", clipboard.getPrimaryClip().getItemAt(0).getText().toString());

        // Cut the selection, then paste it back, each a step of its own.
        text.setSelection(0, 3);
        invoke("cut");
        Assert.assertEquals("one", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        Assert.assertEquals("\ntwo\nthree\n", text.getText().toString());
        text.setSelection(text.length());
        invoke("paste");
        Assert.assertEquals("\ntwo\nthree\none", text.getText().toString());
        Assert.assertTrue(mEditor.isDirty());
        invoke("undo");
        Assert.assertEquals("\ntwo\nthree\n", text.getText().toString());
        invoke("undo");
        Assert.assertEquals("one\ntwo\nthree\n", text.getText().toString());
        Assert.assertFalse(mEditor.isDirty());
    }

    private void invoke(String method) throws Exception {
        java.lang.reflect.Method m = SftpEditorView.class.getDeclaredMethod(method);
        m.setAccessible(true);
        m.invoke(mEditor);
    }

}
