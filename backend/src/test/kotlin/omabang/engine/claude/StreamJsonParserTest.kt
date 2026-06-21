package omabang.engine.claude

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * StreamJsonParser 단위 테스트. 픽스처는 S1 실측 라인(docs/specs/2026-06-22-s1-spike-findings.md) 기반.
 * 외부 프로세스/claude 호출 없음 — 순수 함수.
 */
class StreamJsonParserTest {

    @Test
    fun `stream_event의 text_delta는 StreamEvent로 파싱된다`() {
        val line = """{"type":"stream_event","event":{"type":"content_block_delta","index":1,""" +
            """"delta":{"type":"text_delta","text":"1. 하나 — 외로운 하나"}},"uuid":"x"}"""
        assertEquals(ClaudeCliLine.StreamEvent("1. 하나 — 외로운 하나"), StreamJsonParser.parseLine(line))
    }

    @Test
    fun `thinking_delta는 답변 텍스트가 아니므로 Unknown`() {
        val line = """{"type":"stream_event","event":{"type":"content_block_delta","index":0,""" +
            """"delta":{"type":"thinking_delta","thinking":"음..."}}}"""
        assertEquals(ClaudeCliLine.Unknown, StreamJsonParser.parseLine(line))
    }

    @Test
    fun `content_block_start 등 비-델타 stream_event는 Unknown`() {
        val line = """{"type":"stream_event","event":{"type":"content_block_start","index":1,""" +
            """"content_block":{"type":"text","text":""}}}"""
        assertEquals(ClaudeCliLine.Unknown, StreamJsonParser.parseLine(line))
    }

    @Test
    fun `result 라인에서 text·usage·cost를 추출한다`() {
        val line = """{"type":"result","subtype":"success","is_error":false,"api_error_status":null,""" +
            """"result":"답","total_cost_usd":0.245435,""" +
            """"usage":{"input_tokens":9296,"output_tokens":257,""" +
            """"cache_creation_input_tokens":19253,"cache_read_input_tokens":0}}"""
        val r = StreamJsonParser.parseLine(line) as ClaudeCliLine.Result
        assertEquals("답", r.text)
        assertFalse(r.isError)
        assertEquals(9296, r.signals.inputTokens)
        assertEquals(257, r.signals.outputTokens)
        assertEquals(19253, r.signals.cacheCreationInputTokens)
        assertEquals(0, r.signals.cacheReadInputTokens)
        assertEquals(0.245435, r.signals.costUsd)
    }

    @Test
    fun `subtype이 success가 아니면 isError true (is_error false여도)`() {
        val line = """{"type":"result","subtype":"error_max_turns","is_error":false,"result":""}"""
        assertTrue((StreamJsonParser.parseLine(line) as ClaudeCliLine.Result).isError)
    }

    @Test
    fun `is_error true와 api_error_status를 잡는다`() {
        val line = """{"type":"result","subtype":"success","is_error":true,"api_error_status":429,"result":""}"""
        val r = StreamJsonParser.parseLine(line) as ClaudeCliLine.Result
        assertTrue(r.isError)
        assertEquals(429, r.apiErrorStatus)
    }

    @Test
    fun `rate_limit_event를 신호로 파싱`() {
        val line = """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed",""" +
            """"resetsAt":1782062400,"rateLimitType":"five_hour"}}"""
        val r = StreamJsonParser.parseLine(line) as ClaudeCliLine.RateLimit
        assertEquals("allowed", r.signal.status)
        assertEquals("five_hour", r.signal.rateLimitType)
        assertEquals(1782062400L, r.signal.resetsAt)
    }

    @Test
    fun `assistant 라인은 text 블록만 join (thinking 제외)`() {
        val line = """{"type":"assistant","message":{"content":[""" +
            """{"type":"thinking","thinking":"x"},{"type":"text","text":"가"},{"type":"text","text":"나"}]}}"""
        assertEquals(ClaudeCliLine.Assistant("가나"), StreamJsonParser.parseLine(line))
    }

    @Test
    fun `system 라인은 Unknown으로 흡수 (G4)`() {
        assertEquals(
            ClaudeCliLine.Unknown,
            StreamJsonParser.parseLine("""{"type":"system","subtype":"init","session_id":"x"}"""),
        )
    }

    @Test
    fun `미지 type은 Unknown (G4)`() {
        assertEquals(
            ClaudeCliLine.Unknown,
            StreamJsonParser.parseLine("""{"type":"future_event_42","foo":"bar"}"""),
        )
    }

    @Test
    fun `깨진 JSON·빈 줄·type 없는 객체도 던지지 않고 Unknown (G4)`() {
        assertEquals(ClaudeCliLine.Unknown, StreamJsonParser.parseLine("not json at all {{{"))
        assertEquals(ClaudeCliLine.Unknown, StreamJsonParser.parseLine(""))
        assertEquals(ClaudeCliLine.Unknown, StreamJsonParser.parseLine("   "))
        assertEquals(ClaudeCliLine.Unknown, StreamJsonParser.parseLine("""{"no_type":true}"""))
        // type 필드가 문자열이 아닌 형식 위반도 Unknown
        assertEquals(ClaudeCliLine.Unknown, StreamJsonParser.parseLine("""{"type":{"nested":1}}"""))
    }
}
