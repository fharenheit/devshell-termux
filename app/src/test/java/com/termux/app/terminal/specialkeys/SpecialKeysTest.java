package com.termux.app.terminal.specialkeys;

import android.view.View;

import com.termux.shared.settings.properties.TermuxPropertyConstants;
import com.termux.shared.terminal.io.extrakeys.ExtraKeyButton;
import com.termux.shared.terminal.io.extrakeys.ExtraKeysConstants;
import com.termux.shared.terminal.io.extrakeys.ExtraKeysInfo;
import com.termux.view.TerminalView;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28})
public class SpecialKeysTest {

    @Rule
    public TemporaryFolder mFolder = new TemporaryFolder();

    @Test
    public void testExtraKey() throws Exception {
        ExtraKeyButton[][] matrix = new ExtraKeysInfo(TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS,
            ExtraKeysInfo.STYLE_KEYCAPS, ExtraKeysConstants.CONTROL_CHARS_ALIASES).getMatrix();
        // A key as tall as Enter and Kbd, half as wide, right of them.
        Assert.assertEquals(11, matrix[0].length);
        Assert.assertEquals(10, matrix[1].length);
        ExtraKeyButton key = matrix[0][10];
        Assert.assertEquals(SpecialKeysPanel.EXTRA_KEY, key.getKey());
        Assert.assertEquals("⊞", key.getDisplay());
        Assert.assertEquals(2, key.getRowSpan());
        Assert.assertEquals(0.5f, key.getWidthWeight(), 0.001f);
        Assert.assertEquals(1, matrix[0][0].getRowSpan());
        Assert.assertEquals(1f, matrix[0][0].getWidthWeight(), 0.001f);
    }

    @Test
    public void testTabs() {
        // 9 tabs and the favorites.
        Assert.assertEquals(9, SpecialKeys.TABS.size());
        List<String> frequent = new ArrayList<>();
        for (SpecialKeys.Key key : SpecialKeys.FREQUENT) frequent.add(key.label);
        Assert.assertEquals(Arrays.asList("^C", "^V", "^X", "^Z", "^D", "^A", "^E", "^L",
            "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8",
            "F9", "F10", "F11", "F12", "^R", "^W", "^U", "Del"), frequent);
    }

    @Test
    public void testMacrosAreValid() {
        for (SpecialKeys.Tab tab : SpecialKeys.TABS) {
            for (SpecialKeys.Key key : tab.keys) {
                boolean hasKey = false;
                for (String token : key.macro.split(" ")) {
                    Assert.assertFalse(key.macro, token.isEmpty());
                    if (token.equals("CTRL") || token.equals("ALT") || token.equals("SHIFT")) continue;
                    hasKey = true;
                    Assert.assertTrue("Unknown key " + token + " in " + key.macro,
                        ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS.containsKey(token) || token.codePointCount(0, token.length()) == 1);
                }
                Assert.assertTrue(key.macro, hasKey);
                Assert.assertNotNull(SpecialKeys.find(key.macro));
            }
        }
    }

    @Test
    public void testDescribe() {
        Assert.assertEquals("Ctrl+C", SpecialKeys.describe("CTRL c"));
        Assert.assertEquals("Ctrl+B, %", SpecialKeys.describe("CTRL b %"));
        Assert.assertEquals("Esc, :, w, q, Enter", SpecialKeys.describe("ESC : w q ENTER"));
        Assert.assertEquals("Shift+Tab", SpecialKeys.describe("SHIFT TAB"));
        Assert.assertEquals("Alt+Backspace", SpecialKeys.describe("ALT BKSP"));
        Assert.assertEquals("F12", SpecialKeys.describe("F12"));
        Assert.assertEquals("Ctrl+\\", SpecialKeys.describe("CTRL \\"));
    }

    /** The real sender, recording the keys it would send to the terminal. */
    private static final class RecordingSender extends SpecialKeysSender {
        final List<String> sent = new ArrayList<>();

        RecordingSender() {
            super(new TerminalView(RuntimeEnvironment.application, null));
        }

        @Override
        protected void onTerminalExtraKeyButtonClick(View view, String key, boolean ctrlDown, boolean altDown, boolean shiftDown, boolean fnDown) {
            sent.add((ctrlDown ? "C-" : "") + (altDown ? "A-" : "") + (shiftDown ? "S-" : "") + key);
        }
    }

    @Test
    public void testSendOrder() {
        RecordingSender sender = new RecordingSender();
        sender.send("CTRL b %", false, false);
        Assert.assertEquals(Arrays.asList("C-b", "%"), sender.sent);
        sender.sent.clear();
        // Ctrl or Alt of the extra keys apply to the first key.
        sender.send("F1", false, true);
        Assert.assertEquals(Arrays.asList("A-F1"), sender.sent);
        sender.sent.clear();
        sender.send("SHIFT TAB", false, false);
        Assert.assertEquals(Arrays.asList("S-TAB"), sender.sent);
        sender.sent.clear();
        sender.send("ESC : q ! ENTER", false, false);
        Assert.assertEquals(Arrays.asList("ESC", ":", "q", "!", "ENTER"), sender.sent);
    }

    @Test
    public void testFavorites() throws Exception {
        File file = new File(mFolder.getRoot(), "favorites.json");
        Assert.assertTrue(SpecialKeyFavorites.load(file).isEmpty());
        Assert.assertTrue(SpecialKeyFavorites.toggle(file, "CTRL c"));
        Assert.assertTrue(SpecialKeyFavorites.toggle(file, "F5"));
        Assert.assertEquals(Arrays.asList("CTRL c", "F5"), SpecialKeyFavorites.load(file));
        Assert.assertFalse(SpecialKeyFavorites.toggle(file, "CTRL c"));
        Assert.assertEquals(Arrays.asList("F5"), SpecialKeyFavorites.load(file));
    }

}
