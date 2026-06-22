# Phase 1 — 멀티에이전트 병렬 위임 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Phase 0 `LlmPort` 위에 병렬 위임 capability 레이어(`omabang.engine.orchestrate`)를 얹어, N개 태스크를 동시성 제한 팬아웃으로 병렬 실행하고 결과를 한 답으로 머지한다.

**Architecture:** 3개 격리 단위 — `WorkerPool`(Semaphore 동시성 제한 팬아웃, 부분 결과 수집) + `Synthesizer`(LLM 머지, 단락 처리) + `Orchestrator`(조립). `LlmPort.complete()`만 의존하며 엔진 코어(claude 어댑터)는 건드리지 않는다. 플래너(판단·분해)는 코드에 박지 않고 `runParallel`의 `tasks` 파라미터를 seam으로 둔다.

**Tech Stack:** Kotlin 2.3.0 / JDK 25, kotlinx-coroutines-core 1.11.0(coroutineScope/async/Semaphore/withTimeoutOrNull), kotlinx-coroutines-test 1.11.0(runTest 가상시간), kotlin.test + JUnit5(@Tag).

## Global Constraints

- 빌드/테스트: `cd backend && ./gradlew test` (단위), `./gradlew test -Pintegration` (실제 claude 통합). Kotlin 2.3.0 / `jvmToolchain(25)` / `JvmTarget.JVM_25`.
- 신규 코드는 모두 패키지 `omabang.engine.orchestrate`. 엔진 코어(`omabang.engine.claude.*`, `LlmPort.kt`)는 **수정 금지** — 소비만 한다.
- 워커는 `complete()`만 사용한다 (`stream()` 아님). 단위 테스트 fake의 `stream()`은 `TODO()`.
- 기본값(스펙 §4): `concurrency = 4`, `workerTimeoutMs = 180_000`. 변경 금지.
- 실패 처리(D2): 워커 실패는 예외가 아니라 `WorkerResult.Failed` **반환**. 단 `CancellationException`은 반드시 **재던진다**.
- 역할 주입(D1): `task.role` → `CompleteOpts.systemPrompt`, `task.prompt`(+`context`) → user 메시지.
- 보안(conventions.md): `allowedTools`는 read-only first, 기본 빈 리스트.
- 커밋: `main` 직접, 한글 메시지 `feat: ...` 형식. 계정 `YehyeokBang`(`gh auth switch --user YehyeokBang`).

---

## File Structure

**생성 (main):**
- `backend/src/main/kotlin/omabang/engine/orchestrate/OrchestrationTypes.kt` — 도메인 타입(`WorkerTask`, `WorkerResult`, `OrchestrationResult`, `ParallelOpts`).
- `backend/src/main/kotlin/omabang/engine/orchestrate/WorkerPool.kt` — 동시성 메커니즘(Semaphore 팬아웃 + 부분 결과 + 타임아웃 + 취소).
- `backend/src/main/kotlin/omabang/engine/orchestrate/Synthesizer.kt` — `SynthOutcome` + 머지 1책임 + `buildMergePrompt`.
- `backend/src/main/kotlin/omabang/engine/orchestrate/Orchestrator.kt` — WorkerPool+Synthesizer 조립.
- `backend/src/main/kotlin/omabang/engine/orchestrate/OrchestratorMain.kt` — 콘솔 진입점(P5 수동).

**생성 (test):**
- `backend/src/test/kotlin/omabang/engine/orchestrate/FakeLlmPort.kt` — 단위 테스트용 fake `LlmPort` + 헬퍼.
- `backend/src/test/kotlin/omabang/engine/orchestrate/WorkerPoolTest.kt` — P1/P2/타임아웃/P4.
- `backend/src/test/kotlin/omabang/engine/orchestrate/SynthesizerTest.kt` — P3 + 단락.
- `backend/src/test/kotlin/omabang/engine/orchestrate/OrchestratorTest.kt` — 조립.
- `backend/src/test/kotlin/omabang/engine/orchestrate/OrchestratorIntegrationTest.kt` — P5(`@Tag("integration")`).

**수정 (build):**
- `backend/build.gradle.kts` — `runOrchestrator` JavaExec 태스크 등록(콘솔 stdin 연결).

---

## Task 1: 도메인 타입 + Fake + WorkerPool 동시성 제한 (P1)

`WorkerPool`의 핵심(Semaphore 팬아웃)을 먼저 세운다. 이 태스크의 `runWorker`는 최소형(성공만 `Done`) — 실패/타임아웃/취소는 Task 2·3에서 TDD로 추가한다.

**Files:**
- Create: `backend/src/main/kotlin/omabang/engine/orchestrate/OrchestrationTypes.kt`
- Create: `backend/src/main/kotlin/omabang/engine/orchestrate/WorkerPool.kt`
- Test: `backend/src/test/kotlin/omabang/engine/orchestrate/FakeLlmPort.kt`
- Test: `backend/src/test/kotlin/omabang/engine/orchestrate/WorkerPoolTest.kt`

