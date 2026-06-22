# Phase 1 — 멀티에이전트 병렬 설계 스펙

- 상태: **확정(v1)** — 브레인스토밍(범위 A/B/C·실패처리) 반영
- 작성일: 2026-06-22
- 작성자: 방예혁 + Claude(브레인스토밍)
- 상위 문서: `docs/specs/2026-06-21-ai-workflow-lab-vision.md` (비전 v2) — 로드맵 **Phase 1**의 단독 설계
- 선행: `docs/specs/2026-06-21-headless-engine-design.md` (Phase 0 엔진, `LlmPort` 확정) + `c8fcffe` 구현
- 선행 자산(참고용): `src/adapters/llm/claude-cli.ts`(`spawnWorkers`/`runWithLimit`/`buildWorkerPrompt`), `src/ports/llm.ts`(`WorkerTask`/`WorkerResult`/`SpawnOpts`)

> **범위:** Phase 0 `LlmPort` 위에 얹는 **병렬 위임 capability 레이어**를 **콘솔/테스트로만** 검증한다.
> 엔진 코어(claude 어댑터)는 안 건드린다 — `LlmPort`만 소비. 웹·SSE·SQLite·에이전트 레지스트리는 범위 밖.

---

## 0. 한 줄 요약

> 한 목표를 여러 워커에 **병렬 위임**(동시성 제한 `Semaphore` 팬아웃)하고, 결과를 **한 답으로 머지**한다.
> 단발/병렬 **판단·분해(플래너)** 는 이번엔 사람(콘솔)이 — LLM 플래너는 후속(B)에 같은 파이프라인 앞단으로 붙는다.

## 1. 목표 & 성공 기준 (검증 가능)

| # | 성공 기준 | 검증 방법 |
|---|---|---|
| P1 | N개 태스크가 **동시 실행**되되 `concurrency` 이하로 제한된다 | 단위: fake `LlmPort`(지연+동시 카운터) → 관측 최대 동시 실행 수 == `min(concurrency, N)` |
| P2 | 워커 1개가 실패(에러/타임아웃)해도 **나머지는 Done + 예외 없음**(부분 결과) | 단위: fake가 특정 태스크만 실패 → 그 태스크만 `Failed`, 나머지 `Done` |
| P3 | 신서사이저가 성공 결과들을 **한 답으로 머지**한다 (성공 1개·전부면 머지 생략) | 단위: fake `LlmPort` → 머지 호출 여부·프롬프트 내용 검증, 단락(short-circuit) 확인 |
| P4 | `runParallel` 취소 시 **모든 워커가 취소**된다(구조적 동시성), 호출이 즉시 완료 | 단위: 지연 fake → 취소 → 즉시 종료. (자식 프로세스 destroy는 Phase 0 G3이 보장) |
| P5 | 콘솔로 사람이 **실제 병렬 턴 1회**를 돌린다 | 통합(`@Tag("integration")`): 작은 태스크 2~3개 실제 claude → `merged` 비어있지 않음, 워커 `Done` |

비-목표(이 Phase 아님): **LLM 플래너**(단발/병렬 판단·자동 분해 — B), 워커별 실시간 스트리밍(Phase 2/병렬보드), 에이전트 레지스트리·영속(Phase 3), 웹/SSE.

## 2. 범위 결정 — C (워커풀 + LLM 머지), 플래너는 seam

병렬 처리는 4역할의 파이프라인이다: **판단 → 분해 → 워커풀(동시 실행) → 머지**. "누가 판단·분해·머지를 하느냐"로 범위가 갈린다.

- **A (워커풀만):** 판단·분해·머지 전부 사람(콘솔). 레이어는 동시 팬아웃 + 부분결과 수집만.
- **C (채택):** A + **LLM 머지**(신서사이저). 분해·판단은 사람. 병렬의 보람("흩어진 N개 → 한 답")을 가져가되 분해는 결정적이라 테스트 쉬움.
- **B (연기):** C + **LLM 플래너**(판단·자동 분해). 전자동·비전 그대로지만 비결정적이라 테스트·디버깅 부담.

