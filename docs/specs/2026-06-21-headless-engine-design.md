# Phase 0 — 헤드리스 엔진 설계 스펙

- 상태: **확정(v1)** — Sonnet 서브에이전트 2개 적대 검토(YAGNI·기술) 반영 완료
- 작성일: 2026-06-21
- 작성자: 방예혁 + Claude(브레인스토밍)
- 상위 문서: `docs/specs/2026-06-21-ai-workflow-lab-vision.md` (비전 v2) — 본 스펙은 그 로드맵 **Phase 0**의 단독 설계
- 선행 자산(참고용, Kotlin 재구현 대상): `src/ports/llm.ts`, `src/adapters/llm/claude-cli.ts` (기존 Node/TS — git 히스토리에만 남는다)

> **이 스펙의 범위:** Phase 0 = `LlmPort` + claude-cli **스트리밍 어댑터**를 **콘솔/테스트로만** 검증한다.
> UI·HTTP·SSE·SQLite·병렬 워커는 **범위 밖**(각 후속 Phase). 키스톤(안정적 이벤트 API)도 연기(비전 §3 ⚠️).

---

## 0. 한 줄 요약

> UI 없이 콘솔/테스트로 LLM 호출 파이프를 먼저 깐다. `messages → text`(complete) / `messages → Flow`(stream) 두 입구.
> primary claude-cli, 폴백 Anthropic API 라우팅 자리. 구독 헤드리스라 **비용 0**으로 코어를 다진다.

## 1. 목표 & 성공 기준 (검증 가능)

| # | 성공 기준 | 검증 방법 |
|---|---|---|
| G1 | `complete(messages)` 가 claude-cli를 호출해 최종 텍스트 + 사용량 시그널을 돌려준다 | 통합 테스트: 실제 `claude -p` 1회 호출 → text 비어있지 않음, `result.usage` 파싱 확인 |
| G2 | `stream(messages)` 가 **종료 전에** 진행 중 텍스트를 점진적으로 흘린다 | 통합 테스트: `Done` 이전에 `TextDelta`가 1건 이상 도착(= 진짜 점진 전달). ⚠️ "이벤트 2건"만으로는 블록 통째여도 통과 → 부족. 실제 입자 기준은 S1 확정에 따름 |
| G3 | 타임아웃/취소 시 spawn된 claude 프로세스가 **확실히 죽는다**(좀비 0) | 테스트: 짧은 타임아웃 → 취소 후 `pgrep claude` 잔존 0 (또는 프로세스 핸들 종료 확인) |
| G4 | 알 수 없는 이벤트 라인이 와도 파서가 죽지 않고 무시한다 | 단위 테스트: 미지 `type` 라인 주입 → 예외 없음 |
| G5 | 콘솔 진입점으로 사람이 직접 한 턴 돌려볼 수 있다 | `./gradlew run` 또는 콘솔 main → 프롬프트 입력 → 응답 출력 |

비-목표(이 Phase 아님): 웹/SSE, SQLite 영속, 에이전트 레지스트리, **병렬 워커**(Phase 1), 에이전틱 도구사용(상위 capability 레이어), 안정적 이벤트 API(키스톤, 연기).

## 2. LlmPort — 베이스 포트 (확정: complete + stream 2개)

```kotlin
interface LlmPort {
    suspend fun complete(messages: List<Message>, opts: CompleteOpts = CompleteOpts()): CompleteResult
    fun stream(messages: List<Message>, opts: CompleteOpts = CompleteOpts()): Flow<LlmEvent>
}
```

- **딱 2개만.** 병렬 위임(`spawnWorkers`)은 베이스 포트에 넣지 않는다 — 상위 capability 레이어(Phase 1)로. 에이전틱 도구사용도 마찬가지. (근거: 확정 사실 — "claude -p 는 에이전트≠모델, 베이스 포트엔 안 넣음")
- `claude -p` 는 **모델 호출**로만 취급한다(에이전트 아님).
- **라우터 추상화:** `LlmPort` 구현체가 primary(claude-cli) → fallback(Anthropic API)을 감싼다. 호출자는 백엔드를 모른다.

기존 Node 포트(`src/ports/llm.ts:61`)와의 차이: 기존은 `complete` + `spawnWorkers`. 신규는 `complete` + `stream`. 스트리밍이 들어오고 병렬이 상위로 빠진다.

### 2.0 `stream()` 이 흘리는 이벤트 — `LlmEvent` (정규화, D1 확정)

`stream()`은 **백엔드 무관 정규화 이벤트**를 흘린다. claude-cli와 (후속) Anthropic API가 둘 다 이 타입으로 매핑된다 → 소비자는 누가 응답했는지 모른다(라우터 추상화의 핵심).

