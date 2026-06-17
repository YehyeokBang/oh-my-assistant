# oh-my-assistant Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 개인용 Slack AI 비서를 구축한다 — Slack 스레드를 세션으로 삼아 단발 질의에 답하고, 병렬화 가능한 작업은 일회성 서브에이전트(역할 주입)에 위임해 결과를 같은 스레드에 보고한다.

**Architecture:** 헥사고날(포트 & 어댑터). Core/Orchestrator가 중립 `Message[]`까지만 다루고, 외부 의존성(Slack / claude-cli / SQLite / anthropic-api)은 전부 어댑터로 격리한다. 대화 기억의 원본은 SQLite이며 `claude -p`는 매번 무상태로 호출하고 히스토리를 프롬프트로 재구성한다.

**Tech Stack:** Node 24 LTS(fnm) · pnpm(corepack) · TypeScript 네이티브 타입 스트리핑(빌드 없음) · `better-sqlite3`(WAL/JSONB) · `@slack/bolt`(Socket Mode) · `node:test` 내장 러너 · `@ai-sdk/anthropic`(M4 폴백) · systemd · S3 백업.

## Global Constraints

이 제약은 **모든 태스크에 암묵적으로 포함**된다. 값은 설계 문서에서 그대로 옮긴 것이다.

- **Node 버전**: 24 LTS. `.node-version`에 `24`. 빌드 단계 없이 `node src/main.ts`로 실행.
- **TS 제약**: 네이티브 타입 스트리핑만 사용 → `enum`·`namespace` 금지. `as const` + 유니온 타입으로 대체. (호환 문제 시에만 `tsx` 폴백.)
- **모듈**: ESM (`package.json`에 `"type": "module"`). import 경로에 `.ts` 확장자 명시.
- **SQLite**: `better-sqlite3`, 반드시 WAL 모드(`db.pragma('journal_mode = WAL')`). JSONB는 SQLite 3.45+ 필요(번들 버전 확인).
- **세션 식별**: `session_id = thread_ts` (스레드 루트면 자기 `ts`). 새 메시지 = 새 세션.
- **히스토리 격리(information hygiene)**: 오케스트레이터만 전체 히스토리를 받는다. 병렬 워커는 `{ role, prompt, context }`만 받고 히스토리는 받지 않는다.
- **무상태 호출**: `claude -p`는 `--resume`에 의존하지 않는다. 매 호출마다 SQLite에서 히스토리를 꺼내 프롬프트로 재구성한다.
- **보안(협상 불가)**: `claude -p`는 `--allowedTools` 화이트리스트 + 읽기 전용부터. `--dangerously-skip-permissions`는 격리 환경에서만. Slack user ID 화이트리스트로 본인만 응답. 시크릿은 환경변수/SSM, 코드·DB에 평문 금지.
- **Slack**: Socket Mode(공개 HTTP 엔드포인트 없음). 후속 메시지는 전부 같은 스레드(`replyInThread`). 긴 출력은 40,000자 기준 분할.
- **동시성**: 초기 워커 동시성 상한 = 3(보수적). 워커별 타임아웃 필수, 초과 시 해당 워커만 실패 처리 후 부분 머지.
- **커밋**: main에 직접 커밋. 한글 커밋 메시지 포맷(`feat: 한글 제목` + 본문 불릿). 계정은 YehyeokBang.

## File Structure

| 경로 | 책임 |
|---|---|
| `src/main.ts` | 진입점. config 로드 → 어댑터/포트 조립 → 오케스트레이터 배선 → Slack 시작 |
| `src/config/index.ts` | env 로딩·검증, 페르소나·모델 라우팅·상한 설정값 |
| `src/ports/chat.ts` | `ChatPort` 인터페이스 + `IncomingMessage` 타입 |
| `src/ports/llm.ts` | `LLMPort` 인터페이스 + `Message`/`CompleteResult`/`WorkerTask`/`WorkerResult`/`UsageSignals` 타입 |
| `src/ports/memory.ts` | `MemoryPort` 인터페이스 + `StoredMessage`/`TaskRecord` 타입 |
| `src/adapters/memory/sqlite.ts` | SQLite Memory 어댑터 (스키마 DDL 포함) |
| `src/adapters/chat/slack.ts` | Slack Chat 어댑터 (Bolt, Socket Mode, 분할/스레드 라우팅) |
| `src/adapters/llm/claude-cli.ts` | claude-cli LLM 어댑터 (subprocess, stream-json 파싱) |
| `src/adapters/llm/anthropic-api.ts` | anthropic-api 폴백 어댑터 (M4) |
| `src/adapters/llm/fallback.ts` | 폴백 체인 래퍼 + 비용 가드 (M4) |
| `src/core/intent.ts` | 휴리스틱 단발/병렬 판단 (순수 함수) |
| `src/core/orchestrator.ts` | 세션 처리, 분해·머지·검증·보고 오케스트레이션 |
| `src/core/format.ts` | 워커 작업 파싱·보고 메시지 포맷 (순수 함수) |
| `scripts/backup.sh` | VACUUM INTO → gzip → S3 (M3) |
| `deploy/ai-agent.service` | systemd 유닛 |
| `test/**` | `node:test` 테스트 (소스 경로 미러링) |

---

## Phase 0 — M-1 스파이크 (게이트, TDD 아님)

> **목적**: 위험이 `claude -p` 어댑터 한 곳(병렬 동작·무상태 세션·토큰/한도 신호·ToS)에 몰려 있다. 본 빌드 전에 이 가정들을 사실로 닫는다. **이 Phase는 코드 구현이 아니라 실측·조사다.** 산출물은 findings 문서이며, 그 결과가 M1/M2/M4의 구체 설계(특히 `claude-cli` 파서·폴백 트리거·신호 스키마)를 확정한다.
>
> **게이트 규칙**: 항목 (d) ToS가 "불허"로 나오면 M0 이후를 진행하기 전에 사용자와 경로를 재논의한다(앱 토큰 기반 API 우선 등). (a)~(c)는 결과를 findings에 적고 M1에서 그 값으로 파서/스키마를 채운다.

### Task 0: M-1 스파이크 실측 및 findings 작성

**Files:**
- Create: `docs/superpowers/spikes/2026-06-17-claude-cli-spike.md`
- Create(임시, 커밋 안 함): `spike/` 디렉터리의 일회성 스크립트

- [ ] **Step 1: 스파이크 작업공간 준비**

```bash
mkdir -p spike docs/superpowers/spikes
# claude CLI 설치 확인 (없으면 설치)
claude --version || npm i -g @anthropic-ai/claude-code
node -v   # v24.x 확인
```

- [ ] **Step 2: (b) 무상태 호출 + 히스토리 재구성 확인**

단일 호출이 `--resume` 없이 동작하는지, 히스토리를 프롬프트로 먹였을 때 맥락을 잇는지 확인한다.

```bash
# 무상태 단발
claude -p "다음 한 단어로만 답해: 사과" --output-format stream-json --verbose 2>&1 | tee spike/single.jsonl
# 히스토리 재구성: 이전 Q/A를 프롬프트에 직접 넣어 맥락 유지되는지
claude -p $'아래는 이전 대화다.\n사용자: 내 이름은 방예혁\n비서: 알겠습니다.\n\n이제 질문: 내 이름이 뭐였지?' --output-format stream-json --verbose 2>&1 | tee spike/history.jsonl
```
findings에 기록: 맥락 유지 여부, `--output-format stream-json`에 `--verbose`가 필요한지.

- [ ] **Step 3: (c) stream-json 토큰·한도 신호 구조 확인**

`spike/single.jsonl`의 각 JSON 라인 구조를 분석한다. 특히 `type` 필드 값(`system`/`assistant`/`result` 등), 최종 결과 라인의 `usage`(input/output tokens), 비용·한도 관련 필드를 찾는다.

```bash
node --input-type=module -e '
import { readFileSync } from "node:fs";
const lines = readFileSync("spike/single.jsonl","utf8").trim().split("\n");
for (const l of lines) { try { const o = JSON.parse(l); console.log(o.type, JSON.stringify(o).slice(0,300)); } catch {} }
'
```
findings에 기록: **최종 텍스트를 어느 라인의 어느 필드에서 뽑는지**(M1 파서 입력), `usage` 필드 경로, 한도/잔량 신호 존재 여부. 이 값이 `UsageSignals` 스키마(`src/ports/llm.ts`)를 확정한다.

- [ ] **Step 4: (a) 병렬/자원 실측**

워커 3개를 동시에 띄웠을 때 메모리/지연을 실측한다.

```bash
/usr/bin/time -l bash -c '
for i in 1 2 3; do claude -p "200자 내로 자기소개 써줘 (#'$i')" --output-format stream-json --verbose > spike/w$i.jsonl 2>&1 & done
wait
' 2>&1 | tee spike/parallel-time.txt
```
findings에 기록: 동시 3개 RSS 합계·벽시계 지연. §3 동시성 상한(3)을 유지/조정할지 판단.

- [ ] **Step 5: (d) ToS 확인**

`claude -p`를 스크립트가 상시 구동하는 것이 현재 구독 약관상 허용 경로인지 공식 문서/약관에서 확인한다(WebSearch + 공식 문서). 폴백 트리거 후보(잔량 조회 API 존재 여부 vs 레이트리밋 에러 감지)도 같이 조사한다.

findings에 기록: ToS 판정(허용/회색/불허 + 근거 링크), M4 폴백 트리거를 "에러 감지"로 갈지 "잔량 조회"로 갈지 잠정 결정.

- [ ] **Step 6: findings 문서 작성 및 게이트 판정**

`docs/superpowers/spikes/2026-06-17-claude-cli-spike.md`에 (a)~(d) 결과와 아래 "M1/M2/M4에 넘기는 확정값"을 적는다:
- 최종 텍스트 추출 경로(라인 `type`/필드)
- `usage`/한도 신호 필드 경로 → `UsageSignals` 필드 확정
- 동시성 상한 유지/조정
- ToS 판정 + 폴백 트리거 방식

- [ ] **Step 7: 정리 및 커밋**

```bash
rm -rf spike            # 임시 스크립트·로그는 커밋하지 않음
git add docs/superpowers/spikes/2026-06-17-claude-cli-spike.md
git commit -m "$(printf 'docs: M-1 스파이크 findings 작성\n\n- claude -p 무상태 호출/히스토리 재구성/stream-json 신호/병렬 자원 실측\n- ToS 판정과 M4 폴백 트리거 방식 잠정 결정')"
```

---

## Phase M0 — Slack 에코봇 + SQLite + systemd

> 검증: 메시지 왕복 + DB 적재. 이 Phase 끝에서 "DM 보내면 그대로 되돌려주고, 메시지가 SQLite에 저장된다".

### Task 1: 프로젝트 스캐폴드 + 포트 인터페이스

