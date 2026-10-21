package com.termux.app.terminal.specialkeys;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.terminal.io.extrakeys.ExtraKeysView;
import com.termux.view.TerminalView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A panel of special keys in tabs above the extra keys, opened by the `SPECIAL_KEYS` extra key.
 * Tapping a key sends it to the terminal and keeps the panel open. A long press shows what the key
 * sends, and adds it to or removes it from the favorites, the last tab.
 */
public class SpecialKeysPanel {

    /** The extra key that opens the panel. */
    public static final String EXTRA_KEY = "SPECIAL_KEYS";

    private static final int KEY_HEIGHT_DP = 56;
    private static final int KEY_GAP_DP = 6;
    /** The most rows shown before scrolling, with a part of the next to show that it scrolls. */
    private static final float MAX_VISIBLE_ROWS = 3.4f;

    private static final int PANEL_COLOR = 0xFF161616;
    private static final int KEY_COLOR = 0xFF262626;
    private static final int KEY_BORDER_COLOR = 0xFF4A4A4A;
    private static final int ACCENT_COLOR = 0xFF4FC3F7;
    private static final int TAB_COLOR = 0xFF2A2A2A;
    private static final int CAPTION_COLOR = 0xFFA0A0A0;

    private final TermuxActivity mActivity;
    private LinearLayout mPanel;
    private LinearLayout mTabBar;
    private ScrollView mBody;
    private GridLayout mGrid;
    private final List<Button> mTabButtons = new ArrayList<>();
    private int mTab;
    private SpecialKeysSender mSender;

    public SpecialKeysPanel(@NonNull TermuxActivity activity) {
        mActivity = activity;
    }

    public boolean isShown() {
        return mPanel != null && mPanel.getVisibility() == View.VISIBLE;
    }

    public void toggle() {
        if (isShown()) hide();
        else show();
    }

    /** Show the panel at the frequent keys. */
    public void show() {
        if (mPanel == null) create();
        mPanel.setVisibility(View.VISIBLE);
        showTab(0);
        setExtraKeyHighlighted(true);
    }

    public void hide() {
        if (mPanel == null) return;
        mPanel.setVisibility(View.GONE);
        setExtraKeyHighlighted(false);
    }

