package com.termux.app.terminal.snippets;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The default snippets, in groups. They are the snippets until the user saves their own, and can
 * be added again from the snippets dialog. The names are in the language of the app when added.
 */
public final class SnippetDefaults {

    /** A default snippet, with the resource of its name. */
    static final class Item {
        final int nameId;
        @NonNull final String command;
        final boolean run;

        Item(int nameId, @NonNull String command, boolean run) {
            this.nameId = nameId;
            this.command = command;
            this.run = run;
        }
    }

    /** A group of default snippets. */
    static final class Group {
        final int nameId;
        @NonNull final List<Item> items;

        Group(int nameId, @NonNull List<Item> items) {
            this.nameId = nameId;
            this.items = items;
        }
    }

    private static Item run(int nameId, String command) {
        return new Item(nameId, command, true);
    }

    /** A command to finish before running it, like with a commit message. */
    private static Item type(int nameId, String command) {
        return new Item(nameId, command, false);
    }

    static final List<Group> GROUPS = Collections.unmodifiableList(Arrays.asList(
        new Group(R.string.snippet_group_git, Arrays.asList(
            run(R.string.snippet_git_status, "git status -sb"),
            run(R.string.snippet_git_log, "git log --oneline --graph --decorate -20"),
            run(R.string.snippet_git_diff, "git diff --stat"),
            run(R.string.snippet_git_branches, "git branch -vv"),
            run(R.string.snippet_git_pull, "git pull --rebase"),
            run(R.string.snippet_git_push, "git push"),
            type(R.string.snippet_git_commit, "git commit -am "),
            run(R.string.snippet_git_stash, "git stash list"),
            // Commands that throw away changes are typed without Enter, to run them on purpose.
            type(R.string.snippet_git_discard, "git reset --hard && git clean -fd"),
            type(R.string.snippet_git_force_pull, "git fetch origin && git reset --hard @{u}"),
            type(R.string.snippet_git_reset_commit, "git reset --hard "))),
        new Group(R.string.snippet_group_system, Arrays.asList(
            run(R.string.snippet_system_disk, "df -h"),
            run(R.string.snippet_system_memory, "free -h"),
            run(R.string.snippet_system_big_folders, "du -sh * 2>/dev/null | sort -h | tail -20"),
            run(R.string.snippet_system_top_cpu, "ps -eo pid,user,%cpu,%mem,comm --sort=-%cpu | head -15"),
            run(R.string.snippet_system_top_memory, "ps -eo pid,user,%cpu,%mem,comm --sort=-%mem | head -15"),
            run(R.string.snippet_system_processes, "top"),
            run(R.string.snippet_system_ports, "ss -tulpn"),
            run(R.string.snippet_system_uptime, "uptime"),
            run(R.string.snippet_system_info, "uname -a"),
            type(R.string.snippet_system_find_name, "find . -iname "),
            run(R.string.snippet_system_my_processes, "ps -u \"$(id -u)\" -o pid,user,args"),
            // Without root, ss and netstat show the PIDs of the own processes only.
            run(R.string.snippet_system_my_ports,
                "ss -tulpn 2>/dev/null | grep -E 'Netid|pid=' || netstat -tulpn 2>/dev/null | grep -E 'Proto|[0-9]+/'"))),
        new Group(R.string.snippet_group_termux, Arrays.asList(
            run(R.string.snippet_termux_upgrade, "pkg update && pkg upgrade -y"),
            run(R.string.snippet_termux_storage, "termux-setup-storage"),
            run(R.string.snippet_termux_mirror, "termux-change-repo"),
            type(R.string.snippet_termux_install, "pkg install "))),
        new Group(R.string.snippet_group_tmux, Arrays.asList(
            run(R.string.snippet_tmux_attach, "tmux new -A -s main"),
            run(R.string.snippet_tmux_list, "tmux ls")))));

    private SnippetDefaults() {}

    /** The snippets of all groups. */
    @NonNull
    static List<Snippet> create(@NonNull Context context) {
        return create(context, GROUPS);
    }

    /** The snippets of some groups. */
    @NonNull
    static List<Snippet> create(@NonNull Context context, @NonNull List<Group> groups) {
        List<Snippet> snippets = new ArrayList<>();
        for (Group group : groups) {
            for (Item item : group.items) {
                Snippet snippet = new Snippet();
                snippet.name = context.getString(item.nameId);
                snippet.command = item.command;
                snippet.run = item.run;
                snippets.add(snippet);
            }
        }
        return snippets;
    }

    /** Add snippets to a list, except the ones whose command is already in it. Return the number added. */
    static int addMissing(@NonNull List<Snippet> snippets, @NonNull List<Snippet> defaults) {
        Set<String> commands = new HashSet<>();
        for (Snippet snippet : snippets) if (snippet.host.isEmpty()) commands.add(snippet.command.trim());
        int added = 0;
        for (Snippet snippet : defaults) {
            if (commands.add(snippet.command.trim())) {
                snippets.add(snippet);
                added++;
            }
        }
        return added;
    }

}
