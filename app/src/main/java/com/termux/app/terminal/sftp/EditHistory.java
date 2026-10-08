package com.termux.app.terminal.sftp;

import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.List;

/**
 * The undo and redo history of an {@link EditText}. It records each change of the text, and
 * merges changes made shortly after each other within the same text, like typing a word or the
 * changes of an input method composing a Korean syllable, into one step.
 */
public class EditHistory implements TextWatcher {

    /** Changes merge into one step if they are this close in time. */
    private static final long MERGE_TIME_MS = 1500;
    /** The oldest steps are dropped when the history holds more characters than this. */
    private static final int MAX_CHARS = 4 * 1024 * 1024;

    private static final class Edit {
        int start;
        String before;
        String after;
        long time;

        Edit(int start, String before, String after, long time) {
            this.start = start;
            this.before = before;
            this.after = after;
            this.time = time;
        }
    }

    /** Listens for changes of what can be undone and redone. */
    public interface Listener {
        void onHistoryChanged();
    }

    private final EditText mEditText;
    private final List<Edit> mEdits = new ArrayList<>();
    /** The number of edits that are applied: the ones before it can be undone, from it redone. */
    private int mPosition;
    /** The position of the saved text, or -1 if it is no longer in the history. */
    private int mSavedPosition;
    private int mChars;
    private boolean mApplying;
    private boolean mEnabled = true;
    /** Whether the next change is a step of its own, even if it continues the last one. */
    private boolean mNewStep;
    private String mBefore;
    private Listener mListener;

    public EditHistory(EditText editText) {
        mEditText = editText;
        editText.addTextChangedListener(this);
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    /** Whether changes are recorded. While not, the history is cleared by changes. */
    public void setEnabled(boolean enabled) {
        mEnabled = enabled;
    }

    /** Forget all steps, and take the current text as saved. */
    public void clear() {
        mEdits.clear();
        mPosition = 0;
        mSavedPosition = 0;
        mChars = 0;
        notifyChanged();
    }

    /** Make the next change a step of its own, like a paste that should be undone alone. */
    public void startNewStep() {
        mNewStep = true;
    }

    public void markSaved() {
        mSavedPosition = mPosition;
        notifyChanged();
    }

    /** Whether the text is the saved text, possibly after undoing or redoing to it. */
    public boolean isAtSaved() {
        return mPosition == mSavedPosition;
    }

    public boolean canUndo() {
        return mPosition > 0;
    }

    public boolean canRedo() {
        return mPosition < mEdits.size();
    }

    public void undo() {
        if (!canUndo()) return;
        Edit edit = mEdits.get(--mPosition);
        apply(edit.start, edit.after, edit.before);
    }

    public void redo() {
        if (!canRedo()) return;
        Edit edit = mEdits.get(mPosition++);
        apply(edit.start, edit.before, edit.after);
    }

    private void apply(int start, String from, String to) {
        Editable text = mEditText.getText();
        mApplying = true;
        try {
            text.replace(start, start + from.length(), to);
        } finally {
            mApplying = false;
        }
        int cursor = Math.min(text.length(), start + to.length());
        mEditText.setSelection(cursor);
        notifyChanged();
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        if (mApplying || !mEnabled) return;
        mBefore = s.subSequence(start, start + count).toString();
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        if (mApplying) return;
        if (!mEnabled) {
            if (!mEdits.isEmpty() || mPosition != 0) clear();
            return;
        }
        String after = s.subSequence(start, start + count).toString();
        String removed = mBefore != null ? mBefore : "";
        mBefore = null;
        if (removed.equals(after)) return;
        record(start, removed, after, SystemClock.uptimeMillis());
    }

    @Override
    public void afterTextChanged(Editable s) {
    }

    /** Record a change of {@code before} at {@code start} to {@code after}. */
    void record(int start, String before, String after, long time) {
        // A change after undoing drops the steps that could be redone.
        while (mEdits.size() > mPosition) {
            Edit dropped = mEdits.remove(mEdits.size() - 1);
            mChars -= dropped.before.length() + dropped.after.length();
        }
        if (mSavedPosition > mPosition) mSavedPosition = -1;

        Edit last = mPosition > 0 && mPosition != mSavedPosition && !mNewStep ? mEdits.get(mPosition - 1) : null;
        mNewStep = false;
        if (last != null && time - last.time < MERGE_TIME_MS && after.indexOf('\n') < 0 && merge(last, start, before, after)) {
            last.time = time;
        } else {
            mEdits.add(new Edit(start, before, after, time));
            mPosition++;
            mChars += before.length() + after.length();
        }
        while (mChars > MAX_CHARS && mEdits.size() > 1) {
            Edit dropped = mEdits.remove(0);
            mChars -= dropped.before.length() + dropped.after.length();
            mPosition--;
            mSavedPosition = mSavedPosition > 0 ? mSavedPosition - 1 : -1;
        }
        notifyChanged();
    }

    /** Merge a change into the last step if it continues it, and return whether it did. */
    private boolean merge(Edit last, int start, String before, String after) {
        int lastEnd = last.start + last.after.length();
        if (start >= last.start && start + before.length() <= lastEnd) {
            // A change within the text of the last step, like typing after it, or changing or
            // deleting what it typed.
            int offset = start - last.start;
            int sizeBefore = last.after.length();
            last.after = last.after.substring(0, offset) + after + last.after.substring(offset + before.length());
            mChars += last.after.length() - sizeBefore;
            return true;
        }
        if (after.isEmpty() && last.after.isEmpty()) {
            if (start + before.length() == last.start) {
                // Deleting backwards.
                last.start = start;
                last.before = before + last.before;
                mChars += before.length();
                return true;
            }
            if (start == last.start) {
                // Deleting forwards.
                last.before = last.before + before;
                mChars += before.length();
                return true;
            }
        }
        return false;
    }

    private void notifyChanged() {
        if (mListener != null) mListener.onHistoryChanged();
    }

}
