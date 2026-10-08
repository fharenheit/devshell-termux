package com.termux.app.terminal.sftp;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.termux.R;

import java.util.Locale;

/**
 * A dialog to choose the permissions of files: read, write and execute for the owner, the group
 * and others, as checkboxes and as the octal number of `chmod`, which are kept in sync.
 */
public final class SftpPermissionsDialog {

    /** Receives the chosen permissions. */
    public interface Listener {
        void onPermissions(int permissions);
    }

    private SftpPermissionsDialog() {}

    /** Parse octal permissions like `755` or `0644`, or return -1 if invalid. */
    static int parseOctal(@NonNull String text) {
        text = text.trim();
        if (text.isEmpty() || text.length() > 4) return -1;
        try {
            int value = Integer.parseInt(text, 8);
            return value >= 0 && value <= 07777 ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static void show(@NonNull Activity activity, @NonNull String title, int permissions, @NonNull Listener listener) {
        int padding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20, activity.getResources().getDisplayMetrics()));
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding / 2, padding, 0);

        GridLayout grid = new GridLayout(activity);
        grid.setColumnCount(4);
        grid.addView(new TextView(activity));
        for (int label : new int[]{R.string.sftp_permission_read, R.string.sftp_permission_write, R.string.sftp_permission_execute}) {
            TextView header = new TextView(activity);
            header.setText(label);
            header.setGravity(Gravity.CENTER);
            grid.addView(header);
        }
        CheckBox[] boxes = new CheckBox[9];
        int[] whoLabels = {R.string.sftp_permission_owner, R.string.sftp_permission_group, R.string.sftp_permission_others};
        for (int who = 0; who < 3; who++) {
            TextView whoLabel = new TextView(activity);
            whoLabel.setText(whoLabels[who]);
            whoLabel.setPadding(0, 0, padding / 2, 0);
            grid.addView(whoLabel);
            for (int bit = 0; bit < 3; bit++) {
                CheckBox box = new CheckBox(activity);
                boxes[who * 3 + bit] = box;
                grid.addView(box);
            }
        }
        layout.addView(grid);

        EditText octal = new EditText(activity);
        octal.setInputType(InputType.TYPE_CLASS_NUMBER);
        octal.setHint("644");
        layout.addView(octal);

        // Boxes are in the order of the bits from the highest: owner rwx, group rwx, others rwx.
        final int special = permissions & 07000;
        final boolean[] updating = {false};
        Runnable fromBoxes = () -> {
            if (updating[0]) return;
            int value = special;
            for (int i = 0; i < 9; i++) if (boxes[i].isChecked()) value |= 1 << (8 - i);
            updating[0] = true;
            octal.setText(String.format(Locale.ROOT, special != 0 ? "%04o" : "%03o", value));
            octal.setSelection(octal.length());
            updating[0] = false;
        };
        java.util.function.IntConsumer toBoxes = value -> {
            updating[0] = true;
            for (int i = 0; i < 9; i++) boxes[i].setChecked((value & (1 << (8 - i))) != 0);
            updating[0] = false;
        };
        for (CheckBox box : boxes) box.setOnCheckedChangeListener((b, checked) -> fromBoxes.run());
        octal.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (updating[0]) return;
                int value = parseOctal(s.toString());
                if (value >= 0) toBoxes.accept(value);
            }
        });
        toBoxes.accept(permissions & 0777);
        fromBoxes.run();

        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int value = parseOctal(octal.getText().toString());
            if (value < 0) {
                octal.setError(activity.getString(R.string.sftp_permission_invalid));
                return;
            }
            dialog.dismiss();
            listener.onPermissions(value);
        });
    }

}
