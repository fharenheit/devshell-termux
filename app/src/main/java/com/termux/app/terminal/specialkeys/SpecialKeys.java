package com.termux.app.terminal.specialkeys;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The keys of the special keys panel, in tabs. Each key is sent as a macro of the extra keys,
 * keys separated by spaces where `CTRL`, `ALT` and `SHIFT` apply to the next key, like `CTRL c`
 * for Ctrl+C or `CTRL b %` for the tmux prefix and then `%`.
 */
public final class SpecialKeys {

    /** A key of the panel. */
    public static final class Key {
        /** The macro to send, which also identifies the key. */
        @NonNull public final String macro;
        /** The text on the key, like `^C`. */
        @NonNull public final String label;
        /** The resource of the description below the label, or 0 if {@link #caption} or none. */
        public final int captionId;
        /** A description that is not translated, like `Ctrl+V`, or {@code null}. */
        @Nullable public final String caption;

        Key(@NonNull String macro, @NonNull String label, int captionId, @Nullable String caption) {
            this.macro = macro;
            this.label = label;
            this.captionId = captionId;
            this.caption = caption;
        }
    }

    /** A tab of keys. */
    public static final class Tab {
        /** The resource of the name of the tab, or 0 if {@link #name}. */
        public final int nameId;
        @Nullable public final String name;
        @NonNull public final List<Key> keys;

        Tab(int nameId, @Nullable String name, @NonNull List<Key> keys) {
            this.nameId = nameId;
            this.name = name;
            this.keys = keys;
        }
    }

    private SpecialKeys() {}

    private static Key key(String macro, String label, int captionId) {
        return new Key(macro, label, captionId, null);
    }

    private static Key key(String macro, String label, String caption) {
        return new Key(macro, label, 0, caption);
    }

    private static Key key(String macro, String label) {
        return new Key(macro, label, 0, null);
    }

    private static List<Key> functionKeys(int from, int to) {
        List<Key> keys = new ArrayList<>();
        for (int i = from; i <= to; i++) keys.add(key("F" + i, "F" + i));
        return keys;
    }

    private static List<Key> concat(List<Key>... lists) {
        List<Key> keys = new ArrayList<>();
        for (List<Key> list : lists) keys.addAll(list);
        return keys;
    }

    public static final List<Key> FREQUENT = Collections.unmodifiableList(concat(
        Arrays.asList(
            key("CTRL c", "^C", R.string.special_key_interrupt),
            key("CTRL v", "^V", "Ctrl+V"),
            key("CTRL x", "^X", "Ctrl+X"),
            key("CTRL z", "^Z", R.string.special_key_suspend),
            key("CTRL d", "^D", R.string.special_key_end_of_input),
            key("CTRL a", "^A", R.string.special_key_line_start),
            key("CTRL e", "^E", R.string.special_key_line_end),
            key("CTRL l", "^L", R.string.special_key_clear_screen)),
        functionKeys(1, 12),
        Arrays.asList(
            key("CTRL r", "^R", R.string.special_key_search_history),
            key("CTRL w", "^W", R.string.special_key_delete_word),
            key("CTRL u", "^U", R.string.special_key_delete_before),
            key("DEL", "Del", "Delete"))));

    public static final List<Key> CTRL = Collections.unmodifiableList(Arrays.asList(
        key("CTRL c", "^C", R.string.special_key_interrupt),
        key("CTRL d", "^D", R.string.special_key_end_of_input),
        key("CTRL z", "^Z", R.string.special_key_suspend),
        key("CTRL l", "^L", R.string.special_key_clear_screen),
        key("CTRL r", "^R", R.string.special_key_search_history),
        key("CTRL a", "^A", R.string.special_key_line_start),
        key("CTRL e", "^E", R.string.special_key_line_end),
        key("CTRL u", "^U", R.string.special_key_delete_before),
        key("CTRL k", "^K", R.string.special_key_delete_after),
        key("CTRL w", "^W", R.string.special_key_delete_word),
        key("CTRL \\", "^\\", R.string.special_key_quit),
        key("CTRL s", "^S", R.string.special_key_stop_output),
        key("CTRL q", "^Q", R.string.special_key_resume_output),
        key("CTRL x", "^X", "Ctrl+X"),
        key("CTRL v", "^V", "Ctrl+V"),
        key("CTRL o", "^O", "Ctrl+O"),
        key("CTRL b", "^B", R.string.special_key_tmux_prefix),
        key("CTRL y", "^Y", R.string.special_key_yank),
        key("CTRL t", "^T", "Ctrl+T"),
        key("CTRL g", "^G", "Ctrl+G"),
        key("CTRL _", "^_", R.string.special_key_undo)));

    public static final List<Key> FUNCTION = Collections.unmodifiableList(functionKeys(1, 12));

    public static final List<Key> EDIT = Collections.unmodifiableList(Arrays.asList(
        key("ESC", "Esc", "Escape"),
        key("INS", "Ins", "Insert"),
        key("DEL", "Del", "Delete"),
        key("BKSP", "⌫", "Backspace"),
        key("TAB", "↹", "Tab"),
        key("SHIFT TAB", "⇧↹", "Shift+Tab"),
        key("HOME", "⇤", "Home"),
        key("END", "⇥", "End"),
        key("PGUP", "⇞", "PgUp"),
        key("PGDN", "⇟", "PgDn"),
        key("UP", "↑"),
        key("DOWN", "↓"),
        key("LEFT", "←"),
        key("RIGHT", "→")));

