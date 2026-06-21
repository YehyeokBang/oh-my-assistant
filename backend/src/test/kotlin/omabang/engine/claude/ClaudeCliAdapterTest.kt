package omabang.engine.claude

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import omabang.engine.CompleteOpts
import omabang.engine.LlmEvent
import omabang.engine.Message
import omabang.engine.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ClaudeCliAdapter의 라인→이벤트 매핑·합성 로직을 fake streamer(주입된 NDJSON 라인)로 검증. claude 호출 없음.
 */
class ClaudeCliAdapterTest {

    /** 주입할 NDJSON 라인들을 그대로 흘리는 fake streamer. argv/stdin은 검증용으로 캡처. */
    private class FakeStreamer(private val lines: List<String>) : (List<String>, String?) -> Flow<String> {
        var capturedArgv: List<String>? = null
        var capturedStdin: String? = null
        override fun invoke(argv: List<String>, stdin: String?): Flow<String> {
            capturedArgv = argv
            capturedStdin = stdin
            return lines.asFlow()
        }
    }

    @Test
    fun `stream - TextDelta들이 Done 이전에 흐르고 Done에 usage가 실린다`() = runBlocking {
        val lines = listOf(
            """{"type":"system","subtype":"init"}""",
            delta("안녕"),
            delta("하세요"),
            """{"type":"result","subtype":"success","is_error":false,"result":"안녕하세요","total_cost_usd":0.01,"usage":{"input_tokens":10,"output_tokens":5}}""",
        )
        val adapter = ClaudeCliAdapter(streamer = FakeStreamer(lines))
        val events = adapter.stream(listOf(Message(Role.USER, "인사해"))).toList()

        assertEquals(
            listOf("안녕", "하세요"),
            events.filterIsInstance<LlmEvent.TextDelta>().map { it.text },
        )
        val done = events.last() as LlmEvent.Done
        assertEquals("안녕하세요", done.result.text)
        assertEquals(5, done.result.signals.outputTokens)
        assertEquals(0.01, done.result.signals.costUsd)
        // 점진 전달: 첫 TextDelta가 Done보다 앞 (G2 의미)
        assertTrue(events.indexOfFirst { it is LlmEvent.TextDelta } < events.indexOfFirst { it is LlmEvent.Done })
    }

    @Test
    fun `stream - rate_limit_event가 Done의 signals에 동봉된다`() = runBlocking {
        val lines = listOf(
            """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed","rateLimitType":"five_hour","resetsAt":1782062400}}""",
            """{"type":"result","subtype":"success","is_error":false,"result":"ok","usage":{"output_tokens":1}}""",
        )
        val adapter = ClaudeCliAdapter(streamer = FakeStreamer(lines))
        val done = adapter.stream(listOf(Message(Role.USER, "x"))).toList().last() as LlmEvent.Done
        assertEquals("five_hour", done.result.signals.rateLimit?.rateLimitType)
    }

    @Test
    fun `stream - 에러 result는 Error 이벤트로`() = runBlocking {
        val lines = listOf(
            """{"type":"result","subtype":"success","is_error":true,"api_error_status":429,"result":"한도초과"}""",
        )
        val adapter = ClaudeCliAdapter(streamer = FakeStreamer(lines))
        val err = adapter.stream(listOf(Message(Role.USER, "x"))).toList().single() as LlmEvent.Error
        assertEquals(429, err.status)
    }

    @Test
    fun `stream - result 없이 끝나면 Error로 마감`() = runBlocking {
        val adapter = ClaudeCliAdapter(streamer = FakeStreamer(listOf(delta("끊김"))))
        val events = adapter.stream(listOf(Message(Role.USER, "x"))).toList()
        assertTrue(events.last() is LlmEvent.Error)
    }

    @Test
    fun `complete - D3 합성 - Done의 result를 그대로 반환`() = runBlocking {
        val lines = listOf(
            delta("부"), delta("분"),
            """{"type":"result","subtype":"success","is_error":false,"result":"부분합성","usage":{"output_tokens":2}}""",
        )
        val adapter = ClaudeCliAdapter(streamer = FakeStreamer(lines))
        val r = adapter.complete(listOf(Message(Role.USER, "x")))
        assertFalse(r.isError)
        assertEquals("부분합성", r.text) // result 라인의 권위 텍스트
    }

    @Test
    fun `complete - 에러는 던지지 않고 isError=true로 반환 (§4_2)`() = runBlocking {
        val lines = listOf(
            delta("도중"),
            """{"type":"result","subtype":"error_during_execution","is_error":false,"api_error_status":500,"result":""}""",
        )
        val adapter = ClaudeCliAdapter(streamer = FakeStreamer(lines))
        val r = adapter.complete(listOf(Message(Role.USER, "x")))
        assertTrue(r.isError)
        assertEquals(500, r.apiErrorStatus)
        assertEquals("도중", r.text) // Error시엔 누적 델타를 텍스트로
    }

    @Test
    fun `buildArgs - 필수 플래그와 옵션이 argv에 반영된다`() = runBlocking {
        val fake = FakeStreamer(listOf("""{"type":"result","subtype":"success","is_error":false,"result":"ok"}"""))
        val adapter = ClaudeCliAdapter(command = "claude", streamer = fake)
        adapter.stream(
            listOf(Message(Role.USER, "질문")),
            CompleteOpts(model = "claude-opus-4-8", allowedTools = listOf("Read", "Grep"), systemPrompt = "너는 비서"),
        ).toList()

        val argv = fake.capturedArgv!!
        assertEquals("claude", argv.first())
        assertTrue(argv.containsAll(listOf("-p", "--output-format", "stream-json", "--verbose", "--include-partial-messages")))
        assertContainsSeq(argv, "--model", "claude-opus-4-8")
        assertContainsSeq(argv, "--allowedTools", "Read,Grep")
        assertContainsSeq(argv, "--append-system-prompt", "너는 비서")
        assertEquals("질문", fake.capturedStdin)
    }

    @Test
    fun `messagesToPrompt - 히스토리가 있으면 라벨 붙여 합친다`() {
        val prompt = messagesToPrompt(
            listOf(
                Message(Role.USER, "안녕"),
                Message(Role.ASSISTANT, "안녕하세요"),
                Message(Role.USER, "오늘 날씨는?"),
            ),
        )
        assertTrue(prompt.contains("사용자: 안녕"))
        assertTrue(prompt.contains("비서: 안녕하세요"))
        assertTrue(prompt.trimEnd().endsWith("오늘 날씨는?"))
    }

    private fun delta(text: String) =
        """{"type":"stream_event","event":{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"$text"}}}"""

    private fun assertContainsSeq(list: List<String>, a: String, b: String) {
        val i = list.indexOf(a)
        assertTrue(i >= 0 && i + 1 < list.size && list[i + 1] == b, "argv에 [$a, $b] 없음: $list")
    }
}