**Interfaces:**
- Consumes (Phase 0, 기존): `omabang.engine.LlmPort.complete(messages, opts)`, `Message(Role, content)`, `Role.USER`, `CompleteOpts(model, systemPrompt, allowedTools)`, `CompleteResult(text, backend, signals, isError, apiErrorStatus)`, `UsageSignals(...)`.
- Produces:
  - `data class WorkerTask(val role: String, val prompt: String, val context: String? = null)`
  - `sealed interface WorkerResult { val task: WorkerTask }` + `WorkerResult.Done(task, text: String, signals: UsageSignals)` + `WorkerResult.Failed(task, error: String)`
  - `data class OrchestrationResult(merged: String, workers: List<WorkerResult>, mergeSignals: UsageSignals?)`
  - `data class ParallelOpts(concurrency: Int = 4, workerTimeoutMs: Long = 180_000, model: String? = null, allowedTools: List<String> = emptyList())`
  - `class WorkerPool(llm: LlmPort)` with `suspend fun run(tasks: List<WorkerTask>, opts: ParallelOpts): List<WorkerResult>`
  - 테스트 헬퍼: `class FakeLlmPort(handler: suspend (List<Message>, CompleteOpts) -> CompleteResult)`, `fun fakeResult(text, isError, apiErrorStatus): CompleteResult`, `fun sig(): UsageSignals`

- [ ] **Step 1: 도메인 타입 생성**

Create `backend/src/main/kotlin/omabang/engine/orchestrate/OrchestrationTypes.kt`:

```kotlin
package omabang.engine.orchestrate

import omabang.engine.UsageSignals

/** 워커 1개 = 무상태 단발 호출. 역할 주입 + 프롬프트 + 최소 컨텍스트(히스토리 아님). 스펙 §4. */
data class WorkerTask(val role: String, val prompt: String, val context: String? = null)

/** 부분 성공을 표현하는 sealed 결과. done은 text, failed는 error. 스펙 §4. */
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
    val concurrency: Int = 4,            // Semaphore permit 수
    val workerTimeoutMs: Long = 180_000, // 워커당 타임아웃
    val model: String? = null,
    val allowedTools: List<String> = emptyList(), // 보안: read-only first (conventions.md)
)
```

- [ ] **Step 2: Fake LlmPort 테스트 헬퍼 생성**

Create `backend/src/test/kotlin/omabang/engine/orchestrate/FakeLlmPort.kt`:

```kotlin
package omabang.engine.orchestrate

import kotlinx.coroutines.flow.Flow
import omabang.engine.CompleteOpts
import omabang.engine.CompleteResult
import omabang.engine.LlmEvent
import omabang.engine.LlmPort
import omabang.engine.Message
import omabang.engine.UsageSignals

/** 단위 테스트용 fake. complete만 람다로 구현, stream은 워커가 안 쓰므로 TODO. (스펙 §8) */
class FakeLlmPort(
    private val handler: suspend (List<Message>, CompleteOpts) -> CompleteResult,
) : LlmPort {
    override suspend fun complete(messages: List<Message>, opts: CompleteOpts): CompleteResult =
        handler(messages, opts)

    override fun stream(messages: List<Message>, opts: CompleteOpts): Flow<LlmEvent> =
        TODO("워커는 complete만 사용 (스펙 §5)")
}

fun fakeResult(text: String, isError: Boolean = false, apiErrorStatus: Int? = null) =
    CompleteResult(
        text = text,
        backend = "fake",
        signals = UsageSignals(outputTokens = 1),
        isError = isError,
        apiErrorStatus = apiErrorStatus,
    )

fun sig() = UsageSignals(outputTokens = 1)
```

- [ ] **Step 3: 실패 테스트 작성 (역할 주입 + 동시성 제한 P1)**

Create `backend/src/test/kotlin/omabang/engine/orchestrate/WorkerPoolTest.kt`:

```kotlin
package omabang.engine.orchestrate

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkerPoolTest {

    @Test
    fun `run - role을 systemPrompt로 주입하고 prompt를 user 메시지로 보낸다 (D1)`() = runTest {
        var capturedSys: String? = null
        var capturedUser: String? = null
        val llm = FakeLlmPort { messages, opts ->
            capturedSys = opts.systemPrompt
            capturedUser = messages.single().content
            fakeResult("ok")
        }
        WorkerPool(llm).run(listOf(WorkerTask(role = "백엔드", prompt = "API 설계")), ParallelOpts())
        assertTrue(capturedSys!!.contains("백엔드"), "systemPrompt에 역할이 없음: $capturedSys")
        assertEquals("API 설계", capturedUser)
    }

    @Test
    fun `run - 동시 실행 수가 concurrency 이하로 제한된다 (P1)`() = runTest {
        val active = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val llm = FakeLlmPort { _, _ ->
            val now = active.incrementAndGet()
            maxObserved.updateAndGet { max(it, now) }
            delay(100)
            active.decrementAndGet()
            fakeResult("ok")
        }
        val tasks = (1..10).map { WorkerTask(role = "r$it", prompt = "p$it") }
        val results = WorkerPool(llm).run(tasks, ParallelOpts(concurrency = 3))

        assertEquals(10, results.size)
        assertEquals(3, maxObserved.get(), "관측 최대 동시 실행 == min(concurrency=3, N=10)")
        assertTrue(results.all { it is WorkerResult.Done })
    }
}
```

- [ ] **Step 4: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests "omabang.engine.orchestrate.WorkerPoolTest"`
Expected: 컴파일 실패 — `WorkerPool` 미정의 (unresolved reference: WorkerPool).

- [ ] **Step 5: WorkerPool 최소 구현 (동시성만)**

Create `backend/src/main/kotlin/omabang/engine/orchestrate/WorkerPool.kt`:

```kotlin
package omabang.engine.orchestrate

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import omabang.engine.CompleteOpts
import omabang.engine.LlmPort
import omabang.engine.Message
import omabang.engine.Role

/** 순수 동시성 메커니즘 (스펙 §5). tasks N개를 Semaphore로 동시성 제한 팬아웃 → 부분 결과 수집. */
class WorkerPool(private val llm: LlmPort) {