```kotlin
sealed interface LlmEvent {
    data class TextDelta(val text: String) : LlmEvent        // 진행 중 토큰/블록
    data class Done(val result: CompleteResult) : LlmEvent   // 종료 — usage·cost 동봉
    data class Error(val status: Int?, val message: String) : LlmEvent  // 예: 429
}
```

**왜 정규화인가 (정직한 근거):**
- **합 타입은 어차피 불가피.** `Flow<String>` 한 줄로는 종료 시 `usage/cost`, 에러 시 `apiErrorStatus`를 실을 수 없다. 그래서 `TextDelta`/`Done`/`Error` 최소 3-case 합 타입이 반드시 필요하다. 남는 선택은 "그 합 타입을 claude 종속(`ClaudeCliLine`)으로 노출하느냐, 백엔드 중립으로 두느냐"뿐인데, **중립 네이밍의 추가 비용이 거의 0**이라 중립으로 간다.
- **연기한 키스톤과 스케일이 다르다.** 키스톤은 *엔진↔뷰 사이 시스템 횡단 계약*(뷰 0~1개일 때 미리 박으면 YAGNI). `LlmEvent`는 *한 포트 메서드의 반환 타입*이다 — 어차피 필요한 타입을 어떻게 이름 붙이냐의 문제라 같은 류의 선반영이 아니다.
- ⚠️ **솔직한 한계:** D2대로 Phase 0엔 2번째 백엔드(API)가 실제로는 stub이다. 즉 "지금 당장 두 백엔드가 LlmEvent를 공유한다"는 효용은 **아직 없고**, 두 번째 어댑터가 실제로 생기는 Phase에 가서 실증된다. 그럼에도 위 두 근거(불가피한 합 타입 + ≈0 비용)로 선도입을 택한다. 이 한계를 알고 내린 결정.
- **`ClaudeCliLine`(§3)은 중복 계층이 아니다.** claude 와이어 지식이 멈추는 **어댑터 내부 경계**이자, 확정된 Kotlin 학습 과제(sealed + exhaustive `when`)다. 파싱: NDJSON 라인 → `ClaudeCliLine` → (exhaustive `when`) → `LlmEvent`. 이 `when`이 곧 학습 포인트.

### 2.1 도메인 타입 (초안 — Kotlin)

```kotlin
data class Message(val role: Role, val content: String)
enum class Role { USER, ASSISTANT, SYSTEM }

data class CompleteOpts(
    val model: String? = null,
    val systemPrompt: String? = null,
    val allowedTools: List<String> = emptyList(),   // 보안: read-only first (conventions.md)
)

data class CompleteResult(
    val text: String,
    val backend: String,            // "claude-cli" | "anthropic-api" — 관측/로깅용. 소비자 분기 금지(라우터 투명성 유지)
    val signals: UsageSignals,
    val isError: Boolean = false,
    val apiErrorStatus: Int? = null, // 예: 429
)

data class UsageSignals(           // 출처: 기존 llm.ts:14 — M-1 스파이크 확정 항목
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cacheCreationInputTokens: Int? = null,
    val cacheReadInputTokens: Int? = null,
    val costUsd: Double? = null,   // result.total_cost_usd (구독 소진율 대용)
    val rateLimit: RateLimitSignal? = null,  // S3 전까지 미채움(폴백 트리거용, 아래)
    val raw: Map<String, Any?>? = null,      // result.usage 원본(디버깅). 출처: 기존 llm.ts:23, claude-cli.ts:58
)

data class RateLimitSignal(        // 출처: 기존 llm.ts:8-12. 실제 값은 스파이크 S3로 확정 전까지 미검증
    val status: String? = null,
    val rateLimitType: String? = null, // 예: "five_hour"
    val resetsAt: Long? = null,         // epoch seconds
)
```

## 3. claude-cli 이벤트 모델 (sealed interface + exhaustive when)

claude-cli `stream-json` 출력의 **각 NDJSON 라인**을 sealed interface로 모델링한다. 이건 **어댑터 내부 와이어 포맷**이다(포트 레벨 이벤트와 구분 — §4).

```kotlin
sealed interface ClaudeCliLine {
    // type:"assistant" — message.content[]에 text 블록이 복수로 올 수 있다. 전부 추출해 join한 결과.
    // (기존 claude-cli.ts:29-36은 content[] 순회 + push, 65행에서 join('') — 첫 블록만 쓰면 텍스트 잘림 버그)
    data class Assistant(val text: String) : ClaudeCliLine
    // ⚠️ type:"stream_event" — 스파이크 S1 전 placeholder. 실제 라인 스키마 확정 시 이 케이스 변경 예정.
    data class StreamEvent(val delta: String) : ClaudeCliLine
    data class Result(val text: String, val signals: UsageSignals,
                      val isError: Boolean, val apiErrorStatus: Int?) : ClaudeCliLine  // type:"result"
    data class RateLimit(val signal: RateLimitSignal) : ClaudeCliLine // type:"rate_limit_event"
    data object Unknown : ClaudeCliLine                              // 미지 type → skip (G4)
}
```

