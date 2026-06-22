package omabang.engine.orchestrate

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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

    @Test
    fun `run - 외부 취소 시 워커 본문을 끝까지 돌리지 않고 즉시 취소된다 (P4)`() = runTest {
        val started = AtomicInteger(0)
        val completed = AtomicInteger(0)
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
}
