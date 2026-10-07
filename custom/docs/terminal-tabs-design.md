# 터미널 탭 설계 (여러 세션을 백그라운드로 유지하는 탭 UI)

상태: 1단계(`custom/patches/0004-*`, `0005-*`), 2단계(`0007-*`), 3단계(`0008-*`), 4단계(`0009-*`, 종료/벨 알림만), 탭 이름(`0010-*`) 구현됨.

## 1. 현재 구조

백그라운드 멀티 세션 기능 자체는 업스트림에 **이미 있습니다**. 없는 것은 탭 형태의 UI와 백그라운드 세션의 상태 표시입니다.

| 구성 요소 | 하는 일 | 위치 |
|---|---|---|
| `TermuxService` | 포그라운드 서비스. `mTermuxSessions`(세션 목록)를 갖고 있고, 액티비티가 종료돼도 셸 프로세스를 유지. wakelock, "N sessions" 알림 | `app/.../TermuxService.java` |
| `TermuxSession` / `TerminalSession` | 세션 하나 = PTY 하나 + `TerminalEmulator`(화면 버퍼, 스크롤백) | `termux-shared`, `terminal-emulator` |
| `TerminalView` | 화면에 **하나만** 있음. `attachSession()`으로 표시할 세션을 바꿈(버퍼는 세션에 있으니 전환 비용이 작음) | `terminal-view/.../TerminalView.java:296` |
| `TermuxTerminalSessionClient` | 세션 이벤트 처리(`onTextChanged`, `onTitleChanged`, `onBell`, `onSessionFinished`), `setCurrentSession`, `addNewSession`, `switchToSession`. 세션은 최대 `MAX_SESSIONS = 8`개 | `app/.../terminal/TermuxTerminalSessionClient.java` |
| `TermuxSessionsListViewController` | 왼쪽 드로어의 `ListView`에 세션 목록을 보여줌(탭 = 전환, 길게 누르기 = 이름 바꾸기) | `app/.../terminal/` |
| 단축키 | `termux.properties`의 `shortcut.create-session`, `next-session`, `previous-session`, `rename-session` | `TermuxTerminalViewClient.java:457` |

현재 구조의 한계:

1. 세션을 전환하려면 드로어를 열어야 하고, 지금 어떤 세션들이 떠 있는지 한눈에 보이지 않습니다.
2. 백그라운드 세션에 새 출력이나 벨이 있어도 표시되지 않습니다. 화면에 보이지 않는 세션은 `onTextChanged`에서 아무 처리도 하지 않습니다.
3. 액티비티가 `onDestroy`되면 서비스는 세션 클라이언트를 `mTermuxTerminalSessionClientBase`(아무것도 하지 않음)로 바꿉니다(`TermuxService.java:695`). 그래서 그동안 생긴 이벤트는 남지 않습니다.

## 2. 목표와 비목표

**목표**

- 터미널 위(또는 아래)에 탭 바를 두고 탭으로 세션 생성, 전환, 닫기, 이름 변경
- 백그라운드 탭에 상태 표시: 새 출력(●), 벨(🔔), 종료됨(취소선과 종료 코드 색상)
- 액티비티가 멈춰 있거나 다시 생성된 동안 생긴 상태도 유지
- 하드웨어 키보드 단축키로 탭 이동(`Ctrl+Alt+1..9`는 업스트림에 이미 있음)
- 업스트림 업그레이드에 부담이 적도록 업스트림 파일 수정을 최소화

**비목표**

- 앱 프로세스가 죽은 뒤 세션 복원: PTY와 자식 프로세스는 직렬화할 수 없습니다. 필요하면 `tmux`를 쓰세요.
- 화면 분할(여러 `TerminalView`를 동시에 표시)
- 드로어 제거: 업스트림 동작을 그대로 두고 탭 바와 함께 씁니다.

## 3. 핵심 결정

### 3.1 `TerminalView`는 하나로 두고, 탭은 `attachSession()`으로 전환

ViewPager에 세션마다 `TerminalView`를 하나씩 두는 방식은 쓰지 않습니다.

