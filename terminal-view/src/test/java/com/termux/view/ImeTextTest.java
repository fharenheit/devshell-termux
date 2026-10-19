package com.termux.view;

import org.junit.Assert;
import org.junit.Test;

public class ImeTextTest {

    @Test
    public void testCommonPrefixLength() {
        Assert.assertEquals(0, ImeText.commonPrefixLength("", "안녕"));
        Assert.assertEquals(2, ImeText.commonPrefixLength("안녕", "안녕하"));
        Assert.assertEquals(1, ImeText.commonPrefixLength("안녕", "안녀"));
        Assert.assertEquals(3, ImeText.commonPrefixLength("abc", "abc"));
        Assert.assertEquals(0, ImeText.commonPrefixLength("a", "b"));
    }

    @Test
    public void testCommonPrefixLengthDoesNotSplitSurrogatePairs() {
        // U+1F600 and U+1F601 share their high surrogate.
        Assert.assertEquals(1, ImeText.commonPrefixLength("a😀", "a😁"));
        Assert.assertEquals(3, ImeText.commonPrefixLength("a😀", "a😀b"));
    }

    @Test
    public void testCodePointsFrom() {
        Assert.assertEquals(2, ImeText.codePointsFrom("안녕", 0));
        Assert.assertEquals(0, ImeText.codePointsFrom("안녕", 2));
        Assert.assertEquals(1, ImeText.codePointsFrom("a😀", 1));
    }

}
