package com.termux.view;

/** Comparing the text an IME has committed with the text already sent to the terminal. */
final class ImeText {

    private ImeText() {}

    /**
     * The length of the common start of {@code a} and {@code b}, not ending between the two chars
     * of a surrogate pair.
     */
    static int commonPrefixLength(CharSequence a, CharSequence b) {
        int max = Math.min(a.length(), b.length());
        int length = 0;
        while (length < max && a.charAt(length) == b.charAt(length)) length++;
        if (length > 0 && length < max && Character.isHighSurrogate(a.charAt(length - 1))) length--;
        return length;
    }

    /** The number of code points of {@code text} from {@code start}, which is the number of backspaces to delete them. */
    static int codePointsFrom(CharSequence text, int start) {
        return Character.codePointCount(text, start, text.length());
    }

}