- IME 연결, 포커스, 하드웨어 키 처리, 커서 깜빡임, 한글 조합(패치 0001)이 모두 `TerminalView` 하나를 전제로 합니다.
- 좌우 스와이프가 터미널의 텍스트 선택, 스크롤, 드로어 열기 제스처와 충돌합니다.
- 화면 버퍼는 이미 세션(`TerminalEmulator`)에 있으므로 뷰를 여러 개 둘 이유가 없습니다.

결론적으로 탭 바는 세션 목록을 보여주는 **또 하나의 뷰**일 뿐입니다. 드로어 `ListView`와 같은 데이터(`TermuxService.getTermuxSessions()`)를 보여줍니다.

### 3.2 탭 바 위젯: `HorizontalScrollView` 안에 `LinearLayout`

세션 수가 많지 않으므로(8개, 설정으로 늘려도 수십 개) RecyclerView나 Material `TabLayout` 의존성을 추가하지 않습니다. 목록이 바뀌면 탭 뷰를 다시 그립니다. 탭 뷰는 재사용하고, 개수가 달라질 때만 추가하거나 제거합니다.

### 3.3 탭 상태는 포크 전용 싱글턴에 세션을 약한 참조 키로 보관

탭 상태(새 출력 여부 등)는 액티비티보다 오래 살아 있어야 합니다(3번 한계). 그래서 액티비티에 두지 않고 프로세스 범위의 포크 전용 객체에 둡니다. 업스트림의 `TermuxSession`(`termux-shared`)에 필드를 추가하지 않으면 패치가 그만큼 줄어듭니다.

```java
// 포크 전용: app/src/main/java/com/termux/app/terminal/tabs/TerminalTabStates.java
public final class TerminalTabStates {
    public static final class State {
        public boolean hasUnreadOutput;
        public boolean hasBell;
    }
    // 제거된 세션은 GC되면 저절로 빠지므로 따로 지울 필요가 없음
    private static final Map<TerminalSession, State> sStates = new WeakHashMap<>();
    // 탭 바 갱신 대상. 액티비티를 붙잡지 않도록 약한 참조
    private static WeakReference<TerminalTabsController> sTabsController;

    /** 상태가 false에서 true로 바뀔 때만 리스너를 호출합니다(출력마다 다시 그리지 않음). */
    public static void markOutput(TerminalSession s) { ... }
    public static void markBell(TerminalSession s) { ... }
    public static void markSeen(TerminalSession s) { ... }   // 현재 탭이 되면 초기화
    public static State get(TerminalSession s) { ... }
}
```

`onTextChanged`는 출력이 들어올 때마다 호출되므로 이 경로에서는 boolean 하나만 비교합니다. UI 갱신은 상태가 **바뀔 때만** 합니다.

## 4. 구성 요소

### 4.1 새 파일 (포크 전용, `custom/fork-files.txt`에 추가)

| 파일 | 역할 |
|---|---|
| `app/.../terminal/tabs/TerminalTabStates.java` | 3.3의 상태 저장소 |
| `app/.../terminal/tabs/TerminalTabsController.java` | 탭 바 뷰 생성, 갱신, 클릭/길게 누르기 처리. `TermuxSessionsListViewController`와 같은 역할 |
| `app/.../terminal/tabs/TrackingTerminalSessionClient.java` | `TermuxTerminalSessionClientBase`를 상속. 액티비티가 없을 때도 `onTextChanged`/`onBell`을 `TerminalTabStates`에 기록 |
| `app/src/main/res/layout/view_terminal_tabs.xml` | 탭 바(`HorizontalScrollView` + `LinearLayout` + `+` 버튼) |
| `app/src/main/res/layout/item_terminal_tab.xml` | 탭 하나: 상태 점, 번호와 제목, 닫기(×) 버튼 |
| `app/src/main/res/drawable/terminal_tab_background.xml`, `ic_terminal_tab_*.xml` | 탭 배경(선택 시 아래 강조선), `+`/× 아이콘 |
| `app/src/main/res/values/fork_terminal_tabs.xml` | 탭 관련 크기, 색, 문자열(업스트림 `values/*.xml`을 건드리지 않기 위해) |

