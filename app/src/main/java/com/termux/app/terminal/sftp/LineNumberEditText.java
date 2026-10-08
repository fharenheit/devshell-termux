package com.termux.app.terminal.sftp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.EditText;

/**
 * A text editor that shows line numbers in a gutter at the left. Without word wrap, it scrolls in
 * both directions and each layout line is a line of the file. With word wrap, a line of the file
 * can take several layout lines, and only the first one is numbered.
 *
 * It highlights the syntax of a {@link SyntaxHighlighter.Language}: shortly after the text
 * changes or scrolls, the visible lines and some lines around them are colored with spans.
 */
public class LineNumberEditText extends EditText {

    /** The colors of the token types of {@link SyntaxHighlighter}, on a black background. */
    private static final int[] TOKEN_COLORS = {
        0xFF6A9955, // comment
        0xFFCE9178, // string
        0xFFB5CEA8, // number
        0xFFC586C0, // keyword
        0xFF569CD6, // literal
        0xFFDCDCAA, // function
        0xFF9CDCFE, // key
        0xFF569CD6, // tag
        0xFF4FC1FF, // variable
        0xFFD7BA7D, // meta
        0xFF89D185, // added
        0xFFF48771, // removed
    };
    /** Lines highlighted above and below the visible ones, so that short scrolls show colors at once. */
    private static final int MARGIN_LINES = 60;
    /** In larger texts, scanning starts this far before the highlighted lines instead of at the start. */
    private static final int MAX_SCAN_BACK = 256 * 1024;

    /** A span of the highlighting, to tell it from other spans. */
    private static final class SyntaxSpan extends ForegroundColorSpan {
        SyntaxSpan(int color) {
            super(color);
        }
    }

    private SyntaxHighlighter.Language mLanguage;
    private final Runnable mHighlightRunnable = this::highlight;
    /** The lines highlighted last, and whether the text changed since. */
    private int mHighlightedFirstLine = -1;
    private int mHighlightedLastLine = -1;
    private boolean mHighlightStale = true;

    private boolean mWordWrap;
    /** The number of line feeds in the text, kept up to date by {@link #mLineCounter}. */
    private int mLineFeeds;
    private int mRemovedLineFeeds;
    /** The number of line feeds before an offset, so that counting can continue from there. */
    private int mCachedOffset;
    private int mCachedLineFeeds;

