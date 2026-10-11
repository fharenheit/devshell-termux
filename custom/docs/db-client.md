# 데이터베이스 클라이언트

탭 바의 **+** → **데이터베이스**에서 PostgreSQL, MySQL/MariaDB, SQLite에 접속하는 DB 탭을 엽니다. DB 탭은
웹 탭처럼 터미널 자리에 보이고, 탭 바에서 웹 탭 뒤에 `🗄 이름`으로 나옵니다. 코드는 모두 포크 전용
`app/src/main/java/com/termux/app/terminal/db/`에 있고, 업스트림 파일은 `app/build.gradle`(JDBC 드라이버)만
고칩니다. 계획과 결정 기록은 `db-client-plan.md`에 있습니다.

## 화면

| 화면 | 기능 |
|---|---|
| DB 접속 관리 | 등록한 접속 목록. 누르면 새 탭으로 열기, 길게 누르면 열기 / 편집 / 복제 / 삭제. 아래 버튼: 접속 추가, 닫기. **+** → 데이터베이스 → **DB 접속 관리…**, DB 탭의 제목 메뉴나 ⋮ 메뉴에서 엶 |
| 접속 편집 | 이름, 종류(PostgreSQL / MySQL·MariaDB / SQLite), 호스트, 포트(비우면 기본 포트), 사용자, 비밀번호와 저장 여부, 데이터베이스(SQLite는 파일, `~/`는 홈), SSL(사용 안 함 / 인증서 확인 없이 / 인증서 확인), SSH 터널(등록한 SSH 호스트), 드라이버 파라미터(`key=value&…`), 색상, 읽기 전용. **연결 테스트**로 저장 전에 접속해 봄 |
| DB 탭 툴바 | 색 띠, 접속 이름(🔒 읽기 전용)과 상태(서버 버전, 수동 커밋, 오류). 제목을 누르면 연결/연결 끊기, 접속 편집, DB 접속 관리. 버튼: ▶ 실행, ▶▶ 모두 실행, ■ 실행 취소(실행 중), ✓ 커밋 / ↶ 롤백(자동 커밋을 끈 경우), ⋮ |
| ⋮ 메뉴 | 자동 커밋, 한 번에 가져올 행 수(100~5000, 기본 200), 문장 실행 제한 시간, SQL 정렬, 편집기 비우기, DB 접속 관리, 탭 닫기 |
| 구조 | 스키마 → 테이블/뷰 → 컬럼 트리(🔑 기본 키, 기본 스키마에 ●). 위 칸으로 테이블 이름 찾기, ⟳ 새로 고침. 컬럼을 누르면 편집기에 이름을 넣음. 테이블을 길게 누르면 행 보기(SELECT), 데이터 편집, 행 개수, 컬럼·인덱스·외래 키, DDL 보기, 테이블 전체 내보내기, CSV 가져오기, 이름 넣기, 이름 복사. 스키마를 길게 누르면 이름 복사, 이 스키마 사용(`SET search_path` / `USE`) |
| SQL | 줄 번호와 SQL 문법 강조 편집기. 위 버튼: 실행 취소/다시 실행, 실행 기록, 저장한 쿼리, 쿼리 저장, EXPLAIN. 입력은 탭마다 저장되어 앱을 다시 열어도 남음 |
| 결과 | 문장이 여러 개면 위에 결과 탭(`2: UPDATE 3`, 오류는 ✗). 행 찾기 칸, ⧉ 복사(TSV), ⤓ 내보내기. 표: 머리글을 누르면 정렬, 머리글 경계를 끌어 너비 조절, 행 번호를 누르면 행 선택, 셀을 두 번 누르면 값 보기, 길게 누르면 값 보기 / 값·행·열 복사 / 행 카드 / 이 값으로 찾기. 아래 줄에 행 수, 선택 수, 시간, 경고, **더 가져오기** |

폭이 720dp 이상이면(태블릿, 가로 화면, DeX) 구조 | SQL / 결과를 나란히 보여 주고, 좁으면 **구조 / SQL / 결과**
버튼으로 하나씩 봅니다. 문장을 실행하면 결과로, 뒤로 가기는 SQL로 갔다가 한 번 더 누르면 터미널로 돌아갑니다.

