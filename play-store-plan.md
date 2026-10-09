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

## 결정 기록

(0번의 결정을 내릴 때 날짜, 결정, 이유를 기록)
