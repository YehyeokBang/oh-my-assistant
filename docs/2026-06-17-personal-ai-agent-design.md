# 개인 AI 에이전트 — 설계 문서

> 작성일: 2026-06-17
> 기반 스펙: 「개인 AI 에이전트 — 보강된 MVP 스펙」(같은 날 작성)
> 이 문서는 그 스펙을 브레인스토밍으로 구체화한 결과다. **원래 스펙과 달라지거나 명확해진 부분**을 중심으로 정리하고, 미해결 항목은 마지막에 별도로 묶는다.

---

## 0. 이번 브레인스토밍으로 확정된 핵심 결정

스펙에 모호하게 섞여 있던 부분을 다음과 같이 확정했다.

| # | 결정 | 의미 | 폐기한 대안 |
|---|---|---|---|
| 1 | **병렬화 = 일회성 서브에이전트 + 역할 주입** | 평소엔 일꾼이 없다가, 작업이 들어오면 그 순간 필요한 만큼 워커를 띄우고 끝나면 버린다. 워커는 자기 세션이 없고 "너는 ○○ 전문가야"라는 역할 프롬프트만 주입받는다. | (가) 상주(常駐) 전문가단 — 전문가별로 claude 세션을 상시 유지하며 기억을 쌓는 방식. MVP엔 과함 |
| 2 | **Slack 스레드 = 세션** | 스레드 안에서 답하면 그 스레드 맥락을 이어받는다(같은 `session_id`). 메인 채널에 새 메시지로 물으면 새 세션. 병렬 결과는 **같은 스레드 안 여러 메시지**로 보고(Slack은 스레드 중첩 불가). | "작업당 1 스레드" — Slack은 스레드 안에 스레드를 못 만들므로 폐기 |
| 3 | **대화 기억 원본 = SQLite** | claude -p를 매번 **무상태(stateless)** 로 호출한다. 부를 때마다 SQLite에서 해당 스레드 히스토리를 꺼내 프롬프트로 다시 먹인다. claude 내부 세션 저장소(`--resume`)에 의존하지 않는다. | `--resume` 기반 — claude 내부 저장소에 강결합 → 폴백 시 anthropic-api가 그 세션을 못 읽음. "교체 용이성" 목표와 충돌 |
| 4 | **히스토리는 오케스트레이터만 받는다** | 스레드 대화의 주체(오케스트레이터)만 SQLite 전체 히스토리를 받는다. 병렬 워커는 히스토리 없이 "작업 조각 + 역할 프롬프트"만 받는다(information hygiene). | — |

이 4가지가 아키텍처 전반을 결정한다.

---

## 1. 목표와 비목표

- **대상**: 백엔드 개발자 본인(단일 사용자)
- **핵심 가치**: 병렬화 가능한 작업의 위임 — 내가 다른 일을 하는 동안 비서가 조사/분석/초안을 동시에 진행하고 결과만 보고
- **MVP 비목표**: 멀티유저, 웹 UI, 음성, 자율 행동(승인 없는 파일 변경/배포), 장기 시맨틱 메모리/RAG

---

## 2. 아키텍처 (포트 & 어댑터)

```
                        Slack (Socket Mode, WebSocket)
                                  │
                                  ▼
                         ┌─────────────────┐
                         │  Chat Adapter   │  onMessage / send / sendLong / replyInThread
                         └────────┬────────┘
                                  ▼
                  ┌───────────────────────────────┐
                  │      Core / Orchestrator      │
                  │  - 세션 식별 (thread_ts)        │
                  │  - 단발 vs 병렬 판단            │
                  │  - 작업 분해 + 동시성 상한       │
                  │  - 결과 머지 + 검증 패스         │
                  └───┬───────────┬───────────┬────┘
                      ▼           ▼           ▼
              ┌────────────┐ ┌──────────┐ ┌───────────┐
              │  LLM Port  │ │ Memory   │ │ Scheduler │
              │ complete() │ │  Port    │ │ (cron)    │
              │ stream()   │ │getHistory│ └─────┬─────┘
              │spawnWorkers│ │ append() │       │
              └──┬──┬──┬───┘ └────┬─────┘       ▼
                 ▼  ▼  ▼          ▼        백업 스냅샷
            claude  api ollama  SQLite      → S3
            -cli  (AI SDK)     (WAL,JSONB)
```

