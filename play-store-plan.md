# Google Play 출시 계획

이 포크를 Termux와 다른 이름과 앱 ID로 바꿔 Google Play에 출시하기 위한 작업 목록입니다.
Claude Code CLI에서 단계별로 작업할 수 있게 배경, 결정할 것, 작업, 완료 조건을 적었습니다.

- 작성일: 2026-10-09
- 기준 저장소: `fharenheit/termux-app` (업스트림 `termux/termux-app` v0.118.3 + 포크 패치 28개)
- 관련 문서: `CLAUDE.md`, `custom/README.md`, `FEATURES.md`, `history.md`

## Claude에게: 작업 방법

- 한 번에 한 단계(아래 "작업"의 한 절)만 진행하고, 끝나면 이 문서의 체크박스를 갱신해서 같은 커밋에 넣으세요.
- **"0. 결정할 것"에서 정해지지 않은 항목에 기대는 작업은 시작하지 말고 사용자에게 먼저 물어보세요.**
- 저장소 규칙은 `CLAUDE.md`를 따릅니다.
  - 업스트림 파일을 바꾸면 `custom/update-patches.sh`를 실행합니다.
  - 포크 전용 파일은 `custom/fork-files.txt`에 추가합니다.
  - 문자열은 en/ko/ja/zh 4개 언어로 넣습니다. `TranslationsTest`가 확인합니다.
- 빌드와 테스트:
  `export JAVA_HOME=/root/jdk/jdk-11.0.32.1+1 PATH=$JAVA_HOME/bin:$PATH && ./gradlew --no-daemon -q :app:assembleDebug :app:testDebugUnitTest`
  - 결과는 `app/build/test-results/testDebugUnitTest/*.xml`에 있습니다.
  - 2단계 이후 Gradle과 JDK가 바뀌면 이 명령도 갱신하세요.
- 바꿀 수 없는 결정(앱 ID, 서명 키, 패키지 저장소 경로)은 되돌리기 어렵습니다. 적용 전에 사용자 확인을 받으세요.
- master push와 릴리즈는 사용자가 요청할 때만 합니다.

## 배경 (2026-10 기준으로 확인한 사실)

| 항목 | 내용 |
|---|---|
| Play targetSdk 요구 | 2026-08-31부터 새 앱과 업데이트는 **Android 16(API 36)** 이상을 대상으로 해야 합니다(연장 신청 시 2026-11-01까지). 매년 올라갑니다. 이 앱은 지금 **API 28**입니다 |
| 실행 제한(W^X) | API 29 이상을 대상으로 하면 앱 데이터 폴더(`$PREFIX/bin` 등)의 파일을 `exec`할 수 없습니다. 지금 구조로는 bash, ssh, git이 동작하지 않습니다 |
| 공식 Termux Play판 | `termux-play-store` 조직의 별도 코드베이스(`termux-apps`, `termux-packages` 포크)입니다. 최신 targetSdk로 동작하고, 패키지를 `/system/bin/linker` 모드로 실행합니다(`termux-exec` 수정). Android 11 이상만 지원하고 `sharedUserId`를 없앴습니다. Boot와 Widget은 본 앱에 합쳤고, Termux:API 일부를 앱 안에 넣었습니다. Tasker와 Float는 없습니다 |
| 앱 ID | `com.termux`는 Play와 F-Droid에 이미 있어서 쓸 수 없습니다. 패키지들은 `/data/data/<앱 ID>/files/usr` 경로가 박힌 채로 컴파일되므로, 앱 ID를 바꾸면 부트스트랩과 패키지를 새 경로로 빌드해야 합니다 |
| Play 정책 | Device and Network Abuse 정책은 Play 밖에서 실행 코드(.so 등)를 내려받는 것을 금지합니다. 터미널 앱 예외 문구는 찾지 못했습니다. `pkg install`이 문제가 될 수 있으므로 출시 전에 Play 정책 지원에 문의합니다 |
| 라이선스 | 업스트림은 GPLv3입니다. 소스 공개와 라이선스 표기를 유지하고, Termux 이름과 로고는 쓰지 않습니다(사칭 정책 대비) |

