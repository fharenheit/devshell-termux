package com.termux.app.terminal.search;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import java.util.List;

/**
 * A find bar above the terminal that searches its output with {@link TerminalSearch}. The
 * matches on the screen are highlighted, and ▲ and ▼ go to older and newer matches, scrolling
 * to them. Opened from the tab bar settings menu, the terminal context menu or Ctrl+Shift+F.
 */
public class TerminalSearchBar implements TerminalView.Overlay {

    private static final int MATCH_COLOR = 0x66FFD54F;
    private static final int CURRENT_MATCH_COLOR = 0xAAFF9800;

    private final TermuxActivity mActivity;
    private LinearLayout mBar;
    private EditText mInput;
    private TextView mCount;
    private final Paint mPaint = new Paint();
    @Nullable private TerminalSearch.Match mCurrent;
    @Nullable private TerminalSession mSession;

    public TerminalSearchBar(@NonNull TermuxActivity activity) {
        mActivity = activity;
    }

    public boolean isShown() {
        return mBar != null && mBar.getVisibility() == View.VISIBLE;
    }

    /** Show the bar and focus its input, or select its text if it is already shown. */
    public void show() {
        if (mBar == null) createBar();
        mBar.setVisibility(View.VISIBLE);
        mActivity.getTerminalView().setOverlay(this);
        mInput.requestFocus();
        mInput.selectAll();
        InputMethodManager imm = (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(mInput, InputMethodManager.SHOW_IMPLICIT);
        search(true);
    }

    public void hide() {
        if (mBar == null) return;
        mBar.setVisibility(View.GONE);
        mCurrent = null;
        TerminalView terminalView = mActivity.getTerminalView();
        terminalView.setOverlay(null);
        terminalView.requestFocus();
    }

    /** Search the output of the tab that became current. */
    public void onSessionChanged() {
        if (!isShown()) return;
        mCurrent = null;
        search(true);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, mActivity.getResources().getDisplayMetrics()));
    }

    private void createBar() {
        Context context = mActivity;
        mBar = new LinearLayout(context);
        mBar.setGravity(Gravity.CENTER_VERTICAL);
        mBar.setBackgroundColor(0xFF202020);
        mBar.setPadding(dp(8), 0, 0, 0);

        mInput = new EditText(context);
        mInput.setSingleLine();
        mInput.setHint(R.string.hint_terminal_search);
        mInput.setTextColor(Color.WHITE);
        mInput.setHintTextColor(Color.GRAY);
        mInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        mInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        mInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                mCurrent = null;
                search(true);
            }
        });
        mInput.setOnEditorActionListener((v, actionId, event) -> {
            // Enter goes to the next older match, Shift+Enter to the next newer one.
            boolean newer = event != null && event.isShiftPressed();
            move(!newer);
            return true;
        });
        mInput.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            // Back while the keyboard is hidden closes the bar, like Escape.
            if (keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BACK) {
                hide();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                move(!event.isShiftPressed());
                return true;
            }
            return false;
        });
        mBar.addView(mInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        mCount = new TextView(context);
        mCount.setTextColor(Color.LTGRAY);
        mCount.setPadding(dp(8), 0, dp(4), 0);
        mBar.addView(mCount);
        mBar.addView(createButton("▲", R.string.action_terminal_search_older, v -> move(true)));
        mBar.addView(createButton("▼", R.string.action_terminal_search_newer, v -> move(false)));
        mBar.addView(createButton("✕", R.string.action_terminal_search_close, v -> hide()));

        // Above the terminal, below the tabs if they are at the top.
        TerminalView terminalView = mActivity.getTerminalView();
        ViewGroup parent = (ViewGroup) terminalView.getParent();
        parent.addView(mBar, parent.indexOfChild(terminalView),
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    @NonNull
    private Button createButton(String text, int descriptionId, View.OnClickListener listener) {
        Button button = new Button(mActivity, null, android.R.attr.borderlessButtonStyle);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setMinWidth(dp(44));
        button.setMinimumWidth(dp(44));
        button.setPadding(0, 0, 0, 0);
        button.setContentDescription(mActivity.getString(descriptionId));
        button.setOnClickListener(listener);
        return button;
    }

    @Nullable
    private TerminalEmulator getEmulator() {
        TerminalSession session = mActivity.getCurrentSession();
        mSession = session;
        return session == null ? null : session.getEmulator();
    }

    @NonNull
    private String getQuery() {
        return mInput == null ? "" : mInput.getText().toString();
    }

    /**
     * Search again. Without a current match, or if {@code keepCurrent} and it no longer matches,
     * the newest match is selected, preferring one on the screen.
     */
    private void search(boolean keepCurrent) {
        TerminalEmulator emulator = getEmulator();
        String query = getQuery();
        if (emulator == null || query.isEmpty()) {
            mCurrent = null;
            mCount.setText("");
            mActivity.getTerminalView().invalidate();
            return;
        }
        List<TerminalSearch.Match> matches = TerminalSearch.findAll(emulator, query);
        int index = indexOf(matches, keepCurrent ? mCurrent : null);
        if (index < 0 && !matches.isEmpty()) {
            // The newest match above the bottom of the view, or the oldest one.
            TerminalView terminalView = mActivity.getTerminalView();
            int bottomRow = terminalView.getTopRow() + emulator.mRows - 1;
            index = 0;
            for (int i = matches.size() - 1; i >= 0; i--) {
                if (matches.get(i).startRow <= bottomRow) {
                    index = i;
                    break;
                }
            }
        }
        select(matches, index);
    }

    /** Go to the next older match if {@code older}, else to the next newer one, wrapping around. */
    private void move(boolean older) {
        TerminalEmulator emulator = getEmulator();
        String query = getQuery();
        if (emulator == null || query.isEmpty()) return;
        List<TerminalSearch.Match> matches = TerminalSearch.findAll(emulator, query);
        if (matches.isEmpty()) {
            select(matches, -1);
            return;
        }
        int index;
        if (mCurrent == null) {
            index = older ? matches.size() - 1 : 0;
        } else if (older) {
            index = matches.size() - 1;
            for (int i = matches.size() - 1; i >= 0; i--) {
                if (matches.get(i).compareTo(mCurrent) < 0) {
                    index = i;
                    break;
                }
            }
        } else {
            index = 0;
            for (int i = 0; i < matches.size(); i++) {
                if (matches.get(i).compareTo(mCurrent) > 0) {
                    index = i;
                    break;
                }
            }
        }
        select(matches, index);
    }

    private static int indexOf(@NonNull List<TerminalSearch.Match> matches, @Nullable TerminalSearch.Match match) {
        if (match == null) return -1;
        for (int i = 0; i < matches.size(); i++)
            if (matches.get(i).sameStart(match)) return i;
        return -1;
    }

    private void select(@NonNull List<TerminalSearch.Match> matches, int index) {
        TerminalView terminalView = mActivity.getTerminalView();
        if (index < 0) {
            mCurrent = null;
            mCount.setText(getQuery().isEmpty() ? "" : "0/0");
            mCount.setTextColor(0xFFFF8A80);
            terminalView.invalidate();
            return;
        }
        mCurrent = matches.get(index);
        mCount.setText((index + 1) + "/" + matches.size());
        mCount.setTextColor(Color.LTGRAY);
        scrollTo(mCurrent);
        terminalView.invalidate();
    }

    /** Scroll the terminal so that a match is shown, near the middle if it was not. */
    private void scrollTo(@NonNull TerminalSearch.Match match) {
        TerminalView terminalView = mActivity.getTerminalView();
        TerminalEmulator emulator = terminalView.mEmulator;
        if (emulator == null) return;
        int topRow = terminalView.getTopRow();
        if (match.startRow >= topRow && match.endRow < topRow + emulator.mRows) return;
        int newTopRow = match.startRow - emulator.mRows / 2;
        newTopRow = Math.max(-emulator.getScreen().getActiveTranscriptRows(), Math.min(0, newTopRow));
        terminalView.setTopRow(newTopRow);
    }

    @Override
    public void draw(Canvas canvas, TerminalEmulator emulator, int topRow, float columnWidth, int rowHeight, int firstRowTop) {
        String query = getQuery();
        if (query.isEmpty() || emulator == null) return;
        // The tab may have changed without the bar knowing yet.
        boolean currentValid = mSession != null && mSession.getEmulator() == emulator;
        for (TerminalSearch.Match match : TerminalSearch.findInRows(emulator, topRow, emulator.mRows, query)) {
            boolean current = currentValid && mCurrent != null && match.sameStart(mCurrent);
            mPaint.setColor(current ? CURRENT_MATCH_COLOR : MATCH_COLOR);
            for (int row = match.startRow; row <= match.endRow; row++) {
                if (row < topRow || row >= topRow + emulator.mRows) continue;
                int startColumn = row == match.startRow ? match.startColumn : 0;
                int endColumn = row == match.endRow ? match.endColumn : emulator.mColumns;
                float top = firstRowTop + (row - topRow) * rowHeight;
                canvas.drawRect(startColumn * columnWidth, top, endColumn * columnWidth, top + rowHeight, mPaint);
            }
        }
    }

}
