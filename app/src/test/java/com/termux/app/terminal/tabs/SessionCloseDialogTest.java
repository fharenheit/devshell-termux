package com.termux.app.terminal.tabs;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.widget.Button;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w240dp-h640dp-mdpi")
public class SessionCloseDialogTest {

    private Activity activity(int windowWidth, float fontScale) {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Configuration config = new Configuration(activity.getResources().getConfiguration());
        config.fontScale = fontScale;
        activity.getResources().updateConfiguration(config, activity.getResources().getDisplayMetrics());
        activity.getWindow().getDecorView().layout(0, 0, windowWidth, 640);
        return activity;
    }

    private void assertButtonsFit(AlertDialog dialog, int expectedWidth) {
        Assert.assertEquals(expectedWidth, dialog.getWindow().getAttributes().width);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        View decor = dialog.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(expectedWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST));
        decor.layout(0, 0, expectedWidth, decor.getMeasuredHeight());
        Button confirm = dialog.findViewById(android.R.id.button1);
        Button cancel = dialog.findViewById(android.R.id.button2);
        for (Button button : new Button[]{confirm, cancel}) {
            Rect bounds = new Rect();
            StringBuilder hierarchy = new StringBuilder();
            for (View view = button; view != null; view = view.getParent() instanceof View ? (View) view.getParent() : null) {
                hierarchy.append(view.getClass().getSimpleName()).append(' ')
                    .append(view.getWidth()).append('x').append(view.getHeight())
                    .append(" visibility=").append(view.getVisibility()).append(';');
            }
            Assert.assertTrue(hierarchy.toString(), button.getGlobalVisibleRect(bounds));
            Assert.assertEquals(button.getWidth(), bounds.width());
            Assert.assertEquals(button.getHeight(), bounds.height());
            Assert.assertTrue(button.getHeight() >= 48);
            Assert.assertTrue(bounds.left >= 0 && bounds.right <= expectedWidth);
        }
        int[] confirmPosition = new int[2];
        int[] cancelPosition = new int[2];
        confirm.getLocationInWindow(confirmPosition);
        cancel.getLocationInWindow(cancelPosition);
        Assert.assertTrue(confirmPosition[1] >= cancelPosition[1] + cancel.getHeight());
    }

    @Test
    public void testBothActionsFitOnANarrowPhone() {
        AlertDialog dialog = SessionCloseDialog.show(activity(240, 1f), () -> {});
        assertButtonsFit(dialog, 168);
    }

    @Test
    @Config(qualifiers = "w480dp-h640dp-mdpi")
    public void testLargeTextFitsInsideASmallerAppWindow() {
        AlertDialog dialog = SessionCloseDialog.show(activity(240, 2f), () -> {});
        assertButtonsFit(dialog, 168);
    }

    @Test
    public void testCancelLeavesSessionRunningAndConfirmInvokesItsAction() {
        Activity activity = activity(240, 1f);
        int[] confirmed = {0};
        AlertDialog dialog = SessionCloseDialog.show(activity, () -> confirmed[0]++);
        dialog.findViewById(android.R.id.button2).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Assert.assertFalse(dialog.isShowing());
        Assert.assertEquals(0, confirmed[0]);

        dialog = SessionCloseDialog.show(activity, () -> confirmed[0]++);
        dialog.findViewById(android.R.id.button1).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Assert.assertFalse(dialog.isShowing());
        Assert.assertEquals(1, confirmed[0]);
    }
}