    private final TextWatcher mLineCounter = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            mRemovedLineFeeds = countLineFeeds(s, start, start + count);
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            mLineFeeds += countLineFeeds(s, start, start + count) - mRemovedLineFeeds;
            if (start < mCachedOffset) {
                mCachedOffset = 0;
                mCachedLineFeeds = 0;
            }
        }

        @Override
        public void afterTextChanged(Editable s) {
        }
    };

    private final Paint mNumberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGutterPaint = new Paint();
    private final int mGutterPadding;
    private int mGutterWidth;

    public LineNumberEditText(Context context) {
        super(context);
        setGravity(Gravity.TOP | Gravity.START);
        setHorizontallyScrolling(true);
        setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        setSingleLine(false);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        setBackgroundColor(Color.BLACK);
        setTextColor(Color.WHITE);
        mGutterPadding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6, getResources().getDisplayMetrics()));
        mNumberPaint.setColor(Color.GRAY);
        mNumberPaint.setTextAlign(Paint.Align.RIGHT);
        mGutterPaint.setColor(0xFF1A1A1A);
        mLineFeeds = countLineFeeds(getText(), 0, length());
        addTextChangedListener(mLineCounter);
        updateGutter();
    }

    private static int countLineFeeds(CharSequence text, int start, int end) {
        int count = 0;
        for (int i = start; i < end; i++)
            if (text.charAt(i) == '\n') count++;
        return count;
    }

    /** The number of line feeds before an offset. */
    private int countLineFeedsBefore(int offset) {
        CharSequence text = getText();
        offset = Math.min(offset, text.length());
        if (offset >= mCachedOffset)
            mCachedLineFeeds += countLineFeeds(text, mCachedOffset, offset);
        else
            mCachedLineFeeds -= countLineFeeds(text, offset, mCachedOffset);
        mCachedOffset = offset;
        return mCachedLineFeeds;
    }

    public void setWordWrap(boolean wordWrap) {
        if (wordWrap == mWordWrap) return;
        mWordWrap = wordWrap;
        setHorizontallyScrolling(!wordWrap);
        if (wordWrap) scrollTo(0, getScrollY());
        mHighlightStale = true;
        scheduleHighlight(0);
    }

    public boolean isWordWrap() {
        return mWordWrap;
    }

    /** The number of lines of the text. */
    public int getTextLineCount() {
        return mLineFeeds + 1;
    }

    /** Move the cursor to the start of a line, counted from 1, and scroll to it. */
    public void goToLine(int line) {
        CharSequence text = getText();
        int offset = 0;
        for (int current = 1; current < line && offset < text.length(); offset++)
            if (text.charAt(offset) == '\n') current++;
        requestFocus();
        setSelection(Math.min(offset, text.length()));
        bringPointIntoView(getSelectionStart());
    }

    /** The line, counted from 1, of the cursor. */
    public int getCursorLine() {
        return countLineFeedsBefore(Math.max(0, getSelectionStart())) + 1;
    }

    @Override
    public void setTypeface(Typeface typeface) {
        super.setTypeface(typeface);
        if (mNumberPaint != null) {
            mNumberPaint.setTypeface(typeface);
            updateGutter();
        }
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        if (mNumberPaint != null) {
            updateGutter();
            mHighlightStale = true;
            scheduleHighlight(200);
        }
    }

    @Override
    protected void onScrollChanged(int horizontal, int vertical, int oldHorizontal, int oldVertical) {
        super.onScrollChanged(horizontal, vertical, oldHorizontal, oldVertical);
        if (vertical != oldVertical) scheduleHighlight(100);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        scheduleHighlight(0);
    }

    @Override
    public boolean onTextContextMenuItem(int id) {
        // Pasting text copied from the editor would keep the colors of the highlighting.
        if (id == android.R.id.paste) id = android.R.id.pasteAsPlainText;
        return super.onTextContextMenuItem(id);
    }

    /** Set the language to highlight, or {@code null} for none. */
    public void setLanguage(SyntaxHighlighter.Language language) {
        mLanguage = language;
        mHighlightStale = true;
        scheduleHighlight(0);
    }

    public SyntaxHighlighter.Language getLanguage() {
        return mLanguage;
    }

    private void scheduleHighlight(long delay) {
        if (mHighlightRunnable == null) return;
        removeCallbacks(mHighlightRunnable);
        postDelayed(mHighlightRunnable, delay);
    }

    private void highlight() {
        Editable text = getText();
        Layout layout = getLayout();
        if (text == null || layout == null) return;
        int top = getScrollY() - getPaddingTop();
        int firstVisible = layout.getLineForVertical(Math.max(0, top));
        int lastVisible = layout.getLineForVertical(top + getHeight());
        if (!mHighlightStale && firstVisible >= mHighlightedFirstLine && lastVisible <= mHighlightedLastLine) return;

        for (SyntaxSpan span : text.getSpans(0, text.length(), SyntaxSpan.class)) text.removeSpan(span);
        mHighlightStale = false;
        mHighlightedFirstLine = Math.max(0, firstVisible - MARGIN_LINES);
        mHighlightedLastLine = Math.min(layout.getLineCount() - 1, lastVisible + MARGIN_LINES);
        if (mLanguage == null) return;

        int from = layout.getLineStart(mHighlightedFirstLine);
        int to = layout.getLineEnd(mHighlightedLastLine);
        int scanStart = from <= MAX_SCAN_BACK ? 0 : layout.getLineStart(layout.getLineForOffset(from - MAX_SCAN_BACK));
        SyntaxHighlighter.highlight(text, scanStart, from, to, mLanguage, (start, end, type) ->
            text.setSpan(new SyntaxSpan(TOKEN_COLORS[type]), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE));
    }

    /** Make the gutter wide enough for the largest line number. */
    private void updateGutter() {
        mNumberPaint.setTextSize(getTextSize() * 0.85f);
        int lines = getTextLineCount();
        int width = (int) mNumberPaint.measureText(String.valueOf(Math.max(lines, 99))) + mGutterPadding * 2;
        if (width != mGutterWidth) {
            mGutterWidth = width;
            setPadding(width + mGutterPadding, getPaddingTop(), getPaddingRight(), getPaddingBottom());
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Drawn over the text, so that text scrolled to the left goes under the gutter.
        Layout layout = getLayout();
        if (layout != null) {
            int scrollX = getScrollX();
            int scrollY = getScrollY();
            canvas.drawRect(scrollX, scrollY, scrollX + mGutterWidth, scrollY + getHeight(), mGutterPaint);
            int top = scrollY - getPaddingTop();
            int first = layout.getLineForVertical(Math.max(0, top));
            int last = layout.getLineForVertical(top + getHeight());
            CharSequence text = getText();
            int number = mWordWrap ? countLineFeedsBefore(layout.getLineStart(first)) : first;
            for (int line = first; line <= last; line++) {
                if (mWordWrap) {
                    int lineStart = layout.getLineStart(line);
                    if (lineStart > 0 && text.charAt(lineStart - 1) != '\n') continue;
                    if (line > first) number++;
                } else {
                    number = line;
                }
                float y = getPaddingTop() + layout.getLineBaseline(line);
                canvas.drawText(String.valueOf(number + 1), scrollX + mGutterWidth - mGutterPadding, y, mNumberPaint);
            }
        }
    }

}
