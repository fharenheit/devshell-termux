package com.termux.app.terminal.clipboard;

import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

public class ClipboardHistoryTest {

    @After
    public void tearDown() {
        ClipboardHistory.clear();
    }

    @Test
    public void testNewestFirstWithoutDuplicates() {
        ClipboardHistory.add("a");
        ClipboardHistory.add("b");
        ClipboardHistory.add("a");
        ClipboardHistory.add("  ");
        Assert.assertEquals(Arrays.asList("a", "b"), ClipboardHistory.getItems());
        ClipboardHistory.remove("a");
        Assert.assertEquals(Arrays.asList("b"), ClipboardHistory.getItems());
    }

    @Test
    public void testLimit() {
        for (int i = 0; i < ClipboardHistory.MAX_ITEMS + 5; i++) ClipboardHistory.add("item " + i);
        Assert.assertEquals(ClipboardHistory.MAX_ITEMS, ClipboardHistory.getItems().size());
        Assert.assertEquals("item " + (ClipboardHistory.MAX_ITEMS + 4), ClipboardHistory.getItems().get(0));
    }

    @Test
    public void testPreview() {
        Assert.assertEquals("ls -la ⏎ cd /tmp", ClipboardHistory.preview("  ls -la\n  cd /tmp\n"));
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 200; i++) longText.append('x');
        Assert.assertEquals(121, ClipboardHistory.preview(longText.toString()).length());
    }

}
