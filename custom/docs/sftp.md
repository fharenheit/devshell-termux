# SSH 파일 탐색(SFTP)

SSH 호스트의 파일과 디렉터리를 탐색하고, 이미지를 보고, 텍스트 파일을 보고 편집해서 저장합니다.
코드는 모두 포크 전용 `app/src/main/java/com/termux/app/terminal/sftp/`에 있습니다.

## 여는 방법

- SSH 탭을 길게 누름 → **Browse files**: 그 탭의 ssh 연결을 같이 씀(다시 로그인하지 않음)
- ⚙ 메뉴 → SSH → 호스트를 길게 누름 → **Browse files**: 그 호스트에 접속된 탭이 있으면 그 연결을, 없으면
  새로 접속(저장한 비밀번호, 개인키 사용)

## 연결 방식

- ssh 탭은 `ssh -o ControlMaster=yes -o ControlPath=~/.ssh/cm/tab-xxxxxxxx`로 접속해서 연결 공유 소켓을 만듦
  (mosh 탭은 만들지 않음). 자동 재접속할 때마다 소켓을 새로 만듦
- 파일 화면은 `ssh -o ControlMaster=no -o ControlPath=<소켓> -s <호스트> sftp`를 실행하고 그 표준 입출력으로
  SFTP v3를 직접 처리(`SftpClient`). 소켓이 없으면 `ControlPath=none`으로 새로 접속하고, 비밀번호는 탭과 같은
  askpass + FIFO 방식으로 전달. 터미널이 없으므로 다른 질문(호스트 키 확인 등)에는 답하지 않고 실패 → 탭에서
  한 번 접속해서 호스트 키를 받아 두면 됨
- 파일 화면이 보이지 않게 되면(onStop) sftp를 끝내서 공유한 탭이 `exit`로 바로 끝날 수 있게 함. 다시 보이면 필요할 때
  다시 연결
- 읽기/쓰기는 32KB 요청을 16개까지 동시에 보내서 지연이 있어도 빠르게 전송

## 화면

| 화면 | 기능 |
|---|---|
| 파일 목록 | 폴더 먼저, 이름/크기/수정 시간 정렬, 숨김 파일 보기, 경로로 이동, 새로고침. 누르면 폴더는 들어가고 이미지는 뷰어, 그 외는 편집기. 뒤로 가기는 상위 폴더 |
| 항목 메뉴(길게 누름) | Open, Open as text, Download, Rename, Delete(폴더는 안의 내용까지), Properties(경로, 크기, 권한, uid:gid, 수정 시간) |
| 목록 메뉴 | New folder, New file, Upload(여러 파일 선택, 같은 이름이 있으면 Replace/Skip existing 선택) |
| 이미지 뷰어 | jpg/png/gif/webp/bmp/heic, 32MB까지. 두 손가락 확대, 끌기, 두 번 눌러 확대/원래대로, ◀ ▶로 같은 폴더의 이전/다음 이미지 |
| 편집기 | 터미널 글꼴(`~/.termux/font.ttf`), 줄 번호, 문법 강조. 툴바: ↶ Undo, ↷ Redo, Find, Ln(줄로 이동), Wrap(자동 줄 바꿈), 인코딩, 줄 끝(LF/CRLF), HEX. 메뉴: Save, Find/Find next, Go to line, Syntax highlighting, Reload |

## 편집기 툴바

- **Undo/Redo**: 이어서 입력한 글자(1.5초 이내, 줄 바꿈 전까지)와 한글 조합 입력은 한 단계로 묶음. 연속 지우기도 한 단계.
  저장한 상태까지 되돌리면 저장 안 됨 표시(●)가 없어짐. 기록은 최대 약 400만 글자
- **Wrap**: 자동 줄 바꿈. 줄 번호는 파일의 줄 기준(넘어간 줄에는 번호 없음). 설정은 기억함
- **인코딩**: 파일을 열 때 BOM → UTF-8 → CP949(EUC-KR) → ISO-8859-1 순서로 자동 판별. 버튼에 현재 인코딩(+BOM) 표시.
  Reopen with encoding(다른 인코딩으로 다시 읽기), Save with encoding(글자는 그대로 두고 저장할 인코딩만 바꾸기).
  UTF-8, UTF-16LE/BE, CP949, Shift_JIS, EUC-JP, GB18030, Big5, Windows-1252/1251, KOI8-R, ISO-8859-1. 다시 저장했을 때 원래
  바이트와 똑같지 않으면(그 인코딩으로 맞지 않는 바이트가 있으면) 읽기 전용. 저장할 인코딩으로 쓸 수 없는 글자가 있으면 저장하지 않고 알림
