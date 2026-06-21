package omabang.engine.claude

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ProcessLineStreamer 테스트. claude 없이 fake 프로세스(sh/cat)로 취소·좀비·stdin·라인경계를 결정적으로 검증.
 * → G3(좀비 0)·S2(destroyForcibly)·S4(블로킹 readLine 취소)·S6(stdin→EOF)·S7(라인 경계).
 */
class ProcessLineStreamerTest {

    @Test
    fun `정상 종료 - 모든 라인 수집 후 EOF`() = runBlocking {
        val lines = ProcessLineStreamer.stream(listOf("sh", "-c", "printf 'a\\nb\\nc\\n'"), null).toList()
        assertEquals(listOf("a", "b", "c"), lines)
    }

    @Test
    fun `stdin이 stdout으로 전달된다 (S6) + 라인 경계(S7)`() = runBlocking {
        // cat은 stdin을 그대로 stdout으로. 개행으로 라인이 정확히 분할돼야 한다.
        val lines = ProcessLineStreamer.stream(listOf("cat"), "hello\nworld\n").toList()
        assertEquals(listOf("hello", "world"), lines)
    }

    @Test
    fun `취소 시 자식 프로세스가 죽는다 - 좀비 0 (G3·S2·S4)`() = runBlocking {
        val marker = "omabang-zombie-probe-7f3a9"
        // READY 마커 출력 후 무한 루프. 취소되면 destroyForcibly로 죽어야 한다.
        val cmd = listOf("sh", "-c", "echo $marker; while true; do echo tick; sleep 0.1; done")

        val job = launch(Dispatchers.IO) {
            ProcessLineStreamer.stream(cmd, null).collect { /* 계속 수집 */ }
        }

        waitUntil(3000) { pgrepCount(marker) >= 1 }
        assertTrue(pgrepCount(marker) >= 1, "fake 프로세스가 떠야 한다")

        job.cancelAndJoin() // 취소 → callbackFlow awaitClose → destroyForcibly

        waitUntil(3000) { pgrepCount(marker) == 0 }
        assertEquals(0, pgrepCount(marker), "취소 후 자식 프로세스가 남으면 안 된다 (좀비)")
    }

    private fun pgrepCount(marker: String): Int {
        val p = ProcessBuilder("pgrep", "-f", marker).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        return if (out.isEmpty()) 0 else out.lines().size
    }

    private suspend fun waitUntil(timeoutMs: Long, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !cond()) delay(50)
    }
}
