# 이 포크의 수정 사항과 업스트림 업그레이드 방법

이 저장소는 [termux/termux-app](https://github.com/termux/termux-app)의 포크입니다.
업스트림 릴리즈 소스에 아래 수정 사항을 얹어서 사용합니다.

## 수정 사항

기능 목록입니다. 커밋과 릴리즈별 변경 이력은 저장소 루트의 `history.md`, 릴리즈마다 사용자용 설명은 `.github/release-notes/`에 있습니다.

### 입력과 글꼴

| 내용 | 위치 |
|---|---|
| 블루투스/하드웨어 키보드 한글(IME 조합) 입력, 조합 중인 글자 표시 | `custom/patches/0001-*.patch` (`TerminalView.java`, `TerminalRenderer.java`) |
| D2Coding ligature 글꼴을 기본 터미널 글꼴로 사용 | `custom/patches/0002-*.patch` (`TermuxTerminalSessionClient.java`) + 글꼴 파일 |
| 글꼴 목록(⚙ 메뉴 → Font, 설정 → Terminal View → Font): 터미널과 같은 방식으로 그리는 미리보기, 글꼴별 지원 문자 표시, 큰 글꼴은 처음 적용할 때 `custom/fonts/`에서 내려받기(SHA-256 확인). 고른 글꼴을 `~/.termux/font.ttf`로 복사. 자세한 내용은 `custom/docs/fonts-and-settings.md` | `custom/patches/0011-*.patch` + `app/.../terminal/fonts/`, `app/src/main/assets/fonts/`, `custom/fonts/` |
| 터미널 글자 크기: 기본 extra keys에 A+(PGUP 오른쪽), A−(PGDN 오른쪽)를 추가하고, A+/A−, 두 손가락 확대/축소, Ctrl+Alt+/−는 화면에만 적용(저장하지 않음, 앱을 다시 열면 저장된 크기). 저장되는 크기는 Font 메뉴의 Size… 또는 설정 → Terminal View → Font size에서만 바꿈 | `custom/patches/0016-*.patch` (`TermuxPropertyConstants.java`, `ExtraKeysConstants.java`, `TermuxTerminalExtraKeys.java`, `TermuxTerminalViewClient.java`, `termux_terminal_view_preferences.xml`, `TerminalViewPreferencesFragment.java`) + `app/.../terminal/fonts/TerminalFontSizeDialog.java` |

### 터미널 탭과 알림

| 내용 | 위치 |
|---|---|
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
| 기본 extra keys에 ENTER(PGUP 오른쪽), KEYBOARD(PGDN 오른쪽, 키보드 표시/숨기기) 버튼 추가 | `custom/patches/0006-*.patch` (`TermuxPropertyConstants.java`) |
| 터미널에서 URL을 길게 누르고 More → **Open URL**로 브라우저 열기(선택에 URL이 여러 개면 고르기). 길게 눌러 선택할 때 여러 줄로 넘어간 단어(긴 URL)도 한 번에 선택 | `custom/patches/0015-*.patch` (`TermuxActivity.java`, `TextSelectionCursorController.java`) + `app/.../terminal/links/`, `fork_links.xml` |

### SSH

| 내용 | 위치 |
|---|---|
| SSH 접속 관리(⚙ 메뉴 → SSH): 호스트 등록/수정/삭제, 탭으로 접속, Disconnect/Reconnect, 비밀번호 암호화 저장, 키 생성/복사, `ssh-copy-id`, 점프 호스트, 포트 포워딩, 자동 재접속, mosh. 자세한 내용은 `custom/docs/ssh.md` | 포크 전용 파일만 (`app/.../terminal/ssh/`, `fork_ssh.xml`, `TerminalTabsController.java`) |
| SSH 파일 탐색(SSH 탭 또는 호스트 메뉴 → Browse files): SFTP로 파일 목록, 이미지 보기, 텍스트 보기/편집/저장(문법 강조, 툴바: Undo/Redo, 자동 줄 바꿈, 글꼴 크기, 인코딩, 줄 끝, 16진수 보기, 충돌 확인, 안전한 저장), 업로드/다운로드, 이름 바꾸기, 삭제, 새 폴더/파일, 속성. ssh 탭의 연결을 공유. 자세한 내용은 `custom/docs/sftp.md` | `custom/patches/0013-*.patch` (`AndroidManifest.xml`) + `app/.../terminal/sftp/`, `fork_sftp.xml` |

### 설정

| 내용 | 위치 |
|---|---|
| 설정 내보내기/가져오기(⚙ 메뉴): `Download/termux-settings-*.zip`에 termux.properties, colors, SSH 호스트, 앱 설정, 글꼴 저장(SSH 비밀번호 제외). 가져올 때 기존 파일은 `~/.termux/backup/`에 백업 | `custom/patches/0012-*.patch` (`TermuxActivity.java`) + `app/.../terminal/backup/` |

### 저장소와 빌드

| 내용 | 위치 |
|---|---|
| README 상단의 포크 안내와 기능 요약 | `custom/patches/0003-*.patch`, `0017-*.patch` (`README.md`) |
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
