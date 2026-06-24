package omabang.web

import omabang.engine.CompleteResult
import omabang.engine.LlmEvent
import org.springframework.http.codec.ServerSentEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** LlmEvent → SSE event 순수 매핑 (스펙 §4 DW3). sealed exhaustive when. */
object SseMapping {
    fun toSse(event: LlmEvent): ServerSentEvent<String> = when (event) {
        is LlmEvent.TextDelta ->
            ServerSentEvent.builder(event.text).event("delta").build()
        is LlmEvent.Done ->
            ServerSentEvent.builder(doneJson(event.result)).event("done").build()
        is LlmEvent.Error ->
            ServerSentEvent.builder(errorJson(event.status, event.message)).event("error").build()
    }

    private fun doneJson(r: CompleteResult): String = buildJsonObject {
        put("backend", r.backend)
        put("inputTokens", r.signals.inputTokens)
        put("outputTokens", r.signals.outputTokens)
        put("costUsd", r.signals.costUsd)
    }.toString()

    private fun errorJson(status: Int?, message: String): String = buildJsonObject {
        put("status", status)
        put("message", message)
    }.toString()
}
