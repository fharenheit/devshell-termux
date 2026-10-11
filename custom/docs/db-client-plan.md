# DB 클라이언트 계획

DBeaver처럼 데이터베이스에 접속해서 구조를 보고, SQL을 실행하고, 결과를 보고·고치고·내보내는 기능을 DevShell에 넣기 위한 작업 목록입니다.
Claude Code CLI에서 단계별로 작업할 수 있게 배경, 결정할 것, 작업, 완료 조건을 적었습니다.

- 작성일: 2026-10-11
- 기준 저장소: `fharenheit/devshell-termux` (업스트림 `termux/termux-app` v0.118.3 + 포크 패치 33개)
- 관련 문서: `CLAUDE.md`, `custom/README.md`, `FEATURES.md`, `custom/docs/sftp.md`, `custom/docs/ssh.md`, `custom/docs/play-store-plan.md`

## Claude에게: 작업 방법

- 한 번에 한 단계(아래 "작업"의 한 절)만 진행하고, 끝나면 이 문서의 체크박스를 갱신해서 같은 커밋에 넣으세요.
- **"0. 결정할 것"에서 정해지지 않은 항목에 기대는 작업은 시작하지 말고 사용자에게 먼저 물어보세요.**
- 저장소 규칙은 `CLAUDE.md`를 따릅니다.
  - 새 코드는 포크 전용 패키지(`app/src/main/java/com/termux/app/terminal/db/`)에 두고 `custom/fork-files.txt`에 추가합니다.
  - 업스트림 파일을 바꾸면(예: `AndroidManifest.xml`, `app/build.gradle`의 의존성) `custom/update-patches.sh`를 실행합니다.
  - 문자열은 en/ko/ja/zh 4개 언어로 넣습니다(`fork_db.xml`). `TranslationsTest`가 확인합니다.
- 빌드와 테스트:
  `export JAVA_HOME=/root/jdk/jdk-11.0.32.1+1 PATH=$JAVA_HOME/bin:$PATH && ./gradlew --no-daemon -q :app:assembleDebug :app:testDebugUnitTest`
  - 결과는 `app/build/test-results/testDebugUnitTest/*.xml`에 있습니다.
- 실제 DB로 확인할 때는 컨테이너 안에 PostgreSQL과 MariaDB를 띄워 JVM 테스트로 접속합니다. SFTP 때 실제 sshd를 띄워 `SftpClientTest`로 확인한 방식과 같습니다. 환경 변수가 없으면 건너뛰는 테스트로 만듭니다.
- 사용자에게 보이는 기능을 마치면 `FEATURES.md`, 사용 설명서(`custom/docs/manual/ko`, `en`), `custom/README.md`를 함께 갱신합니다.
- master push와 릴리즈는 사용자가 요청할 때만 합니다.

## 배경

| 항목 | 내용 |
|---|---|
| 접속 방식 후보 | (A) 앱 안 JDBC 드라이버로 직접 접속, (B) 터미널의 `psql`·`mariadb` 클라이언트를 실행하고 결과를 해석. 결과 표·편집·자동 완성 같은 GUI는 A에서만 제대로 됩니다 |
| JDBC on Android | Android에는 `java.sql`이 있어 JDBC 드라이버를 쓸 수 있습니다. 다만 드라이버마다 Android에 없는 JDK 클래스(`java.lang.management`, `javax.naming` 등)를 쓰는 버전이 있어, **Android(minSdk 24)에서 동작하는 버전을 1단계에서 확인**해야 합니다. SQLite는 Android 내장 `android.database.sqlite`를 씁니다 |
| Play 정책 | 드라이버를 APK에 넣는 것은 문제없습니다. 실행 중에 드라이버(jar, .so)를 내려받아 실행하는 것은 Device and Network Abuse 정책 위반이므로 하지 않습니다 |
| 이미 있는 재사용 가능한 부분 | SSH 호스트와 연결 공유 소켓, 접속 중 포트 포워딩 추가(`SshForwards.add`, `ssh -O forward`), 비밀번호 암호화 저장(`SshPasswords`, Android Keystore), SQL 문법 강조(`SyntaxHighlighter`의 SQL), 편집기(`SftpEditorView`, `EditHistory`, `LineNumberEditText`), 16진수 보기(`SftpText`), `Download/` 저장(`DownloadsDirectory`), 스니펫의 `<NAME>` 입력 창(`SnippetPlaceholders`), 백그라운드 서비스(`SftpTransferService` 방식), 새 탭(+) 메뉴(`NewTabMenu`)와 터미널이 아닌 탭(`WebTabs` 방식), 설정 내보내기(`backup/`) |
| 화면 | 휴대폰(세로)과 태블릿(갤럭시 탭 S7+, 가로)을 모두 지원해야 합니다. 하드웨어 키보드 단축키를 씁니다 |

