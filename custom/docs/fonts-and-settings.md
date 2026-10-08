# 글꼴 목록과 설정 내보내기/가져오기

## 글꼴 목록 (⚙ 메뉴 → Font, 설정 → Terminal View → Font)

코드: `app/src/main/java/com/termux/app/terminal/fonts/` (포크 전용)

- 고른 글꼴은 `~/.termux/font.ttf`로 복사합니다(Termux:Styling과 같은 방식). 기본 글꼴(D2Coding)을 고르면 `font.ttf`를 지웁니다. 목록에 없는 `font.ttf`는 바꾸기 전에 `font.ttf.bak`으로 백업합니다.
- **미리보기**: 대화상자 위쪽 한 곳에서 선택한 글꼴만 보여 줍니다(`TerminalFontPreviewView`). 샘플 줄을 `TerminalEmulator`에 쓰고 터미널 화면과 같은 `TerminalRenderer`로 그리므로 터미널에서 보이는 그대로입니다. 글꼴에 없는 글자는 시스템 글꼴로, 아랍어·히브리어는 터미널처럼 왼쪽→오른쪽으로 그려집니다.
- **문자 표시**: 목록의 각 글꼴에 `EN 한 日 中 Кир ع ש ไทย Nerd` 중 가진 문자를 표시합니다. 글꼴을 추가할 때 fontTools로 확인한 값입니다(`TerminalFonts.FONTS`).
- **내려받는 글꼴**: 큰 글꼴은 APK에 넣지 않고 처음 적용할 때 이 저장소의 `custom/fonts/`에서 `~/.termux/fonts/`로 받습니다(raw.githubusercontent.com, `master`). 크기와 SHA-256이 맞을 때만 쓰고, 진행률 대화상자에서 취소할 수 있습니다. `custom/fonts/`의 파일을 바꾸면 `TerminalFonts.FONTS`의 크기와 체크섬도 바꿔야 합니다(`TerminalFontsTest.testFontFilesMatch`가 확인).

| 글꼴 | 위치 | 크기 | 문자 | 라이선스 |
|---|---|---|---|---|
| D2Coding ligature (기본) | APK | 4.1 MB | 한 日 Кир | OFL |
| D2Coding Nerd Font | 내려받기 | 6.2 MB | 한 日 Кир Nerd | OFL (Nerd Fonts 3.4.0) |
| NanumGothicCoding | 내려받기 | 2.2 MB | 한 Кир | OFL |
| Sarasa Mono K | 내려받기 | 24.3 MB | 한 日 中 Кир | OFL |
| Noto Sans Mono CJK KR | 내려받기 | 15.6 MB | 한 日 中 Кир | OFL |
| Cascadia Mono | APK | 0.7 MB | Кир ع ש | OFL |
| DejaVu Sans Mono | APK | 0.3 MB | Кир ع | Bitstream Vera + public domain |
| JetBrains Mono, Fira Code, Hack, Source Code Pro | APK | 0.2~0.3 MB | Кир | OFL, Hack은 MIT + Bitstream Vera |

라이선스 파일은 모두 `app/src/main/assets/fonts/`에 있습니다.

## 설정 내보내기/가져오기 (⚙ 메뉴 → Export settings / Import settings)

코드: `app/src/main/java/com/termux/app/terminal/backup/` (포크 전용) + 파일 선택 결과를 받는 `TermuxActivity.onActivityResult` (패치 `0012`)

- **내보내기**: `Download/termux-settings-<날짜-시간>.zip`. Android 10 이상은 MediaStore로 저장(권한 불필요), 그 전에는 저장소 권한이 필요합니다.
- **zip 내용**

  | 항목 | 내용 |
  |---|---|
  | `manifest.json` | 형식 버전, 앱, 버전, 만든 시각 |
  | `termux.properties`, `colors.properties` | 있으면 |
  | `ssh_hosts.json` | 앱에 등록한 SSH 호스트 (비밀번호 제외) |
  | `preferences.json` | 글꼴 크기, extra keys 표시, 키보드, 화면 켜짐 유지, 로그 수준 등. 현재 세션 같은 상태는 제외 |
  | `font.json` 또는 `font.ttf` | 목록의 글꼴이면 id, 직접 넣은 글꼴이면 파일 |

- **저장한 SSH 비밀번호는 내보내지 않습니다.** 기기의 Android Keystore 키로 암호화되어 있고 그 키는 기기를 떠날 수 없습니다.
- **가져오기**: 시스템 파일 선택기로 zip을 고릅니다. 위 목록의 이름만 읽고(디렉터리나 `../` 같은 이름은 무시), 텍스트는 1 MB, 글꼴은 64 MB까지 읽습니다. `manifest.json`이 없거나 다른 앱의 파일이면 거부합니다.
  1. 지금 파일을 `~/.termux/backup/settings-<날짜-시간>/`에 복사
  2. 파일과 설정을 바꾸고, SSH 호스트는 `~/.ssh/termux_hosts`를 다시 씀
  3. 목록의 글꼴이면 고를 때처럼 적용(필요하면 내려받기)
  4. 액티비티를 다시 만들어 모든 설정을 다시 읽음
