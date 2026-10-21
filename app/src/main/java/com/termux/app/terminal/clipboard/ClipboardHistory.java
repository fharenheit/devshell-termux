package com.termux.app.terminal.clipboard;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import java.util.ArrayList;
import java.util.List;

/**
 * The text copied recently, to paste it again. Copies are recorded while the app is in the
 * foreground, and when it gets the focus, the text another app copied in the meantime. The history
 * is only kept in memory, since copied text can be passwords.
 */
public final class ClipboardHistory {

    public static final int MAX_ITEMS = 20;
    /** Longer texts are kept, but only up to this size, to bound the memory used. */
    private static final int MAX_LENGTH = 256 * 1024;

    private static final List<String> sItems = new ArrayList<>();
    private static boolean sListening;

    private ClipboardHistory() {}

    /** Start recording copies, and record the text on the clipboard when a view gets the focus. */
    public static void attach(@NonNull Context context, @NonNull View focusView) {
        ClipboardManager clipboard = (ClipboardManager) context.getApplicationContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        if (!sListening) {
            sListening = true;
            clipboard.addPrimaryClipChangedListener(() -> record(clipboard, context.getApplicationContext()));
        }
        focusView.getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> {
            // Reading the clipboard needs the focus since Android 10.
            if (hasFocus) record(clipboard, context.getApplicationContext());
        });
    }

    private static void record(@NonNull ClipboardManager clipboard, @NonNull Context context) {
        try {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence text = clip.getItemAt(0).coerceToText(context);
            if (text != null) add(text.toString());
        } catch (RuntimeException ignored) {
            // Like a SecurityException when the app may not read the clipboard.
        }
    }

    /** Add a text as the newest item, moving it up if it is already in the history. */
    static synchronized void add(@NonNull String text) {
        if (text.trim().isEmpty()) return;
        if (text.length() > MAX_LENGTH) text = text.substring(0, MAX_LENGTH);
        sItems.remove(text);
        sItems.add(0, text);
        while (sItems.size() > MAX_ITEMS) sItems.remove(sItems.size() - 1);
    }

    @NonNull
    static synchronized List<String> getItems() {
        return new ArrayList<>(sItems);
    }

    static synchronized void remove(@NonNull String text) {
        sItems.remove(text);
    }

    static synchronized void clear() {
        sItems.clear();
    }

    /** A one line preview of a text. */
    @NonNull
    static String preview(@NonNull String text) {
        String line = text.trim().replaceAll("\\s*\\n\\s*", " ⏎ ");
        return line.length() > 120 ? line.substring(0, 120) + "…" : line;
    }

    /** Show the history: tapping an item pastes it into the current tab. */
    public static void show(@NonNull TermuxActivity activity) {
        List<String> items = getItems();
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
            .setTitle(R.string.title_clipboard_history)
            .setNegativeButton(R.string.action_clipboard_history_close, null);
        if (items.isEmpty()) {
            builder.setMessage(R.string.msg_clipboard_history_empty).show();
            return;
        }
        builder.setNeutralButton(R.string.action_clipboard_history_clear, (d, which) -> clear());
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity, android.R.layout.simple_list_item_2, android.R.id.text1, items) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                String text = items.get(position);
                TextView title = view.findViewById(android.R.id.text1);
                title.setText(preview(text));
                title.setSingleLine();
                title.setEllipsize(TextUtils.TruncateAt.END);
                int lines = text.split("\n", -1).length;
                ((TextView) view.findViewById(android.R.id.text2)).setText(
                    activity.getResources().getString(R.string.clipboard_history_item_size, text.length(), lines));
                return view;
            }
        };
        AlertDialog dialog = builder.setAdapter(adapter, (d, which) -> paste(activity, items.get(which))).create();
        dialog.show();
        ListView listView = dialog.getListView();
        if (listView != null) {
            listView.setOnItemLongClickListener((parent, view, position, id) -> {
                dialog.dismiss();
                showItemMenu(activity, items.get(position));
                return true;
            });
        }
    }

    private static void showItemMenu(@NonNull TermuxActivity activity, @NonNull String text) {
        CharSequence[] actions = {activity.getString(R.string.action_clipboard_history_paste),
            activity.getString(R.string.action_clipboard_history_copy), activity.getString(R.string.action_clipboard_history_delete)};
        new AlertDialog.Builder(activity)
            .setTitle(preview(text))
            .setItems(actions, (d, which) -> {
                if (which == 0) {
                    paste(activity, text);
                } else if (which == 1) {
                    ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(null, text));
                    Toast.makeText(activity, R.string.msg_clipboard_history_copied, Toast.LENGTH_SHORT).show();
                } else {
                    remove(text);
                    show(activity);
                }
            })
            .setNegativeButton(android.R.string.cancel, (d, which) -> show(activity))
            .show();
    }

    /** Paste a text into the current tab, like the clipboard is pasted, with bracketed paste if the program uses it. */
    private static void paste(@NonNull TermuxActivity activity, @NonNull String text) {
        TerminalView terminalView = activity.getTerminalView();
        TerminalSession session = activity.getCurrentSession();
        if (session == null || !session.isRunning() || terminalView.mEmulator == null) return;
        terminalView.prepareForExternalInput();
        terminalView.mEmulator.paste(text);
        // Pasting a text moves it up, like copying it again would.
        add(text);
    }

}
