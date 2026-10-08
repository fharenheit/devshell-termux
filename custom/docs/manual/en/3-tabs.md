# 3. Terminal tabs

[← Display and fonts](2-display.md) · [Contents](README.md) · [Next: Snippets and clipboard →](4-snippets-clipboard.md)

<img src="../images/en/01_terminal_tabs.png" width="270" alt="Session tab bar"> <img src="../images/en/11_rename_tab.png" width="270" alt="Renaming a tab">

The **session tab bar** above the terminal switches sessions (tap a tab), creates them (**+**) and closes them (**×**).

| Shown | Meaning |
|---|---|
| `[1] webapp` | Session number and title. A tab without a name shows the running program (`top`) or the working directory (`src`) |
| **●** | New output in a background tab |
| **🔔** | Bell in a background tab |

- **Rename**: tap the selected tab again (above, right). **Reset** clears the name and goes back to the automatic title.
- **Reorder, close**: long press a tab.
- **Notifications**: when you are not looking at the app, a session that ends or rings the bell shows a notification. Tapping it opens that session.
- **⚙ menu**: at the right end of the tab bar. It has SSH, Snippets, Find, Clipboard history, Settings, Sessions list, Show/hide keyboard, Font, Edit termux.properties, Reload settings, Export/Import settings, Keyboard shortcuts and Language.

## Settings (`~/.termux/termux.properties`)

| Setting | Values |
|---|---|
| `terminal-tabs` | `top` (default), `bottom`, `off` |
| `terminal-tabs-show-close` | Whether tabs show × |
| `terminal-tab-auto-title` | `process`, `cwd`, `off` |
| `terminal-session-notifications` | `all`, `exit`, `bell`, `off` |
| `max-sessions` | Maximum number of sessions |

Tap **⚙ → Reload settings** after a change.
