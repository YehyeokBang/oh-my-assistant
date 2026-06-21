package omabang.engine

import kotlinx.coroutines.flow.Flow

/**
 * 헤드리스 엔진의 베이스 포트 (스펙 §2). 딱 2개: complete + stream.
 * 병렬 위임·에이전틱 도구사용은 베이스 포트에 넣지 않는다(상위 capability 레이어, Phase 1).
 * 구현체가 primary(claude-cli) → fallback(Anthropic API)을 감싸므로 호출자는 백엔드를 모른다.
 */
interface LlmPort {
    suspend fun complete(messages: List<Message>, opts: CompleteOpts = CompleteOpts()): CompleteResult
    fun stream(messages: List<Message>, opts: CompleteOpts = CompleteOpts()): Flow<LlmEvent>
}

enum class Role { USER, ASSISTANT, SYSTEM }

data class Message(val role: Role, val content: String)

data class CompleteOpts(
    val model: String? = null,
    val systemPrompt: String? = null,
    val allowedTools: List<String> = emptyList(), // 보안: read-only first (conventions.md)
)

/**
 * stream()이 흘리는 백엔드 무관 정규화 이벤트 (스펙 §2.0, D1).
 * claude-cli와 (후속) Anthropic API가 둘 다 이 타입으로 매핑 → 소비자는 누가 응답했는지 모른다.
 */
sealed interface LlmEvent {
    data class TextDelta(val text: String) : LlmEvent       // 진행 중 토큰 (S1: content_block_delta/text_delta)
    data class Done(val result: CompleteResult) : LlmEvent  // 종료 — usage·cost 동봉
    data class Error(val status: Int?, val message: String) : LlmEvent // 예: 429
}

data class CompleteResult(
    val text: String,
    val backend: String,             // "claude-cli" | "anthropic-api" — 관측/로깅용. 소비자 분기 금지(라우터 투명성).
    val signals: UsageSignals,
    val isError: Boolean = false,
    val apiErrorStatus: Int? = null, // 예: 429
)

data class UsageSignals(             // 출처: 기존 llm.ts:14, S1 실측 확정
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cacheCreationInputTokens: Int? = null,
    val cacheReadInputTokens: Int? = null,
    val costUsd: Double? = null,                 // result.total_cost_usd (구독 소진율 대용)
    val rateLimit: RateLimitSignal? = null,      // S3 전까지 폴백 트리거 미사용
    val raw: Map<String, Any?>? = null,          // result.usage 원본(디버깅)
)

data class RateLimitSignal(          // 출처: 기존 llm.ts:8-12, S1 실측 확정
    val status: String? = null,
    val rateLimitType: String? = null, // 예: "five_hour"
    val resetsAt: Long? = null,         // epoch seconds
)
