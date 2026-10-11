package com.termux.app.terminal.web;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.fork.DevShellInfo;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.TermuxSession;
import com.termux.terminal.TerminalSession;
import com.termux.app.terminal.ssh.SshConnector;
import com.termux.app.terminal.ssh.SshForward;
import com.termux.app.terminal.ssh.SshForwards;
import com.termux.app.terminal.ssh.SshHost;
import com.termux.app.terminal.ssh.SshHostStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The web tabs, shown in the tab bar after the terminal tabs. A selected web tab shows an address
 * bar and its page in place of the terminal and the extra keys. A new tab starts with quick links:
 * the local ports forwarded by the connected ssh tabs, the recent addresses and the manual.
 * The addresses and titles of the tabs are kept, and the pages loaded again when the app is opened.
 */
public final class WebTabs {

    /** A web tab. */
    public static final class Tab {
        @NonNull String url;
        @NonNull String title;
        @Nullable WebTabView view;

        Tab(@NonNull String url, @NonNull String title) {
            this.url = url;
            this.title = title;
        }

        /** The title of the page, or its address, or empty for a new tab. */
        @NonNull
        public String getLabel() {
            return !title.isEmpty() ? title : url;
        }
    }

    /** The tab bar, which shows the web tabs and switches between all tabs. */
    public interface Host {
        void onWebTabsChanged();

        /** A web tab is shown in place of the terminal, which is hidden. */
        void onWebTabsShown();

        /** No web tab is shown any more. */
        void onWebTabsHidden();

        void switchTab(boolean forward);

        void openLocalShell();
    }

    static final int MAX_TABS = 10;
    private static final int MAX_RECENT = 10;
    private static final String PREFERENCES = "web_tabs";
    private static final String KEY_TABS = "tabs";
    private static final String KEY_RECENT = "recent";

    private static final String LOG_TAG = "WebTabs";

    private final TermuxActivity mActivity;
    private final Host mHost;
    private final List<Tab> mTabs = new ArrayList<>();
    @Nullable private Tab mSelected;

    private final LinearLayout mContainer;
    private final EditText mAddress;
    private final Button mBackButton;
    private final Button mForwardButton;
    private final ProgressBar mProgress;
    private final FrameLayout mContent;
    private final ListView mQuickLinks;