### 4.2 업스트림 파일 수정 (패치 `0004-*`, `0007-*`, 모두 작은 hook)

| 파일 | 변경 |
|---|---|
| `activity_termux.xml` | `DrawerLayout`의 콘텐츠 자식을 세로 `LinearLayout`(`<include layout="@layout/view_terminal_tabs"/>` + `TerminalView`)로 감쌉니다. 탭 바 위치가 `bottom`이면 순서만 바꿉니다. |
| `TermuxActivity.java` | `onServiceConnected`에서 `TerminalTabsController` 생성. `termuxSessionListNotifyUpdated()`에서 탭 바도 갱신. `onReload()`에서 설정 다시 적용 |
| `TermuxTerminalSessionClient.java` | 아래 4.3 이벤트 hook |
| `TermuxService.java` | `mTermuxTerminalSessionClientBase`를 `new TrackingTerminalSessionClient()`로 교체(한 줄) |
| (3단계) properties 클래스는 고치지 않음 | 탭 설정은 포크 전용 `TerminalTabsSettings`가 읽음(6장) |

### 4.3 이벤트 흐름

```
PTY 출력 → TerminalSession(메인 스레드) → client.onTextChanged(s)
   ├─ 액티비티 연결됨 : TermuxTerminalSessionClient
   │     s == 현재 세션 → TerminalView.onScreenUpdated()   (기존 동작)
   │     s != 현재 세션 → TerminalTabStates.markOutput(s)  ← isVisible() 검사보다 먼저
   └─ 액티비티 없음   : TrackingTerminalSessionClient → TerminalTabStates.markOutput(s)
TerminalTabStates 상태 변화 → TerminalTabsController.refresh()  (탭 수가 적어서 전체를 다시 바인딩, 스크롤은 하지 않음)
```

`TermuxTerminalSessionClient`에서 바꿀 곳:

- `onTextChanged`: 지금은 `if (!mActivity.isVisible()) return;`이 맨 앞에 있습니다. 현재 세션이 아니면 `markOutput`을 먼저 호출한 뒤 return하도록 바꿉니다. 액티비티가 `onStop`일 때도 기록해야 하기 때문입니다.
- `onBell`: 백그라운드 세션이면 `markBell`. 진동/소리는 업스트림처럼 어느 세션에서 울려도 냅니다(백그라운드 알림 역할).
- `onTitleChanged`, `onSessionFinished`: `termuxSessionListNotifyUpdated()`가 이미 호출되므로 탭 바도 따라 갱신됩니다. 다른 세션 제목이 바뀔 때마다 뜨는 토스트는 탭 바가 켜져 있으면 끕니다(탭에 제목이 보이므로).
- `setCurrentSession`: `markSeen(session)` 후 선택된 탭이 보이도록 스크롤(`checkAndScrollToSession` 옆에 추가). 탭 바로 바로 전환할 수 있으므로 전환 토스트(`notifyOfSessionChange`)도 탭 바가 켜져 있으면 생략합니다.
- `onSessionFinished`의 "- exited" 토스트는 그대로 둡니다.

## 5. UI와 동작

```
┌──────────────────────────────────────────────┐
│ [1 ~ ×] [2 vim ●] [3 build 🔔] [4 ssh̶ ×]  [+] │  ← 탭 바(높이 36dp)
├──────────────────────────────────────────────┤
│ $ _                                          │
│              TerminalView                    │
└──────────────────────────────────────────────┘
```

