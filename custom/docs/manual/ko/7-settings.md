# 7. 설정과 언어

[← SFTP 파일 탐색](6-sftp.md) · [목차](README.md)

## 설정 내보내기/가져오기

<img src="../images/ko/34_export_settings.png" width="270" alt="설정 내보내기">

- **⚙ → 설정 내보내기**: `Download/termux-settings-*.zip` 파일 하나로 저장합니다.
  - 들어가는 것: `termux.properties`, 색상, SSH 호스트, 스니펫, 특수 키 즐겨찾기, 앱 설정, 글꼴
  - SSH 비밀번호는 기기 밖으로 나갈 수 없어서 포함되지 않습니다. 새 기기에서 다시 입력합니다.
- **⚙ → 설정 가져오기**: **파일 선택**으로 zip 파일을 고릅니다. 기존 파일은 `~/.termux/backup/`에 백업합니다.

## 앱 언어

<img src="../images/ko/35_language.png" width="270" alt="언어 선택"> <img src="../images/ko/36_settings.png" width="270" alt="설정 화면">

- **⚙ → 언어** 또는 설정 → **언어**에서 시스템 기본값, English, 한국어, 日本語, 中文(简体) 중에서 고릅니다.
- 앱을 다시 시작하지 않아도 바로 바뀝니다. 시스템 기본값이면 기기 언어를 따릅니다.

## 설정 화면

<img src="../images/ko/37_settings_terminal_view.png" width="270" alt="Terminal View 설정">

**⚙ → 설정** → Termux → **Terminal View**에서 글꼴과 저장되는 글꼴 크기를 바꿀 수 있습니다. 그 밖의 설정은 `~/.termux/termux.properties`에 있고, **⚙ → termux.properties 편집**으로 열 수 있습니다.