    suspend fun run(tasks: List<WorkerTask>, opts: ParallelOpts): List<WorkerResult> = coroutineScope {
        val gate = Semaphore(opts.concurrency)
        tasks.map { task -> async { gate.withPermit { runWorker(task, opts) } } }.awaitAll()
    }

    private suspend fun runWorker(task: WorkerTask, opts: ParallelOpts): WorkerResult {
        val sys = "너는 \"${task.role}\" 전문가다. 주어진 작업만 독립적으로 수행하고 결과를 간결히 보고하라."
        val user = task.prompt + (task.context?.let { "\n\n참고 컨텍스트:\n$it" } ?: "")
        val co = CompleteOpts(model = opts.model, systemPrompt = sys, allowedTools = opts.allowedTools)
        val r = llm.complete(listOf(Message(Role.USER, user)), co)
        return WorkerResult.Done(task, r.text, r.signals)
    }
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests "omabang.engine.orchestrate.WorkerPoolTest"`
Expected: PASS (2 tests).

- [ ] **Step 7: 커밋**

```bash
cd /Users/yehyeok/Desktop/Dev/oh-my-assistant
git add backend/src/main/kotlin/omabang/engine/orchestrate/OrchestrationTypes.kt \
        backend/src/main/kotlin/omabang/engine/orchestrate/WorkerPool.kt \
        backend/src/test/kotlin/omabang/engine/orchestrate/FakeLlmPort.kt \
        backend/src/test/kotlin/omabang/engine/orchestrate/WorkerPoolTest.kt
git commit -m "feat: WorkerPool 동시성 제한 팬아웃 + 도메인 타입 (P1)

- omabang.engine.orchestrate 도메인 타입(WorkerTask/WorkerResult/OrchestrationResult/ParallelOpts)
- Semaphore로 동시 실행을 concurrency 이하로 제한
- 역할은 systemPrompt로 주입(D1), 단위 테스트용 fake LlmPort 추가"
```

---

## Task 2: WorkerPool 부분 실패 + 타임아웃 (P2)

워커 1개가 실패(예외/claude 에러/타임아웃)해도 나머지는 `Done`, 예외 없음. 실패를 `Failed`로 반환하는 D2의 핵심.

**Files:**
- Modify: `backend/src/main/kotlin/omabang/engine/orchestrate/WorkerPool.kt` (runWorker)
- Test: `backend/src/test/kotlin/omabang/engine/orchestrate/WorkerPoolTest.kt` (테스트 추가)

**Interfaces:**
- Consumes: Task 1의 `WorkerPool.run`, `WorkerResult.Failed`, `ParallelOpts.workerTimeoutMs`.
- Produces: (시그니처 변화 없음) `run`이 실패 워커를 `WorkerResult.Failed`로 수집.

- [ ] **Step 1: 실패 테스트 작성 (부분 실패 + claude 에러 + 타임아웃)**

`WorkerPoolTest.kt`의 클래스 안, 마지막 `}` 직전에 추가:

```kotlin
    @Test
    fun `run - 한 워커가 예외로 실패해도 나머지는 Done이고 예외가 안 난다 (P2)`() = runTest {
        val llm = FakeLlmPort { messages, _ ->
            val text = messages.single().content
            if (text.contains("폭탄")) throw RuntimeException("워커 폭발")
            fakeResult("ok:$text")
        }
        val tasks = listOf(
            WorkerTask("a", "정상1"),
            WorkerTask("b", "폭탄"),
            WorkerTask("c", "정상2"),
        )
        val results = WorkerPool(llm).run(tasks, ParallelOpts())

        assertEquals(3, results.size)
        val failed = results.filterIsInstance<WorkerResult.Failed>()
        assertEquals(1, failed.size)
        assertEquals("폭탄", failed.single().task.prompt)
        assertTrue(failed.single().error.contains("워커 폭발"), "에러 메시지 누락: ${failed.single().error}")
        assertEquals(2, results.filterIsInstance<WorkerResult.Done>().size)
    }

    @Test
    fun `run - claude 에러 결과(isError)는 Failed로 수집된다`() = runTest {
        val llm = FakeLlmPort { _, _ -> fakeResult("한도초과", isError = true, apiErrorStatus = 429) }
        val results = WorkerPool(llm).run(listOf(WorkerTask("a", "p")), ParallelOpts())
        val f = results.single() as WorkerResult.Failed
        assertTrue(f.error.contains("429"), "에러 상태 누락: ${f.error}")
    }

    @Test
    fun `run - 워커가 타임아웃을 넘기면 Failed(timeout)`() = runTest {
        val llm = FakeLlmPort { _, _ ->
            delay(10_000)
            fakeResult("너무 늦음")
        }
        val results = WorkerPool(llm).run(
            listOf(WorkerTask("a", "p")),
            ParallelOpts(workerTimeoutMs = 1_000),
        )
        val f = results.single() as WorkerResult.Failed
        assertTrue(f.error.contains("timeout"), "타임아웃 표기 누락: ${f.error}")
    }
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests "omabang.engine.orchestrate.WorkerPoolTest"`
Expected: 신규 3개 FAIL — 예외가 `awaitAll`을 통해 전파되어 `run`이 throw(부분 실패 테스트), isError가 `Done`으로 처리됨(ClassCastException), 타임아웃 미적용으로 `Done` 반환(ClassCastException).

- [ ] **Step 3: runWorker에 try/catch + isError + 타임아웃 추가**

`WorkerPool.kt`의 `runWorker`를 아래로 교체하고, 파일 상단 import에 `withTimeoutOrNull` 추가:

import 블록에 추가:
```kotlin
import kotlinx.coroutines.withTimeoutOrNull
```

`runWorker` 교체:
```kotlin
    private suspend fun runWorker(task: WorkerTask, opts: ParallelOpts): WorkerResult {
        val sys = "너는 \"${task.role}\" 전문가다. 주어진 작업만 독립적으로 수행하고 결과를 간결히 보고하라."
        val user = task.prompt + (task.context?.let { "\n\n참고 컨텍스트:\n$it" } ?: "")
        val co = CompleteOpts(model = opts.model, systemPrompt = sys, allowedTools = opts.allowedTools)
        return try {
            val r = withTimeoutOrNull(opts.workerTimeoutMs) {
                llm.complete(listOf(Message(Role.USER, user)), co)
            } ?: return WorkerResult.Failed(task, "timeout ${opts.workerTimeoutMs}ms")
            if (r.isError) WorkerResult.Failed(task, "claude error status=${r.apiErrorStatus}")
            else WorkerResult.Done(task, r.text, r.signals)
        } catch (e: Exception) {
            WorkerResult.Failed(task, e.message ?: e.toString()) // 비-취소 예외는 Failed로 흡수 → 부분 결과 보존
        }
    }