- **줄 끝**: LF/CRLF 중에서 골라 저장. 섞여 있는 파일에서 고르면 모두 그 방식으로 통일
- **HEX**: 바이트를 `주소  16진수  ASCII`로 보여 주는 읽기 전용 화면. 저장하지 않은 수정이 있으면 저장될 바이트를 보여 줌. 화면이 좁으면 한 줄에 8바이트.
  바이너리 파일을 열면 Open in hex view / Open as text / Download 중에서 고름. 16진수 화면은 16MB까지 읽음

## 편집기 저장 규칙

- 2MB까지 편집. 더 큰 파일은 앞 2MB만 읽기 전용으로 보여 줌
- 앞부분에 NUL 바이트가 있으면(UTF-16 BOM이 있는 파일 제외) 바이너리로 보고 어떻게 열지 물어봄
- 줄 끝이 모두 CRLF면 CRLF로 다시 저장. 섞여 있으면 그대로 둠. 인코딩과 BOM도 그대로 유지
- 저장 전에 서버 파일의 크기와 수정 시간을 다시 확인해서 그 사이에 바뀌었으면 Overwrite / Reload / Cancel을 물어봄
- 같은 폴더의 임시 파일(`.<이름>.termux-xxxxxxxx.tmp`)에 원래 권한으로 쓴 다음 `posix-rename@openssh.com`으로 한 번에
  바꿈(쓰는 도중 끊겨도 원본이 망가지지 않음). 파일이 심볼릭 링크이거나, 서버가 이 확장을 지원하지 않거나, 임시 파일의
  소유자가 원본과 다르면(다른 사용자의 파일을 쓰기 권한으로 고치는 경우) 원본에 바로 씀
- 저장하지 않고 나가면 Discard를 물어봄

## 문법 강조

- `SyntaxHighlighter`: 언어마다 간단한 규칙(주석, 문자열, 숫자, 키워드, 키 등)으로 나누는 자체 구현. 라이브러리 없음
- 언어: Shell, Python, JavaScript/TypeScript, JSON, Java/Groovy, Kotlin, C/C++, C#, Go, Rust, Swift, Ruby, Lua, PHP, Perl, SQL,
  CSS, HTML/XML, YAML, TOML/INI(properties, conf, env 등), Makefile, Dockerfile, Markdown, Diff
- 파일 이름(`Makefile`, `.bashrc` 등), 확장자, 첫 줄의 `#!`(`#!/usr/bin/env python3` 등) 순서로 언어를 고름. 모르면 일반 텍스트.
  편집기 메뉴 → Syntax highlighting에서 바꿀 수 있고, 선택한 언어는 부제목에 표시
- 화면에 보이는 줄과 위아래 60줄만 색칠하고, 스크롤(0.1초)이나 편집(0.2초)이 멈추면 다시 칠함. 여러 줄 주석/문자열을 알기 위해 파일
  처음부터 훑되, 큰 파일은 256KB 앞에서부터 훑음
- 색은 VS Code Dark+ 계열. 편집기 안에서 붙여 넣을 때는 색 없이 글자만 붙여 넣음

## 다운로드

- `Download/` 폴더에 저장(Android 10 이상은 MediaStore, 그 아래는 저장소 권한). 같은 이름이 있으면 `이름 (1).확장자`
- 진행률과 Cancel. 취소하거나 실패하면 받던 파일을 지움

## 테스트

- `SftpTextTest`, `SftpPathsTest`: 줄 끝, 인코딩 판별(CP949, BOM, UTF-16), 쓸 수 없는 글자, 16진수 줄, 잘린 UTF-8, 경로 처리
- `EditHistoryTest`, `SftpEditorViewTest`, `LineNumberEditTextTest`: 입력/한글 조합/지우기 묶음, 저장 위치, 저장 내용, 읽기 전용, 문법 강조
- `SftpClientTest`: 환경 변수 `TERMUX_SFTP_TEST_HOST`에 키로 로그인되는 ssh 호스트를 주면 실제 OpenSSH `sftp-server`에
  대해 목록(유니코드 이름), 3MB 파이프라인 전송, 크기 제한, 이름 바꾸기/덮어쓰기, 배타적 생성, chmod, 폴더 통째로 지우기,
  취소를 확인. 없으면 건너뜀
