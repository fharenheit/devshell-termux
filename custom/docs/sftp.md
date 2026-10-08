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
| 편집기 | 터미널 글꼴(`~/.termux/font.ttf`), 줄 번호, 줄 바꿈 없음, Find/Find next, Reload, Save |

## 편집기 저장 규칙

- 2MB까지 편집. 더 큰 파일은 앞 2MB만 읽기 전용으로 보여 줌. UTF-8이 아니면 읽기 전용
- 앞부분에 NUL 바이트가 있으면 바이너리로 보고 Download / Open as text를 물어봄
- 줄 끝이 모두 CRLF면 CRLF로 다시 저장. 섞여 있으면 그대로 둠
- 저장 전에 서버 파일의 크기와 수정 시간을 다시 확인해서 그 사이에 바뀌었으면 Overwrite / Reload / Cancel을 물어봄
- 같은 폴더의 임시 파일(`.<이름>.termux-xxxxxxxx.tmp`)에 원래 권한으로 쓴 다음 `posix-rename@openssh.com`으로 한 번에
  바꿈(쓰는 도중 끊겨도 원본이 망가지지 않음). 파일이 심볼릭 링크이거나, 서버가 이 확장을 지원하지 않거나, 임시 파일의
  소유자가 원본과 다르면(다른 사용자의 파일을 쓰기 권한으로 고치는 경우) 원본에 바로 씀
- 저장하지 않고 나가면 Discard를 물어봄

## 다운로드

- `Download/` 폴더에 저장(Android 10 이상은 MediaStore, 그 아래는 저장소 권한). 같은 이름이 있으면 `이름 (1).확장자`
- 진행률과 Cancel. 취소하거나 실패하면 받던 파일을 지움

## 테스트

- `SftpTextTest`, `SftpPathsTest`: 줄 끝, UTF-8 판정, 잘린 UTF-8, 경로 처리
- `SftpClientTest`: 환경 변수 `TERMUX_SFTP_TEST_HOST`에 키로 로그인되는 ssh 호스트를 주면 실제 OpenSSH `sftp-server`에
  대해 목록(유니코드 이름), 3MB 파이프라인 전송, 크기 제한, 이름 바꾸기/덮어쓰기, 배타적 생성, chmod, 폴더 통째로 지우기,
  취소를 확인. 없으면 건너뜀
