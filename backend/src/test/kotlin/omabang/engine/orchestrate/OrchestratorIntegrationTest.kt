package omabang.engine.orchestrate

import kotlinx.coroutines.runBlocking
import omabang.engine.claude.ClaudeCliAdapter
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 실제 `claude -p`로 병렬 2태스크 머지 (P5). 구독 슬롯/시간 소모 → 기본 제외.
 * 실행: ./gradlew test -Pintegration   (claude가 PATH에 있어야 함)
 */
@Tag("integration")
class OrchestratorIntegrationTest {

    @Test
    fun `runParallel - 실제 claude로 병렬 2태스크를 한 답으로 머지한다 (P5)`() = runBlocking {
        val orch = Orchestrator(ClaudeCliAdapter())
        val result = orch.runParallel(
            goal = "두 계산 결과를 한 문장으로 합쳐서 보고하라.",
            tasks = listOf(
                WorkerTask(role = "산수1", prompt = "2 더하기 3은 얼마인가? 숫자만 한 단어로 답해."),
                WorkerTask(role = "산수2", prompt = "10 빼기 4는 얼마인가? 숫자만 한 단어로 답해."),
            ),
        )

        assertTrue(result.merged.isNotBlank(), "merged가 비어있음")
        assertTrue(
            result.workers.all { it is WorkerResult.Done },
            "워커 실패: ${result.workers}",
        )
    }
}