**왜 C인가:** 코루틴 동시성 학습 핵심(`WorkerPool`)은 세 안 공통. 머지는 fake `LlmPort`로 **결정적 테스트 가능**하고 가치가 분명(한 답). 비결정적이라 골치 아픈 건 플래너 하나뿐이라 그것만 미룬다. (비전 리스크 #3 "솔로 토이의 적=미완성"과 일관.)

**플래너 seam (YAGNI):** 빈 `Planner` 인터페이스를 미리 만들지 않는다 — Phase 0의 키스톤/폴백 연기와 같은 원칙(뷰/근거 없는 추상화 금지). 경계는 **`runParallel`의 `tasks` 파라미터 그 자체**다. C는 콘솔/테스트가 `tasks`를 직접 채우고, B는 `request → tasks` 플래너 단계를 **재작성 없이 앞에 추가**한다.

## 3. 아키텍처 — 3개 격리 단위

패키지 `omabang.engine.orchestrate`. `LlmPort`만 의존(엔진 코어 불변).

```
Orchestrator.runParallel(goal, tasks, opts)
   │
   ├─▶ WorkerPool      tasks N개를 Semaphore로 동시성 제한 팬아웃
   │                   각 task = llm.complete() 1콜 (무상태·역할 주입)
   │                   → List<WorkerResult>  (부분 성공: Done/Failed)
   │
   └─▶ Synthesizer     goal + 성공 결과들 → llm.complete() 1콜 머지
                       → merged 텍스트
```

- **`WorkerPool`** — 순수 동시성 메커니즘. 코루틴/`Semaphore` 학습 핵심. 재사용·독립 테스트.
- **`Synthesizer`** — 머지 1책임. fake `LlmPort`로 결정적 테스트.
- **`Orchestrator`** — 위 둘을 조립. B 플래너는 이 앞에 붙는다.

각 단위는 "무엇을 하나/어떻게 쓰나/무엇에 의존하나"가 한눈에 답되고, 내부를 바꿔도 소비자가 안 깨진다.

## 4. 도메인 타입

```kotlin
// 워커 1개 = 무상태 단발 호출. 역할 주입 + 프롬프트 + 최소 컨텍스트(히스토리 아님). 출처: Node WorkerTask
data class WorkerTask(val role: String, val prompt: String, val context: String? = null)

// sealed + exhaustive when (Kotlin 학습 포인트). done은 text, failed는 error.
sealed interface WorkerResult {
    val task: WorkerTask
    data class Done(override val task: WorkerTask, val text: String, val signals: UsageSignals) : WorkerResult
    data class Failed(override val task: WorkerTask, val error: String) : WorkerResult
}

data class OrchestrationResult(
    val merged: String,              // 신서사이저 합성 결과
    val workers: List<WorkerResult>, // per-task 원본(부분 성공 관측·디버깅)
    val mergeSignals: UsageSignals?, // 머지 호출 usage (머지 생략 시 null)
)

data class ParallelOpts(
    val concurrency: Int = 4,            // Semaphore permit 수 (Node 기본 3 → 4)
    val workerTimeoutMs: Long = 180_000, // 워커당 타임아웃 (Node와 동일)
    val model: String? = null,
    val allowedTools: List<String> = emptyList(), // 보안: read-only first (conventions.md)
)
```

**역할 주입:** `role` → `CompleteOpts.systemPrompt`(`--append-system-prompt`), `prompt`+`context` → user 메시지. Phase 0에서 만든 옵션 활용(Node는 프롬프트에 역할을 박았음 — systemPrompt가 더 깔끔, D1).

## 5. WorkerPool — 동시성 상세

```kotlin
class WorkerPool(private val llm: LlmPort) {
    suspend fun run(tasks: List<WorkerTask>, opts: ParallelOpts): List<WorkerResult> = coroutineScope {
        val gate = Semaphore(opts.concurrency)                     // Node runWithLimit 대체
        tasks.map { task -> async { gate.withPermit { runWorker(task, opts) } } }.awaitAll()
    }

    private suspend fun runWorker(task: WorkerTask, opts: ParallelOpts): WorkerResult {
        val sys = "너는 \"${task.role}\" 전문가다. 주어진 작업만 독립적으로 수행하고 결과를 간결히 보고하라."
        val user = task.prompt + (task.context?.let { "\n\n참고 컨텍스트:\n$it" } ?: "")
        val co = CompleteOpts(model = opts.model, systemPrompt = sys, allowedTools = opts.allowedTools)
        return try {
            val r = withTimeoutOrNull(opts.workerTimeoutMs) { llm.complete(listOf(Message(Role.USER, user)), co) }
                ?: return WorkerResult.Failed(task, "timeout ${opts.workerTimeoutMs}ms")
            if (r.isError) WorkerResult.Failed(task, "claude error status=${r.apiErrorStatus}")
            else WorkerResult.Done(task, r.text, r.signals)
        } catch (ce: CancellationException) {
            throw ce                                               // 취소는 전파(외부 취소 시 형제도 취소)
        } catch (e: Exception) {
            WorkerResult.Failed(task, e.message ?: e.toString())   // 비-취소 예외는 Failed로 흡수 → 부분 결과 보존
        }
    }
}
```

**핵심 동작:**
- **부분 결과의 비밀(D2):** 실패를 예외가 아니라 `WorkerResult.Failed`로 *반환*하므로 `awaitAll()`이 안 깨지고 형제가 살아남는다. 예외로 던지면 구조적 동시성이 형제까지 취소 → all-or-nothing이 됨. **단, `CancellationException`은 반드시 재던져** 외부 취소(P4)와 구별한다.
- **취소 안전 = Phase 0 상속:** 외부 취소 → `coroutineScope` 취소 → 각 `complete()` 취소 → `complete()`가 소비하는 `stream()` 취소 → `ProcessLineStreamer.awaitClose`의 `destroyForcibly`(G3). 별도 좀비 처리 불필요.
- **타임아웃:** `withTimeoutOrNull` 초과 → null → `Failed("timeout")`. 취소된 `complete()`의 claude 프로세스도 위 경로로 죽음.
- 워커는 **`complete()`만** 사용(stream 아님). 워커별 실시간 스트리밍은 Phase 2/병렬보드 관심사 — YAGNI.

## 6. Synthesizer + Orchestrator

```kotlin
data class SynthOutcome(val merged: String, val signals: UsageSignals?)

class Synthesizer(private val llm: LlmPort) {
    suspend fun merge(goal: String, results: List<WorkerResult>, opts: ParallelOpts): SynthOutcome {
        val done = results.filterIsInstance<WorkerResult.Done>()
        if (done.isEmpty()) return SynthOutcome("(모든 워커 실패)", null)                  // 단락
        if (done.size == 1 && results.size == 1) return SynthOutcome(done.single().text, null) // 1개면 머지 불필요
        val r = llm.complete(listOf(Message(Role.USER, buildMergePrompt(goal, results))), CompleteOpts(model = opts.model))
        return SynthOutcome(r.text, r.signals)
    }
}

class Orchestrator(llm: LlmPort) {
    private val pool = WorkerPool(llm)
    private val synth = Synthesizer(llm)
    suspend fun runParallel(goal: String, tasks: List<WorkerTask>, opts: ParallelOpts = ParallelOpts()): OrchestrationResult {
        require(tasks.isNotEmpty()) { "tasks가 비어있다" }
        val workers = pool.run(tasks, opts)
        val outcome = synth.merge(goal, workers, opts)
        return OrchestrationResult(outcome.merged, workers, outcome.signals)
    }
}
```

- **`buildMergePrompt(goal, results)`:** `goal` + 성공 워커별 `역할 + 결과` 나열 + 실패 워커는 "(이 항목은 실패)" 표기 → 한 답으로 종합 지시. (실패도 머지가 알아야 누락을 정직하게 표기.)
- **머지 단락:** 성공 0개면 머지 호출 안 함, 성공 1개·전부면 그 텍스트 그대로(불필요한 LLM 콜 회피).

## 7. 콘솔 진입점 (P5)

`OrchestratorMain` — 사람이 직접 한 병렬 턴:
- 1번째 줄 = `goal`, 이후 줄 = `역할 :: 프롬프트` (빈 줄/EOF까지).
- 병렬 실행 후 워커별 상태(Done/Failed + 텍스트)와 최종 `merged`를 출력. (Phase 0 `Main`과 별개 진입점.)

## 8. 테스트 전략

- **단위(claude 없음, 결정적) — fake `LlmPort`:** `complete()`만 람다로 구현(워커·머지 호출을 프롬프트로 구분), `stream()`은 미사용 → `TODO()`.
  - P1 동시성: 지연 + 동시 카운터(원자적) → 관측 최대 동시 == `min(concurrency, N)`.
  - P2 부분 실패: 특정 태스크만 실패 → 그것만 `Failed`, 예외 없음.
  - P3 머지: 머지 호출 내용 검증 + 단락(성공 1개 → 머지 호출 안 됨) 검증.
  - 타임아웃: 워커 지연 > `workerTimeoutMs` → `Failed("timeout")`.
  - P4 취소: 지연 fake → 취소 → 즉시 완료, 워커 취소.
- **통합(`@Tag("integration")`, 실제 claude):** P5 작은 태스크 2~3개 → `merged` 비어있지 않음, 워커 `Done`. 기본 제외(`-Pintegration`).

## 9. 확정 결정 (D1~D3)

- **D1 — 역할 주입 = `systemPrompt`.** `--append-system-prompt`로 역할, user 메시지엔 작업+컨텍스트. (Node식 프롬프트 박기 대비 깔끔.)
- **D2 — 실패는 `WorkerResult.Failed` 반환(예외 X).** 부분 결과 보존의 핵심. `CancellationException`만 재던져 외부 취소와 구별.
- **D3 — 플래너(판단·분해)는 코드에 안 박는다.** seam = `tasks` 파라미터. B는 앞단 추가. (Phase 0 폴백 트리거 연기와 동일 원칙.)

## 10. 비-범위 / 후속

- **B 플래너:** `request → (goal, tasks)` LLM 단계. 같은 파이프라인 앞에 붙임. (이번 미구현.)
- 워커별 스트리밍, 에이전트 레지스트리·영속(Phase 3), 웹/SSE(Phase 2).

## 11. 이번 Phase 산출물

- 이 설계 스펙
- 다음: 구현 계획(writing-plans) → TDD 구현(콘솔/테스트, P1~P5)