    private void setExtraKeyHighlighted(boolean highlighted) {
        ExtraKeysView extraKeysView = mActivity.getExtraKeysView();
        if (extraKeysView != null) extraKeysView.setKeyHighlighted(EXTRA_KEY, highlighted);
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, mActivity.getResources().getDisplayMetrics()));
    }

    private void create() {
        Context context = mActivity;
        mPanel = new LinearLayout(context);
        mPanel.setOrientation(LinearLayout.VERTICAL);
        mPanel.setBackgroundColor(PANEL_COLOR);
        mPanel.setPadding(dp(6), dp(6), dp(6), dp(6));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        HorizontalScrollView tabScroll = new HorizontalScrollView(context);
        tabScroll.setHorizontalScrollBarEnabled(false);
        mTabBar = new LinearLayout(context);
        tabScroll.addView(mTabBar);
        header.addView(tabScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button close = new Button(context, null, android.R.attr.borderlessButtonStyle);
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        close.setMinWidth(dp(44));
        close.setMinimumWidth(dp(44));
        close.setFocusable(false);
        close.setContentDescription(context.getString(R.string.action_special_keys_close));
        close.setOnClickListener(v -> hide());
        header.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        mPanel.addView(header);

        int tabCount = SpecialKeys.TABS.size() + 1;
        for (int i = 0; i < tabCount; i++) {
            Button tab = new Button(context, null, android.R.attr.borderlessButtonStyle);
            String name = i < SpecialKeys.TABS.size() ? getTabName(SpecialKeys.TABS.get(i)) : "★";
            tab.setText(name);
            tab.setAllCaps(false);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tab.setMinWidth(dp(40));
            tab.setMinimumWidth(dp(40));
            tab.setMinHeight(0);
            tab.setMinimumHeight(0);
            tab.setPadding(dp(12), dp(6), dp(12), dp(6));
            tab.setFocusable(false);
            int index = i;
            tab.setOnClickListener(v -> showTab(index));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
            params.setMargins(0, 0, dp(6), 0);
            mTabBar.addView(tab, params);
            mTabButtons.add(tab);
        }

        mBody = new ScrollView(context);
        mBody.setVerticalScrollBarEnabled(true);
        mGrid = new GridLayout(context);
        mBody.addView(mGrid, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyParams.setMargins(0, dp(6), 0, 0);
        mPanel.addView(mBody, bodyParams);

        // Below the terminal, so that the terminal gets smaller instead of being covered.
        TerminalView terminalView = mActivity.getTerminalView();
        ViewGroup parent = (ViewGroup) terminalView.getParent();
        parent.addView(mPanel, parent.indexOfChild(terminalView) + 1,
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    @NonNull
    private String getTabName(@NonNull SpecialKeys.Tab tab) {
        return tab.nameId != 0 ? mActivity.getString(tab.nameId) : String.valueOf(tab.name);
    }

    @NonNull
    private List<SpecialKeys.Key> getKeys(int tab) {
        if (tab < SpecialKeys.TABS.size()) return SpecialKeys.TABS.get(tab).keys;
        List<SpecialKeys.Key> favorites = new ArrayList<>();
        for (String macro : SpecialKeyFavorites.load()) {
            SpecialKeys.Key key = SpecialKeys.find(macro);
            if (key != null) favorites.add(key);
        }
        return favorites;
    }

    /** The number of keys in a row, from the width of the screen. */
    private int getColumnCount() {
        float widthDp = mActivity.getResources().getDisplayMetrics().widthPixels / mActivity.getResources().getDisplayMetrics().density;
        return Math.max(4, Math.min(8, (int) (widthDp / 110)));
    }

    private void showTab(int tab) {
        mTab = tab;
        for (int i = 0; i < mTabButtons.size(); i++) {
            Button button = mTabButtons.get(i);
            boolean selected = i == tab;
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(8));
            background.setColor(selected ? ACCENT_COLOR : TAB_COLOR);
            button.setBackground(background);
            button.setTextColor(selected ? Color.BLACK : Color.WHITE);
        }

        mGrid.removeAllViews();
        List<SpecialKeys.Key> keys = getKeys(tab);
        int columns = getColumnCount();
        mGrid.setColumnCount(columns);
        if (keys.isEmpty()) {
            TextView empty = new TextView(mActivity);
            empty.setText(R.string.special_keys_favorites_empty);
            empty.setTextColor(CAPTION_COLOR);
            empty.setPadding(dp(8), dp(16), dp(8), dp(16));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(GridLayout.spec(0), GridLayout.spec(0, columns));
            mGrid.addView(empty, params);
        }
        for (int i = 0; i < keys.size(); i++) {
            SpecialKeys.Key key = keys.get(i);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                // Fill the row instead of aligning the text baselines, which moves keys whose caption takes more lines.
                GridLayout.spec(i / columns, GridLayout.FILL), GridLayout.spec(i % columns, GridLayout.FILL, 1f));
            params.width = 0;
            params.height = dp(KEY_HEIGHT_DP);
            params.setMargins(dp(KEY_GAP_DP / 2f), dp(KEY_GAP_DP / 2f), dp(KEY_GAP_DP / 2f), dp(KEY_GAP_DP / 2f));
            mGrid.addView(createKey(key), params);
        }

        int rows = Math.max(1, (keys.size() + columns - 1) / columns);
        float visibleRows = Math.min(rows, MAX_VISIBLE_ROWS);
        ViewGroup.LayoutParams bodyParams = mBody.getLayoutParams();
        bodyParams.height = keys.isEmpty() ? ViewGroup.LayoutParams.WRAP_CONTENT : Math.round(visibleRows * dp(KEY_HEIGHT_DP + KEY_GAP_DP));
        mBody.setLayoutParams(bodyParams);
        mBody.scrollTo(0, 0);
    }

    @Nullable
    private String getCaption(@NonNull SpecialKeys.Key key) {
        if (key.captionId == 0) return key.caption;
        return key.captionArg != 0 ? mActivity.getString(key.captionId, key.captionArg) : mActivity.getString(key.captionId);
    }

    @NonNull
    private Button createKey(@NonNull SpecialKeys.Key key) {
        Button button = new Button(mActivity, null, android.R.attr.buttonBarButtonStyle);
        button.setAllCaps(false);
        button.setFocusable(false);
        button.setTextColor(Color.WHITE);
        button.setPadding(0, 0, 0, 0);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        String caption = getCaption(key);
        if (caption == null || caption.equals(key.label)) {
            button.setText(key.label);
        } else {
            SpannableString text = new SpannableString(key.label + "\n" + caption);
            int start = key.label.length() + 1;
            text.setSpan(new RelativeSizeSpan(0.68f), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ForegroundColorSpan(CAPTION_COLOR), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            button.setText(text);
            button.setLineSpacing(0, 0.9f);
        }
        button.setContentDescription(caption != null ? key.label + ", " + caption : key.label);
        GradientDrawable keycap = new GradientDrawable();
        keycap.setColor(KEY_COLOR);
        keycap.setCornerRadius(dp(8));
        keycap.setStroke(Math.max(1, dp(1)), KEY_BORDER_COLOR);
        button.setBackground(new InsetDrawable(keycap, 0));
        button.setOnClickListener(v -> send(key));
        button.setOnLongClickListener(v -> {
            showKeyDialog(key);
            return true;
        });
        return button;
    }

    /** Send a key, with the Ctrl and Alt of the extra keys if they are on. */
    private void send(@NonNull SpecialKeys.Key key) {
        TerminalView terminalView = mActivity.getTerminalView();
        if (terminalView.mEmulator == null) return;
        if (mSender == null) mSender = new SpecialKeysSender(terminalView);
        TermuxTerminalViewClient viewClient = mActivity.getTermuxTerminalViewClient();
        boolean ctrl = viewClient != null && viewClient.readControlKey();
        boolean alt = viewClient != null && viewClient.readAltKey();
        mSender.send(key.macro, ctrl, alt);
    }

    /** Show what a key sends, to send it or add it to or remove it from the favorites. */
    private void showKeyDialog(@NonNull SpecialKeys.Key key) {
        boolean favorite = SpecialKeyFavorites.load().contains(key.macro);
        String caption = getCaption(key);
        String message = (caption != null ? caption + "\n" : "") + mActivity.getString(R.string.special_key_sends, SpecialKeys.describe(key.macro));
        new AlertDialog.Builder(mActivity)
            .setTitle(key.label)
            .setMessage(message)
            .setPositiveButton(R.string.action_special_key_send, (d, which) -> send(key))
            .setNeutralButton(favorite ? R.string.action_special_key_remove_favorite : R.string.action_special_key_add_favorite, (d, which) -> {
                try {
                    SpecialKeyFavorites.toggle(key.macro);
                } catch (IOException e) {
                    Toast.makeText(mActivity, e.getMessage(), Toast.LENGTH_LONG).show();
                }
                if (isShown()) showTab(mTab);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

}