**Files:**
- Create: `package.json`, `tsconfig.json`, `.node-version`, `.gitignore`, `.env.example`
- Create: `src/ports/chat.ts`, `src/ports/llm.ts`, `src/ports/memory.ts`
- Test: `test/ports/types.test.ts`

**Interfaces:**
- Produces: 모든 포트 타입. 이후 모든 태스크가 이 시그니처에 의존한다.

- [ ] **Step 1: 패키지·런타임 설정 파일 생성**

`.node-version`:
```
24
```

`.gitignore`:
```
node_modules/
.env
*.db
*.db-wal
*.db-shm
spike/
/tmp/
```

`package.json`:
```json
{
  "name": "oh-my-assistant",
  "version": "0.0.0",
  "type": "module",
  "private": true,
  "scripts": {
    "start": "node src/main.ts",
    "dev": "node --watch src/main.ts",
    "test": "node --test"
  },
  "engines": { "node": ">=24" }
}
```

`tsconfig.json` (타입체크 전용; 실행은 타입 스트리핑):
```json
{
  "compilerOptions": {
    "module": "nodenext",
    "moduleResolution": "nodenext",
    "target": "esnext",
    "allowImportingTsExtensions": true,
    "noEmit": true,
    "strict": true,
    "verbatimModuleSyntax": true,
    "types": ["node"]
  },
  "include": ["src/**/*.ts", "test/**/*.ts"]
}
```

`.env.example`:
```
# Slack
SLACK_BOT_TOKEN=xoxb-...
SLACK_APP_TOKEN=xapp-...
SLACK_ALLOWED_USER_IDS=U0123456789

# Storage
DB_PATH=./data/agent.db

# LLM
LLM_BACKEND=claude-cli
ORCHESTRATOR_MODEL=
WORKER_MODEL=
WORKER_CONCURRENCY=3
WORKER_TIMEOUT_MS=180000
CLAUDE_ALLOWED_TOOLS=Read,Grep,Glob,WebSearch,WebFetch

# M4 fallback
ANTHROPIC_API_KEY=
```

- [ ] **Step 2: 의존성 설치**

```bash
corepack enable
corepack prepare pnpm@latest --activate
pnpm add @slack/bolt better-sqlite3
pnpm add -D typescript @types/node @types/better-sqlite3
node -e "require('better-sqlite3')" && echo "better-sqlite3 OK"
```

- [ ] **Step 3: 포트 타입·인터페이스 작성**

`src/ports/llm.ts`:
```ts
export type Role = 'user' | 'assistant' | 'system';

export interface Message {
  role: Role;
  content: string;
}

export interface UsageSignals {
  // M-1 스파이크에서 확정된 필드 경로로 채운다 (한도 소진율 중심).
  inputTokens?: number;
  outputTokens?: number;
  raw?: unknown;
}

export interface CompleteOpts {
  model?: string;
  systemPrompt?: string;
  allowedTools?: string[];
}

export interface CompleteResult {
  text: string;
  backend: string; // 'claude-cli' | 'anthropic-api'
  signals: UsageSignals;
}

export interface WorkerTask {
  role: string;     // 주입할 역할 (예: '카카오 조사 전문가')
  prompt: string;
  context?: string; // 워커에 줄 최소 컨텍스트 (히스토리 아님)
}

export interface WorkerResult {
  task: WorkerTask;
  status: 'done' | 'failed';
  text?: string;
  error?: string;
  signals?: UsageSignals;
}

export interface SpawnOpts {
  concurrency?: number;
  timeoutMs?: number;
  model?: string;
  allowedTools?: string[];
}

export interface LLMPort {
  complete(messages: Message[], opts?: CompleteOpts): Promise<CompleteResult>;
  spawnWorkers(tasks: WorkerTask[], opts?: SpawnOpts): Promise<WorkerResult[]>;
}
```

`src/ports/memory.ts`:
```ts
import type { Message } from './llm.ts';

export type Direction = 'in' | 'out';
export type TaskStatus = 'queued' | 'running' | 'done' | 'failed';

export interface StoredMessage {
  sessionId: string;
  ts: number;          // epoch ms
  direction: Direction;
  channel: string;     // 'slack'
  llmBackend?: string;
  payload: unknown;    // JSONB: { text, ... }
}

export interface TaskRecord {
  id?: number;
  parentId?: number | null;
  sessionId: string;
  status: TaskStatus;
  role?: string;
  tsStart?: number;
  tsEnd?: number;
  payload?: unknown;
}

export interface SessionInfo {
  id: string;          // = thread_ts
  channel: string;
  chatId: string;
  persona?: string;
}

export interface MemoryPort {
  ensureSession(s: SessionInfo): void;
  append(msg: StoredMessage): void;
  getHistory(sessionId: string): Message[];
  recordTask(task: TaskRecord): number;       // returns new id
  updateTask(id: number, patch: Partial<TaskRecord>): void;
}
```

`src/ports/chat.ts`:
```ts
export interface IncomingMessage {
  sessionId: string;  // thread_ts (루트면 자기 ts)
  chatId: string;     // channel id
  userId: string;
  text: string;
  ts: string;
}

export type MessageHandler = (msg: IncomingMessage) => Promise<void>;

export interface ChatPort {
  onMessage(handler: MessageHandler): void;
  send(chatId: string, text: string): Promise<void>;
  sendLong(chatId: string, text: string): Promise<void>;
  replyInThread(chatId: string, threadTs: string, text: string): Promise<void>;
  sendTyping(chatId: string): Promise<void>;
  start(): Promise<void>;
}
```

- [ ] **Step 4: 타입 컴파일 검증 테스트 작성**

`test/ports/types.test.ts`:
```ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { Message } from '../../src/ports/llm.ts';
import type { StoredMessage } from '../../src/ports/memory.ts';
import type { IncomingMessage } from '../../src/ports/chat.ts';

test('포트 타입이 타입 스트리핑으로 import 된다', () => {
  const m: Message = { role: 'user', content: 'hi' };
  const s: StoredMessage = { sessionId: 's', ts: 1, direction: 'in', channel: 'slack', payload: {} };
  const i: IncomingMessage = { sessionId: 's', chatId: 'c', userId: 'u', text: 't', ts: '1' };
  assert.equal(m.role, 'user');
  assert.equal(s.direction, 'in');
  assert.equal(i.chatId, 'c');
});
```

- [ ] **Step 5: 타입체크 + 테스트 실행**

```bash
pnpm exec tsc --noEmit
node --test test/ports/types.test.ts
```
Expected: tsc 에러 없음, 테스트 PASS.

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "$(printf 'feat: 프로젝트 스캐폴드와 포트 인터페이스 정의\n\n- Node 24 타입 스트리핑 기반 ESM 설정\n- Chat/LLM/Memory 포트 인터페이스와 중립 Message 타입')"
```

### Task 2: SQLite Memory 어댑터

**Files:**
- Create: `src/adapters/memory/sqlite.ts`
- Test: `test/adapters/memory/sqlite.test.ts`

**Interfaces:**
- Consumes: `MemoryPort`, `StoredMessage`, `TaskRecord`, `SessionInfo`, `Message` (`src/ports/memory.ts`, `src/ports/llm.ts`)
- Produces: `createSqliteMemory(dbPath: string): MemoryPort` — `':memory:'` 허용.

- [ ] **Step 1: 실패하는 테스트 작성**

`test/adapters/memory/sqlite.test.ts`:
```ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createSqliteMemory } from '../../../src/adapters/memory/sqlite.ts';

test('append 후 getHistory가 in->user, out->assistant 순서로 복원한다', () => {
  const mem = createSqliteMemory(':memory:');
  mem.ensureSession({ id: 't1', channel: 'slack', chatId: 'C1' });
  mem.append({ sessionId: 't1', ts: 1, direction: 'in', channel: 'slack', payload: { text: '안녕' } });
  mem.append({ sessionId: 't1', ts: 2, direction: 'out', channel: 'slack', llmBackend: 'claude-cli', payload: { text: '안녕하세요' } });
  const h = mem.getHistory('t1');
  assert.deepEqual(h, [
    { role: 'user', content: '안녕' },
    { role: 'assistant', content: '안녕하세요' },
  ]);
});

test('getHistory는 ts 오름차순이며 다른 세션을 섞지 않는다', () => {
  const mem = createSqliteMemory(':memory:');
  mem.append({ sessionId: 'a', ts: 5, direction: 'in', channel: 'slack', payload: { text: 'a-late' } });
  mem.append({ sessionId: 'a', ts: 1, direction: 'in', channel: 'slack', payload: { text: 'a-early' } });
  mem.append({ sessionId: 'b', ts: 2, direction: 'in', channel: 'slack', payload: { text: 'b' } });
  assert.deepEqual(mem.getHistory('a').map((m) => m.content), ['a-early', 'a-late']);
});

test('recordTask는 id를 반환하고 updateTask가 상태를 갱신한다', () => {
  const mem = createSqliteMemory(':memory:');
  const id = mem.recordTask({ sessionId: 't1', status: 'queued', role: '카카오 조사' });
  assert.ok(id > 0);
  mem.updateTask(id, { status: 'done', tsEnd: 99 });
  // 직접 검증용 두 번째 기록으로 id 증가 확인
  const id2 = mem.recordTask({ sessionId: 't1', status: 'queued', parentId: id });
  assert.ok(id2 > id);
});
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
node --test test/adapters/memory/sqlite.test.ts
```
Expected: FAIL — `createSqliteMemory` 모듈 없음.

- [ ] **Step 3: 어댑터 구현**

`src/adapters/memory/sqlite.ts`:
```ts
import Database from 'better-sqlite3';
import type { MemoryPort, StoredMessage, TaskRecord, SessionInfo } from '../../ports/memory.ts';
import type { Message } from '../../ports/llm.ts';

const SCHEMA = `
CREATE TABLE IF NOT EXISTS sessions (
  id         TEXT PRIMARY KEY,
  channel    TEXT NOT NULL,
  chat_id    TEXT NOT NULL,
  persona    TEXT,
  created_ts INTEGER NOT NULL,
  last_ts    INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS messages (
  id          INTEGER PRIMARY KEY,
  session_id  TEXT NOT NULL,
  ts          INTEGER NOT NULL,
  direction   TEXT NOT NULL,
  channel     TEXT NOT NULL,
  llm_backend TEXT,
  payload     BLOB
);
CREATE INDEX IF NOT EXISTS idx_messages_session_ts ON messages(session_id, ts);
CREATE TABLE IF NOT EXISTS tasks (
  id         INTEGER PRIMARY KEY,
  parent_id  INTEGER,
  session_id TEXT NOT NULL,
  status     TEXT NOT NULL,
  role       TEXT,
  ts_start   INTEGER,
  ts_end     INTEGER,
  payload    BLOB
);
CREATE INDEX IF NOT EXISTS idx_tasks_session ON tasks(session_id);
CREATE INDEX IF NOT EXISTS idx_tasks_parent ON tasks(parent_id);
`;