| 동작 | 결과 |
|---|---|
| 탭 터치 | `setCurrentSession`. 이미 선택된 탭을 누르면 이름 바꾸기 대화상자 |
| 탭 길게 누르기 | 메뉴: 이름 바꾸기 / 왼쪽·오른쪽으로 이동 / 닫기 |
| × | 실행 중이면 확인 대화상자 후 `finishIfRunning()`, 프로세스가 끝나면 종료 코드와 상관없이 탭을 제거. 이미 종료된 세션은 바로 `removeFinishedSession` |
| `+` | `addNewSession(false, null)`. 길게 누르면 기존 드로어 버튼처럼 이름 있는 세션/failsafe 대화상자 |
| ⚙ (`+` 오른쪽) | 팝업 메뉴: Settings(설정 화면) / Sessions list(드로어 열기) / Show/hide keyboard / Edit termux.properties / Reload settings |
| 탭 제목 | `[번호] 라벨`(5.1). 최대 너비 160dp, 넘치면 말줄임 |
| 상태 표시 | 새 출력 ●(회색), 벨 🔔(강조색), 종료 코드 0이면 취소선, 0이 아니면 빨간 취소선(드로어와 같은 규칙) |

탭 순서 바꾸기는 탭을 길게 눌렀을 때 나오는 메뉴의 "Move left"/"Move right"로 합니다(3단계). `getTermuxSessions()`가 돌려주는 리스트를 `synchronized (service)` 안에서 `Collections.swap`하면 됩니다. 이 리스트는 드로어 어댑터가 쓰는 리스트와 같은 객체라서 드로어 순서도 같이 바뀝니다. 업스트림 코드는 바꿀 필요가 없습니다.

**⚙ 메뉴의 Edit termux.properties**: 새 세션에서 `$PREFIX/bin/sh -c`로 `$EDITOR`, `nano`, `vi` 중 처음 찾은 편집기로 `termux.properties`(없으면 `~/.termux/termux.properties`를 만들 경로)를 엽니다. 편집기가 정상 종료하면 `termux-reload-settings`를 실행하고, 세션은 종료 코드 0이라 자동으로 닫힙니다. 편집기가 없으면 설치 방법을 출력하고 종료 코드 1로 남습니다. **Reload settings**는 `termux-reload-settings`와 같은 브로드캐스트(`TermuxActivity.updateTermuxActivityStyling`)를 보냅니다.

### 5.1 탭 이름

탭 라벨은 다음 순서로 정합니다(`TerminalTabTitles.getLabel`). 알림 제목도 같은 라벨을 씁니다.

1. 사용자가 붙인 이름(`TerminalSession.mSessionName`)
2. 프로그램이 OSC 0/2(`printf '\e]0;제목\a'`)로 정한 제목(`TerminalSession.getTitle()`)
3. 자동 제목(`terminal-tab-auto-title`)
   - `process`(기본): 셸의 foreground 프로세스 이름(`vim`, `ssh` 등). 셸 자신이 foreground이면 작업 폴더 이름(홈은 `~`)
   - `cwd`: 항상 작업 폴더 이름
   - `off`: 번호만

**이름 바꾸기 대화상자**(`TerminalTabsController.showRenameDialog`)
- 여는 방법: 선택된 탭 다시 누르기, 탭 길게 누르기 → Rename, 드로어 길게 누르기, `Ctrl+Alt+R`. 업스트림 `renameSession()`이 이 대화상자로 넘깁니다.
- 현재 이름이 전체 선택된 채로 열리고 키보드가 바로 뜹니다.
- 입력칸 힌트에는 이름이 없을 때 보일 라벨(2나 3)을 보여 줍니다.
- `Reset` 버튼이나 빈 이름으로 저장하면 이름을 지우고 2나 3으로 돌아갑니다.

**자동 제목 구현**(`TerminalTabTitles`)
- `/proc/<셸 pid>/stat`의 `tpgid`(터미널의 foreground 프로세스 그룹)를 읽고, 그 값이 셸 pid가 아니면 `/proc/<tpgid>/comm`을 읽습니다.
- `comm`에 공백이나 괄호가 들어갈 수 있으므로 필드는 마지막 `)` 뒤에서 셉니다.
- 세션에 출력이 있을 때 세션마다 최대 1초에 한 번 확인합니다. 마지막 출력이 직전 확인 바로 뒤에 오면 1초 뒤에 한 번 더 확인해서, 프로그램이 끝나고 프롬프트가 나온 뒤 제목이 남아 있지 않게 합니다.
- 바뀌었을 때만 탭을 다시 그립니다. 종료된 세션은 마지막 제목을 유지합니다.
- 드로어 목록은 업스트림 코드 그대로라 자동 제목을 보여 주지 않습니다.

