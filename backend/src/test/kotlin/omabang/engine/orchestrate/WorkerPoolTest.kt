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