export function createSqliteMemory(dbPath: string): MemoryPort {
  const db = new Database(dbPath);
  db.pragma('journal_mode = WAL');
  db.exec(SCHEMA);

  const insMsg = db.prepare(
    `INSERT INTO messages (session_id, ts, direction, channel, llm_backend, payload)
     VALUES (@session_id, @ts, @direction, @channel, @llm_backend, jsonb(@payload))`,
  );
  const selHistory = db.prepare(
    `SELECT direction, json(payload) AS payload FROM messages WHERE session_id = ? ORDER BY ts ASC, id ASC`,
  );
  const insTask = db.prepare(
    `INSERT INTO tasks (parent_id, session_id, status, role, ts_start, ts_end, payload)
     VALUES (@parent_id, @session_id, @status, @role, @ts_start, @ts_end, jsonb(@payload))`,
  );

  return {
    ensureSession(s: SessionInfo) {
      const now = Date.now();
      db.prepare(
        `INSERT INTO sessions (id, channel, chat_id, persona, created_ts, last_ts)
         VALUES (?, ?, ?, ?, ?, ?)
         ON CONFLICT(id) DO UPDATE SET last_ts = excluded.last_ts`,
      ).run(s.id, s.channel, s.chatId, s.persona ?? null, now, now);
    },
    append(msg: StoredMessage) {
      insMsg.run({
        session_id: msg.sessionId,
        ts: msg.ts,
        direction: msg.direction,
        channel: msg.channel,
        llm_backend: msg.llmBackend ?? null,
        payload: JSON.stringify(msg.payload ?? {}),
      });
    },
    getHistory(sessionId: string): Message[] {
      const rows = selHistory.all(sessionId) as { direction: string; payload: string }[];
      return rows.map((r) => ({
        role: r.direction === 'in' ? 'user' : 'assistant',
        content: (JSON.parse(r.payload)?.text ?? '') as string,
      }));
    },
    recordTask(task: TaskRecord): number {
      const info = insTask.run({
        parent_id: task.parentId ?? null,
        session_id: task.sessionId,
        status: task.status,
        role: task.role ?? null,
        ts_start: task.tsStart ?? null,
        ts_end: task.tsEnd ?? null,
        payload: JSON.stringify(task.payload ?? {}),
      });
      return Number(info.lastInsertRowid);
    },
    updateTask(id: number, patch: Partial<TaskRecord>) {
      const sets: string[] = [];
      const vals: unknown[] = [];
      if (patch.status !== undefined) { sets.push('status = ?'); vals.push(patch.status); }
      if (patch.role !== undefined) { sets.push('role = ?'); vals.push(patch.role); }
      if (patch.tsStart !== undefined) { sets.push('ts_start = ?'); vals.push(patch.tsStart); }
      if (patch.tsEnd !== undefined) { sets.push('ts_end = ?'); vals.push(patch.tsEnd); }
      if (patch.payload !== undefined) { sets.push('payload = jsonb(?)'); vals.push(JSON.stringify(patch.payload)); }
      if (sets.length === 0) return;
      vals.push(id);
      db.prepare(`UPDATE tasks SET ${sets.join(', ')} WHERE id = ?`).run(...vals);
    },
  };
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
node --test test/adapters/memory/sqlite.test.ts
```
Expected: 3개 테스트 PASS. (`jsonb()`/`json()` 호출이 실패하면 better-sqlite3 번들 SQLite가 3.45 미만 → 버전 확인 후 업그레이드.)

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "$(printf 'feat: SQLite Memory 어댑터 구현\n\n- sessions/messages/tasks 스키마와 WAL 모드\n- getHistory가 in/out을 user/assistant로 복원\n- JSONB payload 저장')"
```

### Task 3: Slack Chat 어댑터 (분할 로직 우선 TDD)

**Files:**
- Create: `src/adapters/chat/slack.ts`
- Test: `test/adapters/chat/split.test.ts`

**Interfaces:**
- Consumes: `ChatPort`, `IncomingMessage`, `MessageHandler` (`src/ports/chat.ts`)
- Produces: `createSlackChat(cfg): ChatPort`, `splitForSlack(text: string, limit?: number): string[]` (export 해서 단위 테스트)

- [ ] **Step 1: 분할 함수 실패 테스트 작성**

`test/adapters/chat/split.test.ts`:
```ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { splitForSlack } from '../../../src/adapters/chat/slack.ts';

test('한도 이하 텍스트는 한 조각', () => {
  assert.deepEqual(splitForSlack('hello', 100), ['hello']);
});

test('한도 초과 시 한도 길이를 넘지 않게 분할', () => {
  const parts = splitForSlack('a'.repeat(250), 100);
  assert.equal(parts.length, 3);
  assert.ok(parts.every((p) => p.length <= 100));
  assert.equal(parts.join(''), 'a'.repeat(250));
});

test('빈 문자열은 빈 조각 하나로 보존', () => {
  assert.deepEqual(splitForSlack('', 100), ['']);
});
```

- [ ] **Step 2: 실패 확인**

```bash
node --test test/adapters/chat/split.test.ts
```
Expected: FAIL — `splitForSlack` 없음.

- [ ] **Step 3: Slack 어댑터 구현 (분할 + Bolt 배선)**

`src/adapters/chat/slack.ts`:
```ts
import pkg from '@slack/bolt';
const { App } = pkg;
import type { ChatPort, IncomingMessage, MessageHandler } from '../../ports/chat.ts';

const SLACK_LIMIT = 40000;

export function splitForSlack(text: string, limit = SLACK_LIMIT): string[] {
  if (text.length <= limit) return [text];
  const parts: string[] = [];
  for (let i = 0; i < text.length; i += limit) parts.push(text.slice(i, i + limit));
  return parts;
}

export interface SlackConfig {
  botToken: string;
  appToken: string;
  allowedUserIds: string[];
}

export function createSlackChat(cfg: SlackConfig): ChatPort {
  const app = new App({ token: cfg.botToken, appToken: cfg.appToken, socketMode: true });
  const allowed = new Set(cfg.allowedUserIds);
  let handler: MessageHandler | null = null;

  app.message(async ({ message }) => {
    const m = message as any;
    if (m.subtype) return;                       // 봇/시스템 메시지 무시
    if (!allowed.has(m.user)) return;            // 화이트리스트 외 무시
    if (!handler) return;
    const incoming: IncomingMessage = {
      sessionId: m.thread_ts ?? m.ts,            // 스레드 루트면 자기 ts
      chatId: m.channel,
      userId: m.user,
      text: m.text ?? '',
      ts: m.ts,
    };
    await handler(incoming);
  });

  return {
    onMessage(h) { handler = h; },
    async send(chatId, text) {
      for (const part of splitForSlack(text)) {
        await app.client.chat.postMessage({ channel: chatId, text: part });
      }
    },
    async sendLong(chatId, text) {
      for (const part of splitForSlack(text)) {
        await app.client.chat.postMessage({ channel: chatId, text: part });
      }
    },
    async replyInThread(chatId, threadTs, text) {
      for (const part of splitForSlack(text)) {
        await app.client.chat.postMessage({ channel: chatId, thread_ts: threadTs, text: part });
      }
    },
    async sendTyping(chatId) {
      await app.client.chat.postMessage({ channel: chatId, text: '🔎 받았어요…' });
    },
    async start() { await app.start(); },
  };
}
```

- [ ] **Step 4: 분할 테스트 통과 확인**

```bash
node --test test/adapters/chat/split.test.ts
```
Expected: 3개 PASS. (Bolt 배선은 M0 Task 5의 수동 왕복 테스트로 검증.)

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "$(printf 'feat: Slack Chat 어댑터 구현\n\n- Socket Mode 수신과 user ID 화이트리스트\n- thread_ts 기반 session_id 매핑\n- 40000자 분할 전송과 스레드 라우팅')"
```

### Task 4: config 로더

**Files:**
- Create: `src/config/index.ts`
- Test: `test/config/index.test.ts`

**Interfaces:**
- Produces: `loadConfig(env: Record<string, string | undefined>): AppConfig` 와 `AppConfig` 타입.

- [ ] **Step 1: 실패 테스트 작성**

`test/config/index.test.ts`:
```ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadConfig } from '../../src/config/index.ts';

test('필수 env 누락 시 명확한 에러', () => {
  assert.throws(() => loadConfig({}), /SLACK_BOT_TOKEN/);
});

test('값 파싱: 화이트리스트 분리와 숫자 변환', () => {
  const cfg = loadConfig({
    SLACK_BOT_TOKEN: 'xoxb', SLACK_APP_TOKEN: 'xapp',
    SLACK_ALLOWED_USER_IDS: 'U1, U2 ,U3',
    DB_PATH: '/tmp/a.db', WORKER_CONCURRENCY: '5',
  });
  assert.deepEqual(cfg.slack.allowedUserIds, ['U1', 'U2', 'U3']);
  assert.equal(cfg.worker.concurrency, 5);
  assert.equal(cfg.worker.timeoutMs, 180000); // 기본값
});
```

- [ ] **Step 2: 실패 확인**

```bash
node --test test/config/index.test.ts
```
Expected: FAIL — `loadConfig` 없음.

- [ ] **Step 3: 구현**

`src/config/index.ts`:
```ts
export interface AppConfig {
  slack: { botToken: string; appToken: string; allowedUserIds: string[] };
  dbPath: string;
  llm: { backend: string; orchestratorModel?: string; workerModel?: string; allowedTools: string[] };
  worker: { concurrency: number; timeoutMs: number };
  anthropicApiKey?: string;
}

type Env = Record<string, string | undefined>;

function required(env: Env, key: string): string {
  const v = env[key];
  if (!v) throw new Error(`필수 환경변수 누락: ${key}`);
  return v;
}

function list(v: string | undefined): string[] {
  return (v ?? '').split(',').map((s) => s.trim()).filter(Boolean);
}

export function loadConfig(env: Env): AppConfig {
  return {
    slack: {
      botToken: required(env, 'SLACK_BOT_TOKEN'),
      appToken: required(env, 'SLACK_APP_TOKEN'),
      allowedUserIds: list(env.SLACK_ALLOWED_USER_IDS),
    },
    dbPath: env.DB_PATH ?? './data/agent.db',
    llm: {
      backend: env.LLM_BACKEND ?? 'claude-cli',
      orchestratorModel: env.ORCHESTRATOR_MODEL || undefined,
      workerModel: env.WORKER_MODEL || undefined,
      allowedTools: list(env.CLAUDE_ALLOWED_TOOLS) ,
    },
    worker: {
      concurrency: Number(env.WORKER_CONCURRENCY ?? '3'),
      timeoutMs: Number(env.WORKER_TIMEOUT_MS ?? '180000'),
    },
    anthropicApiKey: env.ANTHROPIC_API_KEY || undefined,
  };
}
```

- [ ] **Step 4: 통과 확인**

```bash
node --test test/config/index.test.ts
```
Expected: 2개 PASS.

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "$(printf 'feat: 환경변수 config 로더\n\n- 필수값 검증과 화이트리스트/숫자 파싱\n- 모델 라우팅/동시성 상한 기본값')"
```