출처:
- https://developer.android.com/google/play/requirements/target-sdk
- https://support.google.com/googleplay/android-developer/answer/11926878
- https://github.com/termux-play-store
- https://github.com/termux-play-store/termux-apps
- https://github.com/termux/termux-app/discussions/4000
- https://support.google.com/googleplay/android-developer/answer/16559646

## 0. 결정할 것

정해지면 "결정" 칸을 채우고 날짜를 적으세요.

| # | 질문 | 선택지 | 추천 | 결정 |
|---|---|---|---|---|
| D1 | 기반 코드 | (a) 지금처럼 `termux/termux-app` + 직접 Play 대응, (b) `termux-play-store/termux-apps`로 옮기고 포크 기능 이전 | (b) | (b) (2026-10-09) |
| D2 | 앱 이름 | 후보: Nuri Term, ShellBay, Dalbit, Gilbeot, PocketShell 등 | Play, GitHub, 도메인에서 겹치는 이름 검색 후 결정 | |
| D3 | 앱 ID | `io.github.fharenheit.<이름>` 또는 소유한 도메인 기반 | 한 번 올리면 못 바꿈 | |
| D4 | 최소 Android 버전 | Android 11(API 30) 이상 | Play판 실행 방식과 같게 | Android 11(API 30) 이상 (2026-10-09) |
| D5 | 넣을 플러그인 기능 | API(알림, 클립보드, 공유 등), Boot, Widget | 꼭 필요한 것만 본 앱에 | |
| D6 | GitHub 직접 설치판 | (a) Play판과 같은 코드·같은 ID, (b) 지금 `com.termux`판 유지 | (a) | |
| D7 | 패키지 저장소 호스팅 | GitHub Releases/Pages, 별도 서버(CDN) | GitHub로 시작 | |
| D8 | 지원 아키텍처 | aarch64, x86_64, arm | aarch64 + x86_64(+arm) | |
| D9 | "모든 파일 접근" 권한 | `MANAGE_EXTERNAL_STORAGE` 신청, 또는 문서 선택기/MediaStore로 대체 | 대체 | |

## 작업

### 1. 검증 (먼저, 약 2주)
목표: 새 이름과 앱 ID로 Play 대응 구조가 실제 기기에서 동작하는지 확인합니다. 나머지 작업에 들어가도 될지 판단하는 단계입니다.

- [x] `termux-play-store/termux-apps`를 받아 빌드 환경 준비(필요한 JDK, Gradle, NDK 확인)
- [ ] 앱 ID를 D3 값으로 바꿔 빌드
- [ ] `termux-play-store/termux-packages`로 부트스트랩과 패키지 몇 개(openssh, git)를 새 앱 ID 경로로 빌드
- [ ] 실제 기기(Android 11 이상)에서 확인: 첫 실행 부트스트랩, `pkg install openssh git`, `ssh` 접속, `git clone`
- [ ] 결과와 걸린 문제를 이 문서의 "검증 결과"에 기록

완료 조건: 새 앱 ID 앱에서 새 경로로 빌드한 패키지를 설치하고 ssh와 git이 동작함.

### 2. 실행 구조와 targetSdk
- [ ] targetSdk 36, compileSdk 36. Gradle, AGP, Kotlin(있으면), AndroidX 라이브러리 최신화
- [ ] Robolectric을 최신으로 올리고 테스트 수정. CI JDK와 `run_tests.yml` 갱신(지금은 JDK 11로 고정, 패치 0014)
- [ ] 패키지 실행 방식을 linker 모드로(D1이 (b)면 기반에 이미 있음)
- [ ] 부트스트랩을 새 경로로 빌드해서 APK에 넣기. `app/build.gradle`의 부트스트랩 다운로드 주소와 체크섬
- [ ] 16KB 페이지 크기 지원 확인(네이티브 라이브러리 정렬)
- [ ] 64비트 필수 대응, D8에 맞춘 ABI 분할

완료 조건: targetSdk 36 빌드가 Android 11~16 기기에서 터미널, 패키지 설치, 기존 테스트 통과.