    public WebTabs(@NonNull TermuxActivity activity, @NonNull Host host) {
        mActivity = activity;
        mHost = host;

        mContainer = new LinearLayout(activity);
        mContainer.setOrientation(LinearLayout.VERTICAL);
        mContainer.setBackgroundColor(0xFF000000);
        mContainer.setVisibility(View.GONE);

        LinearLayout bar = new LinearLayout(activity);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF1E1E1E);
        mBackButton = addBarButton(bar, "◀", R.string.action_web_back, v -> goBack());
        mForwardButton = addBarButton(bar, "▶", R.string.action_web_forward, v -> {
            if (mSelected != null && mSelected.view != null && mSelected.view.canGoForward()) mSelected.view.goForward();
        });
        addBarButton(bar, "⟳", R.string.action_web_reload, v -> reload());
        mAddress = new EditText(activity);
        mAddress.setSingleLine();
        mAddress.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mAddress.setTextColor(0xFFFFFFFF);
        mAddress.setHintTextColor(0xFF808080);
        mAddress.setHint(R.string.web_address_hint);
        mAddress.setSelectAllOnFocus(true);
        mAddress.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        mAddress.setImeOptions(EditorInfo.IME_ACTION_GO);
        mAddress.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                && event.getAction() == KeyEvent.ACTION_DOWN)) {
                load(mAddress.getText().toString());
                return true;
            }
            return false;
        });
        bar.addView(mAddress, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button menuButton = addBarButton(bar, "⋮", R.string.action_web_menu, null);
        menuButton.setOnClickListener(this::showMenu);
        mContainer.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mProgress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        mProgress.setMax(100);
        mProgress.setVisibility(View.INVISIBLE);
        mContainer.addView(mProgress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        mContent = new FrameLayout(activity);
        mQuickLinks = new ListView(activity);
        mQuickLinks.setBackgroundColor(0xFF000000);
        mContent.addView(mQuickLinks);
        mContainer.addView(mContent, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // In place of the terminal, which is hidden while a web tab is shown.
        View terminalView = activity.getTerminalView();
        ViewGroup parent = (ViewGroup) terminalView.getParent();
        parent.addView(mContainer, parent.indexOfChild(terminalView) + 1,
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        restore();
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, mActivity.getResources().getDisplayMetrics()));
    }

    @NonNull
    private Button addBarButton(@NonNull LinearLayout bar, @NonNull String text, int descriptionId, @Nullable View.OnClickListener listener) {
        Button button = new Button(mActivity, null, android.R.attr.borderlessButtonStyle);
        button.setText(text);
        button.setTextColor(0xFFFFFFFF);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setMinWidth(dp(40));
        button.setMinimumWidth(dp(40));
        button.setPadding(0, 0, 0, 0);
        button.setFocusable(false);
        button.setContentDescription(mActivity.getString(descriptionId));
        if (listener != null) button.setOnClickListener(listener);
        bar.addView(button, new LinearLayout.LayoutParams(dp(40), dp(44)));
        return button;
    }

    @NonNull
    public List<Tab> getTabs() {
        return mTabs;
    }

    @Nullable
    public Tab getSelected() {
        return mSelected;
    }

    public boolean isShown() {
        return mSelected != null;
    }

    /** Open a new web tab with a URL, or with the quick links if {@code null}. */
    public void open(@Nullable String url) {
        if (mTabs.size() >= MAX_TABS) {
            Toast.makeText(mActivity, mActivity.getString(R.string.msg_web_max_tabs, MAX_TABS), Toast.LENGTH_LONG).show();
            return;
        }
        Tab tab = new Tab(url != null ? url : "", "");
        mTabs.add(tab);
        save();
        select(tab);
        if (url == null) focusAddress();
    }

    /** Show a web tab in place of the terminal. */
    public void select(@NonNull Tab tab) {
        boolean wasShown = isShown();
        mSelected = tab;
        if (!wasShown) {
            mHost.onWebTabsShown();
            mContainer.setVisibility(View.VISIBLE);
        }
        mContent.removeAllViews();
        if (tab.url.isEmpty()) {
            showQuickLinks();
        } else {
            if (tab.view == null) {
                tab.view = createView(tab);
                tab.view.loadUrl(tab.url);
            }
            mContent.addView(tab.view);
            tab.view.requestFocus();
        }
        updateBar();
        mHost.onWebTabsChanged();
    }

    /** Stop showing a web tab, like when the terminal or another kind of tab is shown. */
    public void hide() {
        if (!isShown()) return;
        mSelected = null;
        mContainer.setVisibility(View.GONE);
        mContent.removeAllViews();
        hideKeyboard();
        mHost.onWebTabsHidden();
        mHost.onWebTabsChanged();
    }

    public void close(@NonNull Tab tab) {
        int index = mTabs.indexOf(tab);
        if (index < 0) return;
        mTabs.remove(index);
        if (tab.view != null) {
            mContent.removeView(tab.view);
            tab.view.destroy();
            tab.view = null;
        }
        save();
        if (tab == mSelected) {
            if (!mTabs.isEmpty()) select(mTabs.get(Math.min(index, mTabs.size() - 1)));
            else hide();
        } else {
            mHost.onWebTabsChanged();
        }
    }

    /** Move a web tab by an offset among the web tabs. */
    public void move(@NonNull Tab tab, int offset) {
        int index = mTabs.indexOf(tab);
        int newIndex = index + offset;
        if (index < 0 || newIndex < 0 || newIndex >= mTabs.size()) return;
        mTabs.remove(index);
        mTabs.add(newIndex, tab);
        save();
        mHost.onWebTabsChanged();
    }

    /** Close the views, when the activity is destroyed. The tabs are kept to be opened again. */
    public void destroy() {
        for (Tab tab : mTabs) {
            if (tab.view != null) {
                tab.view.destroy();
                tab.view = null;
            }
        }
    }

    // Pages

    @NonNull
    private WebTabView createView(@NonNull Tab tab) {
        WebTabView view = new WebTabView(mActivity, new WebTabView.KeyListener() {
            @Override
            public void onSwitchTab(boolean forward) {
                mHost.switchTab(forward);
            }

            @Override
            public void onNewLocalShell() {
                mHost.openLocalShell();
            }

            @Override
            public void onCloseTab() {
                close(tab);
            }

            @Override
            public void onFocusAddress() {
                focusAddress();
            }

            @Override
            public void onLeave() {
                hide();
            }
        });
        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("about:")) return false;
                // Links like mailto: and intent: go to other apps.
                openExternal(url);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                tab.url = url;
                if (tab == mSelected) updateBar();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                tab.url = url;
                String title = view.getTitle();
                if (!TextUtils.isEmpty(title) && !title.equals(url)) tab.title = title;
                addRecent(url);
                save();
                if (tab == mSelected) updateBar();
                mHost.onWebTabsChanged();
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (tab != mSelected) return;
                mProgress.setProgress(newProgress);
                mProgress.setVisibility(newProgress < 100 ? View.VISIBLE : View.INVISIBLE);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (TextUtils.isEmpty(title)) return;
                tab.title = title;
                mHost.onWebTabsChanged();
            }
        });
        view.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
            download(url, userAgent, contentDisposition, mimeType));
        return view;
    }

    /** Load what was typed in the address bar in the selected tab. */
    void load(@NonNull String text) {
        String url = WebAddress.toUrl(text);
        if (url == null || mSelected == null) return;
        Tab tab = mSelected;
        tab.url = url;
        tab.title = "";
        hideKeyboard();
        if (tab.view == null) tab.view = createView(tab);
        mContent.removeAllViews();
        mContent.addView(tab.view);
        tab.view.loadUrl(url);
        tab.view.requestFocus();
        save();
        updateBar();
        mHost.onWebTabsChanged();
    }

    private void goBack() {
        if (mSelected != null && mSelected.view != null && mSelected.view.canGoBack()) mSelected.view.goBack();
    }

    private void reload() {
        if (mSelected != null && mSelected.view != null) mSelected.view.reload();
    }

    private void updateBar() {
        Tab tab = mSelected;
        if (tab == null) return;
        if (!mAddress.hasFocus()) mAddress.setText(tab.url);
        mBackButton.setEnabled(tab.view != null && tab.view.canGoBack());
        mForwardButton.setEnabled(tab.view != null && tab.view.canGoForward());
        mBackButton.setAlpha(mBackButton.isEnabled() ? 1f : 0.4f);
        mForwardButton.setAlpha(mForwardButton.isEnabled() ? 1f : 0.4f);
        if (tab.view == null) mProgress.setVisibility(View.INVISIBLE);
    }

    private void focusAddress() {
        mAddress.requestFocus();
        mAddress.selectAll();
        InputMethodManager imm = (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(mAddress, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(mContainer.getWindowToken(), 0);
        mAddress.clearFocus();
    }

    private void showMenu(@NonNull View anchor) {
        Tab tab = mSelected;
        if (tab == null) return;
        PopupMenu popup = new PopupMenu(mActivity, anchor);
        popup.getMenu().add(0, 1, 0, R.string.action_web_open_external).setEnabled(!tab.url.isEmpty());
        popup.getMenu().add(0, 2, 0, R.string.action_web_copy_address).setEnabled(!tab.url.isEmpty());
        popup.getMenu().add(0, 3, 0, R.string.action_web_quick_links);
        popup.getMenu().add(0, 4, 0, R.string.action_web_close_tab);
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: openExternal(tab.url); return true;
                case 2: copy(tab.url); return true;
                case 3:
                    mContent.removeAllViews();
                    showQuickLinks();
                    focusAddress();
                    return true;
                case 4: close(tab); return true;
                default: return false;
            }
        });
        popup.show();
    }

    /** Show the actions of a web tab, from a long press on it in the tab bar. */
    public void showTabMenu(@NonNull Tab tab, int position) {
        int index = mTabs.indexOf(tab);
        List<CharSequence> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        items.add(mActivity.getString(R.string.action_web_reload));
        actions.add(() -> {
            select(tab);
            reload();
        });
        if (!tab.url.isEmpty()) {
            items.add(mActivity.getString(R.string.action_web_open_external));
            actions.add(() -> openExternal(tab.url));
            items.add(mActivity.getString(R.string.action_web_copy_address));
            actions.add(() -> copy(tab.url));
        }
        if (index > 0) {
            items.add(mActivity.getString(R.string.action_move_terminal_tab_left));
            actions.add(() -> move(tab, -1));
        }
        if (index < mTabs.size() - 1) {
            items.add(mActivity.getString(R.string.action_move_terminal_tab_right));
            actions.add(() -> move(tab, 1));
        }
        items.add(mActivity.getString(R.string.action_web_close_tab));
        actions.add(() -> close(tab));
        new AlertDialog.Builder(mActivity)
            .setTitle(mActivity.getString(R.string.title_terminal_tab_menu, position + 1))
            .setItems(items.toArray(new CharSequence[0]), (dialog, which) -> actions.get(which).run())
            .show();
    }

    private void openExternal(@NonNull String url) {
        try {
            mActivity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(mActivity, mActivity.getString(R.string.msg_web_no_app, url), Toast.LENGTH_LONG).show();
        }
    }

    private void copy(@NonNull String text) {
        ClipboardManager clipboard = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText(null, text));
        Toast.makeText(mActivity, R.string.msg_web_address_copied, Toast.LENGTH_SHORT).show();
    }

    /** Download with the download manager to the Download directory, or open it in another app if that fails. */
    private void download(@NonNull String url, @Nullable String userAgent, @Nullable String contentDisposition, @Nullable String mimeType) {
        String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            if (userAgent != null) request.addRequestHeader("User-Agent", userAgent);
            if (mimeType != null) request.setMimeType(mimeType);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            DownloadManager manager = (DownloadManager) mActivity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("No download manager");
            manager.enqueue(request);
            Toast.makeText(mActivity, mActivity.getString(R.string.msg_web_downloading, fileName), Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Logger.logDebug(LOG_TAG, "Failed to download " + url + ": " + e.getMessage());
            openExternal(url);
        }
    }

    // Quick links

    /** A quick link of a new tab. */
    static final class QuickLink {
        final String label;
        final String url;

        QuickLink(String label, String url) {
            this.label = label;
            this.url = url;
        }

        @NonNull
        @Override
        public String toString() {
            return label.equals(url) ? url : label + "\n" + url;
        }
    }

    private void showQuickLinks() {
        List<QuickLink> links = getQuickLinks();
        mQuickLinks.setAdapter(new ArrayAdapter<QuickLink>(mActivity, android.R.layout.simple_list_item_1, links) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(0xFFFFFFFF);
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                return view;
            }
        });
        mQuickLinks.setOnItemClickListener((parent, view, position, id) -> load(links.get(position).url));
        mContent.addView(mQuickLinks);
    }

    @NonNull
    private List<QuickLink> getQuickLinks() {
        List<QuickLink> links = new ArrayList<>();
        // The local ports forwarded by the connected ssh tabs, like a web server on the remote host.
        TermuxService service = mActivity.getTermuxService();
        List<SshHost> hosts = SshHostStore.load();
        Set<String> urls = new LinkedHashSet<>();
        if (service != null) {
            for (TermuxSession termuxSession : service.getTermuxSessions()) {
                TerminalSession session = termuxSession.getTerminalSession();
                String hostName = SshConnector.getHostName(session);
                if (hostName == null || !session.isRunning()) continue;
                SshHost host = SshHostStore.find(hosts, hostName);
                for (SshForwards.Item item : SshForwards.getForwards(session, host, SshConnector.getControlPath(session))) {
                    SshForward forward = item.forward;
                    if (forward.type != SshForward.LOCAL) continue;
                    String url = forward.getBrowserUrl();
                    if (urls.add(url))
                        links.add(new QuickLink(mActivity.getString(R.string.web_quick_link_forward, hostName, forward.getLabel(hostName)), url));
                }
            }
        }
        for (String url : loadRecent()) {
            if (urls.add(url)) links.add(new QuickLink(url, url));
        }
        String manual = DevShellInfo.getManualUrl(mActivity);
        if (urls.add(manual)) links.add(new QuickLink(mActivity.getString(R.string.web_quick_link_manual), manual));
        return links;
    }

    // Saved tabs and recent addresses

    private SharedPreferences getPreferences() {
        return mActivity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private void save() {
        JSONArray array = new JSONArray();
        try {
            for (Tab tab : mTabs) {
                JSONObject json = new JSONObject();
                json.put("url", tab.url);
                json.put("title", tab.title);
                array.put(json);
            }
        } catch (JSONException e) {
            return;
        }
        getPreferences().edit().putString(KEY_TABS, array.toString()).apply();
    }

    private void restore() {
        try {
            JSONArray array = new JSONArray(getPreferences().getString(KEY_TABS, "[]"));
            for (int i = 0; i < array.length() && mTabs.size() < MAX_TABS; i++) {
                JSONObject json = array.getJSONObject(i);
                mTabs.add(new Tab(json.optString("url", ""), json.optString("title", "")));
            }
        } catch (JSONException e) {
            Logger.logDebug(LOG_TAG, "Failed to read the web tabs: " + e.getMessage());
        }
    }

    @NonNull
    private List<String> loadRecent() {
        List<String> recent = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(getPreferences().getString(KEY_RECENT, "[]"));
            for (int i = 0; i < array.length(); i++) recent.add(array.getString(i));
        } catch (JSONException ignored) {
        }
        return recent;
    }

    private void addRecent(@NonNull String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return;
        List<String> recent = loadRecent();
        recent.remove(url);
        recent.add(0, url);
        while (recent.size() > MAX_RECENT) recent.remove(recent.size() - 1);
        getPreferences().edit().putString(KEY_RECENT, new JSONArray(recent).toString()).apply();
    }

}