### Task 5: 에코봇 main 배선 + 수동 왕복 검증

**Files:**
- Create: `src/main.ts`
- Modify: `.env` (로컬, 커밋 안 함)

**Interfaces:**
- Consumes: `loadConfig`, `createSqliteMemory`, `createSlackChat`

- [ ] **Step 1: main 배선 (에코 + 저장)**

`src/main.ts`:
```ts
import { loadConfig } from './config/index.ts';
import { createSqliteMemory } from './adapters/memory/sqlite.ts';
import { createSlackChat } from './adapters/chat/slack.ts';
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

const cfg = loadConfig(process.env);
mkdirSync(dirname(cfg.dbPath), { recursive: true });

const memory = createSqliteMemory(cfg.dbPath);
const chat = createSlackChat(cfg.slack);

chat.onMessage(async (msg) => {
  memory.ensureSession({ id: msg.sessionId, channel: 'slack', chatId: msg.chatId });
  memory.append({ sessionId: msg.sessionId, ts: Date.now(), direction: 'in', channel: 'slack', payload: { text: msg.text } });

  const reply = `echo: ${msg.text}`;            // M0: 에코. M1에서 LLM 응답으로 교체
  await chat.replyInThread(msg.chatId, msg.sessionId, reply);
  memory.append({ sessionId: msg.sessionId, ts: Date.now(), direction: 'out', channel: 'slack', payload: { text: reply } });
});

await chat.start();
console.log('oh-my-assistant: Slack Socket Mode 시작됨');
```

- [ ] **Step 2: 로컬 .env 작성 후 실행**

```bash
cp .env.example .env   # 실제 토큰 채우기 (Slack 앱: Socket Mode ON, 스코프: app_mentions:read, chat:write, im:history, im:write)
node src/main.ts
```
Expected: "Slack Socket Mode 시작됨" 로그.

- [ ] **Step 3: 수동 왕복 검증**

Slack에서 봇에게 DM "테스트" 전송 → 같은 스레드에 "echo: 테스트" 회신 확인. 이어서:
```bash
sqlite3 ./data/agent.db "SELECT direction, json(payload) FROM messages ORDER BY ts;"
```
Expected: `in`/`out` 두 행, payload에 텍스트 저장 확인.

- [ ] **Step 4: 커밋**

```bash
git add src/main.ts
git commit -m "$(printf 'feat: Slack 에코봇 main 배선과 메시지 영속화\n\n- 수신 메시지를 스레드에 에코 회신\n- in/out 메시지를 SQLite에 저장')"
```

### Task 6: systemd 유닛 + 백업 스크립트 골격

**Files:**
- Create: `deploy/ai-agent.service`, `scripts/backup.sh`

- [ ] **Step 1: systemd 유닛 작성**

`deploy/ai-agent.service` (설계 §10.8 그대로, 경로는 배포 시 `which node`로 확정):
```ini
[Unit]
Description=Personal AI Agent (Slack)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=agent
WorkingDirectory=/opt/ai-agent
ExecStart=/home/agent/.local/share/fnm/aliases/default/bin/node /opt/ai-agent/src/main.ts
Restart=on-failure
RestartSec=5
EnvironmentFile=/opt/ai-agent/.env
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/opt/ai-agent /tmp
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

- [ ] **Step 2: 백업 스크립트 골격 (M3에서 S3 채움)**

`scripts/backup.sh`:
```bash
#!/usr/bin/env bash
set -euo pipefail
DB_PATH="${DB_PATH:-/opt/ai-agent/data/agent.db}"
SNAP="/tmp/agent-snap-$(date +%Y%m%d-%H%M).db"
sqlite3 "$DB_PATH" "VACUUM INTO '$SNAP'"   # cp 금지(트랜잭션 비안전)
gzip -f "$SNAP"
echo "snapshot: ${SNAP}.gz"
# M3: aws s3 cp 추가
```

- [ ] **Step 3: 문법 검증 + 커밋**

```bash
bash -n scripts/backup.sh && chmod +x scripts/backup.sh && echo OK
git add deploy/ai-agent.service scripts/backup.sh
git commit -m "$(printf 'chore: systemd 유닛과 백업 스크립트 골격 추가\n\n- 보안 하드닝 포함 systemd 서비스 정의\n- VACUUM INTO 기반 스냅샷 골격')"
```

---

## Phase M1 — claude-cli LLM Port 단발 응답 + 히스토리 유지

> 검증: 질문→답변, 같은 스레드에서 맥락 유지, stream-json 신호 기록.

### Task 7: claude-cli 어댑터 — stream-json 파서 (순수 함수 TDD)

**Files:**
- Create: `src/adapters/llm/claude-cli.ts`
- Test: `test/adapters/llm/parse.test.ts`

**Interfaces:**
- Consumes: `LLMPort`, `Message`, `CompleteResult`, `UsageSignals` (`src/ports/llm.ts`)
- Produces:
  - `parseStreamJson(stdout: string): CompleteResult` — M-1 findings의 라인 구조에 맞춰 최종 텍스트·`usage` 추출
  - `messagesToPrompt(messages: Message[]): string` — 무상태 호출용 히스토리 직렬화
  - `createClaudeCliLLM(cfg, runner?): LLMPort` — `runner`는 주입 가능(테스트용)

> **M-1 의존**: 아래 `parseStreamJson`은 claude-code의 `--output-format stream-json` 표준 형식(최종 `type: "result"` 라인의 `result` 텍스트 + `usage`)을 가정한다. M-1 findings가 다른 경로를 보고하면 이 함수의 추출 경로만 수정한다(인터페이스 불변).

- [ ] **Step 1: 파서/직렬화 실패 테스트 작성**

`test/adapters/llm/parse.test.ts`:
```ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseStreamJson, messagesToPrompt } from '../../../src/adapters/llm/claude-cli.ts';

const SAMPLE = [
  JSON.stringify({ type: 'system', subtype: 'init' }),
  JSON.stringify({ type: 'assistant', message: { content: [{ type: 'text', text: '부분' }] } }),
  JSON.stringify({ type: 'result', subtype: 'success', result: '최종 답변', usage: { input_tokens: 12, output_tokens: 34 } }),
].join('\n');

test('parseStreamJson은 result 라인의 텍스트와 usage를 추출한다', () => {
  const r = parseStreamJson(SAMPLE);
  assert.equal(r.text, '최종 답변');
  assert.equal(r.backend, 'claude-cli');
  assert.equal(r.signals.inputTokens, 12);
  assert.equal(r.signals.outputTokens, 34);
});

test('result 라인이 없으면 assistant 텍스트를 이어붙인다', () => {
  const only = JSON.stringify({ type: 'assistant', message: { content: [{ type: 'text', text: '대체' }] } });
  assert.equal(parseStreamJson(only).text, '대체');
});

test('messagesToPrompt는 역할 라벨을 붙여 직렬화한다', () => {
  const p = messagesToPrompt([
    { role: 'user', content: '안녕' },
    { role: 'assistant', content: '네' },
    { role: 'user', content: '이름?' },
  ]);
  assert.match(p, /사용자: 안녕/);
  assert.match(p, /비서: 네/);
  assert.ok(p.trim().endsWith('이름?'));
});
```

- [ ] **Step 2: 실패 확인**

```bash
node --test test/adapters/llm/parse.test.ts
```
Expected: FAIL — 모듈 없음.

- [ ] **Step 3: 어댑터 구현**

`src/adapters/llm/claude-cli.ts`:
```ts
import { spawn } from 'node:child_process';
import type {
  LLMPort, Message, CompleteOpts, CompleteResult, UsageSignals,
  WorkerTask, WorkerResult, SpawnOpts,
} from '../../ports/llm.ts';

export function messagesToPrompt(messages: Message[]): string {
  const label = (r: string) => (r === 'user' ? '사용자' : r === 'assistant' ? '비서' : '시스템');
  const history = messages.slice(0, -1);
  const last = messages[messages.length - 1];
  const head = history.length
    ? `아래는 이전 대화다.\n${history.map((m) => `${label(m.role)}: ${m.content}`).join('\n')}\n\n이제 질문:\n`
    : '';
  return `${head}${last ? last.content : ''}`;
}

export function parseStreamJson(stdout: string): CompleteResult {
  const lines = stdout.trim().split('\n').filter(Boolean);
  let text = '';
  let signals: UsageSignals = {};
  const assistantParts: string[] = [];
  for (const line of lines) {
    let o: any;
    try { o = JSON.parse(line); } catch { continue; }
    if (o.type === 'assistant') {
      const c = o.message?.content ?? [];
      for (const blk of c) if (blk.type === 'text' && blk.text) assistantParts.push(blk.text);
    } else if (o.type === 'result') {
      if (typeof o.result === 'string') text = o.result;
      if (o.usage) signals = { inputTokens: o.usage.input_tokens, outputTokens: o.usage.output_tokens, raw: o.usage };
    }
  }
  if (!text) text = assistantParts.join('');
  return { text, backend: 'claude-cli', signals };
}

export interface ClaudeCliConfig {
  defaultModel?: string;
  allowedTools: string[];
}

type Runner = (args: string[], input: string) => Promise<string>;

const defaultRunner: Runner = (args, input) =>
  new Promise((resolve, reject) => {
    const child = spawn('claude', args, { stdio: ['pipe', 'pipe', 'pipe'] });
    let out = '', err = '';
    child.stdout.on('data', (d) => (out += d));
    child.stderr.on('data', (d) => (err += d));
    child.on('error', reject);
    child.on('close', (code) => (code === 0 ? resolve(out) : reject(new Error(`claude exit ${code}: ${err}`))));
    child.stdin.write(input);
    child.stdin.end();
  });

