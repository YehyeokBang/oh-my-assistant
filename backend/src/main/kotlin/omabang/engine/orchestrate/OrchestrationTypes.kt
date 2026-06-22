package omabang.engine.orchestrate

import omabang.engine.UsageSignals

/** 워커 1개 = 무상태 단발 호출. 역할 주입 + 프롬프트 + 최소 컨텍스트(히스토리 아님). 스펙 §4. */
data class WorkerTask(val role: String, val prompt: String, val context: String? = null)

/** 부분 성공을 표현하는 sealed 결과. done은 text, failed는 error. 스펙 §4. */
sealed interface WorkerResult {
    val task: WorkerTask
    data class Done(override val task: WorkerTask, val text: String, val signals: UsageSignals) : WorkerResult
    data class Failed(override val task: WorkerTask, val error: String) : WorkerResult
}

data class OrchestrationResult(
    val merged: String,              // 신서사이저 합성 결과
    val workers: List<WorkerResult>, // per-task 원본(부분 성공 관측·디버깅)
    val mergeSignals: UsageSignals?, // 머지 호출 usage (머지 생략 시 null)
)

data class ParallelOpts(
    val concurrency: Int = 4,            // Semaphore permit 수
    val workerTimeoutMs: Long = 180_000, // 워커당 타임아웃
    val model: String? = null,
    val allowedTools: List<String> = emptyList(), // 보안: read-only first (conventions.md)
)