기존에 익숙한 헥사고날 구조를 그대로 적용한다. 모든 외부 의존성을 어댑터로 격리해 교체 용이성을 구조로 보장한다.

### 2.1 Chat Adapter (Slack)

- **인터페이스**: `onMessage(handler)`, `send(chatId, text)`, `sendLong(chatId, text)`, `sendTyping()`, `replyInThread(threadTs, text)`
- **전송 책임**: 메시지 분할(Slack 40,000자 한도 기준), 스레드 라우팅
- **Socket Mode**: 공개 HTTP 엔드포인트 없이 WebSocket으로 이벤트 수신 → 인바운드 포트/도메인/리버스프록시 불필요
- **세션 매핑**: 들어온 이벤트의 `thread_ts`(스레드 루트면 자기 `ts`)를 `session_id`로 사용
- **인증**: Slack user ID 화이트리스트(본인만 응답)
- **SDK**: Bolt for JS (`@slack/bolt`), Socket Mode
- 토큰: `xoxb-`(bot) + `xapp-`(app-level)
- 스코프: `app_mentions:read`, `chat:write`, `im:history`, `im:write` 등 (셋업 시 확정)

### 2.2 Core / Orchestrator

비서의 두뇌. 다음 순서로 동작한다.

1. **세션 식별**: `session_id = thread_ts`
2. **히스토리 적재**: `Memory.getHistory(session_id)` → 중립 `messages[]` 구성
3. **단발 vs 병렬 판단**:
   - MVP는 **명시 트리거 + 휴리스틱**으로 시작 (예: "조사해줘", "후보 N개", "각각" 등 + 길이/복수성 신호). LLM 기반 의도분류는 비용·지연 때문에 후순위 (→ §11)
4. **분해(병렬일 때)**: 독립적인 하위 작업으로 쪼개고, 각 작업에 **역할 프롬프트 + 필요한 컨텍스트만** 부여
5. **실행**:
   - 단발: `LLM.complete(messages)`
   - 병렬: `LLM.spawnWorkers(tasks[])` — 동시성 상한 적용
6. **보고**: 진행 신호와 각 워커 결과를 **같은 스레드에 순차 메시지**로 게시(§5 참고)
7. **머지 + 검증 패스**: 충돌/불일치 점검 후 종합 메시지 게시
8. **영속화**: 입·출력 및 작업 메타데이터를 `Memory.append()`

### 2.3 LLM Port (백엔드 추상화)

- **인터페이스**
  - `complete(messages, opts)` — 단발 응답
  - `stream(messages, opts)` — 스트리밍(내부 진행 추적용; Slack 표면은 §5)
  - `spawnWorkers(tasks[], opts)` — 병렬 워커 실행. 각 task = `{ role, prompt, context }`
- **중립 형식**: Core는 `messages[]`(`[{role, content}, …]`)까지만 만든다. **백엔드별 변환은 어댑터의 책임.**
  - `claude-cli` 어댑터: `messages[]` → `-p` 프롬프트 문자열로 변환, `--output-format stream-json` 파싱, `--allowedTools` 화이트리스트 적용
  - `anthropic-api` 어댑터: `messages[]` → Anthropic API 배열로 변환 (Vercel AI SDK `@ai-sdk/anthropic`)
  - `openai` / `google` / `ollama`: 동일 인터페이스, 설정으로 전환
- **모델 ID는 설정값으로 분리**(모델 deprecation 대비 핀버전 갱신 용이)
- **라우팅 규칙(설정)**: 오케스트레이터 = 고성능 모델, 워커 = 빠르고 싼 모델
- **폴백 체인**: `claude-cli → anthropic-api → openai`
- **비용 가드**: 시간당/일별 요청·동시성 상한, 초과 시 큐잉 또는 알림

### 2.4 Memory / Storage Port

- **인터페이스**: `getHistory(sessionId)`, `append(sessionId, message)`, `recordTask(task)`, `updateTask(id, patch)`
- **"꺼내는 책임"을 이 포트에 캡슐화** — 흩어진 SQL 쿼리가 코드 전반에 새지 않게 한다.
- 엔진: SQLite(WAL 모드, JSONB)
- 스키마는 §6.