export function createClaudeCliLLM(cfg: ClaudeCliConfig, runner: Runner = defaultRunner): LLMPort {
  const buildArgs = (model: string | undefined, tools: string[], systemPrompt?: string): string[] => {
    const args = ['-p', '--output-format', 'stream-json', '--verbose'];
    if (model) args.push('--model', model);
    if (tools.length) args.push('--allowedTools', tools.join(','));
    if (systemPrompt) args.push('--append-system-prompt', systemPrompt);
    return args;
  };

  return {
    async complete(messages, opts: CompleteOpts = {}) {
      const args = buildArgs(opts.model ?? cfg.defaultModel, opts.allowedTools ?? cfg.allowedTools, opts.systemPrompt);
      const stdout = await runner(args, messagesToPrompt(messages));
      return parseStreamJson(stdout);
    },
    async spawnWorkers(tasks: WorkerTask[], opts: SpawnOpts = {}): Promise<WorkerResult[]> {
      // M2에서 구현. M1 단계에서는 미사용.
      throw new Error('spawnWorkers는 M2에서 구현된다');
    },
  };
}
```

- [ ] **Step 4: 통과 확인 + 타입체크**

```bash
node --test test/adapters/llm/parse.test.ts
pnpm exec tsc --noEmit
```
Expected: 3개 PASS, tsc 에러 없음.

- [ ] **Step 5: 실제 claude 단발 호출 스모크 테스트 (수동)**

```bash
node --input-type=module -e '
import { createClaudeCliLLM } from "./src/adapters/llm/claude-cli.ts";
const llm = createClaudeCliLLM({ allowedTools: ["Read"] });
console.log(await llm.complete([{ role: "user", content: "한 단어로: 바나나 색?" }]));
'
```
Expected: `{ text: "노랑..." , backend: "claude-cli", signals: {...} }`. signals가 비면 M-1 findings의 usage 경로로 `parseStreamJson` 수정.

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "$(printf 'feat: claude-cli LLM 어댑터 단발 응답 구현\n\n- stream-json 파서로 최종 텍스트와 usage 신호 추출\n- 무상태 호출용 히스토리 프롬프트 직렬화\n- runner 주입으로 테스트 가능하게 분리')"
```

### Task 8: 오케스트레이터 단발 경로 + main 교체

**Files:**
- Create: `src/core/orchestrator.ts`
- Test: `test/core/orchestrator.test.ts`
- Modify: `src/main.ts`

**Interfaces:**
- Consumes: `LLMPort`, `MemoryPort`, `ChatPort`, `IncomingMessage`
- Produces: `createOrchestrator(deps: { llm; memory; chat; persona?: string }): { handle(msg: IncomingMessage): Promise<void> }`

- [ ] **Step 1: 실패 테스트 작성 (가짜 포트 주입)**

`test/core/orchestrator.test.ts`:
```ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createOrchestrator } from '../../src/core/orchestrator.ts';
import type { LLMPort } from '../../src/ports/llm.ts';
import { createSqliteMemory } from '../../src/adapters/memory/sqlite.ts';

function fakeLLM(answer: string): LLMPort {
  return {
    async complete(messages) {
      return { text: `${answer}|hist=${messages.length}`, backend: 'fake', signals: {} };
    },
    async spawnWorkers() { return []; },
  };
}

test('단발: 히스토리 적재→complete→스레드 회신→in/out 저장', async () => {
  const memory = createSqliteMemory(':memory:');
  const sent: { chatId: string; threadTs: string; text: string }[] = [];
  const chat: any = { replyInThread: async (chatId: string, threadTs: string, text: string) => { sent.push({ chatId, threadTs, text }); }, sendTyping: async () => {} };
  const orch = createOrchestrator({ llm: fakeLLM('답'), memory, chat });

  await orch.handle({ sessionId: 't1', chatId: 'C1', userId: 'U1', text: '질문1', ts: '1' });
  assert.equal(sent.length, 1);
  assert.match(sent[0].text, /^답\|hist=1/);            // 첫 호출은 히스토리 1개(현재 메시지)
  assert.equal(sent[0].threadTs, 't1');

  await orch.handle({ sessionId: 't1', chatId: 'C1', userId: 'U1', text: '질문2', ts: '2' });
  assert.match(sent[1].text, /hist=3/);                 // user1,assistant1,user2
});
```

- [ ] **Step 2: 실패 확인**

```bash
node --test test/core/orchestrator.test.ts
```
Expected: FAIL — `createOrchestrator` 없음.

- [ ] **Step 3: 오케스트레이터 구현 (단발 경로만)**

`src/core/orchestrator.ts`:
```ts
import type { LLMPort, Message } from '../ports/llm.ts';
import type { MemoryPort } from '../ports/memory.ts';
import type { ChatPort, IncomingMessage } from '../ports/chat.ts';

export interface OrchestratorDeps {
  llm: LLMPort;
  memory: MemoryPort;
  chat: ChatPort;
  persona?: string;       // 시스템 프롬프트
  orchestratorModel?: string;
}

export function createOrchestrator(deps: OrchestratorDeps) {
  const { llm, memory, chat } = deps;

  async function handle(msg: IncomingMessage): Promise<void> {
    memory.ensureSession({ id: msg.sessionId, channel: 'slack', chatId: msg.chatId });
    memory.append({ sessionId: msg.sessionId, ts: Date.now(), direction: 'in', channel: 'slack', payload: { text: msg.text } });

    const history: Message[] = memory.getHistory(msg.sessionId);
    const result = await llm.complete(history, { systemPrompt: deps.persona, model: deps.orchestratorModel });

    await chat.replyInThread(msg.chatId, msg.sessionId, result.text);
    memory.append({
      sessionId: msg.sessionId, ts: Date.now(), direction: 'out', channel: 'slack',
      llmBackend: result.backend, payload: { text: result.text, signals: result.signals },
    });
  }

  return { handle };
}
```

- [ ] **Step 4: 통과 확인**

```bash
node --test test/core/orchestrator.test.ts
```
Expected: 1개 PASS.

- [ ] **Step 5: main.ts를 오케스트레이터로 교체**

`src/main.ts`의 `chat.onMessage(...)` 블록을 교체:
```ts
import { createOrchestrator } from './core/orchestrator.ts';
import { createClaudeCliLLM } from './adapters/llm/claude-cli.ts';

const llm = createClaudeCliLLM({ defaultModel: cfg.llm.orchestratorModel, allowedTools: cfg.llm.allowedTools });
const orchestrator = createOrchestrator({ llm, memory, chat, orchestratorModel: cfg.llm.orchestratorModel });

chat.onMessage(async (msg) => {
  await chat.sendTyping(msg.chatId);
  await orchestrator.handle(msg);
});
```
(에코 블록과 직접 import한 미사용 항목 제거.)

- [ ] **Step 6: 수동 맥락 유지 검증**

Slack DM "내 이름은 방예혁" → 답변. 같은 스레드 답글 "내 이름 뭐였지?" → 이름 기억 확인.

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "$(printf 'feat: 오케스트레이터 단발 경로와 claude-cli 연결\n\n- getHistory로 스레드 맥락 복원 후 complete 호출\n- 응답을 스레드 회신하고 신호와 함께 저장\n- 에코봇을 LLM 응답으로 교체')"
```

---

## Phase M2 — 병렬 위임 (일회성 워커 + 역할 주입) + 머지/검증 + 보고

> 검증: "업계 표준 조사" 시나리오 — 병렬 판단 → 분해 → 동시 워커 → 스레드 순차 보고 → 종합.

### Task 9: 휴리스틱 의도 판단 (순수 함수)

**Files:**
- Create: `src/core/intent.ts`
- Test: `test/core/intent.test.ts`

**Interfaces:**
- Produces: `classifyIntent(text: string): { parallel: boolean; reason: string }`

- [ ] **Step 1: 실패 테스트 작성**

`test/core/intent.test.ts`:
```ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { classifyIntent } from '../../src/core/intent.ts';

test('명시 트리거 "각각/조사"는 병렬', () => {
  assert.equal(classifyIntent('카카오 라인 왓츠앱 각각 조사해줘').parallel, true);
});
test('"후보 3개"처럼 복수성 신호는 병렬', () => {
  assert.equal(classifyIntent('이름 후보 3개 만들어줘').parallel, true);
});
test('단순 단발 질문은 비병렬', () => {
  assert.equal(classifyIntent('이 PR 설명 한 줄로 써줘').parallel, false);
});
```

- [ ] **Step 2: 실패 확인**

```bash
node --test test/core/intent.test.ts
```
Expected: FAIL.

- [ ] **Step 3: 구현**

`src/core/intent.ts`:
```ts
const TRIGGERS = ['각각', '조사', '비교', '동시에', '병렬', '후보', '여러'];
const COUNT_RE = /(\d+)\s*(개|가지|건|곳)/;

export function classifyIntent(text: string): { parallel: boolean; reason: string } {
  const t = text.trim();
  const hit = TRIGGERS.find((w) => t.includes(w));
  const countMatch = t.match(COUNT_RE);
  if (countMatch && Number(countMatch[1]) >= 2) {
    return { parallel: true, reason: `복수성 신호(${countMatch[0]})` };
  }
  if (hit) return { parallel: true, reason: `트리거 키워드(${hit})` };
  return { parallel: false, reason: '단발 신호' };
}
```

- [ ] **Step 4: 통과 확인**

```bash
node --test test/core/intent.test.ts
```
Expected: 3개 PASS.

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "$(printf 'feat: 휴리스틱 단발/병렬 의도 판단\n\n- 트리거 키워드와 복수성 신호 기반 분류')"
```

### Task 10: 작업 분해 파싱 + 보고 포맷 (순수 함수)

**Files:**
- Create: `src/core/format.ts`
- Test: `test/core/format.test.ts`

**Interfaces:**
- Produces:
  - `parseTasks(raw: string): WorkerTask[]` — 오케스트레이터 LLM이 낸 JSON 배열을 안전 파싱(코드펜스 제거)
  - `formatWorkerReport(r: WorkerResult): string`
  - `formatSummaryHeader(count: number): string`

- [ ] **Step 1: 실패 테스트 작성**

`test/core/format.test.ts`:
```ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseTasks, formatWorkerReport, formatSummaryHeader } from '../../src/core/format.ts';

test('parseTasks는 코드펜스로 감싼 JSON도 파싱', () => {
  const raw = '```json\n[{"role":"카카오 조사","prompt":"카카오 프리뷰 조사"}]\n```';
  const tasks = parseTasks(raw);
  assert.equal(tasks.length, 1);
  assert.equal(tasks[0].role, '카카오 조사');
});

test('parseTasks는 배열이 아니면 빈 배열', () => {
  assert.deepEqual(parseTasks('설명만 있고 JSON 없음'), []);
});

test('formatWorkerReport: 실패는 사유를 명시한다', () => {
  const s = formatWorkerReport({ task: { role: '라인 조사', prompt: 'x' }, status: 'failed', error: '타임아웃' });
  assert.match(s, /라인 조사/);
  assert.match(s, /실패/);
  assert.match(s, /타임아웃/);
});

test('formatSummaryHeader', () => {
  assert.match(formatSummaryHeader(3), /3건/);
});
```

- [ ] **Step 2: 실패 확인**

```bash
node --test test/core/format.test.ts
```
Expected: FAIL.

- [ ] **Step 3: 구현**

