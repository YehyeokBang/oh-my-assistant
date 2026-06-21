package omabang.engine.claude

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.concurrent.thread

/**
 * 외부 프로세스를 띄워 stdout을 라인 단위로 점진 emit하는 저수준 스트리머.
 *
 * ⚠️ 블로킹 I/O 취소 함정(스펙 §4.2): JVM의 블로킹 readLine은 코루틴 취소에 반응하지 않는다.
 * 따라서 단순 `flow{}+lineSequence`가 아니라 **callbackFlow + 전용 읽기 스레드 + awaitClose { destroyForcibly }**
 * 를 쓴다. 취소·타임아웃·정상종료 어느 경로든 awaitClose에서 자식(과 후손) 프로세스를 강제 종료해 좀비 0(G3).
 * destroyForcibly가 stdout 스트림을 닫으면 블로킹 readLine이 깨어나 읽기 스레드가 끝난다(S4).
 *
 * - stderr 별도 드레인(S5): 안 읽으면 파이프 버퍼 만차 시 자식이 블록(고전 데드락).
 * - stdin 쓰기→close=EOF(S6): 별도 스레드로 stdout 읽기와 동시에.
 * - 라인 경계(S7): BufferedReader.readLine이 read에 걸쳐 라인을 버퍼링하므로 partial-line 자동 처리.
 */
object ProcessLineStreamer {

    fun stream(command: List<String>, stdin: String?): Flow<String> = callbackFlow {
        val process = ProcessBuilder(command)
            .redirectErrorStream(false) // stderr 별도 (S5)
            .start()

        // stderr 드레인 전용 데몬 스레드 (S5)
        thread(isDaemon = true, name = "proc-stderr") {
            runCatching { process.errorStream.bufferedReader().forEachLine { /* drain */ } }
        }

        // stdin 쓰기 → EOF (S6)
        thread(isDaemon = true, name = "proc-stdin") {
            runCatching {
                process.outputStream.bufferedWriter().use { w ->
                    if (stdin != null) w.write(stdin)
                    w.flush()
                } // use{} 종료 = close = EOF
            }
        }

        // stdout 읽기 전용 데몬 스레드 (블로킹 readLine은 취소 미반응 → 전용 스레드)
        thread(isDaemon = true, name = "proc-stdout") {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break          // EOF
                        if (trySendBlocking(line).isClosed) break       // 수집 취소/완료 — backpressure는 block으로 처리(드롭 X)
                    }
                }
                process.waitFor()
                close()                                                 // 정상 EOF → flow 정상 종료
            } catch (t: Throwable) {
                close(t)                                                // destroyForcibly로 스트림이 닫히면 여기로(취소 경로)
            }
        }

        awaitClose {
            // 취소/정상종료/에러 모두 통과. 좀비 0 (G3): 후손까지 강제 종료(real claude가 자식 띄울 수 있음).
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }
}
