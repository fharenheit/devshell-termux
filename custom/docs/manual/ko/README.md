# DevShell 사용 설명서

[English](../en/README.md)

DevShell의 기능을 화면과 함께 설명합니다. DevShell은 Termux를 바탕으로 키보드 입력, SSH, 파일 탐색 등을 더한 앱입니다(저장소: fharenheit/termux-app). 패키지는 Termux 저장소에서 받으므로 `pkg`, `termux-*` 명령과 `~/.termux` 설정 폴더는 Termux와 같습니다. 기능 목록만 보려면 [FEATURES.md](../../../../FEATURES.md)를 봅니다.

<img src="../images/ko/01_terminal_tabs.png" width="270" alt="터미널 화면">

## 목차

1. [키보드와 입력](1-keyboard.md): 하단 키, 특수 키 패널, 하드웨어 키보드와 단축키
2. [화면과 글꼴](2-display.md): 글꼴, 글자 크기, 출력에서 찾기, URL 열기
3. [터미널 탭](3-tabs.md): 세션 탭 바, 새 출력·벨 표시, 이름 바꾸기, ⚙ 메뉴
4. [스니펫과 클립보드](4-snippets-clipboard.md): 스니펫, 기본 스니펫, 클립보드 기록
5. [SSH](5-ssh.md): 호스트 등록, 호스트 키, 키, SSH 탭, 포트 포워딩
6. [SFTP 파일 탐색](6-sftp.md): 파일 작업, 권한, 전송 대기열, 텍스트 편집기, 이미지 보기
7. [설정과 언어](7-settings.md): 설정 내보내기/가져오기, 앱 언어, 셸 첫 화면, 정보와 도움말

## 처음 쓸 때

- **⚙**는 터미널 탭 바 오른쪽 끝의 메뉴입니다. 이 포크의 기능은 대부분 여기서 엽니다.
- "길게 누르기"는 터미널 화면이나 탭을 길게 누르는 것입니다.
- SSH와 SFTP를 쓰려면 터미널에서 `pkg install openssh`를 실행합니다.

화면은 v0.118.3-ko.23을 Android 14 에뮬레이터(Pixel 6)에서 찍었습니다. 다시 찍는 방법은 [tools/README.md](../tools/README.md)에 있습니다.
