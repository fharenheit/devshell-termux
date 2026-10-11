package com.termux.app.terminal.tabs;

import android.view.Menu;
import android.view.SubMenu;
import android.view.View;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.app.terminal.ssh.SshConnector;
import com.termux.app.terminal.ssh.SshHost;
import com.termux.app.terminal.ssh.SshHostStore;
import com.termux.app.terminal.ssh.SshHostsDialog;
import com.termux.shared.shell.TermuxSession;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The menu of the new tab button: a local shell, an ssh connection to a host registered in the
 * app, or a web tab. The SSH item is shown only if hosts are registered.
 */
final class NewTabMenu {

    /** Opens the chosen kind of tab. */
    interface Listener {
        void onLocalShell();

        void onSsh(@NonNull String hostName);

        void onWeb();
    }

    private static final int ITEM_LOCAL = 1;
    private static final int ITEM_WEB = 2;
    private static final int ITEM_MANAGE_SSH = 3;
    private static final int ITEM_HOST = 100;

    private NewTabMenu() {}

    static void show(@NonNull TermuxActivity activity, @NonNull View anchor, @NonNull Listener listener) {
        List<SshHost> hosts = SshHostStore.load();
        Set<String> connected = getConnectedHostNames(activity.getTermuxService());

        PopupMenu popup = new PopupMenu(activity, anchor);
        Menu menu = popup.getMenu();
        menu.add(Menu.NONE, ITEM_LOCAL, 0, activity.getString(R.string.action_new_tab_local_shell));
        if (!hosts.isEmpty()) {
            SubMenu ssh = menu.addSubMenu(Menu.NONE, Menu.NONE, 1, activity.getString(R.string.action_new_tab_ssh));
            ssh.setHeaderTitle(R.string.action_new_tab_ssh);
            for (int i = 0; i < hosts.size(); i++) {
                SshHost host = hosts.get(i);
                ssh.add(Menu.NONE, ITEM_HOST + i, i, getHostLabel(host, connected.contains(host.name)));
            }
            ssh.add(Menu.NONE, ITEM_MANAGE_SSH, hosts.size(), activity.getString(R.string.action_new_tab_manage_ssh));
        }
        menu.add(Menu.NONE, ITEM_WEB, 2, activity.getString(R.string.action_new_tab_web));
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == ITEM_LOCAL) listener.onLocalShell();
            else if (id == ITEM_WEB) listener.onWeb();
            else if (id == ITEM_MANAGE_SSH) SshHostsDialog.show(activity);
            else if (id >= ITEM_HOST && id - ITEM_HOST < hosts.size()) listener.onSsh(hosts.get(id - ITEM_HOST).name);
            else return false;
            return true;
        });
        popup.show();
    }

    /** Like `dev-server · alice@10.0.0.5 · mosh ●`, with ● if a tab is connected to it. */
    @NonNull
    static String getHostLabel(@NonNull SshHost host, boolean connected) {
        StringBuilder label = new StringBuilder(host.name);
        String address = host.getAddress();
        if (!address.equals(host.name)) label.append(" · ").append(address);
        if (host.useMosh) label.append(" · mosh");
        if (!host.jumpHost.isEmpty()) label.append(" · ↪ ").append(host.jumpHost);
        if (connected) label.append("  ●");
        return label.toString();
    }

    @NonNull
    private static Set<String> getConnectedHostNames(@Nullable TermuxService service) {
        Set<String> names = new HashSet<>();
        if (service == null) return names;
        for (TermuxSession session : new ArrayList<>(service.getTermuxSessions())) {
            String hostName = SshConnector.getHostName(session.getTerminalSession());
            if (hostName != null && session.getTerminalSession().isRunning()) names.add(hostName);
        }
        return names;
    }

}
