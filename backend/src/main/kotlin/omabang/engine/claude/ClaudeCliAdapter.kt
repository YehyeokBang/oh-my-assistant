package omabang.engine.claude

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import omabang.engine.CompleteOpts
import omabang.engine.CompleteResult
import omabang.engine.LlmEvent
import omabang.engine.LlmPort
import omabang.engine.Message
import omabang.engine.Role
import omabang.engine.UsageSignals

/**
 * claude-cli 스트리밍 어댑터 (스펙 §4).
 *
 * 인자: `claude -p --output-format stream-json --verbose --include-partial-messages [...]`
 *   - --verbose: S1 실측 필수(없으면 CLI 거부).
 *   - --include-partial-messages: 진짜 토큰 델타(없으면 블록 통째) — S1 확정.
 *
 * stream(): NDJSON 라인 → ClaudeCliLine → LlmEvent 정규화. complete(): D3대로 stream() 소비 후 합성.
 *
 * @param command claude 바이너리 (PATH 의존 — IDE/gradlew run의 PATH가 셸과 다를 수 있음, 스펙 §6 주).
 * @param streamer 테스트용 주입 seam(기본 ProcessLineStreamer). (command 리스트, stdin) → 라인 Flow.
 */
class ClaudeCliAdapter(
    private val command: String = "claude",
    private val defaultModel: String? = null,
    private val defaultAllowedTools: List<String> = emptyList(),
    private val streamer: (List<String>, String?) -> Flow<String> = ProcessLineStreamer::stream,
) : LlmPort {

    private val backend = "claude-cli"

    override fun stream(messages: List<Message>, opts: CompleteOpts): Flow<LlmEvent> = flow {
        val argv = listOf(command) + buildArgs(opts)
        val prompt = messagesToPrompt(messages)
        var rateLimit = null as omabang.engine.RateLimitSignal?
        var terminated = false

        streamer(argv, prompt).collect { line ->
            when (val parsed = StreamJsonParser.parseLine(line)) {
                is ClaudeCliLine.StreamEvent -> emit(LlmEvent.TextDelta(parsed.textDelta))
                is ClaudeCliLine.RateLimit -> rateLimit = parsed.signal // result에 동봉하려 누적
                is ClaudeCliLine.Result -> {
                    terminated = true
                    if (parsed.isError) {
                        emit(LlmEvent.Error(parsed.apiErrorStatus, parsed.text.ifBlank { "claude-cli error" }))
                    } else {
                        val signals = parsed.signals.copy(rateLimit = rateLimit ?: parsed.signals.rateLimit)
                        emit(LlmEvent.Done(CompleteResult(parsed.text, backend, signals, isError = false)))
                    }
                }
                is ClaudeCliLine.Assistant -> Unit // 스트리밍은 델타로 받음(중복) — 무시
                ClaudeCliLine.Unknown -> Unit       // system/미지 → skip (G4)
            }
        }
        // result 라인 없이 스트림이 닫혔다(프로세스 비정상 종료/취소 등) → 에러로 마감.
        if (!terminated) {
            emit(LlmEvent.Error(null, "claude-cli: result 라인 없이 스트림 종료"))
        }
    }

    /** D3: stream()의 TextDelta를 모으고 Done에서 CompleteResult 확정. Error는 던지지 않고 isError=true로 반환(§4.2). */
    override suspend fun complete(messages: List<Message>, opts: CompleteOpts): CompleteResult {
        val parts = StringBuilder()
        var done: CompleteResult? = null
        var error: LlmEvent.Error? = null
        stream(messages, opts).collect { ev ->
            when (ev) {
                is LlmEvent.TextDelta -> parts.append(ev.text)
                is LlmEvent.Done -> done = ev.result
                is LlmEvent.Error -> error = ev
            }
        }
        done?.let { return it }
        return CompleteResult(
            text = parts.toString(),
            backend = backend,
            signals = UsageSignals(),
            isError = true,
            apiErrorStatus = error?.status,
        )
    }

    private fun buildArgs(opts: CompleteOpts): List<String> = buildList {
        addAll(listOf("-p", "--output-format", "stream-json", "--verbose", "--include-partial-messages"))
        (opts.model ?: defaultModel)?.let { add("--model"); add(it) }
        val tools = opts.allowedTools.ifEmpty { defaultAllowedTools }
        if (tools.isNotEmpty()) { add("--allowedTools"); add(tools.joinToString(",")) }
        opts.systemPrompt?.let { add("--append-system-prompt"); add(it) }
    }
}

/** 메시지 목록 → claude 프롬프트. 출처: 기존 claude-cli.ts:7-15 포팅. */
internal fun messagesToPrompt(messages: List<Message>): String {
    if (messages.isEmpty()) return ""
    val label = { r: Role -> when (r) { Role.USER -> "사용자"; Role.ASSISTANT -> "비서"; Role.SYSTEM -> "시스템" } }
    val history = messages.dropLast(1)
    val last = messages.last()
    val head = if (history.isNotEmpty()) {
        "아래는 이전 대화다.\n" + history.joinToString("\n") { "${label(it.role)}: ${it.content}" } + "\n\n이제 질문:\n"
    } else {
        ""
    }
    return head + last.content
}
