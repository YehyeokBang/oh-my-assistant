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
