# 7. Settings and language

[← SFTP file browser](6-sftp.md) · [Contents](README.md)

## Exporting and importing settings

<img src="../images/en/34_export_settings.png" width="270" alt="Settings exported">

- **⚙ → Export settings**: saves one file, `Download/termux-settings-*.zip`.
  - It has `termux.properties`, colors, SSH hosts, snippets, special key favorites, app preferences and the font.
  - SSH passwords can't leave the device, so they are not included. Enter them again on the new device.
- **⚙ → Import settings**: pick the zip file with **Choose file**. The previous files are backed up in `~/.termux/backup/`.

## App language

<img src="../images/en/35_language.png" width="270" alt="Language"> <img src="../images/en/36_settings.png" width="270" alt="Settings">

- Choose System default, English, 한국어, 日本語 or 中文(简体) in **⚙ → Language** or Settings → **Language**.
- It changes right away, without restarting the app. System default follows the device language.

## Settings screen

<img src="../images/en/37_settings_terminal_view.png" width="270" alt="Terminal View settings">

**⚙ → Settings** → Termux → **Terminal View** has the font and the saved font size. Other settings are in `~/.termux/termux.properties`, which **⚙ → Edit termux.properties** opens.
