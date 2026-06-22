package omabang.engine.orchestrate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import omabang.engine.CompleteOpts
import omabang.engine.LlmPort
import omabang.engine.Message
import omabang.engine.Role

/** 순수 동시성 메커니즘 (스펙 §5). tasks N개를 Semaphore로 동시성 제한 팬아웃 → 부분 결과 수집. */
class WorkerPool(private val llm: LlmPort) {

    suspend fun run(tasks: List<WorkerTask>, opts: ParallelOpts): List<WorkerResult> = coroutineScope {
        val gate = Semaphore(opts.concurrency)
        tasks.map { task -> async { gate.withPermit { runWorker(task, opts) } } }.awaitAll()
    }

    private suspend fun runWorker(task: WorkerTask, opts: ParallelOpts): WorkerResult {
        val sys = "너는 \"${task.role}\" 전문가다. 주어진 작업만 독립적으로 수행하고 결과를 간결히 보고하라."
        val user = task.prompt + (task.context?.let { "\n\n참고 컨텍스트:\n$it" } ?: "")
        val co = CompleteOpts(model = opts.model, systemPrompt = sys, allowedTools = opts.allowedTools)
        return try {
            val r = withTimeoutOrNull(opts.workerTimeoutMs) {
                llm.complete(listOf(Message(Role.USER, user)), co)
            } ?: return WorkerResult.Failed(task, "timeout ${opts.workerTimeoutMs}ms")
            if (r.isError) WorkerResult.Failed(task, "claude error status=${r.apiErrorStatus}")
            else WorkerResult.Done(task, r.text, r.signals)
        } catch (ce: CancellationException) {
            throw ce                                              // 취소는 전파(외부 취소 시 형제도 취소, D2)
        } catch (e: Exception) {
            WorkerResult.Failed(task, e.message ?: e.toString()) // 비-취소 예외는 Failed로 흡수 → 부분 결과 보존
        }
    }
}
