package omabang.web

import omabang.engine.CompleteResult
import omabang.engine.LlmEvent
import omabang.engine.UsageSignals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SseMappingTest {
    @Test
    fun textDelta_maps_to_delta_event() {
        val sse = SseMapping.toSse(LlmEvent.TextDelta("안녕"))
        assertEquals("delta", sse.event())
        assertEquals("안녕", sse.data())
    }

    @Test
    fun done_maps_to_done_event_with_usage_json() {
        val r = CompleteResult(
            text = "안녕",
            backend = "claude-cli",
            signals = UsageSignals(inputTokens = 3, outputTokens = 5, costUsd = 0.01),
        )
        val sse = SseMapping.toSse(LlmEvent.Done(r))
        assertEquals("done", sse.event())
        val data = sse.data()!!
        assertTrue(data.contains("claude-cli"))
        assertTrue(data.contains("\"outputTokens\":5"))
    }

    @Test
    fun error_maps_to_error_event() {
        val sse = SseMapping.toSse(LlmEvent.Error(429, "rate limited"))
        assertEquals("error", sse.event())
        val data = sse.data()!!
        assertTrue(data.contains("429"))
        assertTrue(data.contains("rate limited"))
    }
}