## 6. 설정 (`~/.termux/termux.properties`, 3단계)

```properties
terminal-tabs = top              # top | bottom | off   (기본 top)
terminal-tabs-show-close = true  # 탭의 × 버튼 표시 (기본 true)
max-sessions = 8                 # 1..32 (기본 8, 업스트림의 MAX_SESSIONS와 같음)
terminal-session-notifications = all  # all | exit | bell | off (4단계)
terminal-tab-auto-title = process     # process | cwd | off (5.1)
```

- `termux-reload-settings`로 바로 반영됩니다(`TermuxActivity.reloadActivityStyling` → `TerminalTabsController.reloadSettings`).
- 업스트림 properties 클래스(`TermuxPropertyConstants`, `TermuxSharedProperties`)는 고치지 않았습니다. 그 클래스의 메모리 캐시에는 업스트림이 정의한 키만 들어가므로, 포크 전용 `TerminalTabsSettings`가 `SharedProperties.getPropertiesFromFile()`로 파일을 직접 읽습니다.
- 잘못된 값은 로그를 남기고 기본값을 씁니다.
- `terminal-tabs = off`이면 탭 바를 숨기고, 세션 전환/제목 변경 토스트를 다시 보여 줍니다.
- `max-sessions`는 앱에서 새 세션을 만들 때(`addNewSession`)만 제한합니다. 플러그인이 만드는 세션은 업스트림처럼 제한하지 않습니다.

## 7. 백그라운드 동작

- 프로세스 유지: 기존 포그라운드 서비스와 wakelock을 그대로 씁니다. 별도 작업은 없습니다.
- 서비스 알림 문구(`buildNotification`)를 "3 sessions (1 unread, 1 bell)"처럼 바꾸는 것은 **하지 않았습니다**. 쓸모에 비해 업스트림 `TermuxService`의 알림 코드를 더 고쳐야 하기 때문입니다.
- **종료/벨 알림(4단계)**: 액티비티가 보이지 않을 때(`onStop` 이후, 또는 액티비티가 없을 때) 세션이 끝나거나 벨이 울리면 알림을 보냅니다. 포크 전용 `TerminalSessionNotifications`가 처리합니다.
  - 알림 제목은 탭과 같은 `[번호] 이름`, 내용은 `Bell` / `Exited (code 1)` / `Exited (signal 9)`.
  - 채널은 `Termux Sessions`(중요도 기본, 소리/진동). 안드로이드 설정에서 채널별로 끌 수 있습니다.
  - 세션마다 알림은 하나만 쓰고 갱신합니다. 벨 알림은 그 세션을 다시 볼 때까지 한 번만 보냅니다.
  - 알림을 누르면 세션 handle을 extra로 담은 인텐트로 액티비티를 엽니다. `TermuxActivity.onCreate`/`onNewIntent`에서 그 세션을 현재 세션으로 저장하거나 바로 전환합니다.
  - 액티비티가 보이는 상태에서 그 세션이 표시되면(`TerminalTabsController.onCurrentSessionChanged`) 알림을 지웁니다.
  - 알림을 보내지 않는 경우: 서비스 알림의 Exit로 모든 세션을 끝낼 때(`wantsToStop()`), 결과를 플러그인에 돌려주는 플러그인 명령 세션이 끝날 때.
  - 설정: `terminal-session-notifications = all | exit | bell | off` (기본 `all`).
  - 이벤트 경로: 액티비티가 살아 있으면 `TermuxTerminalSessionClient.onBell`/`onSessionFinished`, 없으면 `TrackingTerminalSessionClient`.

## 8. 주의할 점