## 실행

- **▶ / Ctrl+Enter**: 선택한 글이 있으면 그 안의 문장들, 없으면 커서가 있는 문장. 실행하는 문장을 선택해서 보여 줌.
  커서가 문장 사이에 있으면 앞 문장, 빈 줄에 있으면 다음 문장(`SqlScript.at`).
- **▶▶ / Ctrl+Shift+Enter**: 편집기의 모든 문장을 차례로 실행하고 첫 오류에서 멈춤. 결과마다 탭이 생기고, 오류가
  있으면 그 결과를 보여 줌.
- 문장 나누기는 따옴표, 주석, PostgreSQL의 `$$` 문자열, MySQL의 `` ` ``와 `#` 주석을 알아봄(`SqlLexer`). 문장 앞의 주석은
  문장에 넣지 않음.
- 실행 전 확인(`SqlSafety`): `DROP`, `TRUNCATE`, `ALTER`, `WHERE` 없는 `UPDATE`/`DELETE`는 한 번에 모아서 물음.
  읽기 전용 접속은 데이터를 바꾸는 문장(`INSERT`, `UPDATE`, `CREATE`, `SELECT … INTO`, 쓰기가 있는 `WITH`, 값을 바꾸는
  `PRAGMA`, `EXPLAIN ANALYZE` 등)을 실행하지 않고 알려 줌. 서버의 권한을 대신하지는 않음.
- 결과는 한 번에 가져올 행 수만큼만 받고(한 행 더 받아서 더 있는지 앎), **더 가져오기**는 같은 SELECT를 더 큰 한도로
  다시 실행함. 값 하나는 1M 글자, 결과 전체는 64M 글자에서 자르고 표시함.
- **■ / Esc**: JDBC `Statement.cancel()`, SQLite는 `CancellationSignal`.
- 실행 기록: 문장마다 접속, 시간, 걸린 시간, 행 수를 `~/.termux/db_history.json`에 최근 200개 저장(내보내기 안 함).
  🕘에서 골라 편집기에 넣음.
- 저장한 쿼리: ★에서 이름을 붙여 `~/.termux/db_queries.json`에 저장(설정 내보내기에 포함). 이 접속만 / 모든 접속.
  `<ID>`, `<ID=1>` 같은 자리는 쓸 때 값을 물음(스니펫과 같은 `SnippetPlaceholders`).
- EXPLAIN: 커서의 문장을 PostgreSQL·MariaDB는 `EXPLAIN`, SQLite는 `EXPLAIN QUERY PLAN`으로 실행.
- SQL 정렬(`SqlFormatter`): `FROM`, `WHERE`, `JOIN`, `GROUP BY` 같은 절을 줄마다, `WHERE`의 `AND`/`OR`는 들여 씀.
  하위 쿼리는 깊이만큼 들여 쓰고, `extract(year from d)`처럼 괄호 안의 단어는 그대로 둠. 문자열과 주석은 바꾸지 않음.

## 자동 완성

- 두 글자를 치면 키워드, 불러온 테이블·스키마·컬럼 이름 중 그 글자로 시작하는 것을 커서 아래에 보여 줌. Ctrl+Space로
  바로 열기. ↑/↓로 고르고 Enter나 Tab으로 넣기, Esc로 닫기, 눌러서 넣기.
- `이름.` 뒤에는 그 테이블의 컬럼. 별칭도 알아봄(`FROM orders o`, `JOIN customers AS c`, `UPDATE t x`). 컬럼을 아직 안
  불러왔으면 불러온 뒤 다시 보여 줌. 스키마 이름 뒤에는 그 스키마의 테이블.
- 소문자로만 된 이름이 아니면 따옴표를 붙여 넣음(`"Name"`, `` `Name` ``).

## 트랜잭션

- 기본은 자동 커밋. ⋮ → **자동 커밋**을 끄면 툴바에 ✓ 커밋 / ↶ 롤백이 나오고, 바꾼 내용이 있으면 ✓가 초록색, 탭 이름 끝에 `*`.
- 자동 커밋을 다시 켜면 그때까지의 변경이 커밋된다고 묻고 커밋함.
- 커밋하지 않은 변경이 있는 탭을 닫으면 롤백하고 닫을지 물음. 연결 끊기도 롤백 후 닫음.
- SQLite는 Android의 트랜잭션 API로 처리하고 `BEGIN`/`COMMIT`/`ROLLBACK` 문장도 그쪽으로 넘김.

