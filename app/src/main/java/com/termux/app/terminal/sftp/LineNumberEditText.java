package com.termux.app.terminal.sftp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.Layout;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.EditText;

/**
 * A text editor without line wrapping, that scrolls in both directions and shows line numbers in
 * a gutter at the left. Without wrapping, each layout line is a line of the file.
 */
public class LineNumberEditText extends EditText {

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
        updateGutter();
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
        if (mNumberPaint != null) updateGutter();
    }

    /** Make the gutter wide enough for the largest line number. */
    private void updateGutter() {
        mNumberPaint.setTextSize(getTextSize() * 0.85f);
        int lines = Math.max(1, getLineCount());
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
            for (int line = first; line <= last; line++) {
                float y = getPaddingTop() + layout.getLineBaseline(line);
                canvas.drawText(String.valueOf(line + 1), scrollX + mGutterWidth - mGutterPadding, y, mNumberPaint);
            }
        }
    }

}