### 3. 자체 패키지 저장소
- [ ] `termux-play-store/termux-packages` 포크. 앱 ID와 경로 설정 변경(`scripts/properties.sh` 등)
- [ ] 패키지 목록 작성: 부트스트랩 기본 패키지 + openssh, mosh, git, vim, nano, tmux, python, nodejs, curl, wget, rsync, htop, lsof, iproute2 등
- [ ] GitHub Actions 빌드 워크플로(D8 아키텍처별)
- [ ] apt 저장소 생성과 서명 키(GPG). 키는 GitHub Secrets에 보관
- [ ] 호스팅(D7)과 앱 기본 `sources.list` 변경, 미러 선택 기능(`termux-change-repo`) 대체 또는 제거
- [ ] 보안 업데이트를 정기적으로 다시 빌드하는 워크플로(예: 매주 업스트림 버전 비교)

완료 조건: 앱에서 `pkg update && pkg install <목록의 패키지>`가 새 저장소로 동작.

### 4. 포크 기능 이전 (D1이 (b)일 때)
- [ ] `custom/patches/` 28개와 `custom/fork-files.txt`의 포크 전용 파일을 새 기반으로 이전. 탭, SSH/SFTP, 특수 키, 스니펫, 클립보드 기록, 언어, 글꼴, 설정 내보내기 등
- [ ] `FEATURES.md`의 기능 목록을 하나씩 확인하는 체크리스트로 검증
- [ ] `custom/upgrade-upstream.sh`와 `custom/update-patches.sh`를 새 기반 기준으로 다시 만들기
- [ ] 단위 테스트 이전과 통과

완료 조건: `FEATURES.md`의 모든 기능이 새 기반에서 동작하고 테스트 통과.

### 5. 이름과 브랜딩
- [ ] 앱 ID: `applicationId`, `TermuxConstants.TERMUX_PACKAGE_NAME`, 매니페스트 권한과 authority 이름, `manifestPlaceholders`
- [ ] 앱 이름: 빌드 설정의 `TERMUX_APP_NAME`, 문자열(4개 언어), 알림, 설정, 정보 화면
- [ ] 아이콘: 적응형 런처 아이콘, 알림 아이콘, Play 스토어 아이콘(512×512)
- [ ] 터미널 첫 화면(motd): 새 이름, 도움말 링크, 사용 팁(스니펫, 특수 키, SSH). 패키지 업데이트로 덮이지 않게 앱 쪽에서 관리
- [ ] 화면 안의 Termux 문구와 링크(termux.dev, 위키, GitHub)를 새 문서와 링크로
- [ ] 정보 화면과 오픈소스 라이선스 목록에 "Based on Termux"와 GPLv3 표기
- [ ] 사용 설명서(`custom/docs/manual`), `README.md`, `FEATURES.md`의 이름 변경과 스크린샷 다시 찍기
- [ ] 저장소 이름, APK/AAB 파일 이름, 릴리즈 태그 체계

완료 조건: 앱 화면과 터미널 첫 화면에 Termux 이름이 출처 표기 말고는 남아 있지 않음(`grep -ri termux`로 사용자에게 보이는 문자열 점검).

### 6. Android 최신 버전 대응
- [ ] 포그라운드 서비스 유형 선언(Android 14+): `TermuxService`, `SftpTransferService`에 유형(`specialUse`, `dataSync` 등). Play Console 사용 목적 신고 문구 준비
- [ ] `POST_NOTIFICATIONS` 실행 중 권한 요청(Android 13+)
- [ ] 저장소 접근 정리(D9): `WRITE_EXTERNAL_STORAGE` 제거, 다운로드는 MediaStore(이미 Android 10+는 사용), `termux-setup-storage` 대체 방식
- [ ] 제한 권한 점검: 배터리 최적화 예외, 다른 앱 위에 표시, 패키지 설치 요청, 모든 앱 조회. 필요 없으면 제거, 필요하면 사용 목적 문서화
- [ ] 화면: edge-to-edge 강제(API 35+), 큰 화면에서 회전·비율 고정 불가(API 36), 뒤로 가기 예측 동작
- [ ] 백그라운드 세션 유지(wake lock, 서비스)와 SSH 자동 재접속 동작 확인

완료 조건: Android 13~16 기기에서 권한 요청, 알림, 백그라운드 세션, SFTP 전송이 정상.

### 7. 기존 사용자 이전
- [ ] 앱 ID가 바뀌어 덮어 설치가 안 된다는 안내 문서
- [ ] 설정 내보내기(현재 `com.termux`판) → 가져오기(새 앱) 경로 확인: SSH 호스트, 스니펫, 특수 키 즐겨찾기, 글꼴. SSH 비밀번호는 다시 입력
- [ ] 홈 폴더 파일 옮기는 방법 안내(예: `tar`로 묶어 Download로)