## 데이터 편집

테이블을 길게 누르기 → **데이터 편집**. 기본 키가 있는 테이블만 되고(없으면 행 보기만), 읽기 전용 접속과 뷰는 메뉴에 없음.

- 결과 위에 편집 줄: ✎ 테이블 이름과 변경 수, ＋ 행 추가, ✓ 변경 적용, ↺ 변경 버리기, ⟳ 다시 불러오기, ✕ 편집 종료.
  아래 줄에 `WHERE`, `ORDER BY` 칸과 ▶(기본 순서는 기본 키).
- 셀을 두 번 누르거나 길게 누르기 → **값 편집**(여러 줄, **NULL** 버튼), **NULL로 설정**. 행 번호를 길게 누르기 →
  행 삭제(행 번호로 여러 행을 골랐으면 모두) / 삭제 취소, 행 복제(기본값이 있는 기본 키는 비움), 행 카드.
- 바뀐 셀은 노란색, 새 행은 초록색, 지울 행은 빨간색. 바이너리 값은 편집하지 않음.
- **✓ 변경 적용**은 실행할 문장을 값과 함께 먼저 보여 주고(복사 가능), 확인하면 `DELETE`, `UPDATE`, `INSERT` 순서로
  매개 변수를 써서 실행함. `UPDATE`/`DELETE`는 불러올 때의 기본 키 값으로 찾고, 한 행이 아니면(그 사이 다른 곳에서
  바뀌거나 지워짐) 전부 되돌림. 새 행은 값을 넣은 컬럼만 `INSERT`하고, 하나도 없으면 `DEFAULT VALUES`.
- 자동 커밋이면 변경만의 트랜잭션으로, 자동 커밋을 껐으면 열린 트랜잭션 안에서 `SAVEPOINT`까지만 되돌림(커밋은 사용자가).
- 값은 글자로 보냄. PostgreSQL은 형식을 정하지 않은 매개 변수로 보내서 서버가 컬럼 형식으로 바꿈(숫자, 날짜, JSON 등).
- 변경이 있을 때 다른 문장을 실행하거나, 다시 불러오거나, 편집을 끝내면 버릴지 물음.

## 내보내기와 가져오기

- **결과 내보내기**(⤓): 선택한 행이 있으면 그 행, 없으면 보이는 행(찾기 적용). 형식: CSV, TSV, JSON, Markdown, SQL INSERT.
  **Download에 저장**(`devshell-날짜.csv`), 공유, 복사.
- **테이블 전체 내보내기**: 형식을 고르면 백그라운드에서 **따로 연결**해(탭은 계속 씀) 행을 받는 대로 파일에 씀
  (PostgreSQL은 트랜잭션 안에서 1000행씩, MariaDB는 스트리밍, SQLite는 커서). `Download/테이블.csv`. 알림에 행 수와
  취소, 끝나면 완료/실패 알림. SSH 터널 접속이면 터널도 따로 엶. 탭의 비밀번호를 그대로 씀.
- **CSV 가져오기**: 홈 디렉터리의 파일 경로(`~/data.csv`, 50MB까지)나 클립보드. 구분자는 첫 줄에서 쉼표/탭/세미콜론 중
  많은 것. 따옴표 안의 구분자·줄 바꿈·`""`, UTF-8 BOM 처리. 첫 줄이 테이블 컬럼 이름이면 머리글로 보고 이름으로 맞추고
  (대소문자 무시, 맞지 않는 열은 건너뜀), 아니면 순서대로. 미리 보기에서 행 수, 열 연결, 처음 5행을 보여 주고 머리글
  여부와 빈 값을 NULL로 할지 고름. 한 트랜잭션으로 넣고 실패하면 전부 되돌리며 몇 번째 행인지 알려 줌.

## 연결

