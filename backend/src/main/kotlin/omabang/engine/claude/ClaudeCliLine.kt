package omabang.engine.claude

import omabang.engine.RateLimitSignal
import omabang.engine.UsageSignals

/**
 * claude-cli `stream-json` 출력의 각 NDJSON 라인을 모델링한 어댑터 내부 와이어 포맷 (스펙 §3).
 * 포트 레벨 이벤트(LlmEvent)와 구분 — claude 와이어 지식이 멈추는 어댑터 내부 경계.
 *
 * S1 실측(docs/specs/2026-06-22-s1-spike-findings.md)으로 placeholder를 확정:
 * wire `type`은 system/stream_event/assistant/result/rate_limit_event 6종. system + 미지 + stream_event의
 * 비-text_delta 하위이벤트는 모두 Unknown으로 흡수한다(파서 안 죽음, G4).
 */
sealed interface ClaudeCliLine {
    // type=assistant — message.content[]의 text 블록만 join (thinking 블록 제외).
    data class Assistant(val text: String) : ClaudeCliLine

    // type=stream_event 중 event.type=content_block_delta && delta.type=text_delta 의 text만.
    // thinking_delta/signature_delta/block_start·stop/message_* 은 답변 텍스트가 아니므로 Unknown.
    data class StreamEvent(val textDelta: String) : ClaudeCliLine

    // type=result — usage·cost·전체 텍스트의 권위 소스.
    data class Result(
        val text: String,
        val signals: UsageSignals,
        val isError: Boolean,
        val apiErrorStatus: Int?,
    ) : ClaudeCliLine

    // type=rate_limit_event
    data class RateLimit(val signal: RateLimitSignal) : ClaudeCliLine

    // type=system / 미지 type / 파싱 불가 → skip (G4)
    data object Unknown : ClaudeCliLine
}
