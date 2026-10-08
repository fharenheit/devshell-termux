# 스크린샷 다시 만들기

매뉴얼의 스크린샷(`../images/ko`, `../images/en`)은 에뮬레이터에서 이 스크립트로 찍었습니다. 화면이 바뀌면 같은 방법으로 다시 찍습니다.

| 파일 | 내용 |
|---|---|
| `scenario.sh` | 앱을 조작하며 화면 37개를 찍는 시나리오. `LANGX=ko` 또는 `en` |
| `drv.py` | adb로 누르기/입력/스크린샷. `@문자열이름`으로 앱 문자열 리소스(언어별)를 찾아 누름 |
| `setup.sh` | Termux 안에서 예제 git 저장소와 SSH 키를 만듦 |
| `sshd/` | SSH/SFTP 화면용 예제 서버(Docker, 사용자 `demo` / `demo1234`, 호스트 이름 `dev-server`) |
| `process.py` | 스크린샷을 절반 크기, 128색으로 줄여 `../images/<언어>/`에 저장 |

## 준비

1. Android SDK의 emulator, platform-tools, `system-images;android-34;google_apis;x86_64`를 설치하고 Pixel 6 AVD를 만듭니다. AVD의 `config.ini`에 `hw.keyboard=yes`를 넣어 화면 키보드가 가리지 않게 합니다.
2. 에뮬레이터를 켜고 화면 키보드를 끈 뒤, 상태 표시줄을 고정합니다.
   ```sh
   adb shell settings put secure show_ime_with_hard_keyboard 0
   adb shell settings put global sysui_demo_allowed 1
   adb shell am broadcast -a com.android.systemui.demo -e command enter
   adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0930
   adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
   ```
3. 릴리즈의 x86_64 APK를 `adb install -g`로 설치하고, 앱을 한 번 열어 설치를 끝낸 뒤 터미널에서 `pkg install -y openssh git`을 실행합니다.
4. 예제 SSH 서버를 띄웁니다. 에뮬레이터에서는 `10.0.2.2:2222`로 접속합니다.
   ```sh
   docker build -t manual-sshd sshd && docker run -d --name manual-sshd --hostname dev-server -p 127.0.0.1:2222:22 manual-sshd
   ```

## 찍기

```sh
ADB=/path/to/adb LANGX=ko ./scenario.sh /tmp/shots/ko
ADB=/path/to/adb LANGX=en ./scenario.sh /tmp/shots/en
python3 process.py /tmp/shots/ko ko
python3 process.py /tmp/shots/en en
```

- 시나리오는 시작할 때 SSH 호스트, 스니펫, known_hosts를 지우고 앱 언어를 바꿉니다. 설치한 패키지는 그대로 둡니다.
- `scenario.sh <outdir> <단계>`로 중간 단계부터 다시 찍을 수 있습니다(단계마다 앞 단계의 화면 상태가 필요할 수 있음).
- 실패한 동작은 `FAIL`로 출력됩니다. 찍은 뒤 이미지를 한 번씩 확인합니다.