| 종류 | 방식 |
|---|---|
| PostgreSQL | pgjdbc 42.7.4(`org.postgresql:postgresql`, `checker-qual` 제외). `jdbc:postgresql://호스트:포트/db` |
| MySQL / MariaDB | MariaDB Connector/J 2.7.12(MySQL 5.7/8.0도 접속). `jdbc:mariadb://…` |
| SQLite | Android 내장 `SQLiteDatabase`(드라이버 없음). 파일이 없으면 만듦(읽기 전용이면 오류). `NO_LOCALIZED_COLLATORS`로 열어서 `android_metadata` 테이블을 만들지 않음 |

- 드라이버는 Java 8 API(`java.time` 등)를 쓰므로 PostgreSQL·MariaDB는 **Android 8.0(API 26) 이상**. 그 아래에서는 탭을 열 때
  알려 줌(SQLite는 됨). 드라이버를 넣어서 APK가 약 1.4MB 커짐.
- **SSL**: 사용 안 함 / 인증서 확인 없이 암호화(`sslmode=require`, `useSsl&trustServerCertificate`) / 인증서 확인(시스템 CA).
  드라이버의 호스트 이름 확인은 Android에 없는 `javax.naming`을 써서, PostgreSQL은 `verify-full`에 `AndroidHostnameVerifier`로
  호스트 이름까지 확인하고, MariaDB는 인증서 체인만 확인하고 호스트 이름은 확인하지 않음(`disableSslHostnameVerification`).
- **SSH 터널**: 등록한 SSH 호스트로 `ssh -N -L 127.0.0.1:<빈 포트>:<호스트>:<포트> <SSH 호스트>`를 실행하고 포트가 열리면 그
  포트로 접속. 저장한 SSH 비밀번호는 SSH 탭처럼 전달하고, 모르는 호스트 키면 호스트 키 확인 창을 띄운 뒤 다시 접속.
  탭을 닫거나 연결을 끊으면 ssh도 끝냄. openssh 패키지가 있어야 함.
- 연결 시간 제한 15초, 서버 버전을 상태에 표시. 로그인 실패(SQLSTATE `28…`)면 기억한 비밀번호를 지우고 다시 물음.
- 연결은 탭마다 하나이고 탭의 스레드 하나에서만 씀(작업이 겹치지 않음). 앱 프로세스가 살아 있는 동안 유지되고
  (언어를 바꿔 화면을 다시 만들어도), 앱을 다시 열면 탭과 SQL만 돌아오고 연결은 탭을 볼 때 다시 함.

## 저장 위치

- `~/.termux/db_connections.json`: 접속 목록(비밀번호 없음). 설정 내보내기/가져오기에 포함
- 비밀번호: SSH 비밀번호와 같은 Android Keystore 암호화 저장소(`db-connection:<id>`), 내보내지 않음
- `~/.termux/db_queries.json`: 저장한 쿼리. 설정 내보내기에 포함
- `~/.termux/db_history.json`: 실행 기록(최근 200개). 내보내지 않음
- 앱 설정 `db_tabs`: 열린 탭의 접속과 SQL(탭당 10만 자까지), 최대 10개

## 단축키 (DB 탭)

| 키 | 동작 |
|---|---|
| Ctrl+Enter / Ctrl+Shift+Enter | 문장 실행 / 모두 실행 |
| Ctrl+Space | 자동 완성 |
| Ctrl+E | EXPLAIN |
| Ctrl+1 / 2 / 3 | 구조 / SQL / 결과(좁은 화면) |
| Esc | 실행 취소(실행 중), 자동 완성 닫기 |
| Ctrl+Tab / Ctrl+Shift+Tab | 다음 / 이전 탭(터미널·웹·DB 모두) |
| Ctrl+Shift+T / Ctrl+Shift+W | 새 로컬 셸 / DB 탭 닫기 |

## 구조

