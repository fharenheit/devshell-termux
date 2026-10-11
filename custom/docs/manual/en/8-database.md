# 8. Database

[← Settings and language](7-settings.md) · [Contents](README.md)

Connect to PostgreSQL, MySQL/MariaDB and SQLite, run SQL, look at the results and edit data. A database tab is shown in
place of the terminal like a web tab, and as `🗄 name` in the tab bar. There are no screenshots yet. How it works is in
[db-client.md](../../db-client.md) (Korean).

PostgreSQL and MySQL/MariaDB need **Android 8.0 or later**. SQLite works on all devices.

## Adding a connection

- Tap **Add connection** in **+ → Database → Manage connections…** (**+ → Database…** when there are none).
- Choose the **Type** and enter the host, port (empty for the default), user, password and database. For SQLite enter the file path (`~/` is the home directory, as in `~/data.db`; a missing file is created).
- With **Save the password** on, it is saved encrypted like SSH passwords. Otherwise it is asked for when connecting.
- **SSL**: off, required without checking the certificate, or checking the certificate.
- **SSH tunnel**: connect through a saved SSH host. The host and port above are then as seen from the SSH server (usually `127.0.0.1`). Needs `pkg install openssh`.
- The **color** is shown as a stripe in the tab toolbar, to tell production databases apart. **Read only** refuses statements that change data.
- **Test** connects before saving. Long press a connection in the list to open, edit, duplicate or delete it.

## Database tabs

- **+ → Database → a connection** opens a tab and connects. Long press the tab to connect or disconnect, reorder or close it.
- The toolbar has the connection name (tap for connect and edit), the status, **▶** run, **▶▶** run all, **■** cancel and **⋮**.
- On phones the **Schema / SQL / Results** buttons switch between the panels; on tablets and in landscape (720dp or wider) they are side by side.
- Back goes to the SQL panel, and again to the terminal. Open tabs and their SQL are kept when the app is opened again.

## Schema

- Expand schemas → tables → columns. 🔑 marks primary keys, ● the default schema. Type in the box above to find tables.
- Tap a column to insert its name into the SQL editor.
- **Long press a table**: Show rows, Edit rows, Count rows, Columns, indexes and keys, Show DDL, Export the whole table, Import CSV, Insert/Copy the name

## Running SQL

- **▶ or Ctrl+Enter** runs the statement at the cursor (or the statements in the selection). Statements are separated by `;`.
- **▶▶ or Ctrl+Shift+Enter** runs all statements in order and stops at an error. Each result gets a tab above the grid.
- `DROP`, `TRUNCATE`, `ALTER` and `UPDATE`/`DELETE` without `WHERE` are confirmed before running.
- **Completion**: after two letters, keywords and table and column names are offered. After a table name or alias and a dot, like `o.`, its columns. ↑/↓, Enter/Tab, Esc, Ctrl+Space.
- Buttons above the editor: undo/redo, 🕘 history, ★ saved queries, ＋★ save query, EXPLAIN (the plan)
- Words in angle brackets in saved queries, like `<ID>`, are asked for when the query is used.
- **⋮ menu**: auto commit on/off, rows per fetch, statement timeout, Format SQL, Clear the editor

## Results grid

- Tap a header to sort, drag a header edge to resize. Tap row numbers to select rows.
- Double tap a cell to see the whole value (JSON formatted, binary as hex). Long press for copying the value, row or column, the row card, and filtering by the value.
- Filter rows in the box above. **⧉** copies as a table, **⤓** saves, shares or copies as CSV/TSV/JSON/Markdown/SQL INSERT.
- **More rows** at the bottom fetches more.

## Transactions

- Auto commit is on by default. With **⋮ → Auto commit** off, **✓ Commit** and **↶ Roll back** appear, and the tab name ends with `*` when there are changes.
- Closing a tab with uncommitted changes asks to roll them back.

## Editing rows

- Long press a table → **Edit rows**. Only tables with a primary key can be edited.
- Double tap a cell to change its value (**NULL** button). Long press a row number to delete or duplicate the row. **＋** adds a row.
- Changed cells are yellow, new rows green and deleted rows red. The `WHERE` and `ORDER BY` boxes choose the rows.
- **✓** first shows the `UPDATE`/`INSERT`/`DELETE` statements, then applies them together. If a row was changed elsewhere meanwhile, nothing is applied.

## Export and import

- **Export the whole table** saves `Download/table.csv` and the like in the background, with progress and Cancel in a notification. The tab stays usable.
- **Import CSV** reads a file in the home directory, like `~/data.csv`, or the clipboard. A first row with column names is matched by name. Check the preview and tap **Import** to insert all rows at once; on an error nothing is inserted.

## Where things are saved

- Connections `~/.termux/db_connections.json` and saved queries `~/.termux/db_queries.json` are included in the settings export (without passwords).
- History `~/.termux/db_history.json` (the latest 200)