```

> 주의: 이 단계의 `catch (e: Exception)`은 `CancellationException`까지 삼킨다(구조적 동시성 위반). Task 3에서 `CancellationException` 재던짐을 추가한다 — 지금 넣지 않는 이유는 P4 테스트로 그 필요성을 먼저 드러내기 위함이다.

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests "omabang.engine.orchestrate.WorkerPoolTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: 커밋**

```bash
cd /Users/yehyeok/Desktop/Dev/oh-my-assistant
git add backend/src/main/kotlin/omabang/engine/orchestrate/WorkerPool.kt \
        backend/src/test/kotlin/omabang/engine/orchestrate/WorkerPoolTest.kt
git commit -m "feat: WorkerPool 부분 실패·타임아웃 처리 (P2)

- 워커 예외/claude 에러/타임아웃을 WorkerResult.Failed로 수집(예외 미전파, D2)
- withTimeoutOrNull로 워커당 타임아웃 → Failed(timeout)"
```

---

## Task 3: WorkerPool 취소 안전 (P4)

외부 취소 시 모든 워커가 즉시 취소되고 호출이 즉시 종료된다. `CancellationException`을 재던져 D2 실패 흡수와 구별한다.

**Files:**
- Modify: `backend/src/main/kotlin/omabang/engine/orchestrate/WorkerPool.kt` (runWorker의 catch)
- Test: `backend/src/test/kotlin/omabang/engine/orchestrate/WorkerPoolTest.kt` (테스트 추가)

**Interfaces:**
- Consumes: Task 2의 `runWorker` catch 블록.
- Produces: (시그니처 변화 없음) `CancellationException`은 흡수하지 않고 전파.

- [ ] **Step 1: 실패 테스트 작성 (외부 취소 즉시 종료)**

`WorkerPoolTest.kt` 상단 import에 추가:
```kotlin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
```

클래스 안 마지막 `}` 직전에 추가:
```kotlin
    @Test
    fun `run - 외부 취소 시 워커 본문을 끝까지 돌리지 않고 즉시 취소된다 (P4)`() = runTest {
        val started = java.util.concurrent.atomic.AtomicInteger(0)
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        val llm = FakeLlmPort { _, _ ->
            started.incrementAndGet()
            delay(60_000)                 // 긴 작업
            completed.incrementAndGet()   // 취소되면 여기 도달 못 함
            fakeResult("ok")
        }
        val pool = WorkerPool(llm)
        val job = launch {
            pool.run((1..4).map { WorkerTask("r$it", "p$it") }, ParallelOpts(concurrency = 4))
        }
        runCurrent()                      // 워커 4개 시작(delay 진입)까지
        assertEquals(4, started.get(), "워커가 시작되지 않음")
        job.cancelAndJoin()               // 외부 취소
        assertTrue(job.isCancelled)
        assertEquals(0, completed.get(), "취소됐는데 워커 본문이 완료됨 = 취소 미전파")
    }
```

> 솔직한 한계(스펙 §1 P4 주): 순수 fake 단위에서 "재던짐 vs 흡수"는 부모 취소가 지배해 관측상 구별이 어렵다. 이 테스트는 **취소가 60초 delay를 기다리지 않고 즉시 전파**됨을 검증한다. 자식 claude 프로세스의 실제 종료(좀비 0)는 Phase 0 G3(`ProcessLineStreamerTest`)가 보장한다.

`WorkerPoolTest.kt` 상단 import에 추가(취소/조인):
```kotlin
import kotlinx.coroutines.cancelAndJoin
```

- [ ] **Step 2: 테스트 실행 — 현재도 통과할 수 있음을 확인**

Run: `cd backend && ./gradlew test --tests "omabang.engine.orchestrate.WorkerPoolTest"`
Expected: 이 테스트는 **현 구현(Task 2)에서도 통과**할 수 있다(부모 취소가 자식을 취소). 그래도 그대로 둔다 — 회귀 방지 + P4 명세. 다음 Step의 변경은 D2 정합성(취소 흡수 금지)을 코드로 보장하기 위함이다.

- [ ] **Step 3: catch에서 CancellationException 재던짐 추가**

`WorkerPool.kt` 상단 import에 추가:
```kotlin
import kotlinx.coroutines.CancellationException
```

`runWorker`의 `catch (e: Exception)` 바로 위에 취소 전용 catch를 추가(순서 중요 — 취소 catch가 먼저):
```kotlin
        } catch (ce: CancellationException) {
            throw ce                                              // 취소는 전파(외부 취소 시 형제도 취소, D2)
        } catch (e: Exception) {
            WorkerResult.Failed(task, e.message ?: e.toString())  // 비-취소 예외는 Failed로 흡수 → 부분 결과 보존
        }
