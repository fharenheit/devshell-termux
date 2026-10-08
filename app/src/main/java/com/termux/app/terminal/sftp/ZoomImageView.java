package com.termux.app.terminal.sftp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

import androidx.annotation.Nullable;

/**
 * Shows an image fitted to the view, which can be zoomed with two fingers, moved with one, and
 * switched between fitted and actual size with a double tap.
 */
public class ZoomImageView extends ImageView {

    private static final float MAX_SCALE_OF_ACTUAL_SIZE = 8f;

    private final Matrix mMatrix = new Matrix();
    private final ScaleGestureDetector mScaleDetector;
    private final GestureDetector mGestureDetector;
    private float mFitScale = 1f;

    public ZoomImageView(Context context) {
        super(context);
        setScaleType(ScaleType.MATRIX);
        mScaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float scale = getScale();
                float factor = detector.getScaleFactor();
                float target = Math.max(mFitScale, Math.min(scale * factor, Math.max(mFitScale, MAX_SCALE_OF_ACTUAL_SIZE)));
                mMatrix.postScale(target / scale, target / scale, detector.getFocusX(), detector.getFocusY());
                constrain();
                return true;
            }
        });
        mGestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                mMatrix.postTranslate(-distanceX, -distanceY);
                constrain();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                // Switch between fitted and actual size, or twice the fitted size for small images.
                float scale = getScale();
                float target = scale > mFitScale * 1.01f ? mFitScale : Math.max(1f, mFitScale * 2);
                mMatrix.postScale(target / scale, target / scale, e.getX(), e.getY());
                constrain();
                return true;
            }
        });
    }

    @Override
    public void setImageBitmap(@Nullable Bitmap bitmap) {
        super.setImageBitmap(bitmap);
        fit();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fit();
    }

    /** Fit and center the image. */
    private void fit() {
        if (getDrawable() == null || getWidth() == 0 || getHeight() == 0) return;
        float width = getDrawable().getIntrinsicWidth();
        float height = getDrawable().getIntrinsicHeight();
        // Small images are not enlarged.
        mFitScale = Math.min(1f, Math.min(getWidth() / width, getHeight() / height));
        mMatrix.reset();
        mMatrix.postScale(mFitScale, mFitScale);
        mMatrix.postTranslate((getWidth() - width * mFitScale) / 2, (getHeight() - height * mFitScale) / 2);
        setImageMatrix(mMatrix);
    }

    private float getScale() {
        float[] values = new float[9];
        mMatrix.getValues(values);
        return values[Matrix.MSCALE_X];
    }

    /** Keep the image on the view, centered on an axis where it is smaller than the view. */
    private void constrain() {
        if (getDrawable() == null) return;
        RectF bounds = new RectF(0, 0, getDrawable().getIntrinsicWidth(), getDrawable().getIntrinsicHeight());
        mMatrix.mapRect(bounds);
        float dx = 0;
        float dy = 0;
        if (bounds.width() <= getWidth()) dx = (getWidth() - bounds.width()) / 2 - bounds.left;
        else if (bounds.left > 0) dx = -bounds.left;
        else if (bounds.right < getWidth()) dx = getWidth() - bounds.right;
        if (bounds.height() <= getHeight()) dy = (getHeight() - bounds.height()) / 2 - bounds.top;
        else if (bounds.top > 0) dy = -bounds.top;
        else if (bounds.bottom < getHeight()) dy = getHeight() - bounds.bottom;
        mMatrix.postTranslate(dx, dy);
        setImageMatrix(mMatrix);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mScaleDetector.onTouchEvent(event);
        mGestureDetector.onTouchEvent(event);
        return true;
    }

}
