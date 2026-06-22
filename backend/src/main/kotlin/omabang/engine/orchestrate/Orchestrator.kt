package omabang.engine.orchestrate

import omabang.engine.LlmPort

/** WorkerPool + Synthesizer 조립 (스펙 §6). B 플래너는 runParallel 앞단(tasks 생성)에 붙는다(D3 seam). */
class Orchestrator(llm: LlmPort) {
    private val pool = WorkerPool(llm)
    private val synth = Synthesizer(llm)

    suspend fun runParallel(
        goal: String,
        tasks: List<WorkerTask>,
        opts: ParallelOpts = ParallelOpts(),
    ): OrchestrationResult {
        require(tasks.isNotEmpty()) { "tasks가 비어있다" }
        val workers = pool.run(tasks, opts)
        val outcome = synth.merge(goal, workers, opts)
        return OrchestrationResult(outcome.merged, workers, outcome.signals)
    }
}
