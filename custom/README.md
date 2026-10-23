# 이 포크의 수정 사항과 업스트림 업그레이드 방법

이 저장소는 [termux/termux-app](https://github.com/termux/termux-app)의 포크입니다.
업스트림 릴리즈 소스에 아래 수정 사항을 얹어서 사용합니다.

## 수정 사항

기능 목록입니다. 커밋과 릴리즈별 변경 이력은 저장소 루트의 `history.md`, 릴리즈마다 사용자용 설명은 `.github/release-notes/`에 있습니다.

### 입력과 글꼴

| 내용 | 위치 |
|---|---|
| 블루투스/하드웨어 키보드 한글(IME 조합) 입력, 조합 중인 글자 표시 | `custom/patches/0001-*.patch` (`TerminalView.java`, `TerminalRenderer.java`) |
| 한글 조합 중 Enter 등 IME를 거치지 않는 키를 누르면 조합 중 글자를 먼저 보내고, 이후 IME가 커밋하는 글자는 보낸 것과의 차이만 전송(입력 재시작 없음) | `custom/patches/0043-*.patch` (`TerminalView.java`), 포크 전용 `app/src/test/java/com/termux/view/TerminalViewImeTest.java` |
| 한/영 전환 후 이전 단어를 재조합할 때 중복 입력 방지: 확정된 입력 문맥을 유지하고 바뀐 부분만 전송, 일반 영어 키와 삭제도 문맥에 반영, Enter·방향키·세션 전환 시 이전 문맥 분리. IME가 문장 중간에 Enter를 넣어도 뒤쪽 글자를 삭제하거나 다음 줄로 밀지 않음 | `custom/patches/0044-*.patch` (`TerminalView.java`), `app/src/test/java/com/termux/view/TerminalViewImeTest.java` |
| D2Coding ligature 글꼴을 기본 터미널 글꼴로 사용 | `custom/patches/0002-*.patch` (`TermuxTerminalSessionClient.java`) + 글꼴 파일 |
| 글꼴 목록(⚙ 메뉴 → Font, 설정 → Terminal View → Font): 터미널과 같은 방식으로 그리는 미리보기, 글꼴별 지원 문자 표시, 큰 글꼴은 처음 적용할 때 `custom/fonts/`에서 내려받기(SHA-256 확인). 고른 글꼴을 `~/.termux/font.ttf`로 복사. 자세한 내용은 `custom/docs/fonts-and-settings.md` | `custom/patches/0011-*.patch` + `app/.../terminal/fonts/`, `app/src/main/assets/fonts/`, `custom/fonts/` |
| 터미널 글자 크기: 기본 extra keys에 A+(PGUP 오른쪽), A−(PGDN 오른쪽)를 추가하고, A+/A−, 두 손가락 확대/축소, Ctrl+Alt+/−는 현재 탭의 화면에만 적용(탭마다 따로, 저장하지 않음, 앱을 다시 열면 저장된 크기). 저장되는 크기는 Font 메뉴의 Size… 또는 설정 → Terminal View → Font size에서만 바꿈 | `custom/patches/0016-*.patch`, `0024-*.patch` (`TermuxPropertyConstants.java`, `ExtraKeysConstants.java`, `TermuxTerminalExtraKeys.java`, `TermuxTerminalViewClient.java`, `termux_terminal_view_preferences.xml`, `TerminalViewPreferencesFragment.java`) + `app/.../terminal/fonts/TerminalFontSizeDialog.java` |

### 터미널 탭과 알림

| 내용 | 위치 |
|---|---|
| 새 탭(+) 메뉴: 로컬 셸, SSH(앱에 등록한 호스트가 있을 때만, 호스트 목록과 관리), 웹 브라우저. 웹 탭은 터미널 탭 뒤에 🌐 탭으로 보이고, 고르면 터미널·하단 키 대신 주소 줄과 WebView(파일 접근 끔, 다른 스킴은 다른 앱, 내려받기는 DownloadManager, 빠른 주소는 SSH 탭의 로컬 포워딩·최근 주소·설명서, 주소는 앱 설정에 저장) | 포크 전용 파일만 (`app/.../terminal/tabs/NewTabMenu.java`, `app/.../terminal/web/`, `fork_web_tabs.xml`) |
| 터미널 위에 세션 탭 바(전환, 새로 만들기, 닫기, 이름 바꾸기). 설계는 `custom/docs/terminal-tabs-design.md` | `custom/patches/0004-*.patch` (`activity_termux.xml`, `TermuxActivity.java`, `TermuxTerminalSessionClient.java`) + `app/.../terminal/tabs/`, 탭 리소스 |
| 세션을 바꿀 때 IME가 조합 중인 글자(한글)를 버림 | `custom/patches/0005-*.patch` (`TerminalView.java`) |
| 백그라운드 세션의 새 출력(●)과 벨(🔔)을 탭에 표시(앱 화면이 닫혀 있는 동안 생긴 것 포함), 세션 전환/제목 변경 토스트 끔 | `custom/patches/0007-*.patch` (`TermuxService.java`, `TermuxActivity.java`, `TermuxTerminalSessionClient.java`) + `app/.../terminal/tabs/` |
| `termux.properties`의 탭 설정(`terminal-tabs = top/bottom/off`, `terminal-tabs-show-close`, `max-sessions`), 탭 길게 누르기 메뉴로 순서 바꾸기 | `custom/patches/0008-*.patch` (`TermuxActivity.java`, `TermuxTerminalSessionClient.java`) + `app/.../terminal/tabs/TerminalTabsSettings.java` |
| 앱을 보고 있지 않을 때 세션이 끝나거나 벨이 울리면 알림(누르면 그 세션으로 열림), `terminal-session-notifications = all/exit/bell/off` | `custom/patches/0009-*.patch` (`TermuxActivity.java`, `TermuxTerminalSessionClient.java`, `TermuxService.java`) + `app/.../terminal/tabs/TerminalSessionNotifications.java` |
| 선택된 탭을 다시 눌러 이름 바꾸기(Reset으로 이름 지우기), 이름 없는 탭에 실행 중인 프로그램/작업 폴더 표시(`terminal-tab-auto-title = process/cwd/off`) | `custom/patches/0010-*.patch` (`TermuxTerminalSessionClient.java`) + `app/.../terminal/tabs/TerminalTabTitles.java` |
| 탭 바의 ⚙ 메뉴(설정, 세션 목록, 키보드, `termux.properties` 편집, 설정 다시 읽기) | 포크 전용 파일만 (`app/.../terminal/tabs/TerminalTabsController.java`, `view_terminal_tabs.xml`, `ic_terminal_tab_settings.xml`) |

### 터미널 화면

| 내용 | 위치 |
|---|---|
| 기본 extra keys에 ENTER(PGUP 오른쪽), KEYBOARD(PGDN 오른쪽, 키보드 표시/숨기기) 버튼 추가. 둘째 줄의 CTRL, ALT 자리에 `?`와 ⇡ Push(`GIT_PUSH` 키: Main에 Push / 현재 브랜치에 Push / 새로운 브랜치에 Push 중 고르면 그 문구를 입력하고 잠시 뒤 Enter, 문구는 앱 언어) | `custom/patches/0006-*.patch`, `0049-*.patch` (`TermuxPropertyConstants.java`, `ExtraKeysConstants.java`, `TermuxTerminalExtraKeys.java`) + `app/.../terminal/snippets/PushMenu.java`, `fork_push.xml` |
| 기본 extra keys에 ⧉ COPY(A+ 오른쪽, 선택한 글자 복사, 위로 밀면 화면 전체 복사)와 ⎘ PASTE(A− 오른쪽) 추가, 두 줄 10칸. 선택 없이 ⧉를 누르면 선택 방법 안내 | `custom/patches/0021-*.patch` (`TermuxPropertyConstants.java`, `ExtraKeysConstants.java`, `TermuxTerminalExtraKeys.java`) + `app/.../terminal/clipboard/` |
| extra keys를 키캡 모양으로 그리기(`extra-keys-style = keycaps`, 기본값): 둥근 키 안에 아이콘과 그 아래 작은 이름(⇤ Home, ⇞ PgUp, ⧉ Copy, ↲ Enter 등), Esc/Ctrl/Alt는 짧은 이름, 눌린 Ctrl/Alt는 파란색으로 채움. `extra-keys`에 직접 정한 display는 그대로. `extra-keys-style = default`로 예전 모양 | `custom/patches/0022-*.patch` (`ExtraKeysView.java`, `ExtraKeysConstants.java`, `ExtraKeysInfo.java`, `SpecialButtonState.java`, `TermuxPropertyConstants.java`) |
| 특수 키 패널: Enter·Kbd 오른쪽의 좁고 두 줄 높이인 ⊞ 키로 열고, 10개 탭(자주: ^C ^V ^X ^Z ^D ^A ^E ^L·F1~F12·^R ^W ^U Del / Ctrl / Fn / 편집 / Alt / 기호 / tmux / vim / nano / ★). 누르면 하단 키처럼 전송(켜 둔 Ctrl/Alt 함께)하고 패널 유지, ✕ 또는 ⊞로 닫기. 길게 누르면 보내는 키 설명과 ★ 추가/삭제(`~/.termux/special_keys_favorites.json`, 설정 내보내기 포함). 하단 키에 `rows`, `width` 속성 추가(`{key: 'SPECIAL_KEYS', rows: 2, width: 0.5}`) | `custom/patches/0025-*.patch` (`ExtraKeyButton.java`, `ExtraKeysView.java`, `ExtraKeysConstants.java`, `TermuxPropertyConstants.java`, `TermuxTerminalExtraKeys.java`) + `app/.../terminal/specialkeys/`, `fork_special_keys.xml` |
| 클립보드 기록(⚙ → Clipboard history, Ctrl+Shift+H, ⎘ PASTE 키를 위로 밀기, `CLIPBOARD` extra key): 최근 복사한 글 20개(앱 안팎에서 복사한 것, 메모리에만 보관). 누르면 현재 탭에 붙여넣기, 길게 누르면 붙여넣기/복사/삭제, Clear | `custom/patches/0026-*.patch` (`ExtraKeysConstants.java`, `TermuxPropertyConstants.java`, `TermuxTerminalExtraKeys.java`) + `app/.../terminal/clipboard/`, `fork_clipboard.xml` |
| 터미널에서 URL을 길게 누르고 More → **Open URL**로 브라우저 열기(선택에 URL이 여러 개면 고르기). 길게 눌러 선택할 때 여러 줄로 넘어간 단어(긴 URL)도 한 번에 선택 | `custom/patches/0015-*.patch` (`TermuxActivity.java`, `TextSelectionCursorController.java`) + `app/.../terminal/links/`, `fork_links.xml` |
| 키보드 단축키: Ctrl+Tab/Ctrl+Shift+Tab 탭 이동, Ctrl+Shift+T 새 탭, Ctrl+Shift+W 탭 닫기, Ctrl+Shift+C/V 복사/붙여넣기, Ctrl+Shift+F 찾기, Ctrl+Shift+S 스니펫, Ctrl+=/−/0 화면 글자 크기. ⚙ → Keyboard shortcuts에 목록, `terminal-keyboard-shortcuts = false`로 끔 | `custom/patches/0018-*.patch` (`TermuxTerminalViewClient.java`) + `app/.../terminal/tabs/TerminalKeyboardShortcuts.java` |
| 터미널 출력에서 찾기(⚙ → Find, 길게 누르기 → More → Find, Ctrl+Shift+F): 스크롤된 기록까지 검색, 화면의 일치 강조, ▲/▼로 이전/다음, 개수 표시, 대문자가 없으면 대소문자 무시, 여러 줄로 넘어간 줄과 한글 폭 처리 | `custom/patches/0020-*.patch` (`TerminalView.java` 오버레이, `TermuxActivity.java` 메뉴) + `app/.../terminal/search/` |
| 스니펫(⚙ → Snippets, Ctrl+Shift+S, `SNIPPETS` extra key): 자주 쓰는 명령을 저장하고 눌러서 현재 탭에 입력(Enter 여부, 여러 줄, SSH 호스트별). `~/.termux/snippets.json`, 설정 내보내기에 포함. 처음 열 때 기본 스니펫(Git, 시스템 상태, Termux, tmux)으로 시작하고, **기본** 버튼으로 묶음을 골라 다시 추가(이미 있는 명령은 건너뜀, 이름은 앱 언어로). 명령의 `<NAME>`, `<NAME=기본값>`은 누를 때 입력받고(보낼 명령 미리 보기, 실행/입력만, 이름별 마지막 값 기억) | `custom/patches/0019-*.patch` (`TermuxTerminalExtraKeys.java`, `ExtraKeysConstants.java`) + `app/.../terminal/snippets/` |

### SSH

| 내용 | 위치 |
|---|---|
| SSH 접속 관리(⚙ 메뉴 → SSH): 호스트 등록/수정/삭제, 탭으로 접속, Disconnect/Reconnect, 비밀번호 암호화 저장, 키 생성/복사, `ssh-copy-id`, 점프 호스트, 포트 포워딩(SSH 탭 길게 누르기 → Port forwards: 포트가 열려 있는지 표시, 로컬 포트를 브라우저로 열기, 접속 중에 추가/중지), 자동 재접속, mosh. 자세한 내용은 `custom/docs/ssh.md` | 포크 전용 파일만 (`app/.../terminal/ssh/`, `fork_ssh.xml`, `TerminalTabsController.java`) |
| SSH 파일 탐색(SSH 탭 또는 호스트 메뉴 → Browse files): SFTP로 파일 목록, 이미지 보기, 텍스트 보기/편집/저장(문법 강조, 툴바: Undo/Redo, 잘라내기/복사/붙여넣기/전체 선택, 자동 줄 바꿈, 글꼴 크기, 인코딩, 줄 끝, 16진수 보기, 충돌 확인, 안전한 저장), 업로드/다운로드(여러 개 선택, 폴더 통째로), 이름 바꾸기, 삭제, 권한(chmod), 즐겨찾기, 새 폴더/파일, 속성. ssh 탭의 연결을 공유. 자세한 내용은 `custom/docs/sftp.md` | `custom/patches/0013-*.patch` (`AndroidManifest.xml`) + `app/.../terminal/sftp/`, `fork_sftp.xml` |
| SSH 탭의 현재 폴더에서 파일 탐색 열기: 접속 시 로그인 셸 PID를 자동 수신하고 파일 탐색을 열 때 SFTP로 `/proc/PID/cwd` 조회. OSC 7 경로 보고는 대체 경로로 유지. 선택적 원격 Bash/Zsh 설정과 테스트는 `custom/shell-integration/` | `custom/patches/0045-*.patch` (`TerminalEmulator.java`) + `app/.../terminal/sftp/`, `tabs/`, `ssh/`, `custom/docs/manual/ko/6-sftp.md` |
| SSH 호스트 키 확인(SFTP 오류 창의 Check host key, 호스트 메뉴 → Host key): 서버 키 지문 표시, 모르는 키 신뢰, 바뀐 키 경고 후 교체, 신뢰한 키 삭제. 터미널 없는 SFTP 연결에서도 키를 받을 수 있음 | 포크 전용 파일만 (`app/.../terminal/ssh/SshHostKeys*.java`, `fork_ssh_host_key.xml`) |
| 터미널에서 파일 이름(`ls`, `ls -l` 출력, 절대 경로)을 길게 눌러 선택하고 More → **Download (SFTP)**: SSH 탭에서 로그인 셸의 현재 폴더(`/proc/PID/cwd`, 없으면 OSC 7, 홈) 기준으로 찾아 전송 대기열로 `Download/`에 받기(여러 줄, 폴더 포함) | `custom/patches/0046-*.patch` (`TermuxActivity.java` More 메뉴) + `app/.../terminal/sftp/TerminalDownloads.java`, `fork_sftp.xml` |
| SFTP 전송 대기열: 다운로드/업로드를 백그라운드에서 하나씩 실행(파일 화면을 나가도 계속), 알림에 진행률과 Cancel all, 끝나면 완료/실패 알림, 파일 화면 메뉴 → Transfers에서 중지/정리 | `custom/patches/0027-*.patch` (`AndroidManifest.xml`) + `app/.../terminal/sftp/SftpTransfers.java`, `SftpTransferService.java`, `fork_sftp_transfers.xml` |

### 데이터베이스

| 내용 | 위치 |
|---|---|
| DB 클라이언트(+ → 데이터베이스): PostgreSQL, MySQL/MariaDB(JDBC, Android 8.0 이상), SQLite 접속 관리(비밀번호 암호화 저장, SSL, SSH 터널, 읽기 전용, 색상), 웹 탭 뒤의 🗄 DB 탭(스키마 트리, SQL 편집기와 자동 완성·정렬·기록·저장한 쿼리, 캔버스 결과 표, 트랜잭션), 데이터 편집(기본 키로 UPDATE/INSERT/DELETE, 미리 보기, 한 트랜잭션), 결과·테이블 내보내기(테이블은 백그라운드 스트리밍과 알림), CSV 가져오기. 자세한 내용은 `custom/docs/db-client.md` | `custom/patches/0034-*.patch` (`app/build.gradle`: pgjdbc, MariaDB Connector/J) + `app/.../terminal/db/`, `fork_db.xml`, `TerminalTabsController.java`·`NewTabMenu.java`(탭 바와 + 메뉴) |

### 설정

| 내용 | 위치 |
|---|---|
| 설정 내보내기/가져오기(⚙ 메뉴): `Download/termux-settings-*.zip`에 termux.properties, colors, SSH 호스트, 스니펫, 특수 키 즐겨찾기, 앱 설정, 글꼴, DB 접속, 저장한 쿼리 저장(SSH·DB 비밀번호 제외). 가져올 때 기존 파일은 `~/.termux/backup/`에 백업 | `custom/patches/0012-*.patch` (`TermuxActivity.java`) + `app/.../terminal/backup/` |

### 언어

| 내용 | 위치 |
|---|---|
| 앱 언어 선택(설정 → Language, ⚙ 메뉴 → Language): 시스템 기본값, English, 한국어, 日本語, 中文(简体). 화면·서비스·앱의 기본 context를 고른 언어로 감싸고(`AppLocale.wrap`), 바꾸면 화면을 다시 만듦. 설정에서 바꾸면 터미널 화면은 돌아올 때 다시 만듦. 시스템 기본값이면 기기 언어를 따름(한국어 기기는 한국어). 번역은 `values-ko`, `values-ja`, `values-zh`(포크 전용 파일), 명령·경로·설정 이름·키 이름·제품 이름은 번역하지 않음. `TranslationsTest`가 번역마다 문자열과 `%1$s` 같은 자리 표시자가 영어와 같은지 확인 | `custom/patches/0023-*.patch` (각 Activity·`TermuxService`·`TermuxApplication`의 `attachBaseContext`, `TermuxActivity.onStart`, `root_preferences.xml`, `SettingsActivity.java`) + `termux-shared/.../locale/AppLocale.java`, `app/.../language/`, `values-ko`/`values-ja`/`values-zh` |

### 저장소와 빌드

| 내용 | 위치 |
|---|---|
| 저장소 이름 `fharenheit/devshell-termux`(앱 링크, 글꼴 내려받기 주소, 문서), APK 이름 `devshell_<버전>_<ABI>.apk` | `custom/patches/0033-*.patch` (`app/build.gradle`, `debug_build.yml`, `attach_debug_apks_to_release.yml`, `README.md`) |
| 앱 이름 DevShell(`TERMUX_APP_NAME`, 앱 ID는 `com.termux` 그대로). 로컬 셸 첫 화면은 앱이 앱 언어로 쓰는 `~/.termux/motd.sh`(로그인 스크립트가 패키지의 motd보다 먼저 씀, 앱 표시 줄이 없으면 사용자 파일로 보고 두지 않음): 릴리즈 버전(`.github/release-notes/`의 최신 태그)과 빌드 날짜, 릴리즈·문제 신고 링크와 패키지 문제는 Termux에 신고하라는 안내. 도움말은 이 저장소의 설명서, 정보와 문제 신고 화면은 이 앱 링크 먼저, Termux 후원 항목 숨김 | `custom/patches/0031-*.patch` (`TermuxConstants.java`, `TermuxUtils.java`, `SettingsActivity.java`, `HelpActivity.java`, `strings.xml`), `0048-*.patch` (`app/build.gradle`: `RELEASE_VERSION`, `BUILD_DATE`) + `termux-shared/.../fork/DevShellInfo.java`, `app/.../terminal/welcome/`, `fork_shell_welcome.xml` |
| README 상단의 포크 안내와 기능 요약, 전체 기능 목록 `FEATURES.md`(포크 전용) | `custom/patches/0003-*.patch`, `0017-*.patch`, `0028-*.patch`, `0029-*.patch`, `0030-*.patch`, `0032-*.patch` (`README.md`) |
| 단위 테스트 워크플로를 JDK 11로 실행(Robolectric 4.4가 새 JDK에서 실패) | `custom/patches/0014-*.patch` (`.github/workflows/run_tests.yml`) |
| 빌드 결과로 GitHub 릴리즈 게시 워크플로 | `.github/workflows/publish_release_from_build.yml`, `.github/release-notes/` |

- **업스트림에도 있는 파일을 고친 것**은 `custom/patches/`에 패치로 보관합니다.
- **포크에만 있는 파일**(글꼴, 릴리즈 워크플로, 이 디렉터리 등)은 `custom/fork-files.txt`에 목록으로 관리하고, 업그레이드할 때 그대로 복사됩니다.

## 새 업스트림 릴리즈로 업그레이드하기

```bash
custom/upgrade-upstream.sh            # 최신 정식 릴리즈(베타 제외)로
custom/upgrade-upstream.sh v0.119.0   # 특정 태그로
```

스크립트가 하는 일:

1. 업스트림 태그를 받아서 소스 전체를 그 릴리즈로 바꾸고, `custom/fork-files.txt`의 파일은 유지한 채
   `Sync source tree to upstream release <태그>` 커밋을 만듭니다. 히스토리는 그대로 두고 force-push도 필요 없습니다.
2. `custom/patches/`의 패치를 순서대로 `git am -3`로 적용해서 패치마다 커밋을 하나씩 만듭니다.

끝나면 `master`에 push하면 GitHub Actions의 `Build`가 APK를 만듭니다.

### 패치가 충돌하면

업스트림이 같은 부분을 고쳤으면 `git am`이 멈춥니다.

1. 충돌한 파일을 고치고 `git add <파일>` 후 `git am --continue`
2. 스크립트가 알려준 임시 폴더에서 남은 패치를 `git am -3 <패치>`로 적용
3. `custom/update-patches.sh`를 실행해서 패치를 새 소스 기준으로 갱신하고 커밋

포기하려면 `git am --abort && git reset --hard <업그레이드 전 커밋>`.

## 수정 사항을 바꾸거나 추가할 때

- 업스트림에도 있는 파일을 고쳤다면, 커밋한 다음 `custom/update-patches.sh`를 실행하고 그 결과도 커밋합니다.
  마지막 `Sync source tree to upstream release` 커밋 이후의 커밋들에서 패치를 다시 만듭니다.
- 포크에만 있는 새 파일을 추가했다면 `custom/fork-files.txt`에 경로를 추가합니다.

## 릴리즈 게시

1. `.github/release-notes/<태그>.md`에 릴리즈 노트를 작성하고, 저장소 루트의 `history.md`에 그 릴리즈의 커밋을 추가한 뒤 `master`에 push
2. 같은 커밋으로 `release/<태그>` 브랜치 push (예: `release/v0.119.0-ko.1`)

워크플로가 그 커밋의 `Build` 결과 APK로 릴리즈와 태그를 만들고 브랜치를 지웁니다.
태그를 직접 push할 수 있다면 `v*` 태그를 push해도 됩니다.