### 8. Play 출시 준비
- [ ] 릴리즈 서명 키 생성과 Play App Signing 등록. 키와 비밀번호는 GitHub Secrets
- [ ] AAB 빌드와 Play 업로드 워크플로(내부 테스트 트랙). 지금 `Build`/`Publish Release From Build` 워크플로와 공존
- [ ] versionCode 증가 규칙과 versionName 형식
- [ ] Play Console 개발자 계정 등록. 신원 확인, 개인 계정이면 출시 전 비공개 테스트 조건(인원, 기간)을 등록할 때 확인
- [ ] 스토어 등록 정보: 짧은/긴 설명(en/ko/ja/zh), 폰·태블릿 스크린샷, 그래픽 이미지
- [ ] 개인정보처리방침 페이지(URL)와 데이터 보안 양식: 수집·전송 데이터 없음, SSH 비밀번호는 Android Keystore로 기기에만 암호화 저장
- [ ] 콘텐츠 등급, 대상 연령, 광고 없음 신고
- [ ] Play 정책 지원에 `pkg install`(실행 코드 다운로드) 관련 문의와 답변 보관
- [ ] 내부 테스트 → 비공개 테스트 → 프로덕션

### 9. 출시 후 운영
- [ ] 매년 targetSdk 상향(8월 마감) 일정
- [ ] 패키지 저장소 보안 업데이트 모니터링
- [ ] Play Console 충돌·ANR 보고와 리뷰 확인
- [ ] Play 정책 변경 확인

## 추천 순서

1. 0번 결정 중 D1, D2, D3, D4
2. 1번 검증. 실패하면 계획을 다시 세웁니다
3. 2번 실행 구조, 3번 패키지 저장소
4. 4번 포크 기능 이전, 5번 브랜딩
5. 6번 Android 대응, 7번 이전 안내
6. 8번 출시 준비 → 내부 테스트 트랙

## 검증 결과

(1번 검증을 진행하면서 기록)

### 2026-10-09: termux-apps 빌드 환경

- 받은 위치: `/root/projects/termux-apps` (커밋 `5c736894`)
- 요구 사항: JDK 21(CI는 21, 25), Gradle 9.6.1(wrapper), AGP 9.2.1, compileSdk·targetSdk 37, minSdk 30, NDK 29.0.14206865
- 설치한 SDK 구성 요소(`/opt/android-sdk`): `platforms;android-37.0`, `build-tools;37.0.0`, `ndk;29.0.14206865`, `platform-tools`
- 모듈: `termux-app`, `termux-api`, `termux-style`, `terminal-emulator`, `terminal-view`. ABI는 armeabi-v7a, arm64-v8a, x86_64
- 부트스트랩은 빌드할 때 `termux-play-store/termux-packages` 릴리즈(`bootstrap-2026.06.21-r2`)에서 받음. proot loader `.so`도 같은 곳에서 받음
- 빌드 명령:
  `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/opt/android-sdk && ./gradlew --no-daemon assembleDebug test`
  (`local.properties`에 `sdk.dir=/opt/android-sdk`)
- 결과: BUILD SUCCESSFUL(약 5분), 단위 테스트 150개 모두 통과. 디버그 APK 3개(`termux-app` 84MB, `termux-api` 15MB, `termux-style` 84MB)
- 참고: 이 기기에는 위 "작업 방법"의 JDK 11(`/root/jdk/jdk-11.0.32.1+1`)이 없고 JDK 21만 있습니다. 지금 포크(`termux-app`)를 빌드하려면 JDK 11을 따로 준비해야 합니다

## 결정 기록

(0번의 결정을 내릴 때 날짜, 결정, 이유를 기록)

- 2026-10-09 D1: (b) `termux-play-store/termux-apps`로 옮기고 포크 기능을 이전합니다. 최신 targetSdk와 linker 모드 실행 방식이 이미 들어 있어 Play 대응을 직접 하는 것보다 작업이 적습니다.
- 2026-10-09 D4: Android 11(API 30) 이상. 기반으로 정한 Play판 실행 방식과 같게 맞춥니다.
