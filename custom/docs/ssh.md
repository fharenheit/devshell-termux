# SSH 접속 관리

⚙ 메뉴 → **SSH**에서 SSH 접속 정보를 등록하고 탭으로 접속합니다. 접속은 openssh 패키지의 `ssh`를
그대로 실행하고(`pkg install openssh`), 앱은 접속 정보 관리와 탭 연결만 맡습니다. 코드는 모두 포크 전용
`app/src/main/java/com/termux/app/terminal/ssh/`에 있습니다.

## 화면

| 화면 | 기능 |
|---|---|
| SSH hosts | 등록한 호스트와 `~/.ssh/config`의 다른 호스트 목록. 누르면 접속, 길게 누르면 Connect / Browse files / Edit / Install public key / Delete. 아래 버튼: Add host, Keys, Close |
| 호스트 편집 | 이름(= `ssh 이름`의 별칭), 호스트, 포트, 사용자, 비밀번호, 개인키 파일, 접속 후 실행할 명령, mosh로 접속, 자동 재접속, 점프 호스트, 로컬/원격 포트 포워딩, SOCKS 포트, 추가 `ssh_config` 옵션 |
| Keys | `~/.ssh/*.pub` 목록. 누르면 공개키를 클립보드로 복사. Generate key로 새 탭에서 `ssh-keygen -t ed25519` |
| 탭 메뉴 | SSH 탭을 길게 누르면 실행 중일 때 **Disconnect**(ssh에 SIGTERM), 끝났을 때 **Reconnect**(같은 위치에 새로 접속), **Browse files**(파일 탐색, `custom/docs/sftp.md`) |

## 저장 위치

- `~/.termux/ssh_hosts.json`: 앱이 관리하는 호스트 목록(원본)
- `~/.ssh/termux_hosts`: 위 목록을 `Host` 블록으로 쓴 파일. 앱이 덮어씀
- `~/.ssh/config`: 맨 위에 `Include termux_hosts`를 한 번 넣고 나머지는 건드리지 않음. 그래서 셸에서도 `ssh 이름`으로 접속됨
- 모든 호스트에 `ServerAliveInterval 30`을 넣어 백그라운드에서 연결이 끊기지 않게 함. ssh는 옵션의 첫 값을 쓰므로 블록 맨 끝에 넣어서 추가 옵션으로 바꿀 수 있게 함
- 점프 호스트는 `ProxyJump`, 포워딩은 `LocalForward`/`RemoteForward`/`DynamicForward`로 씀. 입력은 `ssh -L`과 같은 `[bind:]port:host:hostport`이고 IPv6는 `[::1]`처럼 대괄호. `ssh_config` 형식(`[bind:]port host:hostport`)으로 바꿔 씀
- "접속 후 실행할 명령"은 `RequestTTY yes` + `RemoteCommand <명령>; exec "$SHELL" -l`(`%`는 `%%`로)

## 자동 재접속과 mosh (3단계)

- **자동 재접속**(ssh만): 접속 스크립트가 `ssh`를 반복 실행. ssh가 255로 끝나면(연결이 끊기거나 접속 실패) 5, 10, 15, 20, 25초 뒤에 다시 접속하고, 연속 5번 실패하면 멈춤. 1분 이상 유지된 연결 뒤에는 횟수를 다시 셈. 기다리는 동안 Ctrl+C로 멈춤. 정상 종료(`exit`)나 원격 명령의 다른 종료 코드는 그대로 탭 종료
- **Disconnect**는 세션 프로세스 그룹 전체에 SIGTERM을 보내서(`kill(-pid)`) 재접속 반복도 함께 멈춤. 탭은 남아서 Reconnect 가능
- **mosh**: `mosh <이름>`으로 접속. mosh가 같은 `~/.ssh/config` 별칭으로 ssh 접속을 하므로 포트·사용자·키·점프 호스트가 적용되고, 저장한 비밀번호도 같은 방식으로 전달됨. mosh는 끊김을 스스로 처리하므로 자동 재접속은 쓰지 않음. 서버에 `mosh-server`가 있어야 하고 포트 포워딩은 안 됨

## 비밀번호