## 0. 결정할 것

정해지면 "결정" 칸을 채우고 날짜를 적으세요.

| # | 질문 | 선택지 | 추천 | 결정 |
|---|---|---|---|---|
| D1 | 접속 방식 | (A) 앱 안 JDBC, (B) 터미널 클라이언트 위 UI | (A). B는 지금도 터미널에서 쓸 수 있음 | (A) 앱 안 JDBC (2026-10-11) |
| D2 | 처음 지원할 DB | PostgreSQL, MySQL/MariaDB, SQLite, SQL Server, Oracle 등 | PostgreSQL, MySQL/MariaDB, SQLite | PostgreSQL, MySQL/MariaDB, SQLite (2026-10-11) |
| D3 | 주 접속 경로 | 직접 접속, SSH 터널(저장된 SSH 호스트 경유) | 둘 다. SSH 터널을 1차에 포함 | 둘 다, SSH 터널 1차 포함 (2026-10-11) |
| D4 | 화면 위치 | (a) +메뉴의 "DB" 탭(웹 탭처럼 탭 바 안), (b) 별도 화면(SFTP 파일 화면처럼) | (a). 웹 탭과 같은 구조라 탭 전환·단축키가 통일됨 | (a) +메뉴의 DB 탭 (2026-10-11) |
| D5 | 데이터 편집 시점 | 1차, 2차 | 2차 | 추천 순서대로 끝까지 구현하기로 해서 1차 뒤 바로 2차까지 (2026-10-11) |
| D6 | 드라이버 크기 허용 | APK가 수 MB 커짐(pgjdbc 약 1MB, MariaDB Connector/J 약 0.7MB) | 허용 | 허용 (2026-10-11) |
| D7 | 접속 정보 저장 위치 | `~/.termux/db_connections.json`(설정 내보내기 포함, 비밀번호 제외) | 그대로 | 그대로 (2026-10-11) |

## 작업

### 1. 검증 (먼저)
목표: 고른 드라이버가 Android에서 동작하는지, SSH 터널로 붙을 수 있는지 확인합니다. 나머지 작업에 들어가도 될지 판단하는 단계입니다.

- [ ] D2의 드라이버 후보 버전 고르기(예: pgjdbc 42.x, MariaDB Connector/J 2.x/3.x). Android에 없는 클래스를 쓰는지 확인(빌드 후 `lint`의 InvalidPackage, 또는 앱에서 실제 접속)
- [ ] `app/build.gradle`에 의존성 추가(업스트림 파일 → 패치)
- [ ] 컨테이너에서 PostgreSQL·MariaDB를 띄우고 JVM 테스트로 접속, 쿼리, 메타데이터 읽기 확인
- [ ] 에뮬레이터나 기기에서 실제 접속 확인(가능하면). 안 되면 결과를 기록하고 사용자에게 알리기
- [ ] SSH 터널: 저장된 SSH 호스트로 `-L 로컬포트:DB호스트:DB포트`를 열고 그 포트로 접속(기존 `SshForwards.add` 또는 별도 `ssh -N -L` 프로세스)
- [ ] 결과를 이 문서의 "검증 결과"에 기록

완료 조건: 기기(또는 에뮬레이터)에서 PostgreSQL과 MariaDB에 직접·SSH 터널로 접속해 `SELECT 1`과 테이블 목록을 읽음.