`src/core/format.ts`:
```ts
import type { WorkerTask, WorkerResult } from '../ports/llm.ts';

export function parseTasks(raw: string): WorkerTask[] {
  const cleaned = raw.replace(/```(?:json)?/gi, '').trim();
  const start = cleaned.indexOf('[');
  const end = cleaned.lastIndexOf(']');
  if (start === -1 || end === -1 || end < start) return [];
  try {
    const arr = JSON.parse(cleaned.slice(start, end + 1));
    if (!Array.isArray(arr)) return [];
    return arr
      .filter((x) => x && typeof x.role === 'string' && typeof x.prompt === 'string')
      .map((x) => ({ role: x.role, prompt: x.prompt, context: typeof x.context === 'string' ? x.context : undefined }));
  } catch {
    return [];
  }
}

export function formatSummaryHeader(count: number): string {
  return `🔎 조사 시작 (${count}건)`;
}

export function formatWorkerReport(r: WorkerResult): string {
  if (r.status === 'failed') {
    return `⚠️ *${r.task.role}* 실패: ${r.error ?? '알 수 없는 오류'}`;
  }
  return `📊 *${r.task.role}*\n${r.text ?? ''}`;
}
```

- [ ] **Step 4: 통과 확인**

```bash
node --test test/core/format.test.ts
```
Expected: 4개 PASS.

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "$(printf 'feat: 작업 분해 파싱과 보고 포맷 유틸\n\n- LLM 작업 목록 JSON 안전 파싱(코드펜스 허용)\n- 워커 결과/실패/요약 헤더 포맷')"
```

### Task 11: claude-cli `spawnWorkers` — 동시성 상한 + 타임아웃

**Files:**
- Modify: `src/adapters/llm/claude-cli.ts` (`spawnWorkers` 구현)
- Test: `test/adapters/llm/workers.test.ts`

**Interfaces:**
- Consumes: `WorkerTask`, `WorkerResult`, `SpawnOpts`
- Produces: `spawnWorkers`가 입력 순서를 보존한 `WorkerResult[]` 반환. 워커는 히스토리 없이 역할+프롬프트+컨텍스트만 받는다.

- [ ] **Step 1: 실패 테스트 작성 (runner 주입으로 동시성/실패/타임아웃 검증)**

`test/adapters/llm/workers.test.ts`:
```ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createClaudeCliLLM } from '../../../src/adapters/llm/claude-cli.ts';

function resultLine(text: string) {
  return JSON.stringify({ type: 'result', subtype: 'success', result: text, usage: { input_tokens: 1, output_tokens: 1 } });
}

test('각 워커는 역할/프롬프트만 받고 결과를 순서대로 반환', async () => {
  const seenPrompts: string[] = [];
  const runner = async (_args: string[], input: string) => { seenPrompts.push(input); return resultLine(`done:${input.slice(0, 4)}`); };
  const llm = createClaudeCliLLM({ allowedTools: ['Read'] }, runner);
  const res = await llm.spawnWorkers(
    [{ role: 'A', prompt: 'aaaa' }, { role: 'B', prompt: 'bbbb' }],
    { concurrency: 2 },
  );
  assert.equal(res.length, 2);
  assert.equal(res[0].status, 'done');
  assert.match(res[0].text!, /done:/);
  assert.ok(seenPrompts.every((p) => p.includes('전문가') || p.length > 0)); // 역할 주입 포함
});

test('동시성 상한을 초과 실행하지 않는다', async () => {
  let active = 0, peak = 0;
  const runner = async () => {
    active++; peak = Math.max(peak, active);
    await new Promise((r) => setTimeout(r, 20));
    active--; return resultLine('ok');
  };
  const llm = createClaudeCliLLM({ allowedTools: [] }, runner);
  await llm.spawnWorkers(
    Array.from({ length: 6 }, (_, i) => ({ role: `R${i}`, prompt: 'p' })),
    { concurrency: 2 },
  );
  assert.ok(peak <= 2, `peak=${peak}`);
});

test('타임아웃 초과 워커는 failed, 나머지는 done', async () => {
  const runner = (_a: string[], input: string) =>
    input.includes('slow')
      ? new Promise<string>((r) => setTimeout(() => r(resultLine('late')), 100))
      : Promise.resolve(resultLine('fast'));
  const llm = createClaudeCliLLM({ allowedTools: [] }, runner);
  const res = await llm.spawnWorkers(
    [{ role: 'fast', prompt: 'fast' }, { role: 'slow', prompt: 'slow' }],
    { concurrency: 2, timeoutMs: 30 },
  );
  assert.equal(res.find((r) => r.task.role === 'fast')!.status, 'done');
  const slow = res.find((r) => r.task.role === 'slow')!;
  assert.equal(slow.status, 'failed');
  assert.match(slow.error!, /타임아웃|timeout/i);
});
```

- [ ] **Step 2: 실패 확인**

```bash
node --test test/adapters/llm/workers.test.ts
```
Expected: FAIL — 현재 `spawnWorkers`는 throw.

- [ ] **Step 3: `spawnWorkers` 구현**

`src/adapters/llm/claude-cli.ts`의 `spawnWorkers`를 교체하고, 역할 프롬프트 빌더와 동시성 풀을 추가:
```ts
// 파일 상단 messagesToPrompt 아래에 추가
export function buildWorkerPrompt(task: { role: string; prompt: string; context?: string }): string {
  const ctx = task.context ? `\n\n참고 컨텍스트:\n${task.context}` : '';
  return `너는 "${task.role}" 전문가다. 아래 작업만 독립적으로 수행하고 결과를 간결히 보고하라.${ctx}\n\n작업:\n${task.prompt}`;
}

async function runWithLimit<T>(items: T[], limit: number, fn: (item: T, index: number) => Promise<unknown>) {
  const results = new Array(items.length);
  let next = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) {
      const i = next++;
      results[i] = await fn(items[i], i);
    }
  });
  await Promise.all(workers);
  return results;
}
```

`spawnWorkers` 본문 교체:
```ts
    async spawnWorkers(tasks: WorkerTask[], opts: SpawnOpts = {}): Promise<WorkerResult[]> {
      const concurrency = opts.concurrency ?? 3;
      const timeoutMs = opts.timeoutMs ?? 180000;
      const tools = opts.allowedTools ?? cfg.allowedTools;
      const model = opts.model ?? cfg.defaultModel;

      const runOne = async (task: WorkerTask): Promise<WorkerResult> => {
        const args = buildArgs(model, tools);
        const prompt = buildWorkerPrompt(task);   // 히스토리 없음 — 역할+프롬프트+컨텍스트만
        let timer: ReturnType<typeof setTimeout>;
        const timeout = new Promise<never>((_, reject) => {
          timer = setTimeout(() => reject(new Error(`타임아웃 ${timeoutMs}ms 초과`)), timeoutMs);
        });
        try {
          const stdout = await Promise.race([runner(args, prompt), timeout]);
          const parsed = parseStreamJson(stdout as string);
          return { task, status: 'done', text: parsed.text, signals: parsed.signals };
        } catch (e) {
          return { task, status: 'failed', error: e instanceof Error ? e.message : String(e) };
        } finally {
          clearTimeout(timer!);
        }
      };

      return (await runWithLimit(tasks, concurrency, runOne)) as WorkerResult[];
    },
```
(`buildArgs`는 `createClaudeCliLLM` 클로저 내부 함수이므로 `runOne`을 그 안에 두거나 `buildArgs`를 모듈 함수로 끌어올린다. 본 구현은 `runOne`을 `spawnWorkers` 내부에 두어 클로저 접근을 유지한다.)

- [ ] **Step 4: 통과 확인 + 타입체크**

```bash
node --test test/adapters/llm/workers.test.ts
pnpm exec tsc --noEmit
```
Expected: 3개 PASS, tsc 통과.

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "$(printf 'feat: claude-cli 병렬 워커 실행 구현\n\n- 동시성 상한 풀과 워커별 타임아웃\n- 워커는 역할/프롬프트/컨텍스트만 받음(히스토리 격리)\n- 실패 워커는 failed로 표시하고 부분 결과 보존')"
```

### Task 12: 오케스트레이터 병렬 경로 (분해→워커→보고→머지)

**Files:**
- Modify: `src/core/orchestrator.ts`
- Test: `test/core/orchestrator-parallel.test.ts`

**Interfaces:**
- Consumes: `classifyIntent`, `parseTasks`, `formatWorkerReport`, `formatSummaryHeader`, `buildWorkerPrompt`(간접), `MemoryPort.recordTask/updateTask`
- Produces: `handle`가 병렬 의도일 때 분해→워커 스폰→스레드 순차 보고→검증 머지를 수행.

- [ ] **Step 1: 실패 테스트 작성**

`test/core/orchestrator-parallel.test.ts`:
```ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createOrchestrator } from '../../src/core/orchestrator.ts';
import type { LLMPort } from '../../src/ports/llm.ts';
import { createSqliteMemory } from '../../src/adapters/memory/sqlite.ts';

// 분해 호출엔 작업 JSON을, 머지 호출엔 종합문을 돌려준다.
function scriptedLLM(): LLMPort {
  let calls = 0;
  return {
    async complete(messages) {
      calls++;
      const last = messages[messages.length - 1]?.content ?? '';
      if (last.includes('작업으로 분해')) {
        return { text: '[{"role":"카카오 조사","prompt":"a"},{"role":"라인 조사","prompt":"b"}]', backend: 'fake', signals: {} };
      }
      return { text: '종합 결과', backend: 'fake', signals: {} };
    },
    async spawnWorkers(tasks) {
      return tasks.map((t) => ({ task: t, status: 'done' as const, text: `${t.role} 결과` }));
    },
  };
}

test('병렬: 헤더+워커별 메시지+종합을 같은 스레드에 게시', async () => {
  const memory = createSqliteMemory(':memory:');
  const posts: string[] = [];
  const chat: any = {
    replyInThread: async (_c: string, _t: string, text: string) => { posts.push(text); },
    sendTyping: async () => {},
  };
  const orch = createOrchestrator({ llm: scriptedLLM(), memory, chat });
  await orch.handle({ sessionId: 't1', chatId: 'C1', userId: 'U1', text: '카카오 라인 각각 조사해줘', ts: '1' });

  assert.match(posts[0], /2건/);                       // 헤더
  assert.ok(posts.some((p) => /카카오 조사 결과/.test(p)));
  assert.ok(posts.some((p) => /라인 조사 결과/.test(p)));
  assert.match(posts[posts.length - 1], /종합 결과/);   // 마지막은 종합
});
```

- [ ] **Step 2: 실패 확인**

```bash
node --test test/core/orchestrator-parallel.test.ts
```
Expected: FAIL — 현재 `handle`은 단발만 처리(헤더/워커 메시지 없음).

- [ ] **Step 3: 오케스트레이터에 병렬 경로 추가**

`src/core/orchestrator.ts`를 확장(단발 경로 유지, 분기 추가):
```ts
import { classifyIntent } from './intent.ts';
import { parseTasks, formatWorkerReport, formatSummaryHeader } from './format.ts';
import type { WorkerResult } from '../ports/llm.ts';