파서는 `when (line)` 을 **exhaustive**하게 처리(미지 타입은 `Unknown`으로 흡수). 출처: 기존 `parseStreamJson`(`claude-cli.ts:28-63`)이 처리하던 `assistant`/`result`/`rate_limit_event` + 미지 default-skip를 Kotlin sealed로 옮긴 것. **`StreamEvent`는 현재 Node 코드에 없다** — `--include-partial-messages` 플래그를 붙여야 나오는 라인이라 신규. 스키마가 S1로 확정되기 전까지 위 `StreamEvent`는 **잠정 placeholder**(단정 금지와 일관).

## 4. claude-cli 어댑터

### 4.1 CLI 인자

```
claude -p --output-format stream-json --verbose [--include-partial-messages] \
       [--model X] [--allowedTools a,b] [--append-system-prompt "..."]
```

- `--verbose`: 기존 코드(`claude-cli.ts:108`)가 `stream-json`에 **항상 붙여 왔다**. 다만 "없으면 안 나온다"는 필수성은 코드가 증명하지 못한다(문서/실측 영역) → 스파이크 S1에서 확인.
- `--include-partial-messages` 는 **진짜 토큰 스트리밍**(`stream_event` 델타 라인)을 켠다. 없으면 블록 단위(메시지 완성 후 통째). 출처: 적대적 검토 + https://code.claude.com/docs/en/headless → **스파이크 S1로 실제 입자 확정 전까지 단정 금지.**
- 보안: `--allowedTools` 화이트리스트, read-only first / `--dangerously-skip-permissions` 격리 환경만 (`docs/conventions.md`).

### 4.2 스트리밍 읽기

stdout을 **라인 단위로 점진 파싱** → `ClaudeCliLine` → 포트 레벨 `LlmEvent`로 매핑해 `Flow` emit.
기존 `complete()`(`claude-cli.ts:118`)는 stdout **전체를 버퍼링 후** 파싱 → 스트리밍이 아니다.

⚠️ **블로킹 I/O 취소 함정(중요).** 순진하게 `flow { BufferedReader.lineSequence().forEach { emit(...) } }`로 짜면, JVM의 블로킹 `readLine()`은 **코루틴 취소에 반응하지 않는다** — `cancel()`이 와도 다음 라인이 도착(또는 EOF)하기 전까지 반환 못 해 `finally`(=`destroyForcibly`)에 도달하지 못한다. 따라서:
- **`callbackFlow` + 전용 읽기 + `awaitClose { process.destroyForcibly() }`** 패턴을 택한다(별도 스레드/`Dispatchers.IO`에서 stdout을 읽어 `trySend`, 채널이 닫히면 `awaitClose`에서 프로세스 강제 종료). 단순 `flow{}`+`lineSequence`는 쓰지 않는다.
- **partial-line 처리:** NDJSON이 항상 한 read에 완결된 한 줄로 온다는 보장이 없다(긴 content). 라인 경계 버퍼링 필요 → 스파이크 S7.

`complete()` 구현은 **D3**(아래) — `stream()`을 소비해 합성한다. `stream()`이 `Error`를 emit하면 `complete()`는 **예외를 던지지 않고** `CompleteResult(isError=true, ...)`로 돌려준다(기존 `parseStreamJson` 동작과 일치, `claude-cli.ts:50`).

### 4.3 ⚠️ 취소/타임아웃 → destroyForcibly (현 코드 버그 수정)

**기존 버그(`defaultRunner` 한정, 확인됨):** `defaultRunner`(`claude-cli.ts:94-104`)는 spawn한 child 핸들을 클로저 안에만 가둔다. `spawnWorkers`의 타임아웃은 `Promise.race([runnerPromise, timeout])`(137행)로 **JS Promise만 버릴 뿐 OS 프로세스는 살아남는다.** `runnerPromise.catch(()=>{})`(135행)는 unhandledRejection 억제일 뿐. (주입 runner를 쓰는 테스트 경로는 해당 없음 — `claude-cli.ts:106`.)

→ 살아남은 claude 프로세스 = **자원 낭비(CPU/메모리) + 구독의 동시 호출/세션 슬롯 점유**다. ⚠️ 구독 헤드리스라 **토큰 추가 과금(=$ 비용)은 아니다** — "비용 누수"가 아니라 자원/슬롯 누수로 본다.