### 2. 접속 관리
- [ ] 접속 모델과 저장: 이름, DB 종류, 호스트, 포트, DB 이름, 사용자, 비밀번호(Keystore 암호화), SSL 사용·인증서 확인, 추가 파라미터, SSH 호스트(선택), 색 태그, 읽기 전용
- [ ] 접속 목록 화면: 추가, 수정, 복제, 삭제, 접속 테스트(서버 버전 표시), 시간 제한
- [ ] SSH 터널 연결과 정리(마지막 DB 탭이 닫히면 터널 종료)
- [ ] 설정 내보내기/가져오기에 포함(비밀번호 제외)
- [ ] +메뉴에 "DB" 항목(D4 (a)일 때). 등록된 접속이 있으면 목록, 없으면 "새 접속"

완료 조건: 접속을 만들고 저장·복원·테스트할 수 있음. 비밀번호는 평문으로 저장되지 않음.

### 3. 구조 탐색
- [ ] 스키마 트리: DB/스키마 → 테이블, 뷰, (2차) 인덱스·함수·시퀀스
- [ ] 테이블 정보: 컬럼(타입, NULL, 기본값, PK), 대략적인 행 수, DDL 보기·복사
- [ ] 이름 검색, 새로 고침
- [ ] 테이블 길게 누르기: SELECT 열기, 행 수 세기, DDL 복사, 이름 복사
- [ ] DB별 메타데이터: JDBC `DatabaseMetaData` + `information_schema`·`pg_catalog` 쿼리. SQLite는 `sqlite_master`, `PRAGMA table_info`

완료 조건: 세 DB 모두에서 테이블과 컬럼 목록이 보이고 DDL을 복사할 수 있음.

### 4. SQL 편집기와 실행
- [ ] 편집기: SQL 문법 강조, Undo/Redo, 찾기, 글자 크기(편집기 툴바 재사용)
- [ ] 실행: 커서의 문장 / 선택 / 전체 스크립트(`;`로 나누기, 문자열·주석 안의 `;` 무시). Ctrl+Enter 단축키와 툴바 버튼
- [ ] 백그라운드 실행, 취소(`Statement.cancel`), 기본 행 제한(예: 200)과 "더 가져오기", 문장 시간 제한
- [ ] 트랜잭션: 자동 커밋 켜기/끄기, 커밋·롤백 버튼, 미커밋 표시
- [ ] 실행 기록(접속별: 시간, 소요 시간, 행 수), 저장 쿼리(`<NAME>` 입력 자리 재사용)
- [ ] 위험한 SQL 확인 창: `WHERE` 없는 `UPDATE`/`DELETE`, `DROP`, `TRUNCATE`, `ALTER`. 읽기 전용 접속은 쓰기 SQL 거부
- [ ] 특수 키 패널에 SQL 탭(`;` `*` `'` `=` `<>` `%` `(` `)`) 추가 검토

완료 조건: 스크립트를 문장별로 실행·취소할 수 있고, 위험한 SQL은 확인을 거침. 문장 나누기와 위험 SQL 판별에 단위 테스트가 있음.

### 5. 결과 그리드
- [ ] 머리글 고정 표, 가로·세로 스크롤, 컬럼 폭 조절, NULL 표시
- [ ] 결과 탭 여러 개(스크립트의 여러 결과), 행 수와 실행 시간
- [ ] 화면 안 정렬·필터(받아 온 행 기준)
- [ ] 셀 상세 보기: 긴 글, JSON(정렬), BLOB(16진수), 날짜
- [ ] 복사: 셀, 행, 컬럼, 선택 영역을 CSV/TSV, Markdown 표, `INSERT` 문으로
- [ ] 좁은 화면용 행 카드 보기
- [ ] 큰 결과 메모리 제한(행 수, 셀 크기)

완료 조건: 1만 행·50컬럼 결과를 끊김 없이 스크롤하고 복사할 수 있음.

### 6. 내보내기
- [ ] 결과·테이블을 CSV, JSON, SQL `INSERT`로 `Download/`에 저장, 공유 메뉴로 보내기
- [ ] 큰 테이블은 백그라운드로 내보내고 알림에 진행률(SFTP 전송 대기열 방식)

