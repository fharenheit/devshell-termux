package com.termux.app.terminal.ssh;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.interact.ShareUtils;
import com.termux.terminal.TerminalSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The port forwards of an ssh tab, with whether the ports listen on the device. Tapping a local
 * forward opens it in the browser. While the tab is connected, forwards can be added and stopped
 * on its connection.
 */
public final class SshForwardsDialog {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private SshForwardsDialog() {}

    /** Whether a tab can have forwards: an ssh tab, not a mosh one, which has no ssh connection to forward over. */
    public static boolean isAvailable(@Nullable TerminalSession session) {
        String hostName = SshConnector.getHostName(session);
        if (hostName == null) return false;
        SshHost host = SshHostStore.find(SshHostStore.load(), hostName);
        return host == null || !host.useMosh;
    }

    public static void show(@NonNull TermuxActivity activity, @NonNull TerminalSession session) {
        String hostName = SshConnector.getHostName(session);
        if (hostName == null) return;
        SshHost host = SshHostStore.find(SshHostStore.load(), hostName);
        String controlPath = SshConnector.getControlPath(session);
        List<SshForwards.Item> items = SshForwards.getForwards(session, host, controlPath);
        String serverName = host != null && !host.hostName.isEmpty() ? host.hostName : hostName;

        // The status of each forward, filled in when the ports were checked.
        String[] statuses = new String[items.size()];
        for (int i = 0; i < statuses.length; i++)
            statuses[i] = items.get(i).forward.listensOnDevice() ? activity.getString(R.string.ssh_forward_checking)
                : activity.getString(R.string.ssh_forward_on_server, serverName);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.title_ssh_forwards, hostName))
            .setNegativeButton(R.string.action_ssh_forwards_close, null);
        if (controlPath != null)
            builder.setNeutralButton(R.string.action_ssh_forward_add, (d, which) -> showAddDialog(activity, session, hostName));
        if (items.isEmpty()) {
            builder.setMessage(controlPath != null ? R.string.msg_ssh_forwards_empty : R.string.msg_ssh_forwards_empty_not_connected).show();
            return;
        }

        ArrayAdapter<SshForwards.Item> adapter = new ArrayAdapter<SshForwards.Item>(activity, android.R.layout.simple_list_item_2, android.R.id.text1, items) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                SshForwards.Item item = items.get(position);
                ((TextView) view.findViewById(android.R.id.text1)).setText(item.forward.getLabel(serverName));
                String status = statuses[position];
                if (item.added) status += " · " + activity.getString(R.string.ssh_forward_added);
                ((TextView) view.findViewById(android.R.id.text2)).setText(status);
                return view;
            }
        };
        AlertDialog dialog = builder
            .setAdapter(adapter, (d, which) -> {
                SshForwards.Item item = items.get(which);
                if (item.forward.type == SshForward.LOCAL) ShareUtils.openURL(activity, item.forward.getBrowserUrl());
                else showItemMenu(activity, session, hostName, controlPath, item, serverName);
            })
            .create();
        dialog.show();
        if (controlPath == null) Toast.makeText(activity, R.string.msg_ssh_forwards_not_connected, Toast.LENGTH_LONG).show();
        ListView listView = dialog.getListView();
        if (listView != null) {
            listView.setOnItemLongClickListener((parent, view, position, id) -> {
                dialog.dismiss();
                showItemMenu(activity, session, hostName, controlPath, items.get(position), serverName);
                return true;
            });
        }

        // Check which ports listen, in the background.
        EXECUTOR.execute(() -> {
            for (int i = 0; i < items.size(); i++) {
                SshForward forward = items.get(i).forward;
                if (!forward.listensOnDevice()) continue;
                boolean listening = SshForwards.isListening(forward);
                int index = i;
                HANDLER.post(() -> {
                    statuses[index] = activity.getString(listening ? R.string.ssh_forward_listening : R.string.ssh_forward_not_listening);
                    adapter.notifyDataSetChanged();
                });
            }
        });
    }

    private static void showItemMenu(@NonNull TermuxActivity activity, @NonNull TerminalSession session, @NonNull String hostName,
                                     @Nullable String controlPath, @NonNull SshForwards.Item item, @NonNull String serverName) {
        SshForward forward = item.forward;
        List<CharSequence> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (forward.type == SshForward.LOCAL) {
            labels.add(activity.getString(R.string.action_ssh_forward_open, forward.getBrowserUrl()));
            actions.add(() -> ShareUtils.openURL(activity, forward.getBrowserUrl()));
        }
        String address = forward.listensOnDevice() ? forward.getDeviceAddress() : serverName + ":" + forward.port;
        labels.add(activity.getString(R.string.action_ssh_forward_copy, address));
        actions.add(() -> {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(null, address));
            Toast.makeText(activity, R.string.msg_ssh_forward_copied, Toast.LENGTH_SHORT).show();
        });
        if (controlPath != null) {
            labels.add(activity.getString(R.string.action_ssh_forward_stop));
            actions.add(() -> runInBackground(activity, () -> SshForwards.stop(activity, session, hostName, controlPath, forward),
                activity.getString(R.string.msg_ssh_forward_stopped, forward.getLabel(serverName)), session));
        }
        new AlertDialog.Builder(activity)
            .setTitle(forward.getLabel(serverName))
            .setItems(labels.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
            .setNegativeButton(android.R.string.cancel, (d, which) -> show(activity, session))
            .show();
    }

    private interface Operation {
        void run() throws IOException;
    }

    /** Run an ssh control command in the background, then show the forwards again or the error. */
    private static void runInBackground(@NonNull TermuxActivity activity, @NonNull Operation operation, @NonNull String successMessage,
                                        @NonNull TerminalSession session) {
        EXECUTOR.execute(() -> {
            IOException error = null;
            try {
                operation.run();
            } catch (IOException e) {
                error = e;
            }
            IOException finalError = error;
            HANDLER.post(() -> {
                if (activity.isFinishing()) return;
                if (finalError != null) {
                    new AlertDialog.Builder(activity)
                        .setTitle(R.string.title_ssh_forward_failed)
                        .setMessage(finalError.getMessage())
                        .setPositiveButton(android.R.string.ok, (d, which) -> show(activity, session))
                        .show();
                    return;
                }
                Toast.makeText(activity, successMessage, Toast.LENGTH_SHORT).show();
                show(activity, session);
            });
        });
    }

    private static void showAddDialog(@NonNull TermuxActivity activity, @NonNull TerminalSession session, @NonNull String hostName) {
        int padding = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20, activity.getResources().getDisplayMetrics()));
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding / 2, padding, 0);
        Spinner type = new Spinner(activity);
        type.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, new String[]{
            activity.getString(R.string.ssh_forward_type_local), activity.getString(R.string.ssh_forward_type_remote),
            activity.getString(R.string.ssh_forward_type_socks)}));
        layout.addView(type);
        EditText spec = new EditText(activity);
        spec.setSingleLine();
        spec.setHint("8080:localhost:80");
        layout.addView(spec);
        type.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                spec.setHint(position == SshForward.SOCKS ? "1080" : position == SshForward.REMOTE ? "9000:localhost:3000" : "8080:localhost:80");
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(R.string.action_ssh_forward_add)
            .setView(layout)
            .setPositiveButton(R.string.action_ssh_forward_add, null)
            .setNegativeButton(android.R.string.cancel, (d, which) -> show(activity, session))
            .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            SshForward forward = SshForward.parse(type.getSelectedItemPosition(), spec.getText().toString());
            if (forward == null) {
                spec.setError(activity.getString(R.string.msg_ssh_forward_invalid));
                return;
            }
            String controlPath = SshConnector.getControlPath(session);
            dialog.dismiss();
            if (controlPath == null) {
                Toast.makeText(activity, R.string.msg_ssh_forwards_not_connected, Toast.LENGTH_LONG).show();
                return;
            }
            runInBackground(activity, () -> SshForwards.add(activity, session, hostName, controlPath, forward),
                activity.getString(R.string.msg_ssh_forward_added, forward.toSpec()), session);
        });
    }

}
