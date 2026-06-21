package omabang.engine.claude

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import omabang.engine.LlmEvent
import omabang.engine.Message
import omabang.engine.Role
import org.junit.jupiter.api.Tag
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 실제 `claude -p`를 호출하는 통합 테스트 (G1·G2·G3). 구독 슬롯/시간 소모 → 기본 제외.
 * 실행: ./gradlew test -Pintegration   (claude가 PATH에 있어야 함)
 */
@Tag("integration")
class ClaudeCliIntegrationTest {

    private val adapter = ClaudeCliAdapter()

    @Test
    fun `complete - 실제 호출로 텍스트와 usage를 받는다 (G1)`() = runBlocking {
        val r = adapter.complete(listOf(Message(Role.USER, "정확히 'OK'라고만 한 단어로 답해.")))
        assertFalse(r.isError, "에러면 안 됨: status=${r.apiErrorStatus} text=${r.text}")
        assertTrue(r.text.isNotBlank(), "텍스트가 비어있음")
        assertNotNull(r.signals.outputTokens, "usage.outputTokens 파싱 실패")
        assertTrue(r.signals.outputTokens!! > 0, "outputTokens가 0")
    }

    @Test
    fun `stream - Done 이전에 TextDelta가 1건 이상 도착한다 (G2 진짜 점진)`() = runBlocking {
        val events = adapter.stream(listOf(Message(Role.USER, "1부터 10까지 한국어로 세어줘."))).toList()
        val doneIdx = events.indexOfFirst { it is LlmEvent.Done }
        assertTrue(doneIdx >= 0, "Done 이벤트 없음: $events")
        val deltasBeforeDone = events.take(doneIdx).count { it is LlmEvent.TextDelta }
        assertTrue(deltasBeforeDone >= 1, "Done 이전 TextDelta가 없음 = 점진 전달 실패")
    }

    @Test
    fun `stream 취소가 즉시 완료된다 - 블로킹 readLine 취소 (G3·S4)`() = runBlocking {
        // 주: 이 환경엔 다른 claude 프로세스(코딩 세션 등)가 떠 있어 `pgrep claude == 0` 단정은 불가.
        // 결정적 좀비 0 검증은 ProcessLineStreamerTest(고유 마커). 여기선 취소가 블로킹에 안 걸리는지 본다.
        val job = launch(Dispatchers.IO) {
            adapter.stream(listOf(Message(Role.USER, "아주 긴 이야기를 길고 자세히 써줘."))).collect { }
        }
        delay(2000) // 스트리밍 시작 후
        val elapsed = measureTimeMillis { job.cancelAndJoin() }
        assertTrue(elapsed < 4000, "취소가 즉시 완료되지 않음(블로킹 readLine 미취소 의심): ${elapsed}ms")
    }
}