**신규 규칙(G3):** 코루틴 취소·타임아웃 시 반드시 자식 프로세스를 종료한다.
- `callbackFlow`의 `awaitClose`(§4.2) 또는 `withTimeout`/수집 취소 경로에서 `process.destroyForcibly()`.
- graceful `destroy()`(SIGTERM) 후 grace 두고 force(`destroyForcibly`, SIGKILL) 가능하나, **취소된 코루틴에서 `Thread.sleep`로 대기 금지** — 정리 대기는 `withContext(NonCancellable) { withTimeoutOrNull(grace) { ... } }` 식으로. (구현 계획에서 확정)
- 테스트로 좀비 0 검증(S2 → G3).

## 5. 폴백 라우팅 (Anthropic API)

- 라우터(`LlmPort` 구현)는 primary claude-cli 호출이 **rate-limit / 특정 에러**일 때 Anthropic API로 폴백한다.
- 트리거 후보(기존 코드 기준): `result.is_error` **또는** `subtype !== 'success'`(`claude-cli.ts:50` — `is_error`만 보면 subtype 기반 에러를 놓침), `api_error_status==429`, `rate_limit_event`. **단 정확한 임계·`subtype` 실제 값은 미검증 → 스파이크 S3.**
- ⚠️ **Phase 0 범위(D2): seam만.** 라우터 인터페이스와 폴백 자리(slot)는 둔다. 그러나 **트리거 판정 로직을 S3 전에 코드에 박지 않는다**(박으면 단정). 실제 API 어댑터 + 판정 구현은 S3 확정 후. Phase 0의 폴백 분기는 "미구현(TODO S3)"로 비워둔다.

## 6. 스파이크 항목 (구현 전 검증 — 단정 금지)

| ID | 검증할 것 | 왜 |
|---|---|---|
| **S1** | `--include-partial-messages` 실제 출력 입자(토큰 델타 vs 블록) + `stream_event` 라인 스키마 + `--verbose` 필수성 | `ClaudeCliLine.StreamEvent` 모양·`stream()` 설계가 여기 달림 |
| **S2** | 타임아웃/취소 후 `destroyForcibly` 가 실제로 좀비를 안 남기는지 (`pgrep claude`) | G3 핵심 |
| **S3** | 구독 헤드리스의 rate-limit 신호 형태·`subtype` 실제 값·폴백 트리거 임계 | D2(폴백 경계) 확정 |
| **S4** | JDK 25 Virtual Thread(Loom) ↔ 코루틴 `Dispatchers.IO` 상호작용 + 블로킹 프로세스 I/O 취소(스레드 pinning) | `callbackFlow` 취소→`destroyForcibly` 경로가 실제로 동작하나 (단순 "관용구 검증" 아님) |
| **S5** | stdout·stderr **동시 소비**(한쪽 버퍼 만차 시 프로세스 블록 = 고전 데드락). 기존 코드는 stderr 별도 리스너(`claude-cli.ts:99`) | 어댑터 안정성 |
| **S6** | stdin 쓰기→EOF(`end()`) 타이밍(`claude-cli.ts:102-103`) 과 stdout 읽기 경합 | 프롬프트 처리 시작 조건 |
| **S7** | 라인 경계: NDJSON이 한 read에 완결되지 않을 때(긴 content) partial-line 버퍼링 | 라인 스트리밍 정확성 |

(참고: claude 바이너리는 PATH 의존 — `ProcessBuilder("claude", …)`. IDE/`gradlew run`의 PATH가 셸과 다를 수 있음. 구현 시 실행 환경 PATH 확인.)

## 7. 확정된 결정 (D1~D3)

- **D1 — `stream()`이 흘리는 이벤트 = 정규화 `LlmEvent`** (§2.0). `TextDelta`/`Done`/`Error`. 어댑터 내부 `ClaudeCliLine`은 유지(와이어 경계 + Kotlin 학습), `LlmEvent`는 공개 투영. 근거: usage/error 때문에 합 타입이 불가피 + 중립 네이밍 추가비용 ≈0 + 시스템 횡단 계약(키스톤) 아닌 한 메서드 반환 타입. (한계: Phase 0엔 2번째 백엔드가 stub이라 공유 효용은 후속 Phase에 실증 — §2.0 ⚠️.)
- **D2 — Anthropic API 폴백 = Phase 0엔 seam + 트리거 판정만.** claude-cli 경로를 완성하고 라우터에 폴백 자리·트리거 판정(§5)만 둔다. 실제 API 어댑터는 스파이크 S3 후 얇게 or 다음 Phase. 헤드리스 범위 최소화.
- **D3 — `complete()` = `stream()` 소비 후 텍스트 합성.** `stream()`의 `TextDelta`를 모으고 `Done`에서 `CompleteResult` 확정. 비스트리밍 별도 호출 경로는 만들지 않는다(중복 제거).

## 8. 이번 Phase 산출물

- 이 설계 스펙 (적대 검토 반영, 확정)
- 다음: 스파이크(S1~S7) → 구현 계획 → TDD 구현(콘솔/테스트 검증, G1~G5)
