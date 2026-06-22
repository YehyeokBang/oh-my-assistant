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
