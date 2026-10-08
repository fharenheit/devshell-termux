package com.termux.app.terminal.io;

import android.annotation.SuppressLint;
import android.view.Gravity;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.drawerlayout.widget.DrawerLayout;

import com.termux.app.terminal.TermuxTerminalSessionClient;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.app.terminal.clipboard.TerminalClipboard;
import com.termux.app.terminal.snippets.SnippetsDialog;
import com.termux.app.terminal.specialkeys.SpecialKeysPanel;
import com.termux.shared.terminal.io.TerminalExtraKeys;
import com.termux.view.TerminalView;

public class TermuxTerminalExtraKeys extends TerminalExtraKeys {


    TermuxTerminalViewClient mTermuxTerminalViewClient;
    TermuxTerminalSessionClient mTermuxTerminalSessionClient;

    public TermuxTerminalExtraKeys(@NonNull TerminalView terminalView,
                                   TermuxTerminalViewClient termuxTerminalViewClient,
                                   TermuxTerminalSessionClient termuxTerminalSessionClient) {
        super(terminalView);
        mTermuxTerminalViewClient = termuxTerminalViewClient;
        mTermuxTerminalSessionClient = termuxTerminalSessionClient;
    }

    @SuppressLint("RtlHardcoded")
    @Override
    public void onTerminalExtraKeyButtonClick(View view, String key, boolean ctrlDown, boolean altDown, boolean shiftDown, boolean fnDown) {
        if ("KEYBOARD".equals(key)) {
            if(mTermuxTerminalViewClient != null)
                mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
        } else if ("DRAWER".equals(key)) {
            DrawerLayout drawerLayout = mTermuxTerminalViewClient.getActivity().getDrawer();
            if (drawerLayout.isDrawerOpen(Gravity.LEFT))
                drawerLayout.closeDrawer(Gravity.LEFT);
            else
                drawerLayout.openDrawer(Gravity.LEFT);
        } else if ("FONT_LARGER".equals(key) || "FONT_SMALLER".equals(key)) {
            if (mTermuxTerminalViewClient != null)
                mTermuxTerminalViewClient.changeFontSize("FONT_LARGER".equals(key));
        } else if ("COPY".equals(key) || "COPY_SCREEN".equals(key)) {
            if (mTermuxTerminalViewClient != null) {
                if ("COPY".equals(key)) TerminalClipboard.copySelection(mTermuxTerminalViewClient.getActivity());
                else TerminalClipboard.copyScreen(mTermuxTerminalViewClient.getActivity());
            }
        } else if (SpecialKeysPanel.EXTRA_KEY.equals(key)) {
            if (mTermuxTerminalViewClient != null && mTermuxTerminalViewClient.getActivity().getTerminalTabsController() != null)
                mTermuxTerminalViewClient.getActivity().getTerminalTabsController().getSpecialKeysPanel().toggle();
        } else if ("SNIPPETS".equals(key)) {
            if (mTermuxTerminalViewClient != null)
                SnippetsDialog.show(mTermuxTerminalViewClient.getActivity());
        } else if ("PASTE".equals(key)) {
            if(mTermuxTerminalSessionClient != null)
                mTermuxTerminalSessionClient.onPasteTextFromClipboard(null);
        } else {
            super.onTerminalExtraKeyButtonClick(view, key, ctrlDown, altDown, shiftDown, fnDown);
        }
    }

}