```

(결과적으로 `runWorker`의 catch는 `CancellationException` → `Exception` 2단으로 정렬된다.)

- [ ] **Step 4: 전체 단위 테스트 통과 확인**

Run: `cd backend && ./gradlew test`
Expected: PASS — `WorkerPoolTest` 6 tests 포함 전부 통과(통합 태그는 기본 제외).

- [ ] **Step 5: 커밋**

```bash
cd /Users/yehyeok/Desktop/Dev/oh-my-assistant
git add backend/src/main/kotlin/omabang/engine/orchestrate/WorkerPool.kt \
        backend/src/test/kotlin/omabang/engine/orchestrate/WorkerPoolTest.kt
git commit -m "feat: WorkerPool 취소 안전 — CancellationException 재던짐 (P4)

- 외부 취소 시 워커 본문을 끝까지 돌리지 않고 즉시 전파
- D2: 취소는 흡수 금지(재던짐), 비-취소 예외만 Failed로 흡수"
```

---

## Task 4: Synthesizer — LLM 머지 + 단락 (P3)

성공 결과들을 한 답으로 머지한다. 성공 0개면 안내 문구(LLM 미호출), 성공 1개·전부면 그 텍스트 그대로(LLM 미호출).

**Files:**
- Create: `backend/src/main/kotlin/omabang/engine/orchestrate/Synthesizer.kt`
- Test: `backend/src/test/kotlin/omabang/engine/orchestrate/SynthesizerTest.kt`

**Interfaces:**
- Consumes: `WorkerResult.Done/Failed`, `ParallelOpts.model`, `LlmPort.complete`, `Message`, `Role.USER`, `CompleteOpts`.
- Produces:
  - `data class SynthOutcome(val merged: String, val signals: UsageSignals?)`
  - `class Synthesizer(llm: LlmPort)` with `suspend fun merge(goal: String, results: List<WorkerResult>, opts: ParallelOpts): SynthOutcome`
  - `internal fun buildMergePrompt(goal: String, results: List<WorkerResult>): String`

- [ ] **Step 1: 실패 테스트 작성 (머지 + 단락 + 실패 표기)**

Create `backend/src/test/kotlin/omabang/engine/orchestrate/SynthesizerTest.kt`:

```kotlin
package omabang.engine.orchestrate

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SynthesizerTest {

    @Test
    fun `merge - 성공 2개를 한 답으로 머지하고 프롬프트에 목표와 역할·결과가 담긴다 (P3)`() = runTest {
        var captured: String? = null
        var capturedSys: String? = null
        val llm = FakeLlmPort { messages, opts ->
            captured = messages.single().content
            capturedSys = opts.systemPrompt
            fakeResult("종합답변")
        }
        val results = listOf(
            WorkerResult.Done(WorkerTask("프런트", "p1"), "프런트 결과", sig()),
            WorkerResult.Done(WorkerTask("백엔드", "p2"), "백엔드 결과", sig()),
        )
        val outcome = Synthesizer(llm).merge("로그인 기능 설계", results, ParallelOpts())

        assertEquals("종합답변", outcome.merged)
        assertNotNull(outcome.signals)
        assertNull(capturedSys, "머지 호출은 역할 systemPrompt를 쓰지 않는다")
        val prompt = captured!!
        assertTrue(prompt.contains("로그인 기능 설계"), "목표 누락")
        assertTrue(prompt.contains("프런트") && prompt.contains("프런트 결과"), "워커1 누락")
        assertTrue(prompt.contains("백엔드") && prompt.contains("백엔드 결과"), "워커2 누락")
    }

    @Test
    fun `merge - 모든 워커 실패면 LLM 호출 없이 안내 문구 반환 (단락)`() = runTest {
        var called = false
        val llm = FakeLlmPort { _, _ -> called = true; fakeResult("x") }
        val results = listOf(WorkerResult.Failed(WorkerTask("a", "p"), "err"))
        val outcome = Synthesizer(llm).merge("목표", results, ParallelOpts())

        assertFalse(called, "성공 0개인데 머지 LLM이 호출됨")
        assertNull(outcome.signals)
        assertTrue(outcome.merged.contains("실패"))
    }

    @Test
    fun `merge - 워커가 1개뿐이면 머지 없이 그 텍스트 그대로 반환 (단락)`() = runTest {
        var called = false
        val llm = FakeLlmPort { _, _ -> called = true; fakeResult("x") }
        val results = listOf(WorkerResult.Done(WorkerTask("a", "p"), "단일결과", sig()))
        val outcome = Synthesizer(llm).merge("목표", results, ParallelOpts())

        assertFalse(called, "워커 1개인데 머지 LLM이 호출됨")
        assertEquals("단일결과", outcome.merged)
        assertNull(outcome.signals)
    }

    @Test
    fun `merge - 성공+실패가 섞이면 머지하고 프롬프트에 실패 항목을 표기한다`() = runTest {
        var captured: String? = null
        val llm = FakeLlmPort { m, _ -> captured = m.single().content; fakeResult("종합") }
        val results = listOf(
            WorkerResult.Done(WorkerTask("성공역", "p1"), "성공결과", sig()),
            WorkerResult.Failed(WorkerTask("실패역", "p2"), "타임아웃"),
        )
        val outcome = Synthesizer(llm).merge("목표", results, ParallelOpts())

        assertEquals("종합", outcome.merged)
        assertTrue(captured!!.contains("실패역"), "실패 워커 역할 누락")
        assertTrue(captured!!.contains("실패"), "실패 표기 누락")
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests "omabang.engine.orchestrate.SynthesizerTest"`
Expected: 컴파일 실패 — `Synthesizer`, `SynthOutcome` 미정의.

- [ ] **Step 3: Synthesizer 구현**

Create `backend/src/main/kotlin/omabang/engine/orchestrate/Synthesizer.kt`:

```kotlin
package omabang.engine.orchestrate

import omabang.engine.CompleteOpts
import omabang.engine.LlmPort
import omabang.engine.Message
import omabang.engine.Role
import omabang.engine.UsageSignals

data class SynthOutcome(val merged: String, val signals: UsageSignals?)

/** 머지 1책임 (스펙 §6). 성공 결과들을 한 답으로 종합. 성공 0개·1개(전부)면 단락. */
class Synthesizer(private val llm: LlmPort) {
    suspend fun merge(goal: String, results: List<WorkerResult>, opts: ParallelOpts): SynthOutcome {
        val done = results.filterIsInstance<WorkerResult.Done>()
        if (done.isEmpty()) return SynthOutcome("(모든 워커 실패)", null)                       // 단락: 합성할 게 없음
        if (done.size == 1 && results.size == 1) return SynthOutcome(done.single().text, null)   // 단락: 1개면 머지 불필요
        val prompt = buildMergePrompt(goal, results)
        val r = llm.complete(listOf(Message(Role.USER, prompt)), CompleteOpts(model = opts.model))
        return SynthOutcome(r.text, r.signals)
    }
}

/** goal + 워커별(역할/결과, 실패는 표기) → 한 답으로 종합 지시. 실패도 머지가 알아야 누락을 정직히 표기. */
internal fun buildMergePrompt(goal: String, results: List<WorkerResult>): String = buildString {
    appendLine("다음은 하나의 목표를 위해 여러 전문가가 병렬로 작업한 결과다.")
    appendLine("목표: $goal")
    appendLine()
    appendLine("각 작업 결과:")
    results.forEachIndexed { i, r ->
        when (r) {
            is WorkerResult.Done -> {
                appendLine("[${i + 1}] 역할=${r.task.role}")
                appendLine(r.text)
            }
            is WorkerResult.Failed -> {
                appendLine("[${i + 1}] 역할=${r.task.role} (이 항목은 실패: ${r.error})")
            }
        }
        appendLine()
    }
    append("위 결과들을 종합해 목표에 대한 하나의 완결된 답을 작성하라. 실패한 항목이 있으면 누락을 정직하게 언급하라.")
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests "omabang.engine.orchestrate.SynthesizerTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: 커밋**

```bash
cd /Users/yehyeok/Desktop/Dev/oh-my-assistant
git add backend/src/main/kotlin/omabang/engine/orchestrate/Synthesizer.kt \
        backend/src/test/kotlin/omabang/engine/orchestrate/SynthesizerTest.kt
git commit -m "feat: Synthesizer — 성공 결과 LLM 머지 + 단락 (P3)

- 성공 0개=안내 문구, 1개(전부)=그대로 → 불필요한 LLM 콜 회피
- buildMergePrompt: 목표+워커별 역할·결과, 실패 항목 정직 표기"
```

---

## Task 5: Orchestrator — 조립

WorkerPool과 Synthesizer를 조립해 `runParallel(goal, tasks, opts) → OrchestrationResult`. 플래너 seam = `tasks` 파라미터(D3).

**Files:**
- Create: `backend/src/main/kotlin/omabang/engine/orchestrate/Orchestrator.kt`
- Test: `backend/src/test/kotlin/omabang/engine/orchestrate/OrchestratorTest.kt`

**Interfaces:**
- Consumes: `WorkerPool.run`, `Synthesizer.merge`, `OrchestrationResult`, `ParallelOpts`.
- Produces:
  - `class Orchestrator(llm: LlmPort)` with `suspend fun runParallel(goal: String, tasks: List<WorkerTask>, opts: ParallelOpts = ParallelOpts()): OrchestrationResult`

- [ ] **Step 1: 실패 테스트 작성 (조립 + 빈 tasks)**

Create `backend/src/test/kotlin/omabang/engine/orchestrate/OrchestratorTest.kt`:

```kotlin
package omabang.engine.orchestrate

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OrchestratorTest {

    @Test
    fun `runParallel - 워커 팬아웃 후 머지까지 조립해 OrchestrationResult를 만든다`() = runTest {
        // 워커 호출은 systemPrompt(역할 주입)가 있고, 머지 호출은 없다 → 분기로 구분.
        val llm = FakeLlmPort { _, opts ->
            if (opts.systemPrompt != null) fakeResult("부분결과") else fakeResult("최종머지")
        }
        val result = Orchestrator(llm).runParallel(
            goal = "목표",
            tasks = listOf(WorkerTask("a", "p1"), WorkerTask("b", "p2")),
        )

        assertEquals("최종머지", result.merged)
        assertEquals(2, result.workers.size)
        assertTrue(result.workers.all { it is WorkerResult.Done })
        assertNotNull(result.mergeSignals)
    }

    @Test
    fun `runParallel - tasks가 비면 IllegalArgumentException`() = runTest {
        val orch = Orchestrator(FakeLlmPort { _, _ -> fakeResult("x") })
        assertFailsWith<IllegalArgumentException> {
            orch.runParallel("목표", emptyList())
        }
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew test --tests "omabang.engine.orchestrate.OrchestratorTest"`
Expected: 컴파일 실패 — `Orchestrator` 미정의.

- [ ] **Step 3: Orchestrator 구현**

Create `backend/src/main/kotlin/omabang/engine/orchestrate/Orchestrator.kt`:

```kotlin
package omabang.engine.orchestrate

import omabang.engine.LlmPort

/** WorkerPool + Synthesizer 조립 (스펙 §6). B 플래너는 runParallel 앞단(tasks 생성)에 붙는다(D3 seam). */
class Orchestrator(llm: LlmPort) {
    private val pool = WorkerPool(llm)
    private val synth = Synthesizer(llm)

    suspend fun runParallel(
        goal: String,
        tasks: List<WorkerTask>,
        opts: ParallelOpts = ParallelOpts(),
    ): OrchestrationResult {
        require(tasks.isNotEmpty()) { "tasks가 비어있다" }
        val workers = pool.run(tasks, opts)
        val outcome = synth.merge(goal, workers, opts)
        return OrchestrationResult(outcome.merged, workers, outcome.signals)
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew test --tests "omabang.engine.orchestrate.OrchestratorTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: 커밋**

```bash
cd /Users/yehyeok/Desktop/Dev/oh-my-assistant
git add backend/src/main/kotlin/omabang/engine/orchestrate/Orchestrator.kt \
        backend/src/test/kotlin/omabang/engine/orchestrate/OrchestratorTest.kt
git commit -m "feat: Orchestrator — WorkerPool+Synthesizer 조립

- runParallel(goal, tasks, opts) → OrchestrationResult
- tasks 빈 경우 require로 차단, 플래너 seam은 tasks 파라미터(D3)"
```

---

## Task 6: 콘솔 진입점 + 실제 claude 통합 (P5)

사람이 콘솔로 실제 병렬 턴 1회를 돌리고(`OrchestratorMain`), 실제 claude로 2태스크 머지를 자동 검증(`@Tag("integration")`)한다.

**Files:**
- Create: `backend/src/main/kotlin/omabang/engine/orchestrate/OrchestratorMain.kt`
- Modify: `backend/build.gradle.kts` (`runOrchestrator` 태스크 등록)
- Test: `backend/src/test/kotlin/omabang/engine/orchestrate/OrchestratorIntegrationTest.kt`

**Interfaces:**
- Consumes: `Orchestrator.runParallel`, `omabang.engine.claude.ClaudeCliAdapter`(Phase 0, 기존), `OrchestrationResult`, `WorkerResult`.
- Produces: `fun main()` (entrypoint `omabang.engine.orchestrate.OrchestratorMainKt`).

- [ ] **Step 1: 통합 테스트 작성 (P5)**

Create `backend/src/test/kotlin/omabang/engine/orchestrate/OrchestratorIntegrationTest.kt`:

```kotlin
package omabang.engine.orchestrate

import kotlinx.coroutines.runBlocking
import omabang.engine.claude.ClaudeCliAdapter
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 실제 `claude -p`로 병렬 2태스크 머지 (P5). 구독 슬롯/시간 소모 → 기본 제외.
 * 실행: ./gradlew test -Pintegration   (claude가 PATH에 있어야 함)
 */
@Tag("integration")
class OrchestratorIntegrationTest {

    @Test
    fun `runParallel - 실제 claude로 병렬 2태스크를 한 답으로 머지한다 (P5)`() = runBlocking {
        val orch = Orchestrator(ClaudeCliAdapter())
        val result = orch.runParallel(
            goal = "두 계산 결과를 한 문장으로 합쳐서 보고하라.",
            tasks = listOf(
                WorkerTask(role = "산수1", prompt = "2 더하기 3은 얼마인가? 숫자만 한 단어로 답해."),
                WorkerTask(role = "산수2", prompt = "10 빼기 4는 얼마인가? 숫자만 한 단어로 답해."),
            ),
        )

        assertTrue(result.merged.isNotBlank(), "merged가 비어있음")
        assertTrue(
            result.workers.all { it is WorkerResult.Done },
            "워커 실패: ${result.workers}",
        )
    }
}
```

- [ ] **Step 2: 통합 테스트가 기본 실행에서 제외되는지 확인**

Run: `cd backend && ./gradlew test --tests "omabang.engine.orchestrate.OrchestratorIntegrationTest"`
Expected: 컴파일은 되지만 `@Tag("integration")` 제외로 **0 tests executed**(NO-SOURCE 또는 skipped). 아직 claude 호출 안 함.

- [ ] **Step 3: OrchestratorMain 콘솔 진입점 작성**

Create `backend/src/main/kotlin/omabang/engine/orchestrate/OrchestratorMain.kt`:

```kotlin
package omabang.engine.orchestrate

import kotlinx.coroutines.runBlocking
import omabang.engine.claude.ClaudeCliAdapter

/**
 * 콘솔 진입점 (P5): 사람이 직접 한 병렬 턴을 돌린다. Phase 0 Main과 별개 진입점.
 *   ./gradlew runOrchestrator
 * 입력: 1번째 줄 = goal, 이후 줄 = "역할 :: 프롬프트" (빈 줄/EOF까지).
 * 출력: 워커별 상태(stderr)와 최종 merged(stdout).
 */
fun main(): Unit = runBlocking {
    System.err.print("목표(goal)> ")
    val goal = readlnOrNull()?.takeIf { it.isNotBlank() } ?: run {
        System.err.println("(빈 목표 — 종료)")
        return@runBlocking
    }

    System.err.println("태스크를 '역할 :: 프롬프트' 형식으로 한 줄에 하나씩. 빈 줄이면 입력 종료:")
    val tasks = buildList {
        while (true) {
            val line = readlnOrNull()?.takeIf { it.isNotBlank() } ?: break
            val parts = line.split("::", limit = 2)
            if (parts.size != 2) {
                System.err.println("(형식 오류, 건너뜀): $line")
                continue
            }
            add(WorkerTask(role = parts[0].trim(), prompt = parts[1].trim()))
        }
    }
    if (tasks.isEmpty()) {
        System.err.println("(태스크 없음 — 종료)")
        return@runBlocking
    }

    System.err.println("--- 병렬 실행 (${tasks.size}개 워커) ---")
    val result = Orchestrator(ClaudeCliAdapter()).runParallel(goal, tasks)

    result.workers.forEach { w ->
        when (w) {
            is WorkerResult.Done -> System.err.println("[Done] ${w.task.role}: ${w.text.take(80)}")
            is WorkerResult.Failed -> System.err.println("[Failed] ${w.task.role}: ${w.error}")
        }
    }
    println("\n=== 머지 결과 ===")
    println(result.merged)
}
```

- [ ] **Step 4: build.gradle.kts에 runOrchestrator 태스크 등록**

`backend/build.gradle.kts`의 `application { ... }` 블록 바로 다음에 추가(`tasks.test` 위):

```kotlin
// 병렬 위임 콘솔 진입점(P5 수동). application의 기본 run(Phase 0 Main)과 별개. stdin 연결 필수.
tasks.register<JavaExec>("runOrchestrator") {
    group = "application"
    description = "병렬 위임 콘솔 진입점 (Phase 1)"
    mainClass.set("omabang.engine.orchestrate.OrchestratorMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}
```

- [ ] **Step 5: 컴파일·단위 테스트 전체 통과 확인**

Run: `cd backend && ./gradlew test`
Expected: PASS — 전체 단위 테스트(WorkerPool 6 + Synthesizer 4 + Orchestrator 2 + Phase 0 기존) 통과, 통합 태그 제외. `runOrchestrator` 태스크 인식(`./gradlew tasks --group application`에 노출).

- [ ] **Step 6: 실제 claude 통합 테스트 실행 (P5 자동 검증)**

Run: `cd backend && ./gradlew test -Pintegration --tests "omabang.engine.orchestrate.OrchestratorIntegrationTest"`
Expected: PASS (1 test) — `merged` 비어있지 않음, 워커 2개 모두 `Done`. (claude가 PATH에 있어야 함. 실패 시 환경 PATH 확인 — 스펙 §6 참고.)

- [ ] **Step 7: 콘솔 진입점 수동 확인 (선택)**

Run: `cd backend && ./gradlew runOrchestrator -q`
입력 예:
```
세 도시의 날씨를 한 문단으로 요약하라.
서울 :: 서울 오늘 날씨를 한 줄로.
부산 :: 부산 오늘 날씨를 한 줄로.

```
Expected: 워커별 `[Done]`/`[Failed]` 라인(stderr) 후 `=== 머지 결과 ===`와 합쳐진 답(stdout).

- [ ] **Step 8: 커밋**

```bash
cd /Users/yehyeok/Desktop/Dev/oh-my-assistant
git add backend/src/main/kotlin/omabang/engine/orchestrate/OrchestratorMain.kt \
        backend/build.gradle.kts \
        backend/src/test/kotlin/omabang/engine/orchestrate/OrchestratorIntegrationTest.kt
git commit -m "feat: 병렬 위임 콘솔 진입점 + 실제 claude 통합 (P5)

- OrchestratorMain: 'goal' + '역할 :: 프롬프트' 입력 → 워커별 상태 + merged 출력
- runOrchestrator gradle 태스크(stdin 연결)
- @Tag(integration) 통합 테스트: 실제 claude 2태스크 머지"
```

---

## Self-Review (작성자 점검 결과)

**1. Spec coverage:**
- P1(동시성 제한) → Task 1 Step 3 테스트. P2(부분 실패+예외 없음) → Task 2. 타임아웃(§8) → Task 2. P3(머지+단락) → Task 4. P4(취소) → Task 3. P5(콘솔+실제 claude) → Task 6.
- D1(역할=systemPrompt) → Task 1 Step 3 첫 테스트 + runWorker. D2(Failed 반환·CancellationException 재던짐) → Task 2·3. D3(플래너 seam=tasks) → Task 5 Orchestrator + 주석.
- 도메인 타입 §4 전체(WorkerTask/WorkerResult/OrchestrationResult/ParallelOpts/SynthOutcome) → Task 1·4. 3개 격리 단위 §3 → Task 1(Pool)/4(Synth)/5(Orch). 콘솔 포맷 §7 → Task 6.
- 비-범위(플래너 B, 워커 스트리밍, 레지스트리/영속, 웹/SSE) → 미포함(의도적).

**2. Placeholder scan:** 모든 코드 step에 실제 코드 포함. "TODO 적절히 처리" 류 없음. (fake의 `stream()` = `TODO()`는 스펙 §8이 명시한 의도된 미사용 표식.)

**3. Type consistency:** `WorkerResult.Done(task, text, signals)`/`Failed(task, error)`, `ParallelOpts(concurrency, workerTimeoutMs, model, allowedTools)`, `SynthOutcome(merged, signals)`, `OrchestrationResult(merged, workers, mergeSignals)`, `runParallel(goal, tasks, opts)` — 전 태스크 일관. `buildMergePrompt`/`merge`/`run`/`runParallel` 시그니처 교차 확인 완료.
</content>
</invoke>