    public static final List<Key> ALT = Collections.unmodifiableList(Arrays.asList(
        key("ALT b", "Alt+B", R.string.special_key_word_back),
        key("ALT f", "Alt+F", R.string.special_key_word_forward),
        key("ALT .", "Alt+.", R.string.special_key_last_argument),
        key("ALT BKSP", "Alt+⌫", R.string.special_key_delete_word),
        key("ALT d", "Alt+D", R.string.special_key_delete_word_after),
        key("ALT ENTER", "Alt+↲", "Alt+Enter"),
        key("ALT u", "Alt+U", R.string.special_key_uppercase_word),
        key("ALT l", "Alt+L", R.string.special_key_lowercase_word)));

    public static final List<Key> SYMBOLS;

    static {
        List<Key> symbols = new ArrayList<>();
        for (String symbol : new String[]{"~", "`", "|", "\\", "{", "}", "[", "]", "<", ">", "^", "&", "$", "#", "_", "=",
            ";", ":", "\"", "'", "*", "%", "!", "?", "@"})
            symbols.add(key(symbol, symbol));
        SYMBOLS = Collections.unmodifiableList(symbols);
    }

    public static final List<Key> TMUX = Collections.unmodifiableList(Arrays.asList(
        key("CTRL b c", "^B c", R.string.special_key_new_window),
        key("CTRL b n", "^B n", R.string.special_key_next_window),
        key("CTRL b p", "^B p", R.string.special_key_previous_window),
        key("CTRL b d", "^B d", R.string.special_key_detach),
        key("CTRL b %", "^B %", R.string.special_key_split_left_right),
        key("CTRL b \"", "^B \"", R.string.special_key_split_top_bottom),
        key("CTRL b o", "^B o", R.string.special_key_next_pane),
        key("CTRL b z", "^B z", R.string.special_key_zoom_pane),
        key("CTRL b [", "^B [", R.string.special_key_scroll_mode),
        key("CTRL b ,", "^B ,", R.string.special_key_rename_window),
        key("CTRL b x", "^B x", R.string.special_key_close_pane)));

    public static final List<Key> VIM = Collections.unmodifiableList(Arrays.asList(
        key("ESC : w ENTER", "Esc :w↲", R.string.special_key_save),
        key("ESC : q ! ENTER", "Esc :q!↲", R.string.special_key_quit_without_saving),
        key("ESC : w q ENTER", "Esc :wq↲", R.string.special_key_save_and_quit),
        key("ESC u", "Esc u", R.string.special_key_undo),
        key("ESC CTRL r", "Esc ^R", R.string.special_key_redo),
        key("ESC /", "Esc /", R.string.special_key_search),
        key("ESC g g", "Esc gg", R.string.special_key_go_to_top),
        key("ESC G", "Esc G", R.string.special_key_go_to_bottom),
        key("ESC d d", "Esc dd", R.string.special_key_delete_line),
        key("ESC y y", "Esc yy", R.string.special_key_copy_line),
        key("ESC p", "Esc p", R.string.special_key_paste)));

    public static final List<Key> NANO = Collections.unmodifiableList(Arrays.asList(
        key("CTRL o", "^O", R.string.special_key_save),
        key("CTRL x", "^X", R.string.special_key_exit),
        key("CTRL w", "^W", R.string.special_key_search),
        key("CTRL \\", "^\\", R.string.special_key_replace),
        key("CTRL k", "^K", R.string.special_key_cut_line),
        key("CTRL u", "^U", R.string.special_key_paste),
        key("CTRL _", "^_", R.string.special_key_go_to_line),
        key("CTRL g", "^G", R.string.special_key_help),
        key("ALT u", "Alt+U", R.string.special_key_undo),
        key("ALT e", "Alt+E", R.string.special_key_redo)));

    /** The tabs before the favorites, which are added by {@link SpecialKeysPanel}. */
    public static final List<Tab> TABS = Collections.unmodifiableList(Arrays.asList(
        new Tab(R.string.special_keys_tab_frequent, null, FREQUENT),
        new Tab(0, "Ctrl", CTRL),
        new Tab(0, "Fn", FUNCTION),
        new Tab(R.string.special_keys_tab_edit, null, EDIT),
        new Tab(0, "Alt", ALT),
        new Tab(R.string.special_keys_tab_symbols, null, SYMBOLS),
        new Tab(0, "tmux", TMUX),
        new Tab(0, "vim", VIM),
        new Tab(0, "nano", NANO)));

    /** Find a key by its macro, like a favorite, or return {@code null}. */
    @Nullable
    public static Key find(@NonNull String macro) {
        for (Tab tab : TABS)
            for (Key key : tab.keys)
                if (key.macro.equals(macro)) return key;
        return null;
    }

    /** A readable form of a macro, like `Ctrl+C` for `CTRL c` or `Ctrl+B, %` for `CTRL b %`. */
    @NonNull
    public static String describe(@NonNull String macro) {
        StringBuilder text = new StringBuilder();
        StringBuilder modifiers = new StringBuilder();
        for (String token : macro.split(" ")) {
            switch (token) {
                case "CTRL": modifiers.append("Ctrl+"); continue;
                case "ALT": modifiers.append("Alt+"); continue;
                case "SHIFT": modifiers.append("Shift+"); continue;
            }
            if (text.length() > 0) text.append(", ");
            String name = token.length() == 1 ? (modifiers.length() > 0 ? token.toUpperCase() : token)
                : token.charAt(0) + token.substring(1).toLowerCase();
            if (token.equals("BKSP")) name = "Backspace";
            if (token.equals("PGUP")) name = "PgUp";
            if (token.equals("PGDN")) name = "PgDn";
            text.append(modifiers).append(name);
            modifiers.setLength(0);
        }
        return text.toString();
    }

}