### 2.5 Scheduler

- cron 기반 백업 스냅샷 트리거(§9). MVP에선 이 한 가지 용도.

### 2.6 페르소나 (씨앗만)

- MVP는 단일 페르소나. 다만 persona(시스템 프롬프트 + 모델 + 도구권한)를 **하드코딩하지 않고 설정값으로 분리**해 오케스트레이터에 주입한다.
- 그러면 나중에 Slack 채널/스레드별 다른 페르소나·모델을 **설정 추가만으로** 부여 가능.
- 결정 #1의 "역할 주입"과 동일 메커니즘: 워커도 같은 방식으로 역할 프롬프트를 받는다.

---

## 3. 동시성 / 자원 (2 vCPU / ~10GB)

- 각 `claude -p`는 별도 프로세스이며 대부분 **I/O 바운드**(모델 응답 대기)다. CPU 코어 수보다 많은 동시 실행이 가능하지만 메모리가 제약이다.
- **초기 동시성 상한: 워커 3개**(보수적 시작). 인스턴스에서 실측 후 조정.
- 상한 초과 작업은 큐잉. 과병렬화(단순 작업 10병렬)·과소병렬화(독립 작업 순차) 둘 다 회피.
- 워커 **타임아웃** 필수(예: 작업당 N분). 초과 시 해당 워커만 실패 처리하고 부분 결과로 머지(§7).

---

## 4. 데이터 흐름

### 4.1 단발 작업

```
Slack DM "이 PR 설명 초안 만들어줘"
  → Core: session_id=thread_ts, getHistory()
  → LLM.complete(messages)         [claude-cli]
  → append(in), append(out)
  → Slack로 응답(긴 출력은 sendLong 분할)
```

### 4.2 병렬 작업

```
Slack "채팅앱 마지막 메시지 프리뷰, 업계 표준 조사해줘"
  → Core: 병렬 판단 → 분해
        [카카오 조사][라인 조사][왓츠앱 조사]  (각자 역할+컨텍스트만)
  → 스레드에 "🔎 조사 시작 (3건)" 게시 + tasks 레코드 생성(parent + 3 children)
  → LLM.spawnWorkers(tasks, {concurrency:3})
        ├─ 📊 카카오 결과  → 완료되는 대로 스레드에 게시
        ├─ 📊 라인 결과
        └─ 📊 왓츠앱 결과
  → 머지 + 검증 패스
  → ✅ "종합하면…" 스레드에 게시
  → 모든 입·출력/작업 SQLite 저장
```

### 4.3 세션 이어가기

```
사용자가 위 스레드에 "라인은 좀 더 자세히" 라고 답글
  → Core: session_id = 같은 thread_ts
  → getHistory() 로 그 스레드 전체 맥락 복원
  → LLM.complete(messages)  ← 맥락 이어받음
```

---

## 5. Slack 보고 UX

- Slack은 토큰 단위 스트리밍이 약하므로(메시지 edit 기반) **진행 신호**로 대체한다.
- 패턴: **접수 → 진행중 → 완료**
  - 접수 즉시: `sendTyping()` 또는 "🔎 받았어요…" 한 줄
  - 병렬 시작: "조사 시작 (3건)" + 각 워커 완료 시 결과 메시지
  - 종료: 종합 메시지
- 모든 후속 메시지는 **같은 스레드**(`replyInThread(thread_ts, …)`)에 게시 → 메인 채널 오염 방지.
- 긴 출력은 Chat Adapter가 40,000자 기준 분할.

---

## 6. 데이터 모델 (SQLite, WAL, JSONB)

