package com.termux.app.terminal.search;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28})
public class TerminalSearchTest {

    private static TerminalEmulator emulator(int columns, int rows, String output) {
        TerminalOutput session = new TerminalOutput() {
            @Override public void write(byte[] data, int offset, int count) {}
            @Override public void titleChanged(String oldTitle, String newTitle) {}
            @Override public void onCopyTextToClipboard(String text) {}
            @Override public void onPasteTextFromClipboard() {}
            @Override public void onBell() {}
            @Override public void onColorsChanged() {}
        };
        TerminalEmulator emulator = new TerminalEmulator(session, columns, rows, 10, 20, 100, null);
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
        return emulator;
    }

    private static String toString(List<TerminalSearch.Match> matches) {
        return matches.toString();
    }

    @Test
    public void testFindOnScreenAndHistory() {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < 10; i++) output.append("line ").append(i).append(" Error\r\n");
        TerminalEmulator emulator = emulator(20, 4, output.toString());
        List<TerminalSearch.Match> matches = TerminalSearch.findAll(emulator, "error");
        Assert.assertEquals(10, matches.size());
        // The last line printed is on the screen, the first ones scrolled above it.
        Assert.assertEquals("2:7-2:12", matches.get(9).toString());
        Assert.assertTrue(matches.get(0).startRow < 0);
        // Capitals make the search case sensitive.
        Assert.assertEquals(10, TerminalSearch.findAll(emulator, "Error").size());
        Assert.assertEquals(0, TerminalSearch.findAll(emulator, "ERROR").size());
    }

    @Test
    public void testWideCharactersAndWrappedLines() {
        // 한글 takes 4 columns, so "abc" starts at column 5 after "한글 ".
        TerminalEmulator emulator = emulator(10, 4, "한글 abc\r\n0123456789wrapped\r\n");
        Assert.assertEquals("[0:5-0:8]", toString(TerminalSearch.findAll(emulator, "abc")));
        Assert.assertEquals("[0:0-0:4]", toString(TerminalSearch.findAll(emulator, "한글")));
        // A match over a wrapped line covers two rows.
        Assert.assertEquals("[1:8-2:3]", toString(TerminalSearch.findAll(emulator, "89wra")));
        // The end of a line does not join the next line.
        Assert.assertEquals(0, TerminalSearch.findAll(emulator, "abc0").size());
    }

    @Test
    public void testFindInRows() {
        TerminalEmulator emulator = emulator(10, 4, "x\r\nfind me\r\nx\r\nfind\r\n");
        Assert.assertEquals(1, TerminalSearch.findInRows(emulator, 0, 2, "find").size());
        Assert.assertEquals(2, TerminalSearch.findInRows(emulator, 0, 4, "find").size());
    }

}
