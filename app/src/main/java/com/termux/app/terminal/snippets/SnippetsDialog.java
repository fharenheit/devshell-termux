package com.termux.app.terminal.snippets;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
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
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminal.ssh.SshConnector;
import com.termux.app.terminal.ssh.SshHost;
import com.termux.app.terminal.ssh.SshHostStore;
import com.termux.shared.logger.Logger;
import com.termux.terminal.TerminalSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The snippets of the current tab: tapping one types it into the tab. Snippets of an SSH host are
 * shown first on its tabs, and only there. Opened from the tab bar settings menu, Ctrl+Shift+S or
 * the `SNIPPETS` extra key.
 */
public final class SnippetsDialog {

    private static final String LOG_TAG = "SnippetsDialog";

    private SnippetsDialog() {}

    public static void show(@NonNull TermuxActivity activity) {
        TerminalSession session = activity.getCurrentSession();
        String hostName = SshConnector.getHostName(session);
        List<Snippet> all;
        if (SnippetStore.SNIPPETS_FILE.exists()) {
            all = SnippetStore.load();
        } else {
            // Start with the default snippets, saved so that deleting one keeps it deleted.
            all = SnippetDefaults.create(activity);
            save(activity, all);
        }
        List<Snippet> items = SnippetStore.getFor(all, hostName);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle(hostName == null ? activity.getString(R.string.title_snippets)
                : activity.getString(R.string.title_snippets_for_host, hostName))
            .setNeutralButton(R.string.action_snippet_add, (d, which) -> showEditDialog(activity, all, null))
            .setPositiveButton(R.string.action_snippet_defaults, (d, which) -> showDefaultsDialog(activity, all))
            .setNegativeButton(R.string.action_snippets_close, null);
        if (items.isEmpty()) {
            builder.setMessage(R.string.msg_snippets_empty).show();
            return;
        }

        ArrayAdapter<Snippet> adapter = new ArrayAdapter<Snippet>(activity, android.R.layout.simple_list_item_2, android.R.id.text1, items) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                Snippet snippet = items.get(position);
                String title = snippet.getTitle();
                if (!snippet.host.isEmpty()) title += "  · " + snippet.host;
                ((TextView) view.findViewById(android.R.id.text1)).setText(title);
                TextView command = view.findViewById(android.R.id.text2);
                command.setText(snippet.command.trim().replace("\n", " ⏎ ") + (snippet.run ? " ⏎" : ""));
                command.setTypeface(Typeface.MONOSPACE);
                command.setSingleLine();
                command.setEllipsize(TextUtils.TruncateAt.END);
                return view;
            }
        };

        AlertDialog dialog = builder
            .setAdapter(adapter, (d, which) -> type(activity, items.get(which)))
            .create();
        dialog.show();
        ListView listView = dialog.getListView();
        if (listView != null) {
            listView.setOnItemLongClickListener((parent, view, position, id) -> {
                dialog.dismiss();
                showSnippetMenu(activity, all, items, items.get(position));
                return true;
            });
        }
    }

    /** Choose groups of default snippets to add, skipping the commands that are already saved. */
    private static void showDefaultsDialog(@NonNull TermuxActivity activity, @NonNull List<Snippet> all) {
        List<SnippetDefaults.Group> groups = SnippetDefaults.GROUPS;
        CharSequence[] names = new CharSequence[groups.size()];
        boolean[] checked = new boolean[groups.size()];
        for (int i = 0; i < groups.size(); i++) {
            SnippetDefaults.Group group = groups.get(i);
            names[i] = activity.getString(group.nameId) + " (" + group.items.size() + ")";
            checked[i] = true;
        }
        new AlertDialog.Builder(activity)
            .setTitle(R.string.title_snippet_defaults)
            .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
            .setPositiveButton(R.string.action_snippet_add, (d, which) -> {
                List<SnippetDefaults.Group> chosen = new ArrayList<>();
                for (int i = 0; i < groups.size(); i++) if (checked[i]) chosen.add(groups.get(i));
                int added = SnippetDefaults.addMissing(all, SnippetDefaults.create(activity, chosen));
                if (added > 0 && !save(activity, all)) return;
                Toast.makeText(activity, activity.getResources().getQuantityString(R.plurals.msg_snippet_defaults_added, added, added),
                    Toast.LENGTH_SHORT).show();
                show(activity);
            })
            .setNegativeButton(android.R.string.cancel, (d, which) -> show(activity))
            .show();
    }

    /** Type a snippet into the current tab. */
    private static void type(@NonNull TermuxActivity activity, @NonNull Snippet snippet) {
        TerminalSession session = activity.getCurrentSession();
        if (session == null || !session.isRunning()) {
            Toast.makeText(activity, R.string.msg_snippet_no_session, Toast.LENGTH_SHORT).show();
            return;
        }
        session.write(snippet.getInput());
    }

    private static void showSnippetMenu(@NonNull TermuxActivity activity, @NonNull List<Snippet> all, @NonNull List<Snippet> items,
                                        @NonNull Snippet snippet) {
        int index = items.indexOf(snippet);
        List<CharSequence> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(activity.getString(R.string.action_snippet_type));
        actions.add(() -> type(activity, snippet));
        labels.add(activity.getString(R.string.action_snippet_edit));
        actions.add(() -> showEditDialog(activity, all, snippet));
        if (index > 0) {
            labels.add(activity.getString(R.string.action_snippet_move_up));
            actions.add(() -> move(activity, all, snippet, items.get(index - 1)));
        }
        if (index < items.size() - 1) {
            labels.add(activity.getString(R.string.action_snippet_move_down));
            actions.add(() -> move(activity, all, snippet, items.get(index + 1)));
        }
        labels.add(activity.getString(R.string.action_snippet_delete));
        actions.add(() -> new AlertDialog.Builder(activity)
            .setMessage(activity.getString(R.string.msg_snippet_delete, snippet.getTitle()))
            .setPositiveButton(R.string.action_snippet_delete, (d, which) -> {
                all.remove(snippet);
                if (save(activity, all)) show(activity);
            })
            .setNegativeButton(android.R.string.cancel, (d, which) -> show(activity))
            .show());

        new AlertDialog.Builder(activity)
            .setTitle(snippet.getTitle())
            .setItems(labels.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
            .setNegativeButton(android.R.string.cancel, (d, which) -> show(activity))
            .show();
    }

    /** Swap a snippet with the one shown next to it. */
    private static void move(@NonNull TermuxActivity activity, @NonNull List<Snippet> all, @NonNull Snippet snippet, @NonNull Snippet other) {
        Collections.swap(all, all.indexOf(snippet), all.indexOf(other));
        if (save(activity, all)) show(activity);
    }

    /** Add a snippet, or edit {@code snippet} if not null. */
    private static void showEditDialog(@NonNull TermuxActivity activity, @NonNull List<Snippet> all, @Nullable Snippet snippet) {
        Context context = activity;
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(context, 20);
        form.setPadding(padding, dp(context, 8), padding, 0);

        EditText name = addField(form, R.string.snippet_name, snippet != null ? snippet.name : "", context.getString(R.string.snippet_name_hint));
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        EditText command = addField(form, R.string.snippet_command, snippet != null ? snippet.command : "", "ls -la");
        command.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        command.setSingleLine(false);
        command.setMinLines(3);
        command.setTypeface(Typeface.MONOSPACE);

        CheckBox run = new CheckBox(context);
        run.setText(R.string.snippet_run);
        run.setChecked(snippet == null || snippet.run);
        form.addView(run);

        TextView hostLabel = new TextView(context);
        hostLabel.setText(R.string.snippet_host);
        hostLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hostLabel.setPadding(0, dp(context, 8), 0, 0);
        form.addView(hostLabel);
        List<String> hostNames = new ArrayList<>();
        hostNames.add(context.getString(R.string.snippet_host_all));
        List<SshHost> hosts = SshHostStore.load();
        for (SshHost host : hosts) hostNames.add(host.name);
        for (String configHost : SshHostStore.loadConfigHostNames(hosts))
            if (!hostNames.contains(configHost)) hostNames.add(configHost);
        String currentHost = snippet != null ? snippet.host : "";
        if (!currentHost.isEmpty() && !hostNames.contains(currentHost)) hostNames.add(currentHost);
        Spinner host = new Spinner(context);
        host.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, hostNames));
        host.setSelection(currentHost.isEmpty() ? 0 : hostNames.indexOf(currentHost));
        form.addView(host);

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(context)
            .setTitle(snippet == null ? R.string.action_snippet_add : R.string.action_snippet_edit)
            .setView(scrollView)
            .setPositiveButton(R.string.action_snippet_save, null)
            .setNegativeButton(android.R.string.cancel, (d, which) -> show(activity))
            .create();
        dialog.show();
        // Validate before closing.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (command.getText().toString().trim().isEmpty()) {
                command.setError(context.getString(R.string.msg_snippet_command_empty));
                return;
            }
            Snippet result = snippet != null ? snippet : new Snippet();
            result.name = name.getText().toString().trim();
            result.command = command.getText().toString();
            result.run = run.isChecked();
            int hostIndex = host.getSelectedItemPosition();
            result.host = hostIndex <= 0 ? "" : hostNames.get(hostIndex);
            if (snippet == null) all.add(result);
            if (save(activity, all)) {
                dialog.dismiss();
                show(activity);
            }
        });
    }

    private static boolean save(@NonNull TermuxActivity activity, @NonNull List<Snippet> snippets) {
        try {
            SnippetStore.save(snippets);
            return true;
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save the snippets", e);
            Toast.makeText(activity, activity.getString(R.string.msg_snippets_save_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    @NonNull
    private static EditText addField(@NonNull LinearLayout form, int labelId, @Nullable String value, @Nullable String hint) {
        Context context = form.getContext();
        TextView label = new TextView(context);
        label.setText(labelId);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setPadding(0, dp(context, 8), 0, 0);
        form.addView(label);
        EditText input = new EditText(context);
        input.setSingleLine();
        if (!TextUtils.isEmpty(value)) input.setText(value);
        if (!TextUtils.isEmpty(hint)) input.setHint(hint);
        form.addView(input);
        return input;
    }

    private static int dp(@NonNull Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }

}