```sql
-- 대화 세션 메타 (thread_ts = id). 선택적이지만 페르소나 바인딩/최근활동에 유용.
CREATE TABLE sessions (
  id           TEXT PRIMARY KEY,   -- = thread_ts
  channel      TEXT NOT NULL,      -- 'slack' | 'telegram' | ...
  chat_id      TEXT NOT NULL,      -- DM/채널 식별자
  persona      TEXT,               -- 주입된 페르소나 키 (없으면 기본)
  created_ts   INTEGER NOT NULL,   -- epoch ms
  last_ts      INTEGER NOT NULL
);

CREATE TABLE messages (
  id          INTEGER PRIMARY KEY,
  session_id  TEXT NOT NULL,       -- = thread_ts
  ts          INTEGER NOT NULL,    -- epoch ms
  direction   TEXT NOT NULL,       -- 'in' | 'out'
  channel     TEXT NOT NULL,       -- 'slack' | ...
  llm_backend TEXT,                -- 'claude-cli' | 'anthropic-api' | ...
  payload     BLOB                 -- JSONB: 원문/도구호출/토큰·한도신호/지연 등
);
CREATE INDEX idx_messages_session_ts ON messages(session_id, ts);

CREATE TABLE tasks (               -- 병렬 작업 추적/분석용
  id          INTEGER PRIMARY KEY,
  parent_id   INTEGER,             -- 오케스트레이터 작업 (NULL이면 자신이 루트)
  session_id  TEXT NOT NULL,
  status      TEXT NOT NULL,       -- 'queued' | 'running' | 'done' | 'failed'
  role        TEXT,                -- 주입된 역할 (예: '카카오 조사 전문가')
  ts_start    INTEGER,
  ts_end      INTEGER,
  payload     BLOB                 -- JSONB: 프롬프트/결과/오류/비용신호
);
CREATE INDEX idx_tasks_session ON tasks(session_id);
CREATE INDEX idx_tasks_parent ON tasks(parent_id);
```

- 분석 친화성: 나중에 워크플로우 개선·불편 발굴 시 `payload`에 `json_extract`로 바로 질의.
- JSONB는 SQLite 3.45+ 필요(아래 환경 구성에서 최신 SQLite 번들 사용).

---

## 7. 에러 처리 / 폴백

- **워커 실패/타임아웃**: 해당 워커만 `failed`로 기록, 나머지 결과로 머지 진행. 종합 메시지에 "○○는 실패(사유)" 명시 — 조용히 누락 금지.
- **검증 패스**: 오케스트레이터가 워커 결과의 충돌·불일치를 점검 후 보고.
- **백엔드 폴백 (M4)**: claude-cli가 한도/레이트리밋 신호를 내면 `anthropic-api`로 전환 + 알림.
  - **결정 #3 덕분에 폴백은 같은 SQLite 히스토리를 그대로 anthropic-api에 먹이면 성립**(중간 전환은 "다음 호출부터" 적용; 진행 중 단일 호출을 끊어서 옮기지는 않음).
  - 트리거 방식(잔량 조회 vs 에러 감지)은 §11에서 확인 후 확정.

---

## 8. 보안 (MVP부터 필수, 협상 불가)

- 자가호스팅 에이전트는 코드 실행·파일 접근 권한을 가짐 → **권한 최소화 기본값**.
- claude -p 도구 권한: `--allowedTools` 명시 화이트리스트, **읽기 전용부터 시작**.
- `--dangerously-skip-permissions`는 **격리(컨테이너/전용 작업 디렉터리)에서만**.
- 단일 사용자 인증: Slack user ID 화이트리스트.
- 시크릿: 환경변수/SSM. 코드·DB에 평문 금지.
- 자가호스팅 에이전트의 공격면을 항상 가정(과거 자가호스팅 에이전트 RCE 사례 교훈).

---

## 9. 백업

- **주력**: cron 일일(또는 시간별) 스냅샷 → 기존 ap-northeast-2 S3 버킷 재사용.
  ```bash
  sqlite3 "$DB_PATH" "VACUUM INTO '/tmp/snap.db'"   # cp 금지 (트랜잭션 비안전)
  gzip -f /tmp/snap.db
  aws s3 cp /tmp/snap.db.gz "s3://<bucket>/agent/db-$(date +%Y%m%d-%H%M).gz"
  ```
- **비용**: 개인 DB는 보통 수십 MB~1GB 미만, 업로드 무료, 저장 ~$0.023/GB/월 → 사실상 월 몇 센트.
- **IAM**: 최소 권한(해당 prefix에 PutObject만).
- **업그레이드(선택)**: "거의 무손실"이 필요해지면 Litestream. 개인 비서엔 보통 과함.
- **복원 리허설**: 인스턴스 재생성 시 S3 최신 스냅샷으로 복구되는지 1회 검증(M3).

---

## 10. 인스턴스 환경 구성 (최신 Node 기준)

