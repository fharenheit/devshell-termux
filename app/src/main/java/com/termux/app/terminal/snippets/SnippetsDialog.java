package com.termux.app.terminal.snippets;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            if (SnippetDefaults.migrate(all)) save(activity, all);
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

    /** Type a snippet into the current tab, asking for the values of its placeholders first if it has any. */
    private static void type(@NonNull TermuxActivity activity, @NonNull Snippet snippet) {
        List<SnippetPlaceholders.Placeholder> placeholders = SnippetPlaceholders.find(snippet.command);
        if (placeholders.isEmpty()) write(activity, snippet);
        else showValuesDialog(activity, snippet, placeholders);
    }

    /**
     * Ask for the values of the placeholders of a snippet, showing the command as it will be sent,
     * then type it, or run it.
     */
    private static void showValuesDialog(@NonNull TermuxActivity activity, @NonNull Snippet snippet,
                                         @NonNull List<SnippetPlaceholders.Placeholder> placeholders) {
        Context context = activity;
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(context, 20);
        form.setPadding(padding, dp(context, 8), padding, 0);

        TextView template = new TextView(context);
        template.setText(snippet.command.trim());
        template.setTypeface(Typeface.MONOSPACE);
        template.setAlpha(0.6f);
        template.setTextIsSelectable(true);
        form.addView(template);

        Map<String, EditText> inputs = new LinkedHashMap<>();
        for (SnippetPlaceholders.Placeholder placeholder : placeholders) {
            String last = SnippetPlaceholders.getLastValue(context, placeholder.name);
            EditText input = addField(form, 0, last != null ? last : placeholder.defaultValue, null);
            ((TextView) form.getChildAt(form.getChildCount() - 2)).setText(placeholder.name);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setTypeface(Typeface.MONOSPACE);
            input.setSelectAllOnFocus(true);
            inputs.put(placeholder.name, input);
        }

        TextView previewLabel = new TextView(context);
        previewLabel.setText(R.string.snippet_preview);
        previewLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        previewLabel.setPadding(0, dp(context, 12), 0, 0);
        form.addView(previewLabel);
        TextView preview = new TextView(context);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setTextIsSelectable(true);
        form.addView(preview);

        Runnable update = () -> preview.setText(SnippetPlaceholders.fill(snippet.command, getValues(inputs)).trim());
        update.run();
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                update.run();
            }
        };
        for (EditText input : inputs.values()) input.addTextChangedListener(watcher);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(context)
            .setTitle(snippet.getTitle())
            .setView(scroll)
            .setPositiveButton(R.string.action_snippet_run, (d, which) -> writeFilled(activity, snippet, inputs, true))
            .setNeutralButton(R.string.action_snippet_type_only, (d, which) -> writeFilled(activity, snippet, inputs, false))
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
        EditText first = inputs.values().iterator().next();
        first.requestFocus();
    }

    @NonNull
    private static Map<String, String> getValues(@NonNull Map<String, EditText> inputs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, EditText> entry : inputs.entrySet()) values.put(entry.getKey(), entry.getValue().getText().toString());
        return values;
    }

    private static void writeFilled(@NonNull TermuxActivity activity, @NonNull Snippet snippet,
                                    @NonNull Map<String, EditText> inputs, boolean run) {
        Map<String, String> values = getValues(inputs);
        SnippetPlaceholders.saveValues(activity, values);
        Snippet filled = new Snippet();
        filled.command = SnippetPlaceholders.fill(snippet.command, values);
        filled.run = run;
        write(activity, filled);
    }

    /** Write a snippet to the current tab. */
    private static void write(@NonNull TermuxActivity activity, @NonNull Snippet snippet) {
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

        TextView placeholderHelp = new TextView(context);
        placeholderHelp.setText(R.string.snippet_placeholder_help);
        placeholderHelp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        placeholderHelp.setAlpha(0.7f);
        form.addView(placeholderHelp);

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
        if (labelId != 0) label.setText(labelId);
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
