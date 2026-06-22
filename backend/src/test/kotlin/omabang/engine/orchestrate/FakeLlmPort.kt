package omabang.engine.orchestrate

import kotlinx.coroutines.flow.Flow
import omabang.engine.CompleteOpts
import omabang.engine.CompleteResult
import omabang.engine.LlmEvent
import omabang.engine.LlmPort
import omabang.engine.Message
import omabang.engine.UsageSignals

/** 단위 테스트용 fake. complete만 람다로 구현, stream은 워커가 안 쓰므로 TODO. (스펙 §8) */
class FakeLlmPort(
    private val handler: suspend (List<Message>, CompleteOpts) -> CompleteResult,
) : LlmPort {
    override suspend fun complete(messages: List<Message>, opts: CompleteOpts): CompleteResult =
        handler(messages, opts)

    override fun stream(messages: List<Message>, opts: CompleteOpts): Flow<LlmEvent> =
        TODO("워커는 complete만 사용 (스펙 §5)")
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
