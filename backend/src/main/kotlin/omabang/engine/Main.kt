package omabang.engine

import kotlinx.coroutines.runBlocking
import omabang.engine.claude.ClaudeCliAdapter

/**
 * 콘솔 진입점 (G5): 사람이 직접 한 턴 돌려보는 용도.
 *   ./gradlew run --args="1부터 5까지 세어줘"   또는 인자 없이 실행하면 프롬프트 입력.
 * 토큰 델타는 stdout, 진단(usage/error)은 stderr로 분리 출력한다.
 */
fun main(args: Array<String>): Unit = runBlocking {
    val adapter = ClaudeCliAdapter() // 보안: 기본 도구 없음(read-only first)

    val prompt = if (args.isNotEmpty()) {
        args.joinToString(" ")
    } else {
        System.err.print("프롬프트> ")
        readlnOrNull()?.takeIf { it.isNotBlank() } ?: run {
            System.err.println("(빈 입력 — 종료)")
            return@runBlocking
        }
    }

    System.err.println("--- stream 시작 ---")
    adapter.stream(listOf(Message(Role.USER, prompt))).collect { ev ->
        when (ev) {
            is LlmEvent.TextDelta -> print(ev.text)
            is LlmEvent.Done -> {
                println()
                val s = ev.result.signals
                System.err.println(
                    "--- done --- backend=${ev.result.backend} " +
                        "in=${s.inputTokens} out=${s.outputTokens} " +
                        "cacheRead=${s.cacheReadInputTokens} cost=\$${s.costUsd}"
                )
            }
            is LlmEvent.Error -> System.err.println("--- error --- status=${ev.status} ${ev.message}")
        }
    }
}
