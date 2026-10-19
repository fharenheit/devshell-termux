package com.termux.view;

import android.text.Editable;
import android.text.Selection;
import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;

import com.termux.terminal.TerminalEmulator;

/**
 * The input connection of a {@link TerminalView}, which sends the text an IME commits to the
 * terminal.
 * <p>
 * The editable text keeps what the IME committed since input was last started, like the line an
 * IME would edit in a text field, instead of being cleared once sent. IMEs keep their own idea of
 * the text before the cursor, and may compose the word before it again, e.g. Korean IMEs after
 * switching between Korean and English: with a cleared text the word could not be replaced, and
 * all of it was sent again. Each change of the text is sent as the backspaces and code points that
 * turn the text sent so far into it, and the composing text is drawn over the cursor until it is
 * committed. {@link TerminalView#finishImeInput()} starts input again when anything else is sent to
 * the terminal, since the text before the cursor then no longer is the IME's.
 */
final class TerminalInputConnection extends BaseInputConnection {

    private static final String LOG_TAG = "TerminalView";

    private final TerminalView mView;

    /** The {@link TerminalView#mInputConnectionGeneration} this connection was created in. */
    private final int mGeneration;

    /** The committed text sent to the terminal, which is the editable text without the composing text. */
    private String mSent = "";

    TerminalInputConnection(TerminalView view, int generation) {
        super(view, true);
        mView = view;
        mGeneration = generation;
    }

    /** Whether text the IME sends should be dropped, since input was started again after this connection was created. */
    private boolean isDiscarded() {
        if (mGeneration == mView.mInputConnectionGeneration) return false;
        getEditable().clear();
        mSent = "";
        return true;
    }

    /** Whether the IME committed or is composing text since this connection was created, see {@link TerminalView#finishImeInput()}. */
    boolean hasText() {
        return !isDiscarded() && getEditable().length() > 0;
    }

