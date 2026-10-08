package com.termux.app.terminal.fonts;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TextStyle;
import com.termux.view.TerminalRenderer;

import java.nio.charset.StandardCharsets;

/**
 * Previews a font the way the terminal draws it: the sample lines are written to a
 * {@link TerminalEmulator} and drawn with the {@link TerminalRenderer} of the terminal view. So
 * each character takes the cells the terminal gives it, characters the font does not have are
 * drawn with a system fallback font, and right-to-left scripts like Arabic and Hebrew are drawn
 * left to right, like in the terminal, which does not reorder them.
 */
public class TerminalFontPreviewView extends View {

    /** The sample lines: Korean, Latin and code, CJK, other Latin, Cyrillic and Greek, right-to-left and Thai, symbols. */
    static final String[] SAMPLE_LINES = {
        "가나다 한글 abc ABC 0O 1lI {} -> != ==",
        "日本語 ひらがな カタカナ 中文 简体 繁體",
        "Привет Γειά Tiếng Việt",
        "مرحبا שלום สวัสดี",
        "─│┌┐└┘█░    "
    };

    private static final int TEXT_SIZE_SP = 14;

    @Nullable private Typeface mTypeface;
    @Nullable private String mMessage;
    @Nullable private TerminalRenderer mRenderer;
    @Nullable private TerminalEmulator mEmulator;
    private final Paint mMessagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TerminalFontPreviewView(@NonNull Context context) {
        super(context);
        mMessagePaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP, getResources().getDisplayMetrics()));
        int padding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, getResources().getDisplayMetrics()));
        setPadding(padding, padding, padding, padding);
    }

    /** Preview a typeface. */
    public void setTypeface(@NonNull Typeface typeface) {
        mTypeface = typeface;
        mMessage = null;
        mRenderer = null;
        mEmulator = null;
        requestLayout();
        invalidate();
    }

    /** Show a message instead of a preview, like for a font that is not downloaded yet. */
    public void setMessage(@NonNull String message) {
        mTypeface = null;
        mMessage = message;
        mRenderer = null;
        mEmulator = null;
        requestLayout();
        invalidate();
    }

    @NonNull
    private TerminalRenderer getRenderer() {
        if (mRenderer == null) {
            int textSize = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP, getResources().getDisplayMetrics()));
            mRenderer = new TerminalRenderer(textSize, mTypeface != null ? mTypeface : Typeface.MONOSPACE);
        }
        return mRenderer;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        // The height of the sample lines, which may wrap on a narrow screen.
        int rows = SAMPLE_LINES.length;
        if (mTypeface != null && width > 0) rows = getEmulator(width).getCursorRow() + 1;
        int lineSpacing = getRenderer().getFontLineSpacing();
        setMeasuredDimension(width, getPaddingTop() + rows * lineSpacing + getPaddingBottom());
    }

    /** The emulator with the sample lines written to it, with as many columns as fit in the width. */
    @NonNull
    private TerminalEmulator getEmulator(int width) {
        TerminalRenderer renderer = getRenderer();
        int columns = Math.max(10, (int) ((width - getPaddingLeft() - getPaddingRight()) / renderer.getFontWidth()));
        if (mEmulator == null || mEmulator.mColumns != columns) {
            int rows = SAMPLE_LINES.length * 3;
            mEmulator = new TerminalEmulator(new NoOutput(), columns, rows, (int) renderer.getFontWidth(),
                renderer.getFontLineSpacing(), rows, new TermuxTerminalSessionClientBase());
            StringBuilder text = new StringBuilder("\033[?25l"); // Hide the cursor.
            for (int i = 0; i < SAMPLE_LINES.length; i++) {
                if (i > 0) text.append("\r\n");
                text.append(SAMPLE_LINES[i]);
            }
            byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
            mEmulator.append(bytes, bytes.length);
        }
        return mEmulator;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // The colors of the terminal, which may be set in ~/.termux/colors.properties.
        int[] colors = TerminalColors.COLOR_SCHEME.mDefaultColors;
        canvas.drawColor(colors[TextStyle.COLOR_INDEX_BACKGROUND]);
        if (mMessage != null) {
            mMessagePaint.setColor(colors[TextStyle.COLOR_INDEX_FOREGROUND]);
            canvas.drawText(mMessage, getPaddingLeft(), getPaddingTop() - mMessagePaint.ascent(), mMessagePaint);
            return;
        }
        if (mTypeface == null || getWidth() == 0) return;
        TerminalEmulator emulator = getEmulator(getWidth());
        canvas.save();
        canvas.translate(getPaddingLeft(), getPaddingTop());
        getRenderer().render(emulator, canvas, 0, -1, -1, -1, -1);
        canvas.restore();
    }

    /** The emulator output for the preview, which never gets input. */
    private static class NoOutput extends TerminalOutput {
        @Override public void write(byte[] data, int offset, int count) {}
        @Override public void titleChanged(String oldTitle, String newTitle) {}
        @Override public void onCopyTextToClipboard(String text) {}
        @Override public void onPasteTextFromClipboard() {}
        @Override public void onBell() {}
        @Override public void onColorsChanged() {}
    }

}
