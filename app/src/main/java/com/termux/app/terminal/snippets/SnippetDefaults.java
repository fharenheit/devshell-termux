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
            run(R.string.snippet_git_commit, "git commit -am \"<MESSAGE>\""),
            run(R.string.snippet_git_stash, "git stash list"),
            // Commands that throw away changes are typed without Enter, to run them on purpose.
            type(R.string.snippet_git_discard, "git reset --hard && git clean -fd"),
            type(R.string.snippet_git_force_pull, "git fetch origin && git reset --hard @{u}"),
            run(R.string.snippet_git_reset_commit, "git reset --hard <HASH>"),
            run(R.string.snippet_git_revert, "git revert <HASH>"))),
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
            run(R.string.snippet_system_find_name, "find <DIR=.> -iname '<PATTERN>' 2>/dev/null"),
            run(R.string.snippet_system_user_processes, "ps -u <USERNAME> -o pid,user,args"),
            // lsof lists the ports with the command, PID and user, else ss is filtered by the PIDs of
            // the user. Without root, only the PIDs of the own processes are shown.
            run(R.string.snippet_system_user_ports, "lsof -nP -i -a -u <USERNAME> 2>/dev/null || "
                + "ss -tulpn | grep -E \"Netid|pid=($(pgrep -d'|' -u <USERNAME>)),\""))),
        new Group(R.string.snippet_group_termux, Arrays.asList(
            run(R.string.snippet_termux_upgrade, "pkg update && pkg upgrade -y"),
            run(R.string.snippet_termux_storage, "termux-setup-storage"),
            run(R.string.snippet_termux_mirror, "termux-change-repo"),
            run(R.string.snippet_termux_install, "pkg install <PACKAGE>"))),
        new Group(R.string.snippet_group_tmux, Arrays.asList(
            run(R.string.snippet_tmux_attach, "tmux new -A -s <SESSION=main>"),
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

    /** The commands of earlier default snippets, and the commands that replace them. */
    private static final String[][] REPLACED_COMMANDS = {
        {"git commit -am ", "git commit -am \"<MESSAGE>\""},
        {"pkg install ", "pkg install <PACKAGE>"},
        {"tmux new -A -s main", "tmux new -A -s <SESSION=main>"},
    };

    /**
     * Replace the commands of earlier default snippets that were not changed by the new ones, which
     * ask for the values to fill in. Return whether a snippet was changed.
     */
    static boolean migrate(@NonNull List<Snippet> snippets) {
        boolean changed = false;
        for (Snippet snippet : snippets) {
            for (String[] replaced : REPLACED_COMMANDS) {
                if (snippet.command.equals(replaced[0])) {
                    snippet.command = replaced[1];
                    snippet.run = true;
                    changed = true;
                }
            }
        }
        return changed;
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