> 대상: Linux 인스턴스(2 vCPU / ~10GB), systemd 운영. 아래 버전은 **2026-06 기준 권장값**이며, 실제 설치 시 최신 LTS를 재확인한다(→ §11).

### 10.1 Node 설치 — fnm (권장)

`nvm`보다 빠르고 가벼운 `fnm`을 권장한다. 버전 핀과 자동 전환이 쉽다.

```bash
# fnm 설치
curl -fsSL https://fnm.vercel.app/install | bash
# 셸 재시작 또는 eval "$(fnm env --use-on-cd)" 적용 후

# Node 24 LTS 설치 (2026-06 기준 Active LTS)
fnm install 24
fnm default 24
fnm use 24
node -v        # v24.x 확인
```

> 프로젝트 루트에 `.nvmrc`(또는 `.node-version`)에 `24`를 적어두면 디렉터리 진입 시 자동 전환된다.

### 10.2 패키지 매니저 — pnpm (corepack)

```bash
corepack enable
corepack prepare pnpm@latest --activate
pnpm -v
```

### 10.3 TypeScript — 빌드 단계 없이 네이티브 실행

Node 24는 **타입 스트리핑(type stripping)** 으로 `.ts`를 빌드 없이 직접 실행한다.

```bash
node app.ts            # 타입만 제거하고 실행 (별도 트랜스파일 불필요)
node --watch app.ts    # 개발용 워치 모드
```

- 주의: 타입 스트리핑은 **타입 제거만** 한다. `enum`·`namespace` 등 런타임 변환이 필요한 TS 기능은 피하고 `as const`/유니온으로 대체한다(혹은 `--experimental-transform-types` 사용).
- 대안: 호환성 이슈가 생기면 `tsx`(`pnpm add -D tsx`, `tsx watch app.ts`)로 폴백.

### 10.4 SQLite

- 권장: `better-sqlite3` — 성숙하고 동기 API라 이 규모에 적합. 최신 SQLite(3.45+, JSONB) 번들.
  ```bash
  pnpm add better-sqlite3
  ```
- 무의존 대안: Node 내장 `node:sqlite`(최신 Node에 포함). 의존성 0이 중요하면 선택. MVP는 `better-sqlite3`로 시작.
- DB는 **WAL 모드**로 연다: `db.pragma('journal_mode = WAL')`.

### 10.5 claude CLI (claude-cli 어댑터 전제)

```bash
# 공식 CLI 설치 (전역)
npm i -g @anthropic-ai/claude-code
claude --version
```

- **인증은 수동 1회**: 인스턴스에서 `claude` 실행 후 구독 계정으로 로그인(헤드리스 환경에서는 로그인 절차 확인 필요).
- 어댑터는 `claude -p "<prompt>" --output-format stream-json --allowedTools <whitelist>` 형태로 subprocess 호출.
- **ToS 확인 필수**(→ §11): 스크립트가 claude -p를 상시 구동하는 것이 현재 구독 약관상 허용 경로인지 사전 확인.

### 10.6 핵심 npm 의존성

```bash
pnpm add @slack/bolt better-sqlite3
pnpm add @ai-sdk/anthropic ai          # M4 폴백용 (Vercel AI SDK)
pnpm add -D typescript @types/node tsx  # 타입/개발 도구
```

### 10.7 AWS CLI v2 (백업용)

```bash
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o awscliv2.zip
unzip awscliv2.zip && sudo ./aws/install
aws --version
# IAM 역할 또는 최소 권한 자격증명 구성
```

### 10.8 systemd 서비스

```ini
# /etc/systemd/system/ai-agent.service
[Unit]
Description=Personal AI Agent (Slack)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=agent
WorkingDirectory=/opt/ai-agent
# fnm로 설치한 node의 절대경로 사용 (which node 로 확인)
ExecStart=/home/agent/.local/share/fnm/aliases/default/bin/node /opt/ai-agent/src/main.ts
Restart=on-failure
RestartSec=5
EnvironmentFile=/opt/ai-agent/.env   # SLACK_BOT_TOKEN, SLACK_APP_TOKEN, 모델 설정 등
# 보안 하드닝 (작업 디렉터리 외 접근 제한)
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/opt/ai-agent /tmp
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now ai-agent
journalctl -u ai-agent -f   # 로그 추적
```

