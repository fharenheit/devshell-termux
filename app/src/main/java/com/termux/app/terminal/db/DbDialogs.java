package com.termux.app.terminal.db;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.terminal.backup.DownloadsDirectory;
import com.termux.app.terminal.snippets.SnippetPlaceholders;
import com.termux.app.terminal.ssh.SshHost;
import com.termux.app.terminal.ssh.SshHostStore;
import com.termux.shared.logger.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** The dialogs of the database client. */
public final class DbDialogs {

    private static final String LOG_TAG = "DbDialogs";
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private DbDialogs() {}

    static int dp(@NonNull Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }

    static void copy(@NonNull Context context, @NonNull String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText(null, text));
        Toast.makeText(context, R.string.msg_db_copied, Toast.LENGTH_SHORT).show();
    }

    @NonNull
    private static LinearLayout form(@NonNull Context context) {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(context, 20);
        form.setPadding(padding, dp(context, 8), padding, 0);
        return form;
    }

    @NonNull
    private static TextView addLabel(@NonNull LinearLayout form, int labelId) {
        TextView label = new TextView(form.getContext());
        label.setText(labelId);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setPadding(0, dp(form.getContext(), 10), 0, 0);
        form.addView(label);
        return label;
    }

    @NonNull
    private static EditText addField(@NonNull LinearLayout form, int labelId, @Nullable String value, @Nullable String hint, int inputType) {
        addLabel(form, labelId);
        EditText input = new EditText(form.getContext());
        input.setSingleLine();
        input.setInputType(inputType);
        if (!TextUtils.isEmpty(value)) input.setText(value);
        if (!TextUtils.isEmpty(hint)) input.setHint(hint);
        form.addView(input);
        return input;
    }

    /** A circle of a color tag, or a ring for none. */
    @NonNull
    static GradientDrawable colorDot(@NonNull Context context, int colorIndex, boolean selected) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        int color = DbConnectionConfig.COLORS[colorIndex];
        dot.setColor(color != 0 ? color : 0x00000000);
        dot.setStroke(dp(context, selected ? 3 : 1), selected ? 0xFFFFFFFF : 0xFF888888);
        return dot;
    }

    // Connections

    /** Show the saved connections, to open one in a new tab, or add, edit, copy or delete them. */
    public static void showConnections(@NonNull Activity activity, @NonNull Consumer<DbConnectionConfig> onOpen) {
        List<DbConnectionConfig> configs = DbConnectionStore.load();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle(R.string.title_db_connections)
            .setNeutralButton(R.string.action_db_add_connection, (d, which) -> showEditor(activity, null,
                saved -> showConnections(activity, onOpen)))
            .setNegativeButton(R.string.action_db_close, null);
        if (configs.isEmpty()) {
            builder.setMessage(R.string.msg_db_no_connections).show();
            return;
        }
        ArrayAdapter<DbConnectionConfig> adapter = new ArrayAdapter<DbConnectionConfig>(activity,
            android.R.layout.simple_list_item_2, android.R.id.text1, configs) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                DbConnectionConfig config = configs.get(position);
                TextView title = view.findViewById(android.R.id.text1);
                title.setText(config.getTitle() + (config.readOnly ? "  🔒" : ""));
                int color = DbConnectionConfig.COLORS[config.color];
                title.setCompoundDrawablesRelativeWithIntrinsicBounds(color != 0 ? sized(colorDot(activity, config.color, false), dp(activity, 10)) : null, null, null, null);
                title.setCompoundDrawablePadding(dp(activity, 8));
                TextView address = view.findViewById(android.R.id.text2);
                String text = config.type.label + " · " + config.getAddress();
                if (!config.sshHost.isEmpty()) text += " · SSH " + config.sshHost;
                address.setText(text);
                address.setSingleLine();
                address.setEllipsize(TextUtils.TruncateAt.END);
                return view;
            }
        };
        AlertDialog dialog = builder.setAdapter(adapter, (d, which) -> onOpen.accept(configs.get(which))).create();
        dialog.show();
        ListView list = dialog.getListView();
        if (list != null) list.setOnItemLongClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            showConnectionMenu(activity, configs.get(position), onOpen);
            return true;
        });
    }

    @NonNull
    private static GradientDrawable sized(@NonNull GradientDrawable drawable, int size) {
        drawable.setSize(size, size);
        return drawable;
    }

    private static void showConnectionMenu(@NonNull Activity activity, @NonNull DbConnectionConfig config,
                                           @NonNull Consumer<DbConnectionConfig> onOpen) {
        CharSequence[] items = {activity.getString(R.string.action_db_open), activity.getString(R.string.action_db_edit),
            activity.getString(R.string.action_db_duplicate), activity.getString(R.string.action_db_delete)};
        new AlertDialog.Builder(activity)
            .setTitle(config.getTitle())
            .setItems(items, (d, which) -> {
                switch (which) {
                    case 0:
                        onOpen.accept(config);
                        break;
                    case 1:
                        showEditor(activity, config, saved -> showConnections(activity, onOpen));
                        break;
                    case 2:
                        DbConnectionConfig copy = config.copy();
                        copy.name = activity.getString(R.string.db_copy_name, config.getTitle());
                        showEditor(activity, copy, saved -> showConnections(activity, onOpen));
                        break;
                    default:
                        new AlertDialog.Builder(activity)
                            .setMessage(activity.getString(R.string.msg_db_delete_connection, config.getTitle()))
                            .setPositiveButton(R.string.action_db_delete, (d2, w) -> {
                                List<DbConnectionConfig> configs = DbConnectionStore.load();
                                DbConnectionConfig saved = DbConnectionConfig.find(configs, config.id);
                                if (saved != null) configs.remove(saved);
                                save(activity, configs);
                                DbConnectionStore.deletePassword(activity, config);
                                showConnections(activity, onOpen);
                            })
                            .setNegativeButton(android.R.string.cancel, (d2, w) -> showConnections(activity, onOpen))
                            .show();
                }
            })
            .setNegativeButton(android.R.string.cancel, (d, which) -> showConnections(activity, onOpen))
            .show();
    }

    private static boolean save(@NonNull Activity activity, @NonNull List<DbConnectionConfig> configs) {
        try {
            DbConnectionStore.save(configs);
            return true;
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save the connections", e);
            Toast.makeText(activity, activity.getString(R.string.msg_db_save_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    /**
     * Add a connection, or edit {@code existing} if not null, which may be a copy that is not
     * saved yet. Test connects with the settings in the form without saving them.
     */
    public static void showEditor(@NonNull Activity activity, @Nullable DbConnectionConfig existing, @Nullable Consumer<DbConnectionConfig> onSaved) {
        DbConnectionConfig config = existing != null ? existing : new DbConnectionConfig();
        Context context = activity;
        LinearLayout form = form(context);

        EditText name = addField(form, R.string.db_field_name, config.name, context.getString(R.string.db_field_name_hint),
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);

        addLabel(form, R.string.db_field_type);
        RadioGroup types = new RadioGroup(context);
        types.setOrientation(RadioGroup.HORIZONTAL);
        for (DbType type : DbType.values()) {
            RadioButton button = new RadioButton(context);
            button.setText(type.label);
            button.setId(View.generateViewId());
            button.setTag(type);
            types.addView(button);
            if (type == config.type) button.setChecked(true);
        }
        HorizontalScrollView typesScroll = new HorizontalScrollView(context);
        typesScroll.addView(types);
        form.addView(typesScroll);

        LinearLayout network = new LinearLayout(context);
        network.setOrientation(LinearLayout.VERTICAL);
        EditText host = addField(network, R.string.db_field_host, config.host, "db.example.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText port = addField(network, R.string.db_field_port, config.port > 0 ? String.valueOf(config.port) : "", null, InputType.TYPE_CLASS_NUMBER);
        EditText user = addField(network, R.string.db_field_user, config.user, null, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        boolean hasPassword = existing != null && DbConnectionStore.hasPassword(context, config);
        EditText password = addField(network, R.string.db_field_password, null,
            context.getString(hasPassword ? R.string.db_field_password_saved : R.string.db_field_password_hint),
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        CheckBox savePassword = new CheckBox(context);
        savePassword.setText(R.string.db_field_save_password);
        savePassword.setChecked(existing == null || hasPassword);
        network.addView(savePassword);
        form.addView(network);

        EditText database = addField(form, R.string.db_field_database, config.database, null,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        TextView databaseLabel = (TextView) form.getChildAt(form.getChildCount() - 2);

        LinearLayout networkOptions = new LinearLayout(context);
        networkOptions.setOrientation(LinearLayout.VERTICAL);
        addLabel(networkOptions, R.string.db_field_ssl);
        Spinner ssl = new Spinner(context);
        ssl.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, new String[]{
            context.getString(R.string.db_ssl_off), context.getString(R.string.db_ssl_require), context.getString(R.string.db_ssl_verify)}));
        ssl.setSelection(config.ssl);
        networkOptions.addView(ssl);

        addLabel(networkOptions, R.string.db_field_ssh);
        List<String> sshNames = new ArrayList<>();
        sshNames.add(context.getString(R.string.db_ssh_none));
        for (SshHost sshHost : SshHostStore.load()) sshNames.add(sshHost.name);
        if (!config.sshHost.isEmpty() && !sshNames.contains(config.sshHost)) sshNames.add(config.sshHost);
        Spinner ssh = new Spinner(context);
        ssh.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, sshNames));
        ssh.setSelection(config.sshHost.isEmpty() ? 0 : sshNames.indexOf(config.sshHost));
        networkOptions.addView(ssh);
        TextView sshHelp = new TextView(context);
        sshHelp.setText(R.string.db_ssh_help);
        sshHelp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sshHelp.setAlpha(0.7f);
        networkOptions.addView(sshHelp);

        EditText parameters = addField(networkOptions, R.string.db_field_parameters, config.parameters, "socketTimeout=60",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        parameters.setSingleLine(false);
        parameters.setMinLines(2);
        parameters.setTypeface(Typeface.MONOSPACE);
        form.addView(networkOptions);

        addLabel(form, R.string.db_field_color);
        LinearLayout colors = new LinearLayout(context);
        int[] selectedColor = {config.color};
        View[] dots = new View[DbConnectionConfig.COLORS.length];
        for (int i = 0; i < dots.length; i++) {
            View dot = new View(context);
            int index = i;
            dot.setBackground(colorDot(context, i, i == config.color));
            dot.setContentDescription(context.getString(R.string.db_field_color) + " " + i);
            dot.setOnClickListener(v -> {
                selectedColor[0] = index;
                for (int j = 0; j < dots.length; j++) dots[j].setBackground(colorDot(context, j, j == index));
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(context, 28), dp(context, 28));
            params.setMargins(0, dp(context, 6), dp(context, 10), dp(context, 6));
            colors.addView(dot, params);
            dots[i] = dot;
        }
        form.addView(colors);

        CheckBox readOnly = new CheckBox(context);
        readOnly.setText(R.string.db_field_read_only);
        readOnly.setChecked(config.readOnly);
        form.addView(readOnly);

        Runnable updateType = () -> {
            DbType type = (DbType) form.findViewById(types.getCheckedRadioButtonId()).getTag();
            boolean isNetwork = type.isNetwork();
            network.setVisibility(isNetwork ? View.VISIBLE : View.GONE);
            networkOptions.setVisibility(isNetwork ? View.VISIBLE : View.GONE);
            port.setHint(isNetwork ? String.valueOf(type.defaultPort) : "");
            databaseLabel.setText(isNetwork ? R.string.db_field_database : R.string.db_field_file);
            database.setHint(isNetwork ? "" : "~/data.db");
        };
        types.setOnCheckedChangeListener((group, checkedId) -> updateType.run());
        updateType.run();

        ScrollView scroll = new ScrollView(context);
        scroll.addView(form);

        AlertDialog dialog = new AlertDialog.Builder(context)
            .setTitle(existing == null ? R.string.action_db_add_connection : R.string.action_db_edit)
            .setView(scroll)
            .setPositiveButton(R.string.action_db_save, null)
            .setNeutralButton(R.string.action_db_test, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.show();

        // Read the form into a connection, or show the problem and return null.
        java.util.function.Supplier<DbConnectionConfig> read = () -> {
            DbConnectionConfig result = DbConnectionConfig.fromJsonQuietly(config);
            result.name = name.getText().toString().trim();
            result.type = (DbType) form.findViewById(types.getCheckedRadioButtonId()).getTag();
            result.host = host.getText().toString().trim();
            String portText = port.getText().toString().trim();
            try {
                result.port = portText.isEmpty() ? 0 : Integer.parseInt(portText);
            } catch (NumberFormatException e) {
                result.port = -1;
            }
            result.user = user.getText().toString().trim();
            result.database = database.getText().toString().trim();
            result.ssl = ssl.getSelectedItemPosition();
            result.sshHost = ssh.getSelectedItemPosition() == 0 ? "" : sshNames.get(ssh.getSelectedItemPosition());
            result.parameters = parameters.getText().toString().trim();
            result.color = selectedColor[0];
            result.readOnly = readOnly.isChecked();
            int problem = result.validate();
            if (problem != 0) {
                Toast.makeText(context, problem, Toast.LENGTH_LONG).show();
                return null;
            }
            return result;
        };

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            DbConnectionConfig result = read.get();
            if (result == null) return;
            List<DbConnectionConfig> configs = DbConnectionStore.load();
            DbConnectionConfig old = DbConnectionConfig.find(configs, result.id);
            if (old != null) configs.set(configs.indexOf(old), result);
            else configs.add(result);
            if (!save(activity, configs)) return;
            String typed = password.getText().toString();
            if (!savePassword.isChecked()) DbConnectionStore.deletePassword(context, result);
            else if (!typed.isEmpty() && !DbConnectionStore.savePassword(context, result, typed.toCharArray()))
                Toast.makeText(context, R.string.msg_db_password_not_saved, Toast.LENGTH_LONG).show();
            dialog.dismiss();
            if (onSaved != null) onSaved.accept(result);
        });
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            DbConnectionConfig result = read.get();
            if (result == null) return;
            String typed = password.getText().toString();
            String testPassword = !typed.isEmpty() ? typed : DbConnectionStore.loadPassword(context, result);
            test(activity, result, testPassword);
        });
    }

    /** Connect with a connection and show the server version or the error. */
    private static void test(@NonNull Activity activity, @NonNull DbConnectionConfig config, @Nullable String password) {
        ProgressBar progress = new ProgressBar(activity);
        progress.setPadding(0, dp(activity, 16), 0, dp(activity, 16));
        AlertDialog progressDialog = new AlertDialog.Builder(activity)
            .setMessage(R.string.msg_db_connecting)
            .setView(progress)
            .setCancelable(false)
            .show();
        new Thread(() -> {
            String message;
            try {
                DbConnector.Connection connection = DbConnector.connect(activity, config, password);
                message = activity.getString(R.string.msg_db_test_ok, connection.session.getServerVersion());
                connection.close();
            } catch (Exception e) {
                message = activity.getString(R.string.msg_db_test_failed, DbTabView.describe(activity, e));
            }
            String finalMessage = message;
            HANDLER.post(() -> {
                progressDialog.dismiss();
                if (activity.isFinishing()) return;
                new AlertDialog.Builder(activity).setMessage(finalMessage).setPositiveButton(android.R.string.ok, null).show();
            });
        }, "DbTest").start();
    }

    /** Ask for the password of a connection, and whether to save it. */
    static void askPassword(@NonNull Activity activity, @NonNull DbConnectionConfig config, @NonNull Consumer<String> onPassword,
                            @Nullable Runnable onCancel) {
        LinearLayout form = form(activity);
        EditText password = addField(form, R.string.db_field_password, null, null,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        CheckBox save = new CheckBox(activity);
        save.setText(R.string.db_field_save_password);
        form.addView(save);
        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.title_db_password, config.getTitle()))
            .setView(form)
            .setPositiveButton(R.string.action_db_connect, (d, which) -> {
                String typed = password.getText().toString();
                if (save.isChecked() && !DbConnectionStore.savePassword(activity, config, typed.toCharArray()))
                    Toast.makeText(activity, R.string.msg_db_password_not_saved, Toast.LENGTH_LONG).show();
                onPassword.accept(typed);
            })
            .setNegativeButton(android.R.string.cancel, (d, which) -> {
                if (onCancel != null) onCancel.run();
            })
            .create();
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
        password.requestFocus();
    }

    // Text

    /** Show text, like DDL or a long value, to read, copy, or insert into the editor if {@code insert} is given. */
    static void showText(@NonNull Activity activity, @NonNull String title, @NonNull String text, @Nullable Consumer<String> insert) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTextIsSelectable(true);
        int padding = dp(activity, 16);
        view.setPadding(padding, padding, padding, padding);
        HorizontalScrollView horizontal = new HorizontalScrollView(activity);
        horizontal.addView(view);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(horizontal);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(R.string.action_db_copy, (d, which) -> copy(activity, text))
            .setNegativeButton(R.string.action_db_close, null);
        if (insert != null) builder.setNeutralButton(R.string.action_db_insert_into_editor, (d, which) -> insert.accept(text));
        builder.show();
    }

    /** A value made readable: JSON indented, binary as a hex dump, text as it is. */
    @NonNull
    static String formatValue(@Nullable Object value) {
        if (value == null) return "NULL";
        if (value instanceof byte[]) return hexDump((byte[]) value);
        String text = value.toString();
        String trimmed = text.trim();
        try {
            if (trimmed.startsWith("{")) return new org.json.JSONObject(trimmed).toString(2);
            if (trimmed.startsWith("[")) return new org.json.JSONArray(trimmed).toString(2);
        } catch (org.json.JSONException ignored) {
        }
        return text;
    }

    @NonNull
    static String hexDump(@NonNull byte[] bytes) {
        StringBuilder dump = new StringBuilder();
        for (int offset = 0; offset < bytes.length; offset += 16) {
            dump.append(String.format(Locale.ROOT, "%08x  ", offset));
            StringBuilder ascii = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                if (offset + i < bytes.length) {
                    int b = bytes[offset + i] & 0xff;
                    dump.append(String.format(Locale.ROOT, "%02x ", b));
                    ascii.append(b >= 0x20 && b < 0x7f ? (char) b : '.');
                } else {
                    dump.append("   ");
                }
            }
            dump.append(' ').append(ascii).append('\n');
        }
        return dump.toString();
    }

    // Export

    /**
     * Copy, save to the Download directory, or share rows of a result in a format. The rows are
     * the given ones, or all if {@code null}.
     */
    static void showExport(@NonNull Activity activity, @NonNull QueryResult result, @Nullable List<Integer> rows,
                           @NonNull DbType type, @Nullable String table) {
        String[] formats = {"CSV", "TSV", "JSON", "Markdown", "SQL INSERT"};
        int[] format = {0};
        LinearLayout form = form(activity);
        TextView scope = new TextView(activity);
        scope.setText(activity.getString(R.string.msg_db_export_rows, rows != null ? rows.size() : result.rows.size()));
        form.addView(scope);
        RadioGroup group = new RadioGroup(activity);
        for (int i = 0; i < formats.length; i++) {
            RadioButton button = new RadioButton(activity);
            button.setText(formats[i]);
            button.setId(View.generateViewId());
            int index = i;
            button.setOnClickListener(v -> format[0] = index);
            group.addView(button);
            if (i == 0) button.setChecked(true);
        }
        form.addView(group);
        new AlertDialog.Builder(activity)
            .setTitle(R.string.action_db_export)
            .setView(form)
            .setPositiveButton(R.string.action_db_save_file, (d, which) -> saveFile(activity, format[0], ResultExport.format(format[0], result, rows, null, type, table)))
            .setNeutralButton(R.string.action_db_share, (d, which) -> share(activity, format[0], ResultExport.format(format[0], result, rows, null, type, table)))
            .setNegativeButton(R.string.action_db_copy, (d, which) -> copy(activity, ResultExport.format(format[0], result, rows, null, type, table)))
            .show();
    }

    @NonNull
    static String exportFileName(int format) {
        return "devshell-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + "." + ResultExport.EXTENSIONS[format];
    }

    private static void saveFile(@NonNull Activity activity, int format, @NonNull String text) {
        new Thread(() -> {
            String message;
            try {
                DownloadsDirectory.NewFile file = DownloadsDirectory.createNewFile(activity, exportFileName(format), ResultExport.MIME_TYPES[format]);
                try {
                    file.out.write(text.getBytes(StandardCharsets.UTF_8));
                    file.out.close();
                } catch (IOException e) {
                    file.delete();
                    throw e;
                }
                message = activity.getString(R.string.msg_db_saved, file.displayPath);
            } catch (IOException e) {
                message = activity.getString(R.string.msg_db_save_failed, e.getMessage());
            }
            String finalMessage = message;
            HANDLER.post(() -> Toast.makeText(activity, finalMessage, Toast.LENGTH_LONG).show());
        }, "DbSave").start();
    }

    private static void share(@NonNull Activity activity, int format, @NonNull String text) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(format == ResultExport.JSON ? "application/json" : "text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, text.length() > 500_000 ? text.substring(0, 500_000) : text);
        try {
            activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.action_db_share)));
        } catch (RuntimeException e) {
            Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // History and saved queries

    /** Show the statements run on a connection, newest first, to insert one into the editor. */
    static void showHistory(@NonNull Activity activity, @NonNull String connectionId, @NonNull Consumer<String> insert) {
        List<QueryHistory.Entry> entries = QueryHistory.filter(QueryHistory.load(QueryHistory.HISTORY_FILE), connectionId);
        if (entries.isEmpty()) {
            new AlertDialog.Builder(activity).setMessage(R.string.msg_db_history_empty).setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        java.text.DateFormat time = DateFormat.getTimeFormat(activity);
        java.text.DateFormat date = DateFormat.getDateFormat(activity);
        List<String> details = new ArrayList<>();
        for (QueryHistory.Entry entry : entries) {
            Date when = new Date(entry.time);
            String rows = entry.rows < 0 ? activity.getString(R.string.db_history_failed) : activity.getString(R.string.db_rows, entry.rows);
            details.add(date.format(when) + " " + time.format(when) + " · " + rows + " · " + entry.elapsedMs + " ms");
        }
        showStatements(activity, activity.getString(R.string.action_db_history), entries, details, insert, null);
    }

    /** Show the saved queries of a connection and of all connections, to insert one, filling its values. */
    static void showSaved(@NonNull Activity activity, @NonNull String connectionId, @NonNull Consumer<String> insert) {
        List<QueryHistory.Entry> entries = QueryHistory.filter(QueryHistory.load(QueryHistory.SAVED_FILE), connectionId);
        if (entries.isEmpty()) {
            new AlertDialog.Builder(activity).setMessage(R.string.msg_db_saved_empty).setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        List<String> details = new ArrayList<>();
        for (QueryHistory.Entry entry : entries) details.add(entry.name + (entry.connectionId.isEmpty() ? " · " + activity.getString(R.string.db_saved_all_connections) : ""));
        showStatements(activity, activity.getString(R.string.action_db_saved_queries), entries, details,
            sql -> fillPlaceholders(activity, sql, insert), entry -> {
                List<QueryHistory.Entry> all = QueryHistory.load(QueryHistory.SAVED_FILE);
                for (int i = all.size() - 1; i >= 0; i--)
                    if (all.get(i).sql.equals(entry.sql) && all.get(i).name.equals(entry.name)) all.remove(i);
                QueryHistory.save(QueryHistory.SAVED_FILE, all);
                showSaved(activity, connectionId, insert);
            });
    }

    private static void showStatements(@NonNull Activity activity, @NonNull String title, @NonNull List<QueryHistory.Entry> entries,
                                       @NonNull List<String> details, @NonNull Consumer<String> insert, @Nullable Consumer<QueryHistory.Entry> delete) {
        ArrayAdapter<QueryHistory.Entry> adapter = new ArrayAdapter<QueryHistory.Entry>(activity, android.R.layout.simple_list_item_2,
            android.R.id.text1, entries) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView sql = view.findViewById(android.R.id.text1);
                sql.setText(entries.get(position).sql.trim().replaceAll("\\s+", " "));
                sql.setTypeface(Typeface.MONOSPACE);
                sql.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                sql.setMaxLines(3);
                sql.setEllipsize(TextUtils.TruncateAt.END);
                ((TextView) view.findViewById(android.R.id.text2)).setText(details.get(position));
                return view;
            }
        };
        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(title)
            .setAdapter(adapter, (d, which) -> insert.accept(entries.get(which).sql))
            .setNegativeButton(R.string.action_db_close, null)
            .create();
        dialog.show();
        ListView list = dialog.getListView();
        if (list != null && delete != null) list.setOnItemLongClickListener((parent, view, position, id) -> {
            QueryHistory.Entry entry = entries.get(position);
            new AlertDialog.Builder(activity)
                .setMessage(activity.getString(R.string.msg_db_delete_saved, entry.name))
                .setPositiveButton(R.string.action_db_delete, (d, which) -> {
                    dialog.dismiss();
                    delete.accept(entry);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return true;
        });
    }

    /** Save a statement with a name, for this connection or all. */
    static void saveQuery(@NonNull Activity activity, @NonNull String connectionId, @NonNull String sql) {
        LinearLayout form = form(activity);
        EditText name = addField(form, R.string.db_field_name, null, null, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        CheckBox all = new CheckBox(activity);
        all.setText(R.string.db_saved_all_connections);
        form.addView(all);
        TextView help = new TextView(activity);
        help.setText(R.string.db_saved_placeholder_help);
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        help.setAlpha(0.7f);
        form.addView(help);
        new AlertDialog.Builder(activity)
            .setTitle(R.string.action_db_save_query)
            .setView(form)
            .setPositiveButton(R.string.action_db_save, (d, which) -> {
                String text = name.getText().toString().trim();
                List<QueryHistory.Entry> entries = QueryHistory.load(QueryHistory.SAVED_FILE);
                entries.add(0, new QueryHistory.Entry(all.isChecked() ? "" : connectionId, sql,
                    text.isEmpty() ? sql.trim().split("\n")[0] : text, System.currentTimeMillis(), 0, 0));
                QueryHistory.save(QueryHistory.SAVED_FILE, entries);
                Toast.makeText(activity, R.string.msg_db_query_saved, Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Ask for the values of the `<NAME>` placeholders of a statement, like a snippet, then give it filled. */
    static void fillPlaceholders(@NonNull Activity activity, @NonNull String sql, @NonNull Consumer<String> done) {
        List<SnippetPlaceholders.Placeholder> placeholders = SnippetPlaceholders.find(sql);
        if (placeholders.isEmpty()) {
            done.accept(sql);
            return;
        }
        LinearLayout form = form(activity);
        Map<String, EditText> inputs = new LinkedHashMap<>();
        for (SnippetPlaceholders.Placeholder placeholder : placeholders) {
            TextView label = new TextView(activity);
            label.setText(placeholder.name);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            label.setPadding(0, dp(activity, 10), 0, 0);
            form.addView(label);
            EditText input = new EditText(activity);
            input.setSingleLine();
            input.setText(placeholder.defaultValue);
            input.setTypeface(Typeface.MONOSPACE);
            form.addView(input);
            inputs.put(placeholder.name, input);
        }
        new AlertDialog.Builder(activity)
            .setView(form)
            .setPositiveButton(R.string.action_db_insert_into_editor, (d, which) -> {
                Map<String, String> values = new LinkedHashMap<>();
                for (Map.Entry<String, EditText> entry : inputs.entrySet()) values.put(entry.getKey(), entry.getValue().getText().toString());
                done.accept(SnippetPlaceholders.fill(sql, values));
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** A row as `column: value` lines, for narrow screens. */
    static void showRowCard(@NonNull Activity activity, @NonNull QueryResult result, int row) {
        StringBuilder text = new StringBuilder();
        Object[] values = result.rows.get(row);
        for (int c = 0; c < result.columns.size(); c++) {
            if (c > 0) text.append("\n\n");
            text.append(result.columns.get(c).name).append(" (").append(result.columns.get(c).typeName).append(")\n");
            text.append(formatValue(values[c]));
        }
        showText(activity, activity.getString(R.string.title_db_row, row + 1), text.toString(), null);
    }

    static void showProgressError(@NonNull Activity activity, @NonNull String message) {
        new AlertDialog.Builder(activity).setMessage(message).setPositiveButton(android.R.string.ok, null).show();
    }

}
