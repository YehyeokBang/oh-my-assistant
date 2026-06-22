package omabang.engine.orchestrate

import kotlinx.coroutines.runBlocking
import omabang.engine.claude.ClaudeCliAdapter

/**
 * 콘솔 진입점 (P5): 사람이 직접 한 병렬 턴을 돌린다. Phase 0 Main과 별개 진입점.
 *   ./gradlew runOrchestrator
 * 입력: 1번째 줄 = goal, 이후 줄 = "역할 :: 프롬프트" (빈 줄/EOF까지).
 * 출력: 워커별 상태(stderr)와 최종 merged(stdout).
 */
fun main(): Unit = runBlocking {
    System.err.print("목표(goal)> ")
    val goal = readlnOrNull()?.takeIf { it.isNotBlank() } ?: run {
        System.err.println("(빈 목표 — 종료)")
        return@runBlocking
    }

    System.err.println("태스크를 '역할 :: 프롬프트' 형식으로 한 줄에 하나씩. 빈 줄이면 입력 종료:")
    val tasks = buildList {
        while (true) {
            val line = readlnOrNull()?.takeIf { it.isNotBlank() } ?: break
            val parts = line.split("::", limit = 2)
            if (parts.size != 2) {
                System.err.println("(형식 오류, 건너뜀): $line")
                continue
            }
            add(WorkerTask(role = parts[0].trim(), prompt = parts[1].trim()))
        }
    }
    if (tasks.isEmpty()) {
        System.err.println("(태스크 없음 — 종료)")
        return@runBlocking
    }

    System.err.println("--- 병렬 실행 (${tasks.size}개 워커) ---")
    val result = Orchestrator(ClaudeCliAdapter()).runParallel(goal, tasks)

    result.workers.forEach { w ->
        when (w) {
            is WorkerResult.Done -> System.err.println("[Done] ${w.task.role}: ${w.text.take(80)}")
            is WorkerResult.Failed -> System.err.println("[Failed] ${w.task.role}: ${w.error}")
        }
    }
    println("\n=== 머지 결과 ===")
    println(result.merged)
}
