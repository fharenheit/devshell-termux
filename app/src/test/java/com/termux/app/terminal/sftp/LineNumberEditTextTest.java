package com.termux.app.terminal.sftp;

import android.app.Activity;
import android.text.Editable;
import android.text.style.ForegroundColorSpan;
import android.view.View;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28})
public class LineNumberEditTextTest {

    private static LineNumberEditText layOut(String text, SyntaxHighlighter.Language language) {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LineNumberEditText editor = new LineNumberEditText(activity);
        // Attached, so that the delayed highlighting runs.
        activity.setContentView(editor);
        editor.setLanguage(language);
        editor.setText(text);
        editor.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY));
        editor.layout(0, 0, 1000, 1000);
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
        return editor;
    }

    private static int colorAt(LineNumberEditText editor, int offset) {
        ForegroundColorSpan[] spans = editor.getText().getSpans(offset, offset + 1, ForegroundColorSpan.class);
        return spans.length == 0 ? 0 : spans[0].getForegroundColor();
    }

    @Test
    public void testHighlightsAndUpdates() {
        String text = "# comment\necho \"hi\"\n";
        LineNumberEditText editor = layOut(text, SyntaxHighlighter.find("Shell"));
        int comment = colorAt(editor, 0);
        int string = colorAt(editor, text.indexOf('"'));
        Assert.assertNotEquals(0, comment);
        Assert.assertNotEquals(0, string);
        Assert.assertNotEquals(comment, string);
        Assert.assertEquals(0, colorAt(editor, text.indexOf("echo")));

        // Editing re-highlights: the comment becomes a string.
        Editable editable = editor.getText();
        editable.replace(0, 1, "\"");
        editable.insert(9, "\"");
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
        Assert.assertEquals(string, colorAt(editor, 3));

        // Plain text has no colors, and the text is unchanged by highlighting.
        editor.setLanguage(null);
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
        Assert.assertEquals(0, editor.getText().getSpans(0, editor.length(), ForegroundColorSpan.class).length);
        Assert.assertEquals("\" comment\"\necho \"hi\"\n", editor.getText().toString());
    }

}