### 7. 화면 구성
- [ ] DB 탭(D4): 휴대폰은 구조 / SQL / 결과 전환, 태블릿 가로는 왼쪽 트리 + 오른쪽 위 편집기 + 오른쪽 아래 결과
- [ ] 단축키: Ctrl+Enter 실행, Ctrl+Shift+Enter 전체 실행, Ctrl+Tab 탭 전환(웹 탭과 같이), Ctrl+Shift+W 탭 닫기
- [ ] 탭을 바꿔도 접속과 결과 유지, 앱을 다시 열면 접속 목록과 편집 중이던 SQL 복원

### 8. 2차 기능
- [ ] 데이터 편집(D5): 테이블 데이터 보기(`WHERE`/`ORDER BY`, 페이지), 셀 수정·행 추가·삭제(PK가 있는 테이블만), 적용 전 SQL 미리 보기, 트랜잭션으로 묶어 커밋·롤백
- [ ] 자동 완성: 키워드, 테이블·컬럼 이름
- [ ] 인덱스·외래 키·함수 상세, CSV 가져오기(컬럼 매핑·미리 보기), `EXPLAIN` 보기
- [ ] 다른 DB 종류(D2에서 뺀 것) 검토

### 9. 문서와 테스트
- [ ] 단위 테스트: 문장 나누기, 위험 SQL 판별, 결과 → CSV/INSERT 변환, 접속 정보 저장·복원(비밀번호 제외), 메타데이터 해석
- [ ] 실제 DB 테스트(환경 변수 있을 때만): 접속, 쿼리, 취소, 트랜잭션, 메타데이터
- [ ] `FEATURES.md`, 사용 설명서(새 페이지 `8-database.md`, ko/en), `custom/README.md`, 설계 문서(`custom/docs/db-client.md`)

## 추천 순서

1. 0번 결정 중 D1, D2, D3, D4
2. 1번 검증. 드라이버가 Android에서 안 되면 계획을 다시 세웁니다
3. 2번 접속 관리 → 3번 구조 탐색 → 4번 SQL 편집기 → 5번 결과 그리드 → 6번 내보내기 → 7번 화면 구성(여기까지 1차 릴리즈)
4. 8번 2차 기능
5. 각 단계마다 9번의 테스트와 문서를 함께

## 검증 결과

- 2026-10-11 드라이버: `org.postgresql:postgresql:42.2.29`, `org.mariadb.jdbc:mariadb-java-client:2.7.12`. APK에 들어가고 빌드됨(universal APK 약 1.4MB 증가). pgjdbc의 `checker-qual`은 다른 라이브러리와 겹쳐서 제외(어노테이션뿐)
- `jdeps`로 Android에 없는 API 참조 확인:
  - 두 드라이버 모두 `java.time`, `java.util.Base64`, `java.nio.file`(Android 8.0, API 26부터)을 씀 → **DB 기능은 Android 8.0 이상에서만** 켬. 그보다 낮으면 안내 표시
  - TLS 호스트 이름 확인이 `javax.naming.ldap`(Android에 없음)을 씀 → PostgreSQL은 Android의 확인기를 `sslhostnameverifier`로 넘김, MariaDB는 인증서는 확인하고 호스트 이름 확인은 끔(`disableSslHostnameVerification`)
  - `javax.naming`(JNDI DataSource), `java.lang.management`(`maxResultBuffer`), `java.awt`(PGpoint), `javax.xml`(SQLXML)은 쓰지 않는 기능에서만 참조
- 컨테이너의 PostgreSQL 16, MariaDB 10.11에 JVM 테스트로 접속, 쿼리, 메타데이터, 취소, 트랜잭션 확인(`DbLiveTest`, 환경 변수 `DEVSHELL_TEST_PG_URL`, `DEVSHELL_TEST_MARIADB_URL`)
- 이 컨테이너에는 Android 기기·에뮬레이터가 없어서 **실제 기기 접속은 확인하지 못함**. 기기에서 확인할 것: 두 DB 직접·SSH 터널 접속, SSL

## 결정 기록

- 2026-10-11 D1~D7: 사용자가 "추천 순서대로 끝까지 구현"을 요청해서 모두 추천안으로 결정
