#!/bin/bash
# Usage: LANGX=en|ko scenario.sh <outdir> [first-step]
D=$(cd "$(dirname "$0")" && pwd)
OUT=$1; mkdir -p "$OUT"
FROM=${2:-0}
A="${ADB:-adb} -s ${ANDROID_SERIAL:-emulator-5554}"
export LANGX=${LANGX:-en}

drv() { python3 "$D/drv.py" "$@"; }
t() { drv tap "$@" || echo "FAIL tap $*" >&2; sleep 0.8; }
lp() { drv long "$@" || echo "FAIL long $*" >&2; sleep 1; }
ty() { drv type "$1"; }
key() { drv key "$@"; sleep 0.3; }
run() { ty "$1"; key ENTER; }
shot() { drv shot "$OUT/$1.png"; echo "shot $1"; }
waitfor() { drv wait "$@" || echo "FAIL wait $*" >&2; }
menu() {  # open the ⚙ menu and tap an item by string resource
  t terminal_tabs_menu_button; sleep 0.5
  if ! drv tap "@$1" 2>/dev/null; then drv swipe 800 1300 800 300 300; sleep 0.8; t "@$1"; fi
  sleep 1
}
more() { t 1027,201; }  # SFTP / editor overflow button
clear_field() { key MOVE_END; drv key $(printf 'DEL %.0s' $(seq 40)); }
step() { N=$1; [ "$N" -ge "$FROM" ]; }

if step 0; then
  # Reset app state but keep the installed packages.
  $A shell am force-stop com.termux
  $A shell run-as com.termux rm -f files/home/.termux/ssh_hosts.json files/home/.termux/snippets.json \
    files/home/.termux/special_keys_favorites.json files/home/.ssh/known_hosts files/home/.ssh/termux_hosts files/home/.ssh/config
  $A shell rm -f '/sdcard/Download/termux-settings-*' /sdcard/Download/photo1.jpg /sdcard/Download/photo2.jpg
  $A shell am start -n com.termux/.app.TermuxActivity >/dev/null
  waitfor terminal_tabs_menu_button 30; sleep 2
  # Language: last item of the ⚙ menu.
  t terminal_tabs_menu_button; drv swipe 800 1300 800 300 300; sleep 0.8; t 822,1923
  if [ "$LANGX" = ko ]; then t "한국어"; else t "English"; fi
  sleep 3
  $A push "$D/setup.sh" /sdcard/setup.sh >/dev/null
  run "sh /sdcard/setup.sh; clear; cd ~/webapp; git status -sb; git log --oneline --graph --decorate --all"
  sleep 3
  t terminal_tabs_new_session_button; sleep 1.5
  run "clear; top -d 2"
  t terminal_tabs_new_session_button; sleep 1.5
  run "cd ~/webapp/src; clear; sleep 4; echo Docs: https://github.com/termux/termux-app/wiki; printf '\\a'"
  t "[1]"; sleep 7
  shot 01_terminal_tabs
fi

if step 1; then
  t SPECIAL_KEYS; sleep 1; shot 02_special_keys
  lp "^C"; shot 03_special_key_info; key ESCAPE; drv tap button2 2>/dev/null; sleep 0.5
  drv swipe 700 1436 100 1436 300; sleep 1; t tmux; sleep 0.5; shot 04_special_keys_tmux
  drv tap SPECIAL_KEYS 2>/dev/null; sleep 0.5
fi

if step 2; then
  menu title_terminal_keyboard_shortcuts; shot 05_keyboard_shortcuts; t button1
  menu action_terminal_tabs_menu_font; t "JetBrains Mono"; shot 06_font; t button3; sleep 1; shot 07_font_size
  t button2; t button2
  menu action_terminal_search; ty "add"; sleep 1; shot 08_find; t "@action_terminal_search_close"
fi

if step 3; then
  t "[3]"; sleep 1
  lp 500,250 1200; sleep 0.5; shot 09_select_url
  t 571,409; sleep 1; shot 10_more_menu; key ESCAPE; t 500,1300
  t "[1]"; t "[1]"; sleep 1; shot 11_rename_tab; t button2
fi

