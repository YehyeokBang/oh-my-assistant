package omabang.engine.orchestrate

import omabang.engine.CompleteOpts
import omabang.engine.LlmPort
import omabang.engine.Message
import omabang.engine.Role
import omabang.engine.UsageSignals

data class SynthOutcome(val merged: String, val signals: UsageSignals?)

/** 머지 1책임 (스펙 §6). 성공 결과들을 한 답으로 종합. 성공 0개·1개(전부)면 단락. */
class Synthesizer(private val llm: LlmPort) {
    suspend fun merge(goal: String, results: List<WorkerResult>, opts: ParallelOpts): SynthOutcome {
        val done = results.filterIsInstance<WorkerResult.Done>()
        if (done.isEmpty()) return SynthOutcome("(모든 워커 실패)", null)                       // 단락: 합성할 게 없음
        if (done.size == 1 && results.size == 1) return SynthOutcome(done.single().text, null)   // 단락: 1개면 머지 불필요
        val prompt = buildMergePrompt(goal, results)
        val r = llm.complete(listOf(Message(Role.USER, prompt)), CompleteOpts(model = opts.model))
        return SynthOutcome(r.text, r.signals)
    }
}

/** goal + 워커별(역할/결과, 실패는 표기) → 한 답으로 종합 지시. 실패도 머지가 알아야 누락을 정직히 표기. */
internal fun buildMergePrompt(goal: String, results: List<WorkerResult>): String = buildString {
    appendLine("다음은 하나의 목표를 위해 여러 전문가가 병렬로 작업한 결과다.")
    appendLine("목표: $goal")
    appendLine()
    appendLine("각 작업 결과:")
    results.forEachIndexed { i, r ->
        when (r) {
            is WorkerResult.Done -> {
                appendLine("[${i + 1}] 역할=${r.task.role}")
                appendLine(r.text)
            }
            is WorkerResult.Failed -> {
                appendLine("[${i + 1}] 역할=${r.task.role} (이 항목은 실패: ${r.error})")
            }
        }
        appendLine()
    }
    append("위 결과들을 종합해 목표에 대한 하나의 완결된 답을 작성하라. 실패한 항목이 있으면 누락을 정직하게 언급하라.")
}