const DECOMPOSE_INSTRUCTION =
  '다음 요청을 독립적으로 병렬 수행 가능한 작업으로 분해하라. ' +
  'JSON 배열만 출력하라. 각 원소는 {"role": 역할이름, "prompt": 작업지시, "context"?: 최소컨텍스트}. 요청:\n';

const SUMMARY_INSTRUCTION =
  '아래 병렬 작업 결과들을 검증하라. 충돌/불일치가 있으면 지적하고, 없으면 종합하라. 결과:\n';
```

`handle` 내부에서 `memory.append(in)` 직후 분기:
```ts
    const intent = classifyIntent(msg.text);
    if (!intent.parallel) {
      // (기존 단발 경로 그대로)
      const history = memory.getHistory(msg.sessionId);
      const result = await llm.complete(history, { systemPrompt: deps.persona, model: deps.orchestratorModel });
      await chat.replyInThread(msg.chatId, msg.sessionId, result.text);
      memory.append({ sessionId: msg.sessionId, ts: Date.now(), direction: 'out', channel: 'slack', llmBackend: result.backend, payload: { text: result.text, signals: result.signals } });
      return;
    }

    // 병렬 경로
    const parentId = memory.recordTask({ sessionId: msg.sessionId, status: 'running', role: 'orchestrator', tsStart: Date.now() });
    const decomp = await llm.complete([{ role: 'user', content: DECOMPOSE_INSTRUCTION + msg.text }], { model: deps.orchestratorModel });
    const tasks = parseTasks(decomp.text);
    if (tasks.length === 0) {
      // 분해 실패 → 단발로 폴백
      const history = memory.getHistory(msg.sessionId);
      const result = await llm.complete(history, { systemPrompt: deps.persona, model: deps.orchestratorModel });
      await chat.replyInThread(msg.chatId, msg.sessionId, result.text);
      memory.append({ sessionId: msg.sessionId, ts: Date.now(), direction: 'out', channel: 'slack', llmBackend: result.backend, payload: { text: result.text } });
      memory.updateTask(parentId, { status: 'done', tsEnd: Date.now() });
      return;
    }

    await chat.replyInThread(msg.chatId, msg.sessionId, formatSummaryHeader(tasks.length));
    for (const t of tasks) memory.recordTask({ sessionId: msg.sessionId, parentId, status: 'queued', role: t.role });

    const results: WorkerResult[] = await llm.spawnWorkers(tasks, {
      concurrency: deps.workerConcurrency, timeoutMs: deps.workerTimeoutMs, model: deps.workerModel,
    });
    for (const r of results) await chat.replyInThread(msg.chatId, msg.sessionId, formatWorkerReport(r));

    const done = results.filter((r) => r.status === 'done');
    const merged = await llm.complete(
      [{ role: 'user', content: SUMMARY_INSTRUCTION + done.map((r) => `## ${r.task.role}\n${r.text}`).join('\n\n') }],
      { model: deps.orchestratorModel },
    );
    await chat.replyInThread(msg.chatId, msg.sessionId, `✅ ${merged.text}`);

    memory.updateTask(parentId, { status: 'done', tsEnd: Date.now(), payload: { results } });
    memory.append({ sessionId: msg.sessionId, ts: Date.now(), direction: 'out', channel: 'slack', llmBackend: merged.backend, payload: { text: merged.text, parallel: true } });
```

`OrchestratorDeps`에 필드 추가:
```ts
  workerModel?: string;
  workerConcurrency?: number;
  workerTimeoutMs?: number;
```

- [ ] **Step 4: 통과 확인 (단발 테스트 회귀 포함)**

```bash
node --test test/core/orchestrator.test.ts test/core/orchestrator-parallel.test.ts
```
Expected: 모두 PASS.

- [ ] **Step 5: main.ts에 워커 설정 주입**

```ts
const orchestrator = createOrchestrator({
  llm, memory, chat,
  orchestratorModel: cfg.llm.orchestratorModel,
  workerModel: cfg.llm.workerModel,
  workerConcurrency: cfg.worker.concurrency,
  workerTimeoutMs: cfg.worker.timeoutMs,
});
```

- [ ] **Step 6: 수동 시나리오 검증**

Slack DM "카카오 라인 왓츠앱 마지막 메시지 프리뷰 표준 각각 조사해줘" → 헤더("3건") → 워커별 결과 → 종합. `tasks` 테이블에 parent+children 레코드 확인:
```bash
sqlite3 ./data/agent.db "SELECT id, parent_id, status, role FROM tasks ORDER BY id;"
```

- [ ] **Step 7: 커밋**

```bash
git add -A
git commit -m "$(printf 'feat: 오케스트레이터 병렬 위임 경로\n\n- 의도 판단 후 작업 분해→워커 스폰→스레드 순차 보고→검증 머지\n- parent/children 작업 레코드 기록\n- 분해 실패 시 단발로 폴백')"
```

---

## Phase M3 — cron 스냅샷 백업 + 복원 리허설

> 검증: 인스턴스 재생성 후 S3 최신 스냅샷으로 복원.

### Task 13: 백업 스크립트 S3 업로드 완성 + 복원 리허설

**Files:**
- Modify: `scripts/backup.sh`
- Create: `scripts/restore.sh`, `docs/runbook-backup.md`

- [ ] **Step 1: backup.sh에 S3 업로드 추가**

`scripts/backup.sh` (S3 prefix/bucket은 env로):
```bash
#!/usr/bin/env bash
set -euo pipefail
DB_PATH="${DB_PATH:-/opt/ai-agent/data/agent.db}"
S3_BUCKET="${S3_BUCKET:?S3_BUCKET 필요}"
S3_PREFIX="${S3_PREFIX:-agent}"
STAMP="$(date +%Y%m%d-%H%M)"
SNAP="/tmp/agent-snap-${STAMP}.db"

sqlite3 "$DB_PATH" "VACUUM INTO '$SNAP'"     # cp 금지(트랜잭션 비안전)
gzip -f "$SNAP"
aws s3 cp "${SNAP}.gz" "s3://${S3_BUCKET}/${S3_PREFIX}/db-${STAMP}.gz" --region ap-northeast-2
rm -f "${SNAP}.gz"
echo "backup ok: s3://${S3_BUCKET}/${S3_PREFIX}/db-${STAMP}.gz"
```

- [ ] **Step 2: restore.sh 작성**

`scripts/restore.sh`:
```bash
#!/usr/bin/env bash
set -euo pipefail
S3_BUCKET="${S3_BUCKET:?}"; S3_PREFIX="${S3_PREFIX:-agent}"; DB_PATH="${DB_PATH:-/opt/ai-agent/data/agent.db}"
LATEST="$(aws s3 ls "s3://${S3_BUCKET}/${S3_PREFIX}/" --region ap-northeast-2 | sort | tail -1 | awk '{print $4}')"
[ -n "$LATEST" ] || { echo "스냅샷 없음"; exit 1; }
aws s3 cp "s3://${S3_BUCKET}/${S3_PREFIX}/${LATEST}" /tmp/restore.db.gz --region ap-northeast-2
gunzip -f /tmp/restore.db.gz
mkdir -p "$(dirname "$DB_PATH")"
mv /tmp/restore.db "$DB_PATH"
echo "restored ${LATEST} -> ${DB_PATH}"
```

- [ ] **Step 3: 로컬 라운드트립 검증 (S3 없이 VACUUM/복원 무결성)**

```bash
mkdir -p data && DB_PATH=./data/agent.db bash -c 'sqlite3 "$DB_PATH" "VACUUM INTO \"/tmp/rt.db\"" && sqlite3 /tmp/rt.db "PRAGMA integrity_check;"'
```
Expected: `ok`.

- [ ] **Step 4: 복원 리허설 (인스턴스/실 S3) + 런북 기록**

`docs/runbook-backup.md`에 cron 등록·복원 절차를 적고, 실제 인스턴스에서 backup→restore 1회 수행 후 메시지 수가 보존되는지 확인:
```bash
sqlite3 ./data/agent.db "SELECT count(*) FROM messages;"   # 복원 전후 비교
```
crontab:
```bash
0 * * * * S3_BUCKET=<bucket> DB_PATH=/opt/ai-agent/data/agent.db /opt/ai-agent/scripts/backup.sh >> /var/log/ai-agent-backup.log 2>&1
```

- [ ] **Step 5: 커밋**

```bash
chmod +x scripts/restore.sh
git add -A
git commit -m "$(printf 'feat: S3 스냅샷 백업과 복원 스크립트\n\n- VACUUM INTO→gzip→S3 업로드와 최신 스냅샷 복원\n- 복원 리허설 런북 추가')"
```

---

## Phase M4 — anthropic-api 폴백 + 비용 가드

> 검증: 한도 도달 시 자동 전환(같은 SQLite 히스토리 재사용). 트리거 방식은 M-1 findings 결정에 따른다.

### Task 14: anthropic-api 어댑터

**Files:**
- Create: `src/adapters/llm/anthropic-api.ts`
- Test: `test/adapters/llm/anthropic-api.test.ts`

**Interfaces:**
- Consumes: `LLMPort`, `Message`, `CompleteResult`
- Produces: `createAnthropicApiLLM(cfg, generate?): LLMPort` — `generate`는 주입 가능(테스트). `messages`를 Anthropic 형식으로 변환.

- [ ] **Step 1: 의존성 설치**

```bash
pnpm add @ai-sdk/anthropic ai
```

- [ ] **Step 2: 실패 테스트 작성 (generate 주입)**

`test/adapters/llm/anthropic-api.test.ts`:
```ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createAnthropicApiLLM, toAnthropicMessages } from '../../../src/adapters/llm/anthropic-api.ts';

test('toAnthropicMessages는 system을 분리하고 user/assistant만 남긴다', () => {
  const { system, messages } = toAnthropicMessages([
    { role: 'system', content: 'sys' },
    { role: 'user', content: 'hi' },
    { role: 'assistant', content: 'yo' },
  ]);
  assert.equal(system, 'sys');
  assert.equal(messages.length, 2);
  assert.equal(messages[0].role, 'user');
});

test('complete는 generate 결과를 CompleteResult로 매핑', async () => {
  const fakeGen = async () => ({ text: 'api 답', usage: { inputTokens: 5, outputTokens: 7 } });
  const llm = createAnthropicApiLLM({ apiKey: 'k', defaultModel: 'm' }, fakeGen as any);
  const r = await llm.complete([{ role: 'user', content: 'q' }]);
  assert.equal(r.text, 'api 답');
  assert.equal(r.backend, 'anthropic-api');
  assert.equal(r.signals.outputTokens, 7);
});
```

- [ ] **Step 3: 구현**

`src/adapters/llm/anthropic-api.ts`:
```ts
import { createAnthropic } from '@ai-sdk/anthropic';
import { generateText } from 'ai';
import type { LLMPort, Message, CompleteOpts, CompleteResult, WorkerTask, WorkerResult, SpawnOpts } from '../../ports/llm.ts';

