package com.termux.app.terminal.db;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.OverScroller;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A table of the rows of a {@link QueryResult}, drawn on a canvas so that many rows and columns
 * scroll smoothly: a header that stays on top, row numbers that stay on the left, NULL in gray,
 * numbers on the right. Tapping a cell selects it, tapping a row number selects the row, tapping a
 * header sorts by the column, and dragging the edge of a header changes the width of the column.
 * Sorting and filtering change only what is shown, not the result.
 */
public class ResultGridView extends View {

    /** Gets the taps of the grid. */
    public interface Listener {
        void onCellLongPress(int row, int column);

        void onRowNumberLongPress(int row);

        void onHeaderLongPress(int column);

        void onSelectionChanged();

        /** A cell was tapped twice. */
        void onCellDoubleTap(int row, int column);
    }

    /** Marks rows and cells, like changed ones while editing. */
    public interface Marker {
        /** The background of a row, as a result index, or 0 for none. */
        int getRowColor(int row);

        /** The background of a cell, or 0 for none. */
        int getCellColor(int row, int column);
    }

    private static final int MAX_CELL_CHARS = 200;
    private static final int TEXT_COLOR = 0xFFE0E0E0;
    private static final int NULL_COLOR = 0xFF707070;
    private static final int HEADER_BACKGROUND = 0xFF2A2A2A;
    private static final int LINE_COLOR = 0xFF3A3A3A;
    private static final int SELECTED_BACKGROUND = 0xFF264F78;
    private static final int ROW_SELECTED_BACKGROUND = 0xFF1E3A5A;
    private static final int ODD_BACKGROUND = 0xFF161616;

    @Nullable private QueryResult mResult;
    /** The rows shown, as indexes of the result, after filtering and sorting. */
    private final List<Integer> mRows = new ArrayList<>();
    private int[] mWidths = new int[0];
    private int mRowNumberWidth;
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBoldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint();
    private final int mRowHeight;
    private final int mPadding;
    private final int mMinWidth;
    private final int mMaxWidth;
    private final int mResizeSlop;
    private final float mTextBaseline;

    private int mScrollX;
    private int mScrollY;
    private final OverScroller mScroller;
    private final GestureDetector mGestures;

    /** The selected cell, as a shown row and a column, or -1. */
    private int mSelectedRow = -1;
    private int mSelectedColumn = -1;
    /** Rows selected with their numbers, as indexes of the result. */
    private final List<Integer> mSelectedRows = new ArrayList<>();
    private int mSortColumn = -1;
    private boolean mSortAscending = true;
    @NonNull private String mFilter = "";
    /** The column whose width is being dragged, or -1. */
    private int mResizing = -1;
    @Nullable private Listener mListener;
    @Nullable private Marker mMarker;