if step 4; then
  menu title_snippets; shot 12_snippets
  t button3; sleep 1
  t 540,969; ty "Deploy"; t 540,1206; ty "./deploy.sh --prod"
  shot 13_snippet_add; t button2; sleep 0.5
  t button1; sleep 1; shot 14_snippet_defaults; t button2 || true; sleep 0.5
  drv tap button2 2>/dev/null; sleep 0.5
fi

if step 5; then
  # Clipboard history: copy the screen of two tabs (swipe up on ⧉ Copy).
  read -r cx cy < <(drv dump | awk '/COPY/{split($1,a,","); print a[1], a[2]; exit}')
  drv swipe $cx $cy $cx $((cy-300)) 150; sleep 1
  t "[3]"; drv swipe $cx $cy $cx $((cy-300)) 150; sleep 1
  menu title_clipboard_history; shot 15_clipboard_history; t button2
fi

if step 6; then
  menu action_terminal_tabs_menu_ssh; shot 16_ssh_hosts_empty
  t button1; sleep 1
  t 540,432; ty dev-server; t 540,614; ty 10.0.2.2
  t 540,796; key MOVE_END DEL DEL; ty 2222
  t 540,978; ty demo; t 540,1160; ty demo1234
  t 540,1724; t 540,2074; ty "8080:localhost:8080"
  shot 17_ssh_host_edit; t button1; sleep 1
  t button1; sleep 1
  t 540,432; ty prod-web; t 540,614; ty 203.0.113.10; t 540,978; ty deploy
  t 540,1342; ty "~/.ssh/id_ed25519"; t 540,1894; ty dev-server
  t button1; sleep 1; shot 18_ssh_hosts
  lp dev-server; shot 19_ssh_host_menu
  t "@title_ssh_host_key"; sleep 5; shot 20_ssh_host_key; t button1; sleep 1
  menu action_terminal_tabs_menu_ssh; t button3; sleep 1; shot 21_ssh_keys; t button2 2>/dev/null; sleep 0.5
  drv tap button2 2>/dev/null; sleep 0.5
fi

if step 7; then
  menu action_terminal_tabs_menu_ssh; t dev-server; sleep 6
  run "cd projects/webapp && (python3 -m http.server 8080 >/dev/null 2>&1 &) && clear && ls -la"; sleep 2; shot 22_ssh_session
  lp "[4]"; shot 23_ssh_tab_menu
  t "@action_ssh_forwards"; sleep 2; shot 24_port_forwards; t button2
fi

if step 8; then
  lp "[4]"; t "@action_sftp_browse_files"; sleep 4; shot 25_sftp_browser
  more; shot 26_sftp_menu; key ESCAPE
  t projects; sleep 1.5; t webapp; sleep 1.5
  lp deploy.sh; shot 27_sftp_file_menu
  t "@sftp_action_permissions"; sleep 1; shot 28_sftp_permissions; t button2
  t src; sleep 1.5; t server.py; sleep 3; shot 29_editor
  drv swipe 1000 333 100 333 300; sleep 1; shot 30_editor_toolbar
  key BACK; sleep 1.5
  more; t "@sftp_action_go_to"; t 540,1205; clear_field; ty "/home/demo/photos"; t button1; sleep 2
  t photo1.jpg; sleep 3; shot 31_image_viewer; key ESCAPE; sleep 1
  drv dump | grep -q photo2.jpg || { key BACK; sleep 1; }
  lp photo1.jpg; t "@sftp_action_select"; t photo2.jpg; sleep 0.5; shot 32_sftp_multi_select
  more; t "@sftp_action_download"; sleep 4
  more; t "@sftp_action_transfers"; sleep 1; shot 33_transfers; t button1
  more; t "@sftp_action_close" 2>/dev/null; sleep 1
fi

if step 9; then
  menu action_terminal_tabs_menu_export_settings; sleep 2; shot 34_export_settings; t button1
  menu title_language; shot 35_language; t button2
  menu action_terminal_tabs_menu_settings; sleep 2; shot 36_settings
  t 540,563; sleep 1; t 320,729; sleep 1; shot 37_settings_terminal_view
  key BACK; key BACK; key BACK; sleep 1
fi
echo DONE
