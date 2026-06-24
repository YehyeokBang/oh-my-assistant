package omabang.engine.orchestrate

import kotlinx.coroutines.flow.Flow
import omabang.engine.CompleteOpts
import omabang.engine.CompleteResult
import omabang.engine.LlmEvent
import omabang.engine.LlmPort
import omabang.engine.Message
import omabang.engine.UsageSignals

/**
 * 단위 테스트용 fake. complete/stream 각각 람다 주입(미주입 시 TODO).
 * Phase 1 워커 테스트는 complete만, Phase 2 웹 테스트는 stream만 쓴다. (스펙 §8)
 */
class FakeLlmPort(
    private val streamHandler: (List<Message>, CompleteOpts) -> Flow<LlmEvent> =
        { _, _ -> TODO("stream 미사용 — 필요 시 streamHandler 주입") },
    private val handler: suspend (List<Message>, CompleteOpts) -> CompleteResult =
        { _, _ -> TODO("complete 미사용 — 필요 시 handler 주입") },
) : LlmPort {
    override suspend fun complete(messages: List<Message>, opts: CompleteOpts): CompleteResult =
        handler(messages, opts)

    override fun stream(messages: List<Message>, opts: CompleteOpts): Flow<LlmEvent> =
        streamHandler(messages, opts)
}

fun fakeResult(text: String, isError: Boolean = false, apiErrorStatus: Int? = null) =
    CompleteResult(
        text = text,
        backend = "fake",
        signals = UsageSignals(outputTokens = 1),
        isError = isError,
        apiErrorStatus = apiErrorStatus,
    )

fun sig() = UsageSignals(outputTokens = 1)
