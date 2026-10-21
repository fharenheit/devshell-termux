package com.termux.app.terminal.tabs;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.termux.R;

/** Confirmation before terminating a running terminal session. */
public final class SessionCloseDialog {

    private SessionCloseDialog() {}

    public static AlertDialog show(Activity activity, Runnable onConfirm) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        Context context = builder.getContext();
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(context, 16);
        content.setPadding(padding, padding, padding, padding);

        TextView message = new TextView(context);
        message.setTextAppearance(context, android.R.style.TextAppearance_Material_Subhead);
        message.setText(R.string.title_confirm_kill_process);
        content.addView(message, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));

        Button cancel = actionButton(context, android.R.id.button2, android.R.string.no);
        Button confirm = actionButton(context, android.R.id.button1, android.R.string.yes);
        content.addView(cancel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(confirm, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        AlertDialog dialog = builder.setView(scroll).create();
        cancel.setOnClickListener(view -> dialog.dismiss());
        confirm.setOnClickListener(view -> {
            dialog.dismiss();
            onConfirm.run();
        });
        dialog.show();

        // Use the app window, which can be narrower than the display in split-screen mode.
        int availableWidth = activity.getWindow().getDecorView().getWidth();
        if (availableWidth <= 0) availableWidth = activity.getResources().getDisplayMetrics().widthPixels;
        dialog.getWindow().setLayout(Math.max(1, Math.round(availableWidth * 0.7f)), ViewGroup.LayoutParams.WRAP_CONTENT);
        return dialog;
    }

    private static Button actionButton(Context context, int id, int text) {
        Button button = new Button(context);
        button.setId(id);
        button.setText(text);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(context, 48));
        button.setMinimumHeight(dp(context, 48));
        button.setSingleLine(false);
        int padding = dp(context, 8);
        button.setPadding(padding, padding, padding, padding);
        return button;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