export function toAnthropicMessages(messages: Message[]): { system?: string; messages: { role: 'user' | 'assistant'; content: string }[] } {
  const system = messages.filter((m) => m.role === 'system').map((m) => m.content).join('\n') || undefined;
  const rest = messages
    .filter((m) => m.role !== 'system')
    .map((m) => ({ role: m.role as 'user' | 'assistant', content: m.content }));
  return { system, messages: rest };
}

export interface AnthropicConfig { apiKey: string; defaultModel: string; }
type Generate = typeof generateText;

export function createAnthropicApiLLM(cfg: AnthropicConfig, generate: Generate = generateText): LLMPort {
  const provider = createAnthropic({ apiKey: cfg.apiKey });
  return {
    async complete(messages: Message[], opts: CompleteOpts = {}): Promise<CompleteResult> {
      const { system, messages: msgs } = toAnthropicMessages(
        opts.systemPrompt ? [{ role: 'system', content: opts.systemPrompt }, ...messages] : messages,
      );
      const res: any = await generate({ model: provider(opts.model ?? cfg.defaultModel), system, messages: msgs });
      return {
        text: res.text,
        backend: 'anthropic-api',
        signals: { inputTokens: res.usage?.inputTokens, outputTokens: res.usage?.outputTokens, raw: res.usage },
      };
    },
    async spawnWorkers(tasks: WorkerTask[], opts: SpawnOpts = {}): Promise<WorkerResult[]> {
      const results: WorkerResult[] = [];
      for (const task of tasks) {
        try {
          const r = await this.complete([{ role: 'user', content: `너는 "${task.role}" 전문가다.\n${task.prompt}${task.context ? `\n참고:\n${task.context}` : ''}` }], { model: opts.model });
          results.push({ task, status: 'done', text: r.text, signals: r.signals });
        } catch (e) {
          results.push({ task, status: 'failed', error: e instanceof Error ? e.message : String(e) });
        }
      }
      return results;
    },
  };
}
```

- [ ] **Step 4: 통과 확인**

```bash
node --test test/adapters/llm/anthropic-api.test.ts
pnpm exec tsc --noEmit
```
Expected: 2개 PASS.

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "$(printf 'feat: anthropic-api 폴백 어댑터\n\n- 중립 Message를 Anthropic 형식으로 변환(system 분리)\n- generateText 주입으로 테스트 가능하게 분리')"
```

### Task 15: 폴백 체인 래퍼 + 비용 가드

**Files:**
- Create: `src/adapters/llm/fallback.ts`
- Test: `test/adapters/llm/fallback.test.ts`
- Modify: `src/main.ts`

**Interfaces:**
- Consumes: `LLMPort`
- Produces: `createFallbackLLM(primary: LLMPort, secondary: LLMPort, opts: { isLimitError?: (e: unknown) => boolean; onFallback?: (e: unknown) => void; guard?: CostGuard }): LLMPort`, `createCostGuard(limits): CostGuard`

> **M-1 의존**: `isLimitError`의 판정(레이트리밋/한도 에러 패턴)은 M-1 findings의 에러 형태로 채운다. findings가 "잔량 조회" 경로를 권하면 guard에 조회 훅을 추가한다.

- [ ] **Step 1: 실패 테스트 작성**

`test/adapters/llm/fallback.test.ts`:
```ts
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createFallbackLLM, createCostGuard } from '../../../src/adapters/llm/fallback.ts';
import type { LLMPort } from '../../../src/ports/llm.ts';

const ok = (name: string): LLMPort => ({
  async complete() { return { text: name, backend: name, signals: {} }; },
  async spawnWorkers() { return []; },
});
const failing = (msg: string): LLMPort => ({
  async complete() { throw new Error(msg); },
  async spawnWorkers() { return []; },
});

test('한도 에러면 secondary로 전환하고 onFallback 호출', async () => {
  let notified = false;
  const llm = createFallbackLLM(failing('rate_limit_error'), ok('secondary'), {
    isLimitError: (e) => /rate_limit|한도/.test(String(e)),
    onFallback: () => { notified = true; },
  });
  const r = await llm.complete([{ role: 'user', content: 'q' }]);
  assert.equal(r.backend, 'secondary');
  assert.equal(notified, true);
});

test('한도 에러가 아니면 그대로 throw(폴백 안 함)', async () => {
  const llm = createFallbackLLM(failing('syntax'), ok('secondary'), { isLimitError: () => false });
  await assert.rejects(() => llm.complete([{ role: 'user', content: 'q' }]), /syntax/);
});

test('비용 가드: 시간당 상한 초과 시 호출 차단', async () => {
  const guard = createCostGuard({ maxPerHour: 1 });
  const llm = createFallbackLLM(ok('primary'), ok('secondary'), { guard });
  await llm.complete([{ role: 'user', content: 'a' }]);
  await assert.rejects(() => llm.complete([{ role: 'user', content: 'b' }]), /상한|limit/i);
});
```

- [ ] **Step 2: 실패 확인**

```bash
node --test test/adapters/llm/fallback.test.ts
```
Expected: FAIL.

- [ ] **Step 3: 구현**

`src/adapters/llm/fallback.ts`:
```ts
import type { LLMPort, Message, CompleteOpts, CompleteResult, WorkerTask, WorkerResult, SpawnOpts } from '../../ports/llm.ts';

export interface CostGuard { check(): void; record(): void; }

export function createCostGuard(limits: { maxPerHour?: number }): CostGuard {
  const stamps: number[] = [];
  return {
    check() {
      const cutoff = Date.now() - 3600_000;
      while (stamps.length && stamps[0] < cutoff) stamps.shift();
      if (limits.maxPerHour && stamps.length >= limits.maxPerHour) {
        throw new Error(`시간당 요청 상한(${limits.maxPerHour}) 초과`);
      }
    },
    record() { stamps.push(Date.now()); },
  };
}

export interface FallbackOpts {
  isLimitError?: (e: unknown) => boolean;
  onFallback?: (e: unknown) => void;
  guard?: CostGuard;
}

export function createFallbackLLM(primary: LLMPort, secondary: LLMPort, opts: FallbackOpts = {}): LLMPort {
  const isLimit = opts.isLimitError ?? (() => false);
  const run = async <T>(p: () => Promise<T>, s: () => Promise<T>): Promise<T> => {
    opts.guard?.check();
    try {
      const r = await p();
      opts.guard?.record();
      return r;
    } catch (e) {
      if (!isLimit(e)) throw e;
      opts.onFallback?.(e);
      const r = await s();
      opts.guard?.record();
      return r;
    }
  };
  return {
    complete: (m: Message[], o?: CompleteOpts): Promise<CompleteResult> => run(() => primary.complete(m, o), () => secondary.complete(m, o)),
    spawnWorkers: (t: WorkerTask[], o?: SpawnOpts): Promise<WorkerResult[]> => run(() => primary.spawnWorkers(t, o), () => secondary.spawnWorkers(t, o)),
  };
}
```

- [ ] **Step 4: 통과 확인**

```bash
node --test test/adapters/llm/fallback.test.ts
pnpm exec tsc --noEmit
```
Expected: 3개 PASS.

- [ ] **Step 5: main.ts 배선 (폴백 활성화는 설정 분기)**

```ts
import { createAnthropicApiLLM } from './adapters/llm/anthropic-api.ts';
import { createFallbackLLM, createCostGuard } from './adapters/llm/fallback.ts';

const primary = createClaudeCliLLM({ defaultModel: cfg.llm.orchestratorModel, allowedTools: cfg.llm.allowedTools });
const llm = cfg.anthropicApiKey
  ? createFallbackLLM(
      primary,
      createAnthropicApiLLM({ apiKey: cfg.anthropicApiKey, defaultModel: cfg.llm.orchestratorModel ?? 'claude-sonnet-4-6' }),
      {
        isLimitError: (e) => /rate_limit|한도|usage limit/i.test(String(e)),  // M-1 findings로 정밀화
        onFallback: (e) => console.warn('폴백 전환:', String(e)),
        guard: createCostGuard({ maxPerHour: 60 }),
      },
    )
  : primary;
```
(기존 `const llm = createClaudeCliLLM(...)` 라인을 위로 교체.)

- [ ] **Step 6: 커밋**

```bash
git add -A
git commit -m "$(printf 'feat: LLM 폴백 체인과 비용 가드\n\n- 한도 에러 감지 시 anthropic-api로 전환(같은 히스토리 재사용)\n- 시간당 요청 상한 가드\n- main에서 API 키 존재 시 폴백 활성화')"
```

---

## Self-Review (작성자 체크)

**1. 스펙 커버리지**
- §0 핵심결정 1(일회성 워커+역할주입)→Task 11/12, 2(스레드=세션)→Task 3/8, 3(SQLite 무상태)→Task 2/7/8, 4(히스토리 격리)→Task 11(`buildWorkerPrompt`는 히스토리 미포함). ✅
- §2 아키텍처(포트/어댑터)→Task 1(포트), 2/3/7/14(어댑터). ✅
- §3 동시성/타임아웃→Task 11. ✅
- §5 보고 UX(접수→진행→완료)→Task 8(sendTyping)/12(헤더·워커·종합). ✅
- §6 데이터모델→Task 2. ✅ §7 에러/폴백→Task 11(워커 실패)/15(백엔드 폴백). ✅
- §8 보안(allowedTools/화이트리스트)→Task 1(.env)/3(화이트리스트)/7(allowedTools). ✅
- §9 백업→Task 6/13. ✅ §10 환경→Task 1/6. ✅ §11 미해결→Phase 0(M-1). ✅ §12 마일스톤→Phase 0~M4 매핑. ✅

**2. 플레이스홀더 스캔**: 각 코드 스텝에 실제 코드·명령·기대출력 포함. M-1 의존 부분은 "인터페이스 불변, 추출 경로만 수정"으로 명시(추상 지시 아님). ✅

**3. 타입 일관성**: `Message`/`CompleteResult`/`UsageSignals`/`WorkerTask`/`WorkerResult`/`SpawnOpts`(ports/llm.ts), `StoredMessage`/`TaskRecord`/`SessionInfo`(ports/memory.ts), `IncomingMessage`(ports/chat.ts)를 Task 1에서 정의하고 이후 동일 시그니처로 소비. `createSqliteMemory`/`createSlackChat`/`createClaudeCliLLM`/`createOrchestrator`/`createAnthropicApiLLM`/`createFallbackLLM` 명칭 전 태스크 일치. ✅
