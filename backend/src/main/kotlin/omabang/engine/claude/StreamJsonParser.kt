package omabang.engine.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import omabang.engine.RateLimitSignal
import omabang.engine.UsageSignals

/**
 * NDJSON 한 라인 → ClaudeCliLine. (스펙 §3·§4.2, S1 실측 스키마 기반)
 *
 * 절대 예외를 던지지 않는다(G4): 파싱 불가/미지/형식 위반은 모두 Unknown. JVM엔 내장 JSON이 없어
 * parseToJsonElement(런타임만, 컴파일러 플러그인 불필요)로 JsonElement를 순회한다.
 */
object StreamJsonParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parseLine(line: String): ClaudeCliLine {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return ClaudeCliLine.Unknown
        return runCatching {
            val obj = json.parseToJsonElement(trimmed).jsonObject
            when (obj.str("type")) {
                "assistant" -> parseAssistant(obj)
                "stream_event" -> parseStreamEvent(obj)
                "result" -> parseResult(obj)
                "rate_limit_event" -> parseRateLimit(obj)
                else -> ClaudeCliLine.Unknown // system, 미지 type
            }
        }.getOrDefault(ClaudeCliLine.Unknown)
    }

    private fun parseAssistant(obj: JsonObject): ClaudeCliLine {
        val content = (obj["message"] as? JsonObject)?.get("content")?.jsonArray
            ?: return ClaudeCliLine.Unknown
        val text = content
            .mapNotNull { it as? JsonObject }
            .filter { it.str("type") == "text" }
            .mapNotNull { it.str("text") }
            .joinToString("")
        return ClaudeCliLine.Assistant(text)
    }

    private fun parseStreamEvent(obj: JsonObject): ClaudeCliLine {
        val event = obj["event"] as? JsonObject ?: return ClaudeCliLine.Unknown
        if (event.str("type") != "content_block_delta") return ClaudeCliLine.Unknown
        val delta = event["delta"] as? JsonObject ?: return ClaudeCliLine.Unknown
        if (delta.str("type") != "text_delta") return ClaudeCliLine.Unknown // thinking/signature 무시
        val text = delta.str("text") ?: return ClaudeCliLine.Unknown
        return ClaudeCliLine.StreamEvent(text)
    }

    private fun parseResult(obj: JsonObject): ClaudeCliLine {
        val subtype = obj.str("subtype")
        val isError = (obj.prim("is_error")?.booleanOrNull == true) ||
            (subtype != null && subtype != "success") // is_error만 보면 subtype 기반 에러를 놓침 (claude-cli.ts:50)
        val usage = obj["usage"] as? JsonObject
        val signals = UsageSignals(
            inputTokens = usage?.int("input_tokens"),
            outputTokens = usage?.int("output_tokens"),
            cacheCreationInputTokens = usage?.int("cache_creation_input_tokens"),
            cacheReadInputTokens = usage?.int("cache_read_input_tokens"),
            costUsd = obj.prim("total_cost_usd")?.doubleOrNull,
            raw = usage?.toRawMap(),
        )
        return ClaudeCliLine.Result(
            text = obj.str("result") ?: "",
            signals = signals,
            isError = isError,
            apiErrorStatus = obj.prim("api_error_status")?.intOrNull,
        )
    }

    private fun parseRateLimit(obj: JsonObject): ClaudeCliLine {
        val rl = obj["rate_limit_info"] as? JsonObject
        return ClaudeCliLine.RateLimit(
            RateLimitSignal(
                status = rl?.str("status"),
                rateLimitType = rl?.str("rateLimitType"),
                resetsAt = rl?.prim("resetsAt")?.longOrNull,
            )
        )
    }

    // result.usage 원본을 디버깅용 평면 맵으로. 중첩 값은 문자열화(YAGNI).
    private fun JsonObject.toRawMap(): Map<String, Any?> = mapValues { (_, v) ->
        if (v is JsonPrimitive) {
            v.intOrNull ?: v.longOrNull ?: v.doubleOrNull ?: v.booleanOrNull ?: v.contentOrNull
        } else {
            v.toString()
        }
    }

    private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
    private fun JsonObject.str(key: String): String? = prim(key)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = prim(key)?.intOrNull
}