| 클래스 | 역할 |
|---|---|
| `DbType`, `DbConnectionConfig`, `DbConnectionStore` | 종류별 기본 포트·이름 따옴표, 접속 정보와 JSON, 저장과 비밀번호 |
| `DbSession`, `JdbcSession`, `SqliteSession` | 실행, 취소, 트랜잭션, 메타데이터(스키마, 테이블, 컬럼, 인덱스, 외래 키, DDL), 스트리밍(`stream`) |
| `DbConnector`, `SshTunnel`, `AndroidHostnameVerifier` | 터널을 열고 세션 만들기 |
| `SqlLexer`, `SqlScript`, `SqlSafety`, `SqlFormatter` | 토큰, 문장 나누기와 커서의 문장, 쓰기/위험 판단, 정렬 |
| `QueryResult`, `ResultExport`, `QueryHistory` | 결과, 형식별 내보내기(`Writer`로 한 행씩), 기록과 저장한 쿼리 |
| `DbTabs`, `DbTab`, `DbTabView` | 탭 목록과 표시, 탭의 상태(프로세스 동안 유지), 탭 화면 |
| `ResultGridView` | 캔버스로 그리는 결과 표(고정 머리글과 행 번호, 정렬, 너비, 선택, 표시 색) |
| `SqlCompletion`, `DbDataEditor`, `DbTableExport`, `DbCsvImport`, `DbDialogs` | 자동 완성, 데이터 편집, 백그라운드 내보내기, CSV 가져오기, 접속·내보내기·기록 창 |

`TerminalTabsController`(포크 전용)가 터미널 탭 → 웹 탭 → DB 탭 순서로 탭 바를 만들고, 웹·DB 탭이 보이는 동안 터미널,
하단 키, 특수 키 패널, 찾기 줄을 숨깁니다.

## 한계

- 기기에서 직접 확인하지 못했습니다(개발 환경에 에뮬레이터가 없음). 드라이버는 JVM에서 실제 서버로, 화면은 Robolectric으로
  시험합니다(아래).
- 결과 셀 안에서 바로 고치지 않고 창으로 고칩니다. 바이너리 값 편집, 여러 결과 집합을 돌려주는 프로시저는 지원하지 않습니다.
- Oracle, SQL Server, Redis, MongoDB 등은 없습니다.
- CSV 가져오기는 홈 디렉터리 경로나 클립보드만 받습니다(파일 고르기 창 없음).
- 테이블 내보내기는 프로세스가 끝나면 멈춥니다(알림만 있고 포그라운드 서비스가 아님).

## 시험

- `SqlScriptTest`, `DbCoreTest`, `SqlEditingTest`: 문장 나누기, 안전 판단, 내보내기, 정렬, 별칭, CSV
- `SqliteSessionTest`(Robolectric): SQLite 세션
- `DbTabViewTest`(Robolectric): SQLite 접속으로 탭 열기 → 여러 문장 실행 → 구조 갱신 → 자동 완성 → 데이터 편집과 적용 → 탭 닫기
- `DbLiveTest`: 실제 서버. 환경 변수 `DEVSHELL_TEST_PG`, `DEVSHELL_TEST_MARIADB`에 `호스트:포트:데이터베이스:사용자:비밀번호`를
  주면 실행하고, 없으면 건너뜀. 메타데이터, 한도, 한글, 트랜잭션, 매개 변수, 스트리밍, 데이터 편집의 되돌리기와
  `SAVEPOINT`, 알림(`RAISE NOTICE`), 실행 취소를 확인

`DbLiveTest`가 쓰는 예제 테이블(PostgreSQL; MariaDB는 `serial` → `int auto_increment`, `text` → `varchar(100)`/`text`,
`jsonb` 컬럼 없이, 주문 2개):

```sql
create table customers (
  id serial primary key,
  name text not null,
  email text unique,
  created timestamptz default now(),
  note text
);
create table orders (
  id serial primary key,
  customer_id int references customers(id),
  total numeric(10,2),
  status varchar(20) default 'new',
  data jsonb
);
create index orders_status on orders(status);
create view big_orders as select * from orders where total > 100;
insert into customers (name, email) values ('김철수', 'kim@example.com'), ('Alice', 'alice@example.com');
insert into customers (name, email, note) values ('Bob', 'bob@example.com', E'line\ttab');
insert into orders (customer_id, total, status)
  select 1 + i % 3, 1.5 * i, case when i % 2 = 0 then 'paid' else 'new' end from generate_series(1, 500) i;
```

MariaDB는 `utf8mb4` 데이터베이스에 `--default-character-set=utf8mb4`로 넣어야 한글이 깨지지 않습니다.
