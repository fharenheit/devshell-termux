package com.termux.app.terminal.fonts;

import android.app.Activity;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.widget.Button;

import com.termux.shared.settings.properties.TermuxPropertyConstants;
import com.termux.shared.terminal.io.extrakeys.ExtraKeyButton;
import com.termux.shared.terminal.io.extrakeys.ExtraKeysConstants;
import com.termux.shared.terminal.io.extrakeys.ExtraKeysInfo;
import com.termux.shared.terminal.io.extrakeys.ExtraKeysView;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28})
public class ExtraKeysKeycapsTest {

    private static ExtraKeysInfo info(String keys, String style) throws Exception {
        return new ExtraKeysInfo(keys, style, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
    }

    @Test
    public void testKeycapsIsTheDefaultStyle() throws Exception {
        Assert.assertEquals(ExtraKeysInfo.STYLE_KEYCAPS, TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE);
        ExtraKeysInfo info = info(TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS, ExtraKeysInfo.STYLE_KEYCAPS);
        Assert.assertTrue(info.isKeycaps());
        ExtraKeyButton[][] matrix = info.getMatrix();
        Assert.assertEquals("Esc", matrix[0][0].getDisplay());
        Assert.assertEquals("⇤\nHome", matrix[0][3].getDisplay());
        Assert.assertEquals("⇞\nPgUp", matrix[0][6].getDisplay());
        Assert.assertEquals("⧉\nCopy", matrix[0][8].getDisplay());
        Assert.assertEquals("Ctrl", matrix[1][1].getDisplay());
        Assert.assertEquals("⎘\nPaste", matrix[1][8].getDisplay());
        // The other styles are unchanged.
        Assert.assertFalse(info(TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS, "default").isKeycaps());
        Assert.assertEquals("HOME", info(TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS, "default").getMatrix()[0][3].getDisplay());
    }

    @Test
    public void testDrawKeycaps() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ExtraKeysView view = new ExtraKeysView(activity, null);
        activity.setContentView(view);
        // A display set in the properties is kept.
        view.reload(info("[['HOME', {key: 'CTRL', display: 'my ctrl'}, 'q']]", ExtraKeysInfo.STYLE_KEYCAPS));
        Button home = (Button) view.getChildAt(0);
        Assert.assertEquals("⇤\nHome", home.getText().toString());
        // The caption is smaller.
        Assert.assertTrue(home.getText() instanceof Spanned);
        Assert.assertEquals(1, ((Spanned) home.getText()).getSpans(0, home.getText().length(), RelativeSizeSpan.class).length);
        Assert.assertEquals("my ctrl", ((Button) view.getChildAt(1)).getText().toString());
        // Not made upper case with keycaps.
        Assert.assertFalse(((Button) view.getChildAt(2)).isAllCaps());

        // The default style still makes the text upper case.
        view.reload(info("[['q']]", "default"));
        Assert.assertTrue(((Button) view.getChildAt(0)).isAllCaps());
    }

    @Test
    public void testActiveCtrlIsFilled() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ExtraKeysView view = new ExtraKeysView(activity, null);
        activity.setContentView(view);
        view.reload(info("[['CTRL']]", ExtraKeysInfo.STYLE_KEYCAPS));
        Button ctrl = (Button) view.getChildAt(0);
        Assert.assertNotEquals(0xFF000000, ctrl.getCurrentTextColor());
        ctrl.performClick();
        Assert.assertEquals(0xFF000000, ctrl.getCurrentTextColor());
        ctrl.performClick();
        Assert.assertNotEquals(0xFF000000, ctrl.getCurrentTextColor());
    }

    @Test
    public void testSpecialKeysKey() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ExtraKeysView view = new ExtraKeysView(activity, null);
        activity.setContentView(view);
        view.reload(info(TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS, ExtraKeysInfo.STYLE_KEYCAPS));
        Assert.assertEquals(21, view.getChildCount());
        Assert.assertEquals(11, view.getColumnCount());
        Button special = (Button) view.findViewWithTag("SPECIAL_KEYS");
        Assert.assertNotNull(special);
        Assert.assertEquals("⊞", special.getText().toString());
        // Shown as active while its panel is open.
        view.setKeyHighlighted("SPECIAL_KEYS", true);
        Assert.assertEquals(0xFF000000, special.getCurrentTextColor());
        view.setKeyHighlighted("SPECIAL_KEYS", false);
        Assert.assertNotEquals(0xFF000000, special.getCurrentTextColor());
    }

}