1. **한글 조합(패치 0001)**: 조합 중에 세션을 바꾸면 조합하던 글자는 **버립니다**(패치 0005). `attachSession()`이 `restartInput`을 하고, 이전 입력 연결은 IME가 뒤늦게 보내는 `commitText`/`finishComposingText`를 무시합니다(`mInputConnectionGeneration`).
2. **액티비티 재생성**(회전, 테마 변경): 탭 바는 상태를 갖지 않고 `TerminalTabStates`와 서비스 목록으로 다시 그리므로 상태가 사라지지 않습니다.
3. **스레드**: `TerminalSession` 콜백은 메인 스레드 Handler에서 오므로 탭 갱신을 바로 호출합니다. 맵 접근은 `synchronized`로 감쌉니다.
4. **현재 세션이 제거될 때**: 기존 `removeFinishedSession`이 인접 세션을 고르므로 그대로 따르고, 탭 바는 notify만 받습니다.
5. **세로 공간**: 가로 모드나 키보드가 올라왔을 때 탭 바가 화면을 줄입니다. 높이는 `terminal-toolbar-height` 배율을 따르고, `off` 옵션을 둡니다. `TermuxActivityRootView`의 키보드 여백 계산은 레이아웃 높이 기준이라 영향이 없는지 확인해야 합니다.
6. **블랙 UI, 다크 모드**: `isUsingBlackUI()`에 따라 배경과 글자 색을 바꿉니다(드로어와 같은 규칙).
7. **Android TV(leanback)**: D-pad 포커스로 탭 바에 갈 수 있게 합니다. 기본값은 터미널에 포커스를 둡니다.
8. **접근성**: 탭마다 `contentDescription`("세션 2, vim, 새 출력 있음")을 둡니다.

## 9. 단계별 작업

| 단계 | 내용 | 업스트림 패치 |
|---|---|---|
| 1 ✅ | 탭 바(전환, 새로 만들기, 닫기, 이름 바꾸기), 드로어와 동기화, 세션 전환 시 조합 중인 글자 버리기 | layout, Activity, SessionClient, TerminalView |
| 2 ✅ | 새 출력/벨 표시 + 액티비티 없을 때 추적, 토스트 정리 | SessionClient, Service(한 줄), Activity |
| 3 ✅ | `termux.properties` 설정(`terminal-tabs`, `terminal-tabs-show-close`, `max-sessions`), 순서 바꾸기 | Activity, SessionClient (properties 클래스는 고치지 않음) |
| 4 ✅ | 백그라운드 종료/벨 알림 (서비스 알림 요약은 하지 않음) | Activity, SessionClient, Service(생성자 인자) |
| 탭 이름 ✅ | 선택된 탭 다시 눌러 이름 바꾸기, 이름 대화상자 개선, 자동 제목(5.1) | SessionClient(`renameSession`, `onTextChanged`) |

각 단계는 커밋한 뒤 `custom/update-patches.sh`로 패치를 다시 만들고, `custom/README.md` 수정 사항 표에 항목을 추가합니다.

## 10. 검증

- 세션 8개를 만들어 탭 터치, `+`, ×, 드로어 선택, `Ctrl+Alt+↑/↓/1..9`로 전환할 때 탭 바와 드로어 선택 상태가 일치하는지
- 백그라운드 탭에서 `sleep 5; echo hi`, `sleep 5; printf '\a'` 실행 후 ● / 🔔이 표시되고, 전환하면 사라지는지
- 홈으로 나간 상태(onStop)와 최근 앱에서 지운 상태(onDestroy, 서비스 유지)에서 위 출력을 만든 뒤 돌아왔을 때 상태가 남아 있는지
- `exit` / `exit 1` 세션의 탭 표시와 자동 제거 규칙(종료 코드 0/130이면 제거)
- 회전, `termux-reload-settings`, 블랙 UI, 하드웨어 키보드로 한글을 조합하는 중에 탭 전환
- 세션 30개 이상(`max-sessions` 상향)에서 탭 바 스크롤과 출력이 많은 세션(`yes`)이 백그라운드에 있을 때 UI 부하