### 10.9 백업 cron

```bash
# crontab -e  (agent 사용자)
0 * * * * /opt/ai-agent/scripts/backup.sh >> /var/log/ai-agent-backup.log 2>&1
```

### 10.10 디렉터리 구조(제안)

```
/opt/ai-agent
├─ src/
│  ├─ main.ts                 # 진입점, 부트스트랩
│  ├─ core/                   # 오케스트레이터, 분해/머지/검증
│  ├─ adapters/
│  │  ├─ chat/slack.ts        # Chat Adapter
│  │  ├─ llm/claude-cli.ts    # LLM 어댑터들
│  │  ├─ llm/anthropic-api.ts
│  │  └─ memory/sqlite.ts     # Memory Port 구현
│  ├─ ports/                  # 인터페이스 정의
│  └─ config/                 # 페르소나/모델 라우팅/상한 설정
├─ scripts/backup.sh
├─ .env
├─ .node-version              # 24
└─ package.json
```

---

## 11. 확인 필요 / 미해결 (구현 전·중 확정)

| 항목 | 무엇을 확인 | 영향 |
|---|---|---|
| **ToS** | 스크립트가 공식 `claude -p`를 상시 구동하는 것이 현재 구독 약관상 허용 경로인지 (2026년 정책 유동적) | **차단 리스크** — M0 전 확인 권장 |
| **claude -p 한도/사용량 조회** | 잔량을 프로그래밍으로 조회 가능한지 → 폴백 트리거를 "잔량 조회" vs "레이트리밋 에러 감지" 중 무엇으로 | M4 폴백 설계 |
| **"비용 기록"의 실체** | 구독은 토큰 과금이 아니라 한도 차감 → stream-json이 주는 토큰 신호를 "한도 소진율"로 기록할지 정의 | messages.payload 스키마 |
| **claude -p 세션/병렬 실측** | 동시 워커 N개에서 메모리/지연 실측, 무상태 호출 시 프롬프트 재구성 토큰 비용 | §3 동시성 상한 확정 |
| **의도분류 방식** | MVP는 휴리스틱으로 시작, LLM 분류는 지연/비용 측정 후 도입 여부 결정 | Core 복잡도 |
| **모델 deprecation** | Anthropic 구버전 모델 retire 주기 → 어댑터의 모델 ID 설정값을 핀버전으로 갱신 | LLM Port 설정 |
| **Node/SQLite 버전 재확인** | 설치 시점 최신 LTS·SQLite 버전 재확인(JSONB 3.45+) | §10 환경 구성 |
| **정책 워치** | claude -p 별도 크레딧 풀 전환안(보류 상태) 재도입 여부 주시 — 재도입 시 폴백 헤지가 실제 발동 | 비용 통제 |

---

## 12. 마일스톤

| 단계 | 내용 | 검증 |
|---|---|---|
| **M-1 (스파이크)** | `claude -p`의 (a) 병렬/자원 (b) 무상태 호출+히스토리 재구성 (c) stream-json 토큰·한도 신호 (d) ToS 확인 | §11의 ToS·실측·비용 항목 닫힘 |
| M0 | Slack 에코봇(Socket Mode) + SQLite 저장 + systemd | 메시지 왕복·DB 적재 |
| M1 | LLM Port(claude-cli) 단발 응답 + Memory Port(getHistory/append) | 질문→답변, 스레드 맥락 유지, 신호 기록 |
| M2 | 병렬 위임(일회성 워커 + 역할 주입) + 머지/검증 + 스레드 보고 | "업계 표준 조사" 시나리오 통과 |
| M3 | cron 스냅샷(VACUUM INTO→gzip→S3) + 복원 리허설 | 인스턴스 재생성 후 복원 |
| M4 | anthropic-api 폴백 + 비용 가드 | 한도 도달 시 자동 전환(같은 히스토리 재사용) |

> **M-1 스파이크를 0단계로 둔 이유**: 위험이 거의 다 `claude -p` 어댑터 한 곳(병렬 동작·무상태 세션·토큰/한도 신호·ToS)에 몰려 있다. 본 빌드 전에 하루짜리 스파이크로 이 가정들을 사실로 닫으면 M0~M4가 안정적으로 진행된다.
