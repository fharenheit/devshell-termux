# 4. Snippets and clipboard

[← Terminal tabs](3-tabs.md) · [Contents](README.md) · [Next: SSH →](5-ssh.md)

## Snippets

Save the commands you use often, and tap one to type it into the current tab. Open them with **⚙ → Snippets**, Ctrl+Shift+S, or the `SNIPPETS` extra key.

<img src="../images/en/12_snippets.png" width="270" alt="Snippets"> <img src="../images/en/13_snippet_add.png" width="270" alt="Adding a snippet"> <img src="../images/en/14_snippet_defaults.png" width="270" alt="Adding default snippets">

- **Use**: tap a snippet to type it into the current tab. With ⏎ it also presses Enter.
- **Manage**: long press a snippet for Type into the tab, Edit, Move up/down and Delete.
- **Add** (middle): a name (the command is shown if empty), the command (can have several lines), **Press Enter after the command**, and **Show on** (all tabs, or the tabs of one SSH host).
- **Defaults** (right): pick the Git, System status, Termux and tmux groups to add again. Commands you already have are skipped.
  - The first time you open the snippets, all the defaults are there.
  - Commands that need more input, like `git commit -am ` and `pkg install `, are typed without Enter.
- Saved in `~/.termux/snippets.json`, and included in the settings export.

## Clipboard history

Open it with **⚙ → Clipboard history**, Ctrl+Shift+H, **swiping up on the ⎘ Paste key**, or the `CLIPBOARD` extra key.

<img src="../images/en/15_clipboard_history.png" width="270" alt="Clipboard history">

- Keeps the last 20 texts copied, in or outside the app, with their length in characters and lines.
- Tap one to paste it into the current tab, or long press it to paste, copy or delete it. **Clear** empties the list.
- It is kept only in memory and is gone when the app is closed.