    public ResultGridView(Context context) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        mTextPaint.setTypeface(Typeface.MONOSPACE);
        mTextPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13, getResources().getDisplayMetrics()));
        mTextPaint.setColor(TEXT_COLOR);
        mBoldPaint.set(mTextPaint);
        mBoldPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        mRowHeight = Math.round(30 * density);
        mPadding = Math.round(6 * density);
        mMinWidth = Math.round(48 * density);
        mMaxWidth = Math.round(320 * density);
        mResizeSlop = Math.round(14 * density);
        Paint.FontMetrics metrics = mTextPaint.getFontMetrics();
        mTextBaseline = (mRowHeight - metrics.ascent - metrics.descent) / 2f;
        mScroller = new OverScroller(context);
        mGestures = new GestureDetector(context, new Gestures());
        setFocusable(true);
        setBackgroundColor(0xFF101010);
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    public void setMarker(@Nullable Marker marker) {
        mMarker = marker;
        invalidate();
    }

    /** Show the rows of the result again after they were changed, keeping the position. */
    public void refresh() {
        if (mResult != null && mWidths.length != mResult.columns.size()) measureColumns();
        updateRows();
    }

    /** Scroll so that a row, as a result index, is shown. */
    public void scrollToRow(int row) {
        int shown = mRows.indexOf(row);
        if (shown < 0) return;
        int top = shown * mRowHeight;
        int visible = Math.max(mRowHeight, getHeight() - mRowHeight);
        if (top < mScrollY) mScrollY = top;
        else if (top + mRowHeight > mScrollY + visible) mScrollY = top + mRowHeight - visible;
        clampScroll();
        invalidate();
    }

    /** Show a result, keeping the scroll position if {@code keepPosition}, like after fetching more rows. */
    public void setResult(@Nullable QueryResult result, boolean keepPosition) {
        mResult = result;
        if (!keepPosition) {
            mScrollX = mScrollY = 0;
            mSelectedRow = mSelectedColumn = -1;
            mSelectedRows.clear();
            mSortColumn = -1;
            measureColumns();
        } else if (result != null && mWidths.length != result.columns.size()) {
            measureColumns();
        }
        updateRows();
    }

    @Nullable
    public QueryResult getResult() {
        return mResult;
    }

    /** Show only the rows with a cell that contains the text, ignoring case. */
    public void setFilter(@NonNull String filter) {
        mFilter = filter.trim().toLowerCase(Locale.ROOT);
        mScrollY = 0;
        mSelectedRow = -1;
        updateRows();
    }

    public int getShownRowCount() {
        return mRows.size();
    }

    /** The result index of a shown row. */
    public int getResultRow(int shownRow) {
        return mRows.get(shownRow);
    }

    public int getSelectedColumn() {
        return mSelectedColumn;
    }

    /** The result row of the selected cell, or -1. */
    public int getSelectedResultRow() {
        return mSelectedRow >= 0 && mSelectedRow < mRows.size() ? mRows.get(mSelectedRow) : -1;
    }

    /** The rows selected with their numbers, in the shown order. */
    @NonNull
    public List<Integer> getSelectedRows() {
        List<Integer> rows = new ArrayList<>();
        for (int row : mRows) if (mSelectedRows.contains(row)) rows.add(row);
        return rows;
    }

    /** The shown rows, in order, as indexes of the result. */
    @NonNull
    public List<Integer> getShownRows() {
        return new ArrayList<>(mRows);
    }

    public void clearSelection() {
        mSelectedRows.clear();
        mSelectedRow = mSelectedColumn = -1;
        invalidate();
        if (mListener != null) mListener.onSelectionChanged();
    }

    private void measureColumns() {
        QueryResult result = mResult;
        if (result == null) {
            mWidths = new int[0];
            return;
        }
        int count = result.columns.size();
        mWidths = new int[count];
        int sample = Math.min(result.rows.size(), 200);
        for (int c = 0; c < count; c++) {
            float width = mBoldPaint.measureText(result.columns.get(c).name + " ▲");
            for (int r = 0; r < sample; r++) width = Math.max(width, mTextPaint.measureText(cellText(result.rows.get(r)[c])));
            mWidths[c] = Math.max(mMinWidth, Math.min(mMaxWidth, Math.round(width) + 2 * mPadding));
        }
        mRowNumberWidth = Math.round(mTextPaint.measureText(String.valueOf(Math.max(9, result.rows.size())))) + 2 * mPadding;
    }

    private void updateRows() {
        mRows.clear();
        QueryResult result = mResult;
        if (result != null) {
            for (int r = 0; r < result.rows.size(); r++) if (matches(result.rows.get(r))) mRows.add(r);
            if (mSortColumn >= 0 && mSortColumn < result.columns.size()) sortRows(result);
            mRowNumberWidth = Math.round(mTextPaint.measureText(String.valueOf(Math.max(9, result.rows.size())))) + 2 * mPadding;
        }
        clampScroll();
        invalidate();
        if (mListener != null) mListener.onSelectionChanged();
    }

    private boolean matches(@NonNull Object[] row) {
        if (mFilter.isEmpty()) return true;
        for (Object value : row) if (value != null && QueryResult.toText(value).toLowerCase(Locale.ROOT).contains(mFilter)) return true;
        return false;
    }

    private void sortRows(@NonNull QueryResult result) {
        int column = mSortColumn;
        boolean numeric = result.columns.get(column).isNumeric();
        Collections.sort(mRows, (a, b) -> {
            int order = compare(result.rows.get(a)[column], result.rows.get(b)[column], numeric);
            return mSortAscending ? order : -order;
        });
    }

    /** Compare values for sorting: NULL first, then numbers by value, then text. */
    static int compare(@Nullable Object a, @Nullable Object b, boolean numeric) {
        if (a == null || b == null) return a == null ? (b == null ? 0 : -1) : 1;
        String textA = QueryResult.toText(a);
        String textB = QueryResult.toText(b);
        if (numeric) {
            try {
                return new java.math.BigDecimal(textA).compareTo(new java.math.BigDecimal(textB));
            } catch (NumberFormatException ignored) {
            }
        }
        return textA.compareToIgnoreCase(textB);
    }

    /** Sort by a column, toggling the order if it is already sorted by it. */
    public void sortBy(int column) {
        if (mSortColumn == column) mSortAscending = !mSortAscending;
        else {
            mSortColumn = column;
            mSortAscending = true;
        }
        updateRows();
    }

    @NonNull
    private static String cellText(@Nullable Object value) {
        if (value == null) return "NULL";
        String text;
        if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            text = QueryResult.toHex(bytes.length > MAX_CELL_CHARS / 2 ? java.util.Arrays.copyOf(bytes, MAX_CELL_CHARS / 2) : bytes);
        } else {
            text = value.toString();
            if (text.length() > MAX_CELL_CHARS) text = text.substring(0, MAX_CELL_CHARS);
        }
        // One line in the cell.
        return text.replace('\n', '⏎').replace('\r', ' ').replace('\t', ' ');
    }

    private int contentWidth() {
        int width = mRowNumberWidth;
        for (int w : mWidths) width += w;
        return width;
    }

    private int contentHeight() {
        return (mRows.size() + 1) * mRowHeight;
    }

    private void clampScroll() {
        mScrollX = Math.max(0, Math.min(mScrollX, Math.max(0, contentWidth() - getWidth())));
        mScrollY = Math.max(0, Math.min(mScrollY, Math.max(0, contentHeight() - getHeight())));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        clampScroll();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        QueryResult result = mResult;
        if (result == null) return;
        int width = getWidth();
        int height = getHeight();
        int columnCount = result.columns.size();
        int firstRow = Math.max(0, mScrollY / mRowHeight);
        int lastRow = Math.min(mRows.size() - 1, (mScrollY + height) / mRowHeight);

        // Cells.
        for (int r = firstRow; r <= lastRow; r++) {
            int resultRow = mRows.get(r);
            Object[] row = result.rows.get(resultRow);
            int top = (r + 1) * mRowHeight - mScrollY;
            boolean rowSelected = mSelectedRows.contains(resultRow);
            int x = mRowNumberWidth - mScrollX;
            int rowColor = mMarker != null ? mMarker.getRowColor(resultRow) : 0;
            if (rowSelected || rowColor != 0 || r % 2 == 1) {
                mFillPaint.setColor(rowSelected ? ROW_SELECTED_BACKGROUND : rowColor != 0 ? rowColor : ODD_BACKGROUND);
                canvas.drawRect(0, top, width, top + mRowHeight, mFillPaint);
            }
            for (int c = 0; c < columnCount; c++) {
                int columnWidth = mWidths[c];
                if (x + columnWidth > mRowNumberWidth && x < width) {
                    if (r == mSelectedRow && c == mSelectedColumn) {
                        mFillPaint.setColor(SELECTED_BACKGROUND);
                        canvas.drawRect(x, top, x + columnWidth, top + mRowHeight, mFillPaint);
                    } else if (mMarker != null) {
                        int cellColor = mMarker.getCellColor(resultRow, c);
                        if (cellColor != 0) {
                            mFillPaint.setColor(cellColor);
                            canvas.drawRect(x, top, x + columnWidth, top + mRowHeight, mFillPaint);
                        }
                    }
                    drawCell(canvas, row[c], result.columns.get(c).isNumeric(), x, top, columnWidth);
                }
                x += columnWidth;
            }
        }

        // Column lines.
        mFillPaint.setColor(LINE_COLOR);
        int x = mRowNumberWidth - mScrollX;
        for (int c = 0; c < columnCount; c++) {
            x += mWidths[c];
            if (x > mRowNumberWidth && x < width) canvas.drawRect(x - 1, 0, x, height, mFillPaint);
        }

        // Row numbers, which stay on the left.
        mFillPaint.setColor(HEADER_BACKGROUND);
        canvas.drawRect(0, mRowHeight, mRowNumberWidth, height, mFillPaint);
        for (int r = firstRow; r <= lastRow; r++) {
            int top = (r + 1) * mRowHeight - mScrollY;
            if (top + mRowHeight <= mRowHeight) continue;
            int resultRow = mRows.get(r);
            if (mSelectedRows.contains(resultRow)) {
                mFillPaint.setColor(SELECTED_BACKGROUND);
                canvas.drawRect(0, top, mRowNumberWidth, top + mRowHeight, mFillPaint);
            }
            mTextPaint.setColor(NULL_COLOR);
            String number = String.valueOf(resultRow + 1);
            canvas.drawText(number, mRowNumberWidth - mPadding - mTextPaint.measureText(number), top + mTextBaseline, mTextPaint);
        }

        // The header, which stays on top.
        mFillPaint.setColor(HEADER_BACKGROUND);
        canvas.drawRect(0, 0, width, mRowHeight, mFillPaint);
        x = mRowNumberWidth - mScrollX;
        for (int c = 0; c < columnCount; c++) {
            int columnWidth = mWidths[c];
            if (x + columnWidth > mRowNumberWidth && x < width) {
                String name = result.columns.get(c).name;
                if (c == mSortColumn) name += mSortAscending ? " ▲" : " ▼";
                canvas.save();
                canvas.clipRect(Math.max(x, mRowNumberWidth), 0, x + columnWidth - 1, mRowHeight);
                mBoldPaint.setColor(TEXT_COLOR);
                canvas.drawText(name, x + mPadding, mTextBaseline, mBoldPaint);
                canvas.restore();
            }
            x += columnWidth;
            mFillPaint.setColor(LINE_COLOR);
            if (x > mRowNumberWidth && x < width) canvas.drawRect(x - 1, 0, x, mRowHeight, mFillPaint);
        }
        mFillPaint.setColor(LINE_COLOR);
        canvas.drawRect(0, mRowHeight - 1, width, mRowHeight, mFillPaint);
        canvas.drawRect(mRowNumberWidth - 1, 0, mRowNumberWidth, height, mFillPaint);
    }

    private void drawCell(@NonNull Canvas canvas, @Nullable Object value, boolean numeric, int x, int top, int width) {
        String text = cellText(value);
        mTextPaint.setColor(value == null ? NULL_COLOR : TEXT_COLOR);
        canvas.save();
        canvas.clipRect(Math.max(x, mRowNumberWidth), Math.max(top, mRowHeight), x + width - 1, top + mRowHeight);
        float textX = x + mPadding;
        if (numeric && value != null) {
            float textWidth = mTextPaint.measureText(text);
            if (textWidth < width - 2 * mPadding) textX = x + width - mPadding - textWidth;
        }
        canvas.drawText(text, textX, top + mTextBaseline, mTextPaint);
        canvas.restore();
    }

    @Override
    public void computeScroll() {
        if (mScroller.computeScrollOffset()) {
            mScrollX = mScroller.getCurrX();
            mScrollY = mScroller.getCurrY();
            clampScroll();
            postInvalidateOnAnimation();
        }
    }

    /** The column at a view x, or -1. */
    private int columnAt(float x) {
        if (x < mRowNumberWidth) return -1;
        float position = x + mScrollX - mRowNumberWidth;
        for (int c = 0; c < mWidths.length; c++) {
            if (position < mWidths[c]) return c;
            position -= mWidths[c];
        }
        return -1;
    }

    /** The column whose right edge is near a view x, to resize it, or -1. */
    private int columnEdgeAt(float x) {
        float edge = mRowNumberWidth - mScrollX;
        for (int c = 0; c < mWidths.length; c++) {
            edge += mWidths[c];
            if (Math.abs(x - edge) < mResizeSlop) return c;
        }
        return -1;
    }

    /** The shown row at a view y, or -1 for the header or below the rows. */
    private int rowAt(float y) {
        if (y < mRowHeight) return -1;
        int row = (int) ((y + mScrollY) / mRowHeight) - 1;
        return row < mRows.size() ? row : -1;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mResult == null) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && event.getY() < mRowHeight) {
            mResizing = columnEdgeAt(event.getX());
            if (mResizing >= 0) {
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        }
        if (mResizing >= 0) {
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float left = mRowNumberWidth - mScrollX;
                for (int c = 0; c < mResizing; c++) left += mWidths[c];
                mWidths[mResizing] = Math.max(mMinWidth, Math.round(event.getX() - left));
                invalidate();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                mResizing = -1;
                clampScroll();
            }
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
        return mGestures.onTouchEvent(event) || super.onTouchEvent(event);
    }

    private final class Gestures extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            mScroller.forceFinished(true);
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            mScrollX += Math.round(distanceX);
            mScrollY += Math.round(distanceY);
            clampScroll();
            invalidate();
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            mScroller.fling(mScrollX, mScrollY, Math.round(-velocityX), Math.round(-velocityY), 0,
                Math.max(0, contentWidth() - getWidth()), 0, Math.max(0, contentHeight() - getHeight()));
            postInvalidateOnAnimation();
            return true;
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            int row = rowAt(e.getY());
            int column = columnAt(e.getX());
            if (e.getY() < mRowHeight) {
                if (column >= 0) sortBy(column);
            } else if (row >= 0 && e.getX() < mRowNumberWidth) {
                Integer resultRow = mRows.get(row);
                if (!mSelectedRows.remove(resultRow)) mSelectedRows.add(resultRow);
                invalidate();
                if (mListener != null) mListener.onSelectionChanged();
            } else if (row >= 0 && column >= 0) {
                mSelectedRow = row;
                mSelectedColumn = column;
                invalidate();
                if (mListener != null) mListener.onSelectionChanged();
            }
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            int row = rowAt(e.getY());
            int column = columnAt(e.getX());
            if (mListener != null && row >= 0 && column >= 0 && e.getX() >= mRowNumberWidth) {
                mSelectedRow = row;
                mSelectedColumn = column;
                invalidate();
                mListener.onCellDoubleTap(mRows.get(row), column);
            }
            return true;
        }

        @Override
        public void onLongPress(MotionEvent e) {
            if (mListener == null) return;
            int row = rowAt(e.getY());
            int column = columnAt(e.getX());
            if (e.getY() < mRowHeight) {
                if (column >= 0) mListener.onHeaderLongPress(column);
            } else if (row >= 0 && e.getX() < mRowNumberWidth) {
                mListener.onRowNumberLongPress(mRows.get(row));
            } else if (row >= 0 && column >= 0) {
                mSelectedRow = row;
                mSelectedColumn = column;
                invalidate();
                mListener.onCellLongPress(mRows.get(row), column);
            }
        }
    }

}
