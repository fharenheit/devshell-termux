package com.termux.app.terminal.ssh;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminal.tabs.TerminalTabsController;
import com.termux.shared.logger.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The dialogs to manage SSH hosts and keys and connect to hosts, opened from the terminal tab bar
 * menu.
 *
 * - The hosts list shows the hosts registered in the app and the other hosts of `~/.ssh/config`.
 *   Tapping a host connects to it, long pressing it shows a menu to edit, delete or install a
 *   public key on it.
 * - The host dialog edits a host, including its saved password.
 * - The keys dialog lists the public keys in `~/.ssh`, copies one to the clipboard, or generates
 *   a new key.
 */
public final class SshHostsDialog {

    private static final String LOG_TAG = "SshHostsDialog";

    private SshHostsDialog() {}

    /** An item of the hosts list: a registered host, or a host of `~/.ssh/config`. */
    private static final class Item {
        @NonNull final String name;
        @Nullable final SshHost host;

        Item(@NonNull String name, @Nullable SshHost host) {
            this.name = name;
            this.host = host;
        }
    }

    /** Show the hosts list. */
    public static void show(@NonNull TermuxActivity activity) {
        final List<SshHost> hosts = SshHostStore.load();
        final List<Item> items = new ArrayList<>();
        for (SshHost host : hosts) items.add(new Item(host.name, host));
        for (String name : SshHostStore.loadConfigHostNames(hosts)) items.add(new Item(name, null));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle(R.string.title_ssh_hosts)
            .setPositiveButton(R.string.action_ssh_add_host, (d, which) -> showHostDialog(activity, null))
            .setNeutralButton(R.string.action_ssh_keys, (d, which) -> showKeysDialog(activity))
            .setNegativeButton(R.string.action_ssh_close, null);

        if (items.isEmpty()) {
            builder.setMessage(R.string.msg_ssh_no_hosts).show();
            return;
        }

        ArrayAdapter<Item> adapter = new ArrayAdapter<Item>(activity, android.R.layout.simple_list_item_2, android.R.id.text1, items) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                Item item = items.get(position);
                ((TextView) view.findViewById(android.R.id.text1)).setText(item.name);
                String summary;
                if (item.host != null) {
                    summary = item.host.getAddress();
                    if (SshPasswords.has(activity, item.name))
                        summary += " · " + activity.getString(R.string.ssh_password_saved);
                } else {
                    summary = activity.getString(R.string.ssh_host_from_config);
                }
                ((TextView) view.findViewById(android.R.id.text2)).setText(summary);
                return view;
            }
        };

        final AlertDialog dialog = builder
            .setAdapter(adapter, (d, which) -> SshConnector.connect(activity, items.get(which).name))
            .create();
        dialog.show();
        ListView listView = dialog.getListView();
        if (listView != null) {
            listView.setOnItemLongClickListener((parent, view, position, id) -> {
                dialog.dismiss();
                showHostMenu(activity, items.get(position));
                return true;
            });
        }
    }

    /** The menu of a host: connect, edit, install a public key, delete. */
    private static void showHostMenu(@NonNull TermuxActivity activity, @NonNull Item item) {
        List<CharSequence> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(activity.getString(R.string.action_ssh_connect));
        actions.add(() -> SshConnector.connect(activity, item.name));
        if (item.host != null) {
            labels.add(activity.getString(R.string.action_ssh_edit_host));
            actions.add(() -> showHostDialog(activity, item.host));
        }
        labels.add(activity.getString(R.string.action_ssh_copy_id));
        actions.add(() -> chooseKeyToCopy(activity, item.name));
        if (item.host != null) {
            labels.add(activity.getString(R.string.action_ssh_delete_host));
            actions.add(() -> confirmDelete(activity, item.host));
        }

        new AlertDialog.Builder(activity)
            .setTitle(item.name)
            .setItems(labels.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
            .setNegativeButton(android.R.string.cancel, (d, which) -> show(activity))
            .show();
    }

    private static void confirmDelete(@NonNull TermuxActivity activity, @NonNull SshHost host) {
        new AlertDialog.Builder(activity)
            .setMessage(activity.getString(R.string.msg_ssh_confirm_delete_host, host.name))
            .setPositiveButton(android.R.string.ok, (d, which) -> {
                List<SshHost> hosts = SshHostStore.load();
                SshHost stored = SshHostStore.find(hosts, host.name);
                if (stored != null) hosts.remove(stored);
                if (save(activity, hosts)) SshPasswords.delete(activity, host.name);
                show(activity);
            })
            .setNegativeButton(android.R.string.cancel, (d, which) -> show(activity))
            .show();
    }

    /** Edit a host, or add one if {@code host} is {@code null}. */
    private static void showHostDialog(@NonNull TermuxActivity activity, @Nullable SshHost host) {
        final boolean isNew = host == null;
        final SshHost editedHost = isNew ? new SshHost() : host;
        final String oldName = editedHost.name;
        final boolean hasPassword = !isNew && SshPasswords.has(activity, oldName);

        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(activity, 20);
        form.setPadding(padding, dp(activity, 8), padding, 0);

        final EditText nameInput = addField(form, R.string.ssh_field_name, editedHost.name, "my-server", InputType.TYPE_CLASS_TEXT);
        final EditText hostInput = addField(form, R.string.ssh_field_host, editedHost.hostName, "example.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        final EditText portInput = addField(form, R.string.ssh_field_port, String.valueOf(editedHost.port), "22", InputType.TYPE_CLASS_NUMBER);
        final EditText userInput = addField(form, R.string.ssh_field_user, editedHost.user, "", InputType.TYPE_CLASS_TEXT);
        final EditText passwordInput = addField(form, R.string.ssh_field_password, "",
            activity.getString(hasPassword ? R.string.ssh_password_keep_hint : R.string.ssh_password_hint),
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final CheckBox removePasswordCheckBox = new CheckBox(activity);
        if (hasPassword) {
            removePasswordCheckBox.setText(R.string.ssh_remove_saved_password);
            form.addView(removePasswordCheckBox);
        }
        final EditText identityInput = addField(form, R.string.ssh_field_identity_file, editedHost.identityFile, "~/.ssh/id_ed25519", InputType.TYPE_CLASS_TEXT);
        final EditText startupCommandInput = addField(form, R.string.ssh_field_startup_command, editedHost.startupCommand, "tmux attach || tmux", InputType.TYPE_CLASS_TEXT);
        final EditText extraOptionsInput = addField(form, R.string.ssh_field_extra_options, editedHost.extraOptions,
            "ProxyJump bastion\nLocalForward 8080 localhost:80", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        extraOptionsInput.setSingleLine(false);
        extraOptionsInput.setMinLines(2);

        ScrollView scrollView = new ScrollView(activity);
        scrollView.addView(form);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(isNew ? R.string.title_ssh_add_host : R.string.title_ssh_edit_host)
            .setView(scrollView)
            .setPositiveButton(R.string.action_ssh_save, null)
            .setNegativeButton(android.R.string.cancel, (d, which) -> show(activity))
            .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();

        // Validate before closing the dialog, so that the input is not lost on an error.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = nameInput.getText().toString().trim();
            String hostName = hostInput.getText().toString().trim();
            int port;
            try {
                port = Integer.parseInt(portInput.getText().toString().trim());
            } catch (NumberFormatException e) {
                port = -1;
            }

            List<SshHost> hosts = SshHostStore.load();
            if (!SshHost.isValidName(name)) {
                nameInput.setError(activity.getString(R.string.ssh_error_name));
                return;
            }
            if (!name.equals(oldName) && SshHostStore.find(hosts, name) != null) {
                nameInput.setError(activity.getString(R.string.ssh_error_name_exists));
                return;
            }
            if (hostName.isEmpty() || hostName.contains(" ")) {
                hostInput.setError(activity.getString(R.string.ssh_error_host));
                return;
            }
            if (port < 1 || port > 65535) {
                portInput.setError(activity.getString(R.string.ssh_error_port));
                return;
            }

            SshHost stored = isNew ? null : SshHostStore.find(hosts, oldName);
            SshHost savedHost = stored != null ? stored : new SshHost();
            savedHost.name = name;
            savedHost.hostName = hostName;
            savedHost.port = port;
            savedHost.user = userInput.getText().toString().trim();
            savedHost.identityFile = identityInput.getText().toString().trim();
            savedHost.startupCommand = startupCommandInput.getText().toString().trim();
            savedHost.extraOptions = extraOptionsInput.getText().toString().trim();
            if (stored == null) hosts.add(savedHost);
            if (!save(activity, hosts)) return;

            // The password is kept in the app, not in the files written above.
            if (hasPassword) SshPasswords.rename(activity, oldName, name);
            if (removePasswordCheckBox.isChecked()) SshPasswords.delete(activity, name);
            int passwordLength = passwordInput.length();
            if (passwordLength > 0) {
                char[] password = new char[passwordLength];
                passwordInput.getText().getChars(0, passwordLength, password, 0);
                passwordInput.getText().clear();
                if (!SshPasswords.save(activity, name, password))
                    Toast.makeText(activity, R.string.msg_ssh_password_not_saved, Toast.LENGTH_LONG).show();
                Arrays.fill(password, '\0');
            }

            dialog.dismiss();
            show(activity);
        });
    }

    private static boolean save(@NonNull TermuxActivity activity, @NonNull List<SshHost> hosts) {
        try {
            SshHostStore.save(hosts);
            return true;
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save the SSH hosts", e);
            Toast.makeText(activity, activity.getString(R.string.msg_ssh_save_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    @NonNull
    private static EditText addField(@NonNull LinearLayout form, int labelId, @Nullable String value, @Nullable String hint, int inputType) {
        Context context = form.getContext();
        TextView label = new TextView(context);
        label.setText(labelId);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setPadding(0, dp(context, 8), 0, 0);
        form.addView(label);

        EditText input = new EditText(context);
        input.setInputType(inputType);
        input.setSingleLine((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        if (!TextUtils.isEmpty(value)) input.setText(value);
        if (!TextUtils.isEmpty(hint)) input.setHint(hint);
        form.addView(input);
        return input;
    }

    /** The public keys in `~/.ssh`. */
    @NonNull
    private static List<File> getPublicKeys() {
        List<File> keys = new ArrayList<>();
        File[] files = SshHostStore.SSH_DIR.listFiles((dir, name) -> name.endsWith(".pub"));
        if (files != null) {
            Arrays.sort(files);
            keys.addAll(Arrays.asList(files));
        }
        return keys;
    }

    /** Choose the public key to install on a host with `ssh-copy-id`. */
    private static void chooseKeyToCopy(@NonNull TermuxActivity activity, @NonNull String hostName) {
        List<File> keys = getPublicKeys();
        if (keys.isEmpty()) {
            new AlertDialog.Builder(activity)
                .setMessage(R.string.msg_ssh_no_keys)
                .setPositiveButton(R.string.action_ssh_generate_key, (d, which) -> showGenerateKeyDialog(activity))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return;
        }
        if (keys.size() == 1) {
            SshConnector.copyId(activity, hostName, keys.get(0));
            return;
        }
        String[] names = new String[keys.size()];
        for (int i = 0; i < keys.size(); i++) names[i] = keys.get(i).getName();
        new AlertDialog.Builder(activity)
            .setTitle(R.string.title_ssh_choose_key)
            .setItems(names, (d, which) -> SshConnector.copyId(activity, hostName, keys.get(which)))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** The keys dialog: tap a public key to copy it, or generate a new key. */
    private static void showKeysDialog(@NonNull TermuxActivity activity) {
        final List<File> keys = getPublicKeys();
        String[] names = new String[keys.size()];
        for (int i = 0; i < keys.size(); i++) names[i] = keys.get(i).getName();

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle(R.string.title_ssh_keys)
            .setPositiveButton(R.string.action_ssh_generate_key, (d, which) -> showGenerateKeyDialog(activity))
            .setNegativeButton(R.string.action_ssh_close, (d, which) -> show(activity));
        if (keys.isEmpty()) builder.setMessage(R.string.msg_ssh_no_keys);
        else builder.setItems(names, (d, which) -> copyPublicKey(activity, keys.get(which)));
        builder.show();
    }

    private static void copyPublicKey(@NonNull TermuxActivity activity, @NonNull File keyFile) {
        try {
            String key = SshHostStore.readFile(keyFile).trim();
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return;
            clipboard.setPrimaryClip(ClipData.newPlainText(keyFile.getName(), key));
            Toast.makeText(activity, activity.getString(R.string.msg_ssh_key_copied, keyFile.getName()), Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Ask for the key file name and generate an ed25519 key with `ssh-keygen` in a new tab. */
    private static void showGenerateKeyDialog(@NonNull TermuxActivity activity) {
        String defaultName = "id_ed25519";
        for (int i = 2; new File(SshHostStore.SSH_DIR, defaultName).exists(); i++)
            defaultName = "id_ed25519_" + i;

        LinearLayout layout = new LinearLayout(activity);
        int padding = dp(activity, 20);
        layout.setPadding(padding, dp(activity, 8), padding, 0);
        final EditText nameInput = new EditText(activity);
        nameInput.setSingleLine();
        nameInput.setText(defaultName);
        nameInput.selectAll();
        layout.addView(nameInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(activity)
            .setTitle(R.string.title_ssh_generate_key)
            .setMessage(R.string.msg_ssh_generate_key)
            .setView(layout)
            .setPositiveButton(R.string.action_ssh_generate_key, (d, which) -> {
                String name = nameInput.getText().toString().trim();
                if (!SshHost.isValidName(name)) {
                    Toast.makeText(activity, R.string.ssh_error_name, Toast.LENGTH_LONG).show();
                    return;
                }
                generateKey(activity, new File(SshHostStore.SSH_DIR, name));
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private static void generateKey(@NonNull TermuxActivity activity, @NonNull File keyFile) {
        TerminalTabsController tabsController = activity.getTerminalTabsController();
        if (tabsController == null) return;
        try {
            SshHostStore.ensureSshDir();
        } catch (IOException e) {
            Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        String script =
            "if ! command -v ssh-keygen >/dev/null 2>&1; then\n" +
            "  echo \"ssh-keygen is not installed. Install it with: pkg install openssh\"\n" +
            "  exit 1\n" +
            "fi\n" +
            "ssh-keygen -t ed25519 -f \"$1\" -C \"termux@$(getprop ro.product.model 2>/dev/null | tr ' ' '_')\"\n" +
            "status=$?\n" +
            "[ $status -eq 0 ] && echo && echo \"Public key: $1.pub\" && cat \"$1.pub\"\n" +
            "echo \"Press Enter to close.\"\n" +
            "read -r _\n" +
            "exit $status\n";
        tabsController.startScriptSession("ssh-keygen", script, keyFile.getAbsolutePath());
    }

    private static int dp(@NonNull Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }

}