    @Override
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        if (TerminalView.TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
            mView.mClient.logInfo(LOG_TAG, "IME: setComposingText(\"" + text + "\", " + newCursorPosition + ")");
        }
        if (isDiscarded()) return true;
        super.setComposingText(text, newCursorPosition);
        sendChanges();
        return true;
    }

    @Override
    public boolean setComposingRegion(int start, int end) {
        if (TerminalView.TERMINAL_VIEW_KEY_LOGGING_ENABLED) mView.mClient.logInfo(LOG_TAG, "IME: setComposingRegion(" + start + ", " + end + ")");
        if (isDiscarded()) return true;
        super.setComposingRegion(start, end);
        sendChanges();
        return true;
    }

    @Override
    public boolean finishComposingText() {
        if (TerminalView.TERMINAL_VIEW_KEY_LOGGING_ENABLED) mView.mClient.logInfo(LOG_TAG, "IME: finishComposingText()");
        if (isDiscarded()) return true;
        super.finishComposingText();
        sendChanges();
        return true;
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
        if (TerminalView.TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
            mView.mClient.logInfo(LOG_TAG, "IME: commitText(\"" + text + "\", " + newCursorPosition + ")");
        }
        if (isDiscarded()) return true;
        super.commitText(text, newCursorPosition);
        sendChanges();
        return true;
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        if (TerminalView.TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
            mView.mClient.logInfo(LOG_TAG, "IME: deleteSurroundingText(" + beforeLength + ", " + afterLength + ")");
        }
        if (isDiscarded()) return true;
        // The text before the cursor that the IME committed is deleted from the editable text, and
        // the rest, e.g. what the stock Samsung keyboard with 'Auto check spelling' deletes, with
        // backspaces.
        int beyond = Math.max(0, beforeLength - Math.max(0, Selection.getSelectionStart(getEditable())));
        super.deleteSurroundingText(beforeLength, afterLength);
        sendChanges();
        sendBackspaces(beyond);
        return true;
    }

    @Override
    public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        if (TerminalView.TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
            mView.mClient.logInfo(LOG_TAG, "IME: deleteSurroundingTextInCodePoints(" + beforeLength + ", " + afterLength + ")");
        }
        if (isDiscarded()) return true;
        Editable content = getEditable();
        int cursor = Math.max(0, Selection.getSelectionStart(content));
        int beyond = Math.max(0, beforeLength - Character.codePointCount(content, 0, cursor));
        super.deleteSurroundingTextInCodePoints(beforeLength, afterLength);
        sendChanges();
        sendBackspaces(beyond);
        return true;
    }

    /**
     * Send the committed text to the terminal, as the backspaces and code points that turn the text
     * sent so far into it, and draw the composing text over the cursor.
     */
    private void sendChanges() {
        Editable content = getEditable();
        int composingStart = getComposingSpanStart(content);
        int composingEnd = getComposingSpanEnd(content);
        String committed;
        CharSequence composing;
        if (composingStart < 0 || composingEnd < 0) {
            committed = content.toString();
            composing = "";
        } else {
            int start = Math.min(composingStart, composingEnd);
            int end = Math.max(composingStart, composingEnd);
            committed = content.subSequence(0, start).toString() + content.subSequence(end, content.length());
            composing = content.subSequence(start, end);
        }

        if (!committed.equals(mSent) && mView.mEmulator != null) {
            int common = ImeText.commonPrefixLength(mSent, committed);
            sendBackspaces(ImeText.codePointsFrom(mSent, common));
            String added = committed.substring(common);
            sendText(added);
            mSent = committed;
            // A new line ends what the IME can edit, so start input again with an empty text.
            for (int i = 0; i < added.length(); i++) {
                if (added.charAt(i) < ' ') {
                    mView.post(mView::finishImeInput);
                    break;
                }
            }
        }
        mView.setComposingTextPreview(composing);
    }

    private void sendBackspaces(int count) {
        if (count <= 0 || mView.mTermSession == null) return;
        mView.stopTextSelectionMode();
        for (int i = 0; i < count; i++) mView.writeKeyCode(KeyEvent.KEYCODE_DEL);
    }

    private void sendText(CharSequence text) {
        if (text.length() == 0) return;
        mView.stopTextSelectionMode();
        final int textLengthInChars = text.length();
        for (int i = 0; i < textLengthInChars; i++) {
            char firstChar = text.charAt(i);
            int codePoint;
            if (Character.isHighSurrogate(firstChar)) {
                if (++i < textLengthInChars) {
                    codePoint = Character.toCodePoint(firstChar, text.charAt(i));
                } else {
                    // At end of string, with no low surrogate following the high:
                    codePoint = TerminalEmulator.UNICODE_REPLACEMENT_CHAR;
                }
            } else {
                codePoint = firstChar;
            }

            // Check onKeyDown() for details.
            if (mView.mClient.readShiftKey())
                codePoint = Character.toUpperCase(codePoint);

            boolean ctrlHeld = false;
            if (codePoint <= 31 && codePoint != 27) {
                if (codePoint == '\n') {
                    // The AOSP keyboard and descendants seems to send \n as text when the enter key is pressed,
                    // instead of a key event like most other keyboard apps. A terminal expects \r for the enter
                    // key (although when icrnl is enabled this doesn't make a difference - run 'stty -icrnl' to
                    // check the behaviour).
                    codePoint = '\r';
                }

                // E.g. penti keyboard for ctrl input.
                ctrlHeld = true;
                switch (codePoint) {
                    case 31:
                        codePoint = '_';
                        break;
                    case 30:
                        codePoint = '^';
                        break;
                    case 29:
                        codePoint = ']';
                        break;
                    case 28:
                        codePoint = '\\';
                        break;
                    default:
                        codePoint += 96;
                        break;
                }
            }

            mView.inputCodePoint(codePoint, ctrlHeld, false, true);
        }
    }

}
