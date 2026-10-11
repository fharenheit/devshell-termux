package com.termux.app.terminal.web;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.KeyEvent;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.NonNull;

/**
 * The web view of a web tab. It handles the tab keyboard shortcuts, since keys typed in the page
 * do not go through the terminal, and Back goes back in the page history or to the terminal.
 */
@SuppressLint("ViewConstructor")
public class WebTabView extends WebView {

    /** Handles the shortcuts of the web tab. */
    public interface KeyListener {
        /** Go to the next or previous tab. */
        void onSwitchTab(boolean forward);

        void onNewLocalShell();

        void onCloseTab();

        void onFocusAddress();

        /** Back with no page to go back to. */
        void onLeave();
    }

    @NonNull private final KeyListener mKeyListener;

    @SuppressLint("SetJavaScriptEnabled")
    public WebTabView(@NonNull Context context, @NonNull KeyListener keyListener) {
        super(context);
        mKeyListener = keyListener;
        WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportMultipleWindows(false);
        // Pages must not read the files of the app.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && handleKey(event)) return true;
        if (event.getAction() == KeyEvent.ACTION_UP && isShortcut(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private boolean isShortcut(@NonNull KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BACK) return true;
        if (!event.isCtrlPressed() || event.isAltPressed()) return false;
        return keyCode == KeyEvent.KEYCODE_TAB || keyCode == KeyEvent.KEYCODE_L || keyCode == KeyEvent.KEYCODE_R ||
            (event.isShiftPressed() && (keyCode == KeyEvent.KEYCODE_T || keyCode == KeyEvent.KEYCODE_W));
    }

    private boolean handleKey(@NonNull KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean repeat = event.getRepeatCount() > 0;
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (repeat) return true;
            if (canGoBack()) goBack();
            else mKeyListener.onLeave();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_F5) {
            reload();
            return true;
        }
        if (!event.isCtrlPressed() || event.isAltPressed() || event.isMetaPressed()) return false;
        boolean shift = event.isShiftPressed();
        switch (keyCode) {
            case KeyEvent.KEYCODE_TAB:
                mKeyListener.onSwitchTab(!shift);
                return true;
            case KeyEvent.KEYCODE_L:
                if (shift) return false;
                mKeyListener.onFocusAddress();
                return true;
            case KeyEvent.KEYCODE_R:
                if (shift) return false;
                reload();
                return true;
            case KeyEvent.KEYCODE_T:
                if (!shift) return false;
                if (!repeat) mKeyListener.onNewLocalShell();
                return true;
            case KeyEvent.KEYCODE_W:
                if (!shift) return false;
                if (!repeat) mKeyListener.onCloseTab();
                return true;
            default:
                return false;
        }
    }

}
