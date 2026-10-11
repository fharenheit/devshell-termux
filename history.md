# 변경 이력

이 포크(fharenheit/devshell-termux)의 커밋별 수정 사항입니다. 업스트림 [termux/termux-app](https://github.com/termux/termux-app)
릴리즈 v0.118.3 위에 쌓인 커밋을 릴리즈별로 묶었고, 최근 릴리즈가 위에 있습니다. 릴리즈마다 사용자용 설명은
`.github/release-notes/<태그>.md`(GitHub 릴리즈 본문)에, 기능 목록은 `custom/README.md`의 "수정 사항" 표에 있습니다.

- **Added**: 새 기능, **Changed**: 동작·문서·패치 변경, **Fixed**: 버그 수정
- "패치 갱신" 커밋은 업스트림 파일을 고친 커밋 뒤에 `custom/update-patches.sh`로 `custom/patches/`를 다시 만든 것입니다.

## v0.118.3-ko.28 (2026-10-11)

| 커밋 | 내용 |
|---|---|
| [`c87be65`](https://github.com/fharenheit/devshell-termux/commit/c87be65) | Added: 새 탭(+) 메뉴(로컬 셸, 등록한 호스트가 있을 때 SSH 호스트 목록과 관리, 웹 브라우저). 탭 바의 🌐 웹 탭(주소 줄, 빠른 주소: SSH 로컬 포워딩·최근 주소·설명서, 뒤로/Ctrl+Tab/Ctrl+Shift+W, 내려받기, 주소 저장) |
| (이 릴리즈 커밋) | Added: v0.118.3-ko.28 릴리즈 노트와 이력 |

## v0.118.3-ko.27 (2026-10-11)

| 커밋 | 내용 |
|---|---|
| [`34d587a`](https://github.com/fharenheit/devshell-termux/commit/34d587a) | Changed: 사용 설명서를 DevShell 이름으로, 셸 첫 화면과 정보·도움말 설명 추가 |
| [`af4f1e0`](https://github.com/fharenheit/devshell-termux/commit/af4f1e0) | Changed: 저장소 이름 `fharenheit/devshell-termux`(앱 링크, 글꼴 내려받기 주소, 문서), APK 이름 `devshell_<버전>_<ABI>.apk` |
| [`15b16c9`](https://github.com/fharenheit/devshell-termux/commit/15b16c9) | Changed: 패치 갱신(`0033`) |
| [`b3e7bb0`](https://github.com/fharenheit/devshell-termux/commit/b3e7bb0) | Added: v0.118.3-ko.27 릴리즈 노트와 이력 |

## v0.118.3-ko.26 (2026-10-11)

| 커밋 | 내용 |
|---|---|
| [`7c9a388`](https://github.com/fharenheit/devshell-termux/commit/7c9a388) | Added: Google Play 출시 계획 문서(`custom/docs/play-store-plan.md`) |
| [`ccf322c`](https://github.com/fharenheit/devshell-termux/commit/ccf322c) | Added: Google Play 계획 복원과 결정 D1, D4 기록 (다음 커밋들도 계획 문서 갱신: `9e4d892`, `d685f54`, `371075e`, `3477d6e` 이름 DevShell·앱 ID `com.fharenheit.devshell`, `6cf65c8`, `1979834`, `71d554a`, `afb81a4`, `736b63b` 문서 정리) |
| [`4c5ff69`](https://github.com/fharenheit/devshell-termux/commit/4c5ff69) | Changed: README 소개를 IME로 조합하는 모든 언어 기준으로 바꾸고 기능 요약과 스크린샷 추가 |
| [`8b27ce0`](https://github.com/fharenheit/devshell-termux/commit/8b27ce0) | Changed: 패치 갱신(`0029`) |
| [`c86505b`](https://github.com/fharenheit/devshell-termux/commit/c86505b) | Changed: README에 앱 언어, 글꼴, 설정 내보내기 스크린샷 추가 |
| [`aab2e3c`](https://github.com/fharenheit/devshell-termux/commit/aab2e3c) | Changed: 패치 갱신(`0030`) |
| [`9bd30ac`](https://github.com/fharenheit/devshell-termux/commit/9bd30ac) | Changed: 앱 이름 DevShell, 로컬 셸 첫 화면을 앱 언어의 `~/.termux/motd.sh`로(설명서·릴리즈·문제 신고 링크, 패키지 문제는 Termux에), 도움말은 이 저장소 설명서, 정보·문제 신고 화면에 DevShell 링크, Termux 후원 숨김 |
| [`e80cf24`](https://github.com/fharenheit/devshell-termux/commit/e80cf24) | Changed: README와 기능 목록에 DevShell 이름 |
| [`d9699ff`](https://github.com/fharenheit/devshell-termux/commit/d9699ff) | Changed: 패치 갱신(`0031`, `0032`) |
| [`53464fd`](https://github.com/fharenheit/devshell-termux/commit/53464fd) | Added: v0.118.3-ko.26 릴리즈 노트와 이력 |

## v0.118.3-ko.25 (2026-10-09)

| 커밋 | 내용 |
|---|---|
| [`04fceb9`](https://github.com/fharenheit/devshell-termux/commit/04fceb9) | Added: 기본 스니펫에 Git 강제 되돌리기/강제 pull/특정 커밋으로 되돌리기, 이름으로 찾기, 내 프로세스, 내 포트와 PID |
| [`2bc68d7`](https://github.com/fharenheit/devshell-termux/commit/2bc68d7) | Added: 스니펫 명령의 `<NAME>`, `<NAME=기본값>`을 누를 때 입력받는 창(보낼 명령 미리 보기, 실행/입력만, 마지막 값 기억). 기본 스니펫이 메시지·해시·사용자 이름 등을 입력받도록 바꾸고 `git revert <HASH>` 추가, 예전 기본 명령 자동 변경 |
| [`8e81b0a`](https://github.com/fharenheit/devshell-termux/commit/8e81b0a) | Added: v0.118.3-ko.25 릴리즈 노트와 이력, ko.24(사용 설명서, SSH 수정)와 합침 |

## v0.118.3-ko.24 (2026-10-08)

| 커밋 | 내용 |
|---|---|
| [`5edd8e7`](https://github.com/fharenheit/devshell-termux/commit/5edd8e7) | Changed: FEATURES.md에 특수 키 설명과 기본 스니펫 반영 |
| [`cda7f90`](https://github.com/fharenheit/devshell-termux/commit/cda7f90) | Fixed: "접속 후 실행할 명령"이 있는 호스트에서 호스트 키 확인과 공개 키 설치가 `Cannot execute command-line and remote command`로 실패하던 문제(RemoteCommand 끄고 실행) |
| [`258e9bb`](https://github.com/fharenheit/devshell-termux/commit/258e9bb) | Added: 화면과 함께 보는 사용 설명서(`custom/docs/manual`, 한국어/English)와 스크린샷을 다시 찍는 스크립트 |
| [`01bc747`](https://github.com/fharenheit/devshell-termux/commit/01bc747) | Added: v0.118.3-ko.24 릴리즈 노트와 이력 |

## v0.118.3-ko.23 (2026-10-08)

| 커밋 | 내용 |
|---|---|
| [`cd3848d`](https://github.com/fharenheit/devshell-termux/commit/cd3848d) | Added: 기본 스니펫(Git, 시스템 상태, Termux, tmux). 처음 열 때 들어 있고, 스니펫 창의 **기본** 버튼으로 묶음을 골라 다시 추가(같은 명령은 건너뜀), 이름은 앱 언어로 |
| [`c10995a`](https://github.com/fharenheit/devshell-termux/commit/c10995a) | Added: v0.118.3-ko.23 릴리즈 노트와 이력 |

## v0.118.3-ko.22 (2026-10-08)

| 커밋 | 내용 |
|---|---|
| [`fefe7bd`](https://github.com/fharenheit/devshell-termux/commit/fefe7bd) | Added: 전체 기능 목록 `FEATURES.md`, README에서 링크 |
| [`dff4b94`](https://github.com/fharenheit/devshell-termux/commit/dff4b94) | Changed: 패치 갱신(`0028`)과 문서 |
| [`b96227b`](https://github.com/fharenheit/devshell-termux/commit/b96227b) | Fixed: 특수 키 패널에서 아래 이름이 있는 키와 없는 키가 섞인 줄의 키가 어긋나던 문제. 모든 키에 번역된 아래 이름(기능 키, 화살표, 편집 키, 기호), 줄 높이에 맞춰 배치 |
| [`ef74aed`](https://github.com/fharenheit/devshell-termux/commit/ef74aed) | Added: v0.118.3-ko.22 릴리즈 노트와 이력 |

## v0.118.3-ko.21 (2026-10-08)

| 커밋 | 내용 |
|---|---|
| [`8655033`](https://github.com/fharenheit/devshell-termux/commit/8655033) | Added: 클립보드 기록(최근 20개, ⚙ 메뉴·Ctrl+Shift+H·⎘ PASTE 위로 밀기·`CLIPBOARD` 키). 누르면 붙여넣기, 길게 눌러 복사/삭제 |
| [`eccb6c6`](https://github.com/fharenheit/devshell-termux/commit/eccb6c6) | Added: SSH 호스트 키 확인. 로그인 없이 서버 키를 받아 지문 표시, 모르는 키 신뢰, 바뀐 키 경고 후 교체, 신뢰한 키 삭제. SFTP 호스트 키 오류와 호스트 메뉴에서 열기 |
| [`f1d6df5`](https://github.com/fharenheit/devshell-termux/commit/f1d6df5) | Added: SFTP 전송 대기열. 다운로드/업로드를 포그라운드 서비스에서 하나씩 실행, 알림에 진행률과 Cancel all, 파일 화면의 Transfers 목록 |
| [`9ac6a29`](https://github.com/fharenheit/devshell-termux/commit/9ac6a29) | Changed: 패치 갱신(`0026`, `0027`)과 문서 |
| [`3246d35`](https://github.com/fharenheit/devshell-termux/commit/3246d35) | Added: v0.118.3-ko.21 릴리즈 노트와 이력 |

## v0.118.3-ko.20 (2026-10-08)

| 커밋 | 내용 |
|---|---|
| [`be6cd0f`](https://github.com/fharenheit/devshell-termux/commit/be6cd0f) | Added: 특수 키 패널. 두 줄 높이의 좁은 ⊞ 키로 열고, 10개 탭(자주·Ctrl·Fn·편집·Alt·기호·tmux·vim·nano·★), 누르면 전송 후 닫힘, 길게 눌러 설명과 ★ 즐겨찾기. 하단 키에 `rows`, `width` 속성 |
| [`f81ffbf`](https://github.com/fharenheit/devshell-termux/commit/f81ffbf) | Changed: 패치 갱신(`0025`)과 문서 |
| [`083a519`](https://github.com/fharenheit/devshell-termux/commit/083a519) | Added: v0.118.3-ko.20 릴리즈 노트와 이력 |

## v0.118.3-ko.19 (2026-10-08)

| 커밋 | 내용 |
|---|---|
| [`0039b07`](https://github.com/fharenheit/devshell-termux/commit/0039b07) | Added: 하단 키를 키캡 모양으로(`extra-keys-style = keycaps`, 기본값). 아이콘과 그 아래 작은 이름, 눌린 Ctrl/Alt는 파란색 |
| [`83f16ae`](https://github.com/fharenheit/devshell-termux/commit/83f16ae) | Changed: 패치 갱신(`0022`)과 문서 |
| [`3eafcdc`](https://github.com/fharenheit/devshell-termux/commit/3eafcdc) | Added: 앱 언어 선택(설정·⚙ 메뉴 → Language). 각 화면·서비스·앱의 context를 고른 언어로 감싸고(`AppLocale`), 바꾸면 화면을 다시 만듦 |
| [`01d9808`](https://github.com/fharenheit/devshell-termux/commit/01d9808) | Added: 한국어·일본어·중국어(간체) 번역 약 420개 문자열씩, 번역마다 문자열과 자리 표시자를 확인하는 `TranslationsTest` |
| [`ea43350`](https://github.com/fharenheit/devshell-termux/commit/ea43350) | Changed: 패치 갱신(`0023`)과 문서 |
| [`083ed32`](https://github.com/fharenheit/devshell-termux/commit/083ed32) | Fixed: 터미널 글자 크기(A+/A−, 확대/축소, Ctrl+=/−)를 현재 탭에만 적용. 탭마다 크기를 기억하고 탭을 바꾸면 그 크기로 |
| [`fe92487`](https://github.com/fharenheit/devshell-termux/commit/fe92487) | Changed: 패치 갱신(`0024`) |
| [`71294f1`](https://github.com/fharenheit/devshell-termux/commit/71294f1) | Changed: 패치 문서 |
| [`59cbc92`](https://github.com/fharenheit/devshell-termux/commit/59cbc92) | Added: v0.118.3-ko.19 릴리즈 노트와 이력 |

## v0.118.3-ko.18 (2026-10-08)

| 커밋 | 내용 |
|---|---|
| [`2028cbd`](https://github.com/fharenheit/devshell-termux/commit/2028cbd) | Added: 기본 extra keys에 ⧉ COPY(선택 복사, 위로 밀면 화면 복사)와 ⎘ PASTE 추가, Ctrl+Shift+C도 같은 방식. SFTP 편집기 툴바에 잘라내기/복사/붙여넣기/전체 선택(선택이 없으면 현재 줄, 각각 Undo 한 단계) |
| [`d499700`](https://github.com/fharenheit/devshell-termux/commit/d499700) | Changed: 패치 갱신(`0021`)과 문서 |
| [`69430ee`](https://github.com/fharenheit/devshell-termux/commit/69430ee) | Added: v0.118.3-ko.18 릴리즈 노트와 이력 |

## v0.118.3-ko.17 (2026-10-08)

| 커밋 | 내용 |
|---|---|
| [`af1f9ed`](https://github.com/fharenheit/devshell-termux/commit/af1f9ed) | Added: 커밋별 변경 이력 `history.md`. `custom/README.md` 기능 목록을 분야별로 정리, README 상단에 기능 요약, 릴리즈 절차에 이력 갱신 추가 |
| [`eb64e3d`](https://github.com/fharenheit/devshell-termux/commit/eb64e3d) | Changed: 패치 갱신(`0017`, README) |
| [`5db7a0c`](https://github.com/fharenheit/devshell-termux/commit/5db7a0c) | Added: 데스크톱 터미널 키보드 단축키(Ctrl+Tab, Ctrl+Shift+T/W/C/V/F/S, Ctrl+=/−/0), ⚙ → Keyboard shortcuts, `terminal-keyboard-shortcuts = false`로 끔 |
| [`e331baf`](https://github.com/fharenheit/devshell-termux/commit/e331baf) | Added: 스니펫. 자주 쓰는 명령을 저장해 현재 탭에 입력(⚙ → Snippets, Ctrl+Shift+S, `SNIPPETS` extra key), SSH 호스트별 스니펫, `~/.termux/snippets.json`, 설정 내보내기에 포함 |
| [`5588fed`](https://github.com/fharenheit/devshell-termux/commit/5588fed) | Added: 터미널 출력에서 찾기. 스크롤된 기록까지 검색, 일치 강조, 이전/다음, 개수 표시, 여러 줄로 넘어간 줄과 한글 폭 처리(`TerminalView` 오버레이 hook) |
| [`004b62e`](https://github.com/fharenheit/devshell-termux/commit/004b62e) | Added: SFTP 여러 개 선택(다운로드/삭제/권한), 폴더 다운로드·업로드, 권한(chmod) 대화상자, 호스트별 폴더 즐겨찾기 |
| [`aeb6988`](https://github.com/fharenheit/devshell-termux/commit/aeb6988) | Changed: 패치 갱신(`0018`~`0020`)과 문서 |
| [`b492c3e`](https://github.com/fharenheit/devshell-termux/commit/b492c3e) | Added: SSH 탭의 포트 포워딩 목록과 포트 열림 상태, 로컬 포트를 브라우저로 열기, 접속 중 `ssh -O forward/cancel`로 추가·중지 |
| [`4d0edbe`](https://github.com/fharenheit/devshell-termux/commit/4d0edbe) | Added: v0.118.3-ko.17 릴리즈 노트와 이력 |

## v0.118.3-ko.16 (2026-10-08)

| 커밋 | 내용 |
|---|---|
| [`3be691b`](https://github.com/fharenheit/devshell-termux/commit/3be691b) | Added: 기본 extra keys에 **A+**(PGUP 오른쪽), **A−**(PGDN 오른쪽) 추가(`FONT_LARGER`, `FONT_SMALLER`). 화면의 글자 크기만 2픽셀씩 바꾸고 저장하지 않음. 두 손가락 확대/축소와 Ctrl+Alt+`+`/`-`도 화면에만 적용되게 변경. 저장되는 크기는 새 Font size 대화상자(⚙ → Font → Size…, 설정 → Terminal View → Font size)에서만 바꿈 |
| [`dcd40dc`](https://github.com/fharenheit/devshell-termux/commit/dcd40dc) | Changed: 패치 갱신(`0016`) |
| [`03139cc`](https://github.com/fharenheit/devshell-termux/commit/03139cc) | Added: v0.118.3-ko.16 릴리즈 노트 |

## v0.118.3-ko.15 (2026-10-08)

| 커밋 | 내용 |
|---|---|
| [`51695b9`](https://github.com/fharenheit/devshell-termux/commit/51695b9) | Added: SFTP 편집기 문법 강조. 24개 언어를 파일 이름·확장자·`#!` 줄로 자동 선택, 메뉴 → Syntax highlighting에서 변경. 화면에 보이는 부분만 칠해서 큰 파일도 빠르게 |
| [`8d53c22`](https://github.com/fharenheit/devshell-termux/commit/8d53c22) | Added: SFTP 편집기 툴바. Undo/Redo(입력·한글 조합 묶음, 저장 상태로 되돌리면 저장 안 됨 표시 해제), Find, 줄로 이동, 자동 줄 바꿈, 인코딩(BOM/UTF-8/CP949/ISO-8859-1 자동 판별, 다른 인코딩으로 다시 읽기·저장), 줄 끝(LF/CRLF), 16진수 보기. 원래 바이트로 되돌아가는 비 UTF-8 파일도 편집 가능 |
| [`83c7abb`](https://github.com/fharenheit/devshell-termux/commit/83c7abb) | Added: SFTP 편집기 툴바의 `A− 14 A+`로 글꼴 크기 변경(8~32, 기억함) |
| [`852d24b`](https://github.com/fharenheit/devshell-termux/commit/852d24b) | Added: v0.118.3-ko.15 릴리즈 노트 |

## v0.118.3-ko.14 (2026-10-08)

| 커밋 | 내용 |
|---|---|
| [`1ff2a20`](https://github.com/fharenheit/devshell-termux/commit/1ff2a20) | Fixed: Unit tests 워크플로를 JDK 11로 실행. 러너의 새 기본 JDK에서는 Robolectric 4.4가 클래스를 읽지 못해 계속 실패하던 문제 |
| [`1401504`](https://github.com/fharenheit/devshell-termux/commit/1401504) | Changed: 패치 갱신(`0014`) |
| [`779b4a9`](https://github.com/fharenheit/devshell-termux/commit/779b4a9) | Changed: `custom/README.md`에 테스트 JDK 패치 설명 |
| [`bac21b5`](https://github.com/fharenheit/devshell-termux/commit/bac21b5) | Added: 터미널에서 URL을 길게 누르고 More → **Open URL**로 브라우저 열기(여러 개면 선택). 길게 눌러 선택할 때 여러 줄로 넘어간 단어(긴 URL)를 한 번에 선택 |
| [`ebd3be4`](https://github.com/fharenheit/devshell-termux/commit/ebd3be4) | Changed: 패치 갱신(`0015`)과 문서 |
| [`96cb3d9`](https://github.com/fharenheit/devshell-termux/commit/96cb3d9) | Added: v0.118.3-ko.14 릴리즈 노트 |

## v0.118.3-ko.13 (2026-10-08)

| 커밋 | 내용 |
|---|---|
| [`5766b1c`](https://github.com/fharenheit/devshell-termux/commit/5766b1c) | Added: SSH 호스트 파일 탐색(SSH 탭 또는 호스트 메뉴 → Browse files). SSH 탭의 연결을 공유(ControlMaster)하고 SFTP v3를 직접 구현. 파일 목록(정렬, 숨김 파일, 경로 이동), 이미지 보기(확대, 이전/다음), 텍스트 보기·편집·저장(충돌 확인, 임시 파일로 안전한 저장), 업로드/다운로드, 이름 바꾸기, 삭제, 새 폴더/파일, 속성 |
| [`bbed938`](https://github.com/fharenheit/devshell-termux/commit/bbed938) | Changed: 패치 갱신(`0013`, `AndroidManifest.xml`) |
| [`6d44f1f`](https://github.com/fharenheit/devshell-termux/commit/6d44f1f) | Added: v0.118.3-ko.13 릴리즈 노트 |

## v0.118.3-ko.12 (2026-10-08)

| 커밋 | 내용 |
|---|---|
| [`672d7fe`](https://github.com/fharenheit/devshell-termux/commit/672d7fe) | Fixed: 글꼴 미리보기의 일본어·중국어 줄이 보이지 않던 문제 |
| [`6ca88bc`](https://github.com/fharenheit/devshell-termux/commit/6ca88bc) | Added: 글꼴 미리보기를 위쪽 한 곳으로 통합하고 터미널과 같은 방식으로 그림, 여러 언어 미리보기 줄, 글꼴별 지원 문자 표시(`EN 한 日 中 Кир ع ש Nerd`), D2Coding Nerd Font·Cascadia Mono·DejaVu Sans Mono 추가, 큰 글꼴은 처음 적용할 때 내려받기(SHA-256 확인). APK 약 67MB → 39MB |
| [`e3fb9c5`](https://github.com/fharenheit/devshell-termux/commit/e3fb9c5) | Added: 설정 내보내기/가져오기(⚙ 메뉴). `Download/termux-settings-*.zip`에 termux.properties, colors, SSH 호스트, 앱 설정, 글꼴 저장(SSH 비밀번호 제외), 가져올 때 기존 설정은 `~/.termux/backup/`에 백업 |
| [`91785b0`](https://github.com/fharenheit/devshell-termux/commit/91785b0) | Changed: 패치 갱신(`0011`, `0012`)과 문서 |
| [`98fa3fc`](https://github.com/fharenheit/devshell-termux/commit/98fa3fc) | Added: v0.118.3-ko.12 릴리즈 노트 |

## v0.118.3-ko.11 (2026-10-08)

| 커밋 | 내용 |
|---|---|
| [`701bb54`](https://github.com/fharenheit/devshell-termux/commit/701bb54) | Changed: 글꼴 미리보기에 일본어·중국어 글자 추가 |
| [`ce0faee`](https://github.com/fharenheit/devshell-termux/commit/ce0faee) | Changed: 한글이 없는 글꼴 표시를 "(no Hangul)"에서 "(English only)"로 |
| [`0878658`](https://github.com/fharenheit/devshell-termux/commit/0878658) | Added: v0.118.3-ko.11 릴리즈 노트 |

## v0.118.3-ko.10 (2026-10-08)

| 커밋 | 내용 |
|---|---|
| [`3bfe8d2`](https://github.com/fharenheit/devshell-termux/commit/3bfe8d2) | Added: SSH 호스트에 점프 호스트, 로컬/원격 포트 포워딩, SOCKS 포트, 자동 재접속(5회까지, 저장한 비밀번호 재사용), mosh 접속 추가. Disconnect가 재접속 대기까지 멈춤 |
| [`6bb36f1`](https://github.com/fharenheit/devshell-termux/commit/6bb36f1) | Added: v0.118.3-ko.10 릴리즈 노트 |

## v0.118.3-ko.9 (2026-10-07)

| 커밋 | 내용 |
|---|---|
| [`94b50c2`](https://github.com/fharenheit/devshell-termux/commit/94b50c2) | Added: SSH 접속 관리(⚙ 메뉴 → SSH). 호스트 등록/수정/삭제, 탭으로 접속, Disconnect/Reconnect, 비밀번호를 Android Keystore로 암호화해 저장하고 askpass로 전달, 키 생성·공개키 복사·`ssh-copy-id`, `~/.ssh/termux_hosts`를 `~/.ssh/config`에서 Include |
| [`f2ae85d`](https://github.com/fharenheit/devshell-termux/commit/f2ae85d) | Added: v0.118.3-ko.9 릴리즈 노트 |

## v0.118.3-ko.8 (2026-10-07)

| 커밋 | 내용 |
|---|---|
| [`ccc2ddb`](https://github.com/fharenheit/devshell-termux/commit/ccc2ddb) | Added: 터미널 글꼴을 내장 글꼴 목록에서 선택(⚙ 메뉴 → Font, 설정 → Terminal View → Font). 고른 글꼴을 `~/.termux/font.ttf`로 복사하고 직접 넣은 글꼴은 `font.ttf.bak`으로 백업. NanumGothicCoding, Sarasa Mono K, Noto Sans Mono CJK KR, JetBrains Mono, Fira Code, Hack, Source Code Pro 내장 |
| [`0f443fa`](https://github.com/fharenheit/devshell-termux/commit/0f443fa) | Changed: 패치 갱신(`0011`)과 문서 |
| [`41f94f7`](https://github.com/fharenheit/devshell-termux/commit/41f94f7) | Added: v0.118.3-ko.8 릴리즈 노트 |

## v0.118.3-ko.7 (2026-10-07)

| 커밋 | 내용 |
|---|---|
| [`56096a0`](https://github.com/fharenheit/devshell-termux/commit/56096a0) | Added: 탭 바의 `+` 오른쪽에 ⚙ 메뉴(Settings, Sessions list, Show/hide keyboard, Edit termux.properties, Reload settings) |
| [`1cd950d`](https://github.com/fharenheit/devshell-termux/commit/1cd950d) | Changed: ⚙ 메뉴 문서 |
| [`b88f484`](https://github.com/fharenheit/devshell-termux/commit/b88f484) | Added: v0.118.3-ko.7 릴리즈 노트 |

## v0.118.3-ko.6 (2026-10-07)

| 커밋 | 내용 |
|---|---|
| [`4a43f8c`](https://github.com/fharenheit/devshell-termux/commit/4a43f8c) | Added: 선택된 탭을 다시 눌러 이름 바꾸기(Reset으로 이름 지우기), 이름 없는 탭에 실행 중인 프로그램/작업 폴더 자동 제목(`terminal-tab-auto-title = process/cwd/off`) |
| [`2e418d7`](https://github.com/fharenheit/devshell-termux/commit/2e418d7) | Changed: 패치 갱신(`0010`)과 문서 |
| [`b5c4085`](https://github.com/fharenheit/devshell-termux/commit/b5c4085) | Added: v0.118.3-ko.6 릴리즈 노트 |

## v0.118.3-ko.5 (2026-10-07)

| 커밋 | 내용 |
|---|---|
| [`4dc0abe`](https://github.com/fharenheit/devshell-termux/commit/4dc0abe) | Added: 앱을 보고 있지 않을 때 세션이 끝나거나 벨이 울리면 알림(누르면 그 세션 탭으로 열림), `terminal-session-notifications = all/exit/bell/off` |
| [`b833ff8`](https://github.com/fharenheit/devshell-termux/commit/b833ff8) | Changed: 패치 갱신(`0009`)과 문서 |
| [`4bb3347`](https://github.com/fharenheit/devshell-termux/commit/4bb3347) | Added: v0.118.3-ko.5 릴리즈 노트 |

## v0.118.3-ko.4 (2026-10-07)

| 커밋 | 내용 |
|---|---|
| [`bb77894`](https://github.com/fharenheit/devshell-termux/commit/bb77894) | Added: `termux.properties`의 탭 설정(`terminal-tabs = top/bottom/off`, `terminal-tabs-show-close`, `max-sessions`), 탭 메뉴의 Move left/right |
| [`34630a5`](https://github.com/fharenheit/devshell-termux/commit/34630a5) | Changed: 패치 갱신(`0008`)과 문서 |
| [`9eb9f57`](https://github.com/fharenheit/devshell-termux/commit/9eb9f57) | Changed: 탭 설계 문서의 오래된 내용 수정 |
| [`111984f`](https://github.com/fharenheit/devshell-termux/commit/111984f) | Added: v0.118.3-ko.4 릴리즈 노트 |

## v0.118.3-ko.3 (2026-10-07)

| 커밋 | 내용 |
|---|---|
| [`abb3d36`](https://github.com/fharenheit/devshell-termux/commit/abb3d36) | Added: 보고 있지 않은 세션의 새 출력(●)과 벨(🔔)을 탭에 표시(앱 화면이 닫혀 있는 동안 생긴 것 포함), 세션 전환·제목 변경 토스트 끔 |
| [`4c2c81f`](https://github.com/fharenheit/devshell-termux/commit/4c2c81f) | Changed: 패치 갱신(`0007`)과 문서 |
| [`25d4778`](https://github.com/fharenheit/devshell-termux/commit/25d4778) | Added: v0.118.3-ko.3 릴리즈 노트 |

## v0.118.3-ko.2 (2026-10-07)

| 커밋 | 내용 |
|---|---|
| [`de2725c`](https://github.com/fharenheit/devshell-termux/commit/de2725c) | Changed: README 상단 안내 문구 |
| [`69f970a`](https://github.com/fharenheit/devshell-termux/commit/69f970a) | Added: 터미널 탭 설계 문서(`custom/docs/terminal-tabs-design.md`) |
| [`9bcd2c4`](https://github.com/fharenheit/devshell-termux/commit/9bcd2c4) | Added: 터미널 위에 세션 탭 바(전환, `+`로 새 세션, `×`로 닫기, 길게 눌러 이름 바꾸기, 종료/오류 세션 표시) |
| [`acfeb35`](https://github.com/fharenheit/devshell-termux/commit/acfeb35) | Changed: 세션을 바꿀 때 IME가 조합 중인 한글을 버림 |
| [`3aecf80`](https://github.com/fharenheit/devshell-termux/commit/3aecf80) | Changed: 패치 갱신(`0004`, `0005`)과 문서 |
| [`d8c73e8`](https://github.com/fharenheit/devshell-termux/commit/d8c73e8) | Added: 기본 extra keys에 ENTER(PGUP 오른쪽), KEYBOARD(PGDN 오른쪽) 버튼 |
| [`0d4ec13`](https://github.com/fharenheit/devshell-termux/commit/0d4ec13) | Changed: 패치 갱신(`0006`) |
| [`db9747e`](https://github.com/fharenheit/devshell-termux/commit/db9747e) | Added: v0.118.3-ko.2 릴리즈 노트 |

## v0.118.3-ko.1 (2026-10-05)

| 커밋 | 내용 |
|---|---|
| [`ffc93e9`](https://github.com/fharenheit/devshell-termux/commit/ffc93e9) | 소스를 업스트림 릴리즈 v0.118.3으로 동기화(포크 수정의 기준점) |
| [`378eddb`](https://github.com/fharenheit/devshell-termux/commit/378eddb) | Fixed: 블루투스/하드웨어 키보드에서 한글(IME 조합) 입력, 조합 중인 글자를 커서 위치에 표시 |
| [`a23420d`](https://github.com/fharenheit/devshell-termux/commit/a23420d) | Added: D2Coding ligature를 기본 터미널 글꼴로 내장(`~/.termux/font.ttf`가 있으면 그 글꼴 우선) |
| [`ad0da50`](https://github.com/fharenheit/devshell-termux/commit/ad0da50) | Added: `Build` 실행의 APK로 GitHub 릴리즈를 게시하는 워크플로 |
| [`ceed529`](https://github.com/fharenheit/devshell-termux/commit/ceed529) | Added: `v*` 태그를 push하면 릴리즈 게시 |
| [`0633c25`](https://github.com/fharenheit/devshell-termux/commit/0633c25) | Added: `release/<태그>` 브랜치를 push해서 릴리즈 요청, v0.118.3-ko.1 릴리즈 노트 |
| [`2a15a89`](https://github.com/fharenheit/devshell-termux/commit/2a15a89) | Added: 업스트림 업그레이드 때 포크 수정을 다시 적용하는 스크립트(`custom/upgrade-upstream.sh`, `custom/update-patches.sh`, `custom/fork-files.txt`) |
| [`b86c28e`](https://github.com/fharenheit/devshell-termux/commit/b86c28e) | Changed: 릴리즈 게시 때 릴리즈 없이 남은 같은 태그를 교체 |
