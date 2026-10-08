package com.termux.app.terminal.fonts;

import com.termux.shared.settings.properties.TermuxPropertyConstants;
import com.termux.shared.terminal.io.extrakeys.ExtraKeyButton;
import com.termux.shared.terminal.io.extrakeys.ExtraKeysConstants;
import com.termux.shared.terminal.io.extrakeys.ExtraKeysInfo;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28})
public class FontSizeExtraKeysTest {

    @Test
    public void testDefaultExtraKeys() throws Exception {
        ExtraKeyButton[][] matrix = new ExtraKeysInfo(TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS,
            "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES).getMatrix();
        // A+ right of PGUP, A− right of PGDN.
        Assert.assertEquals("PGUP", matrix[0][6].getKey());
        Assert.assertEquals("FONT_LARGER", matrix[0][7].getKey());
        Assert.assertEquals("A+", matrix[0][7].getDisplay());
        // ⧉ copies the selection, or the screen when swiped up, and ⎘ pastes.
        Assert.assertEquals("COPY", matrix[0][8].getKey());
        Assert.assertEquals("⧉", matrix[0][8].getDisplay());
        Assert.assertEquals("COPY_SCREEN", matrix[0][8].getPopup().getKey());
        Assert.assertEquals("ENTER", matrix[0][9].getKey());
        Assert.assertEquals("PGDN", matrix[1][6].getKey());
        Assert.assertEquals("FONT_SMALLER", matrix[1][7].getKey());
        Assert.assertEquals("A−", matrix[1][7].getDisplay());
        Assert.assertEquals("PASTE", matrix[1][8].getKey());
        Assert.assertEquals("CLIPBOARD", matrix[1][8].getPopup().getKey());
        Assert.assertEquals("⎘", matrix[1][8].getDisplay());
        Assert.assertEquals("KEYBOARD", matrix[1][9].getKey());
    }

}