- 저장: Android Keystore의 내보낼 수 없는 AES-256 키로 AES-GCM 암호화해서 앱 전용 SharedPreferences에 저장(`SshPasswords`). 앱 데이터를 지우면 키와 함께 사라짐. 같은 사용자로 도는 Termux 안의 프로그램으로부터는 보호하지 못함
- 전달: 명령줄, 환경 변수, 파일에 비밀번호를 두지 않음
  1. 앱 캐시 폴더에 named pipe(FIFO, 0600)를 만들고, ssh를 `SSH_ASKPASS=<askpass 스크립트> SSH_ASKPASS_REQUIRE=force`로 실행(OpenSSH 8.4 이상)
  2. askpass 스크립트는 password 프롬프트를 받으면 파이프에서 한 번 읽어 답하고 파이프를 지움
  3. 앱은 백그라운드 스레드에서 파이프에 읽는 쪽이 생기면(`O_NONBLOCK` open이 `ENXIO`가 아니게 되면) 비밀번호를 쓰고 닫음. 120초가 지나거나 ssh가 끝나면 파이프를 지움
  4. 저장한 비밀번호가 틀리면 두 번째부터는 터미널에서 직접 입력(에코 끔). 호스트 키 확인 같은 다른 프롬프트도 터미널에서 물어봄
  5. 자동 재접속을 켠 호스트는 재접속할 때마다 비밀번호가 필요하므로, 앱이 그 탭이 끝날 때까지 비밀번호를 메모리에 두고 파이프를 유지하면서 읽을 때마다 씀. askpass는 접속 시도마다 만드는 표시 파일(`TERMUX_SSH_ATTEMPT`)로 시도당 한 번만 파이프를 읽으므로, 저장한 비밀번호가 틀려도 같은 시도 안에서 반복해 보내지 않고 터미널 입력으로 넘어감
- "Install public key"는 같은 방식으로 저장한 비밀번호를 써서 `ssh-copy-id -i <키>.pub <호스트>`를 실행. `ssh-copy-id`가 없으면 `ssh <호스트> 'umask 077; mkdir -p ~/.ssh && cat >> ~/.ssh/authorized_keys'`

## 검증

- `SshHostTest`(Robolectric): `Host` 블록 생성, JSON 왕복, 이름 검사
- 리눅스에서 OpenSSH 9.6 sshd(비밀번호 로그인 테스트 사용자)를 띄우고, `SshConnector.java`의 스크립트 문자열을 그대로 꺼내 pty에서 실행해 확인:
  - `Include termux_hosts`와 `Host` 블록이 `ssh -G`에 반영됨
  - 첫 접속의 호스트 키 확인을 터미널에서 묻고, 저장한 비밀번호(공백과 `$` 포함)로 로그인, 파이프가 지워짐
  - 저장한 비밀번호가 틀리면 터미널 입력으로 넘어가고 입력이 화면에 보이지 않음
  - 접속 후 명령(`%` 포함) 실행 뒤 로그인 셸이 열림
  - 저장한 비밀번호로 `ssh-copy-id`, `ssh-copy-id`가 없을 때의 대체 방식 모두 키 등록 후 비밀번호 없이 로그인됨
- 3단계(같은 방식, mosh 1.4 추가):
  - `ssh -G`가 앱이 쓰는 `LocalForward`(bind 주소, IPv6 포함), `RemoteForward`, `DynamicForward`를 그대로 읽음
  - 자동 재접속: 원격 셸에서 sshd 프로세스를 죽여 연결을 끊으면 5초 뒤 재접속, 저장한 비밀번호가 다시 전달되어 로그인
  - 서버가 없을 때 5, 10, 15, 20, 25초 간격으로 다시 시도한 뒤 멈춤(종료 코드 255), 기다리는 중 Ctrl+C로 멈춤
  - Disconnect(프로세스 그룹에 SIGTERM)로 셸과 ssh가 함께 끝나고 다시 접속하지 않음
  - 자동 재접속에서 저장한 비밀번호가 틀리면 한 번만 보내고 터미널 입력으로 넘어감
  - mosh: 저장한 비밀번호로 접속해 `mosh-server` 아래 셸이 열림
