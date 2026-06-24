package omabang.web

import kotlinx.coroutines.flow.flowOf
import omabang.engine.CompleteResult
import omabang.engine.LlmEvent
import omabang.engine.LlmPort
import omabang.engine.UsageSignals
import omabang.engine.orchestrate.FakeLlmPort
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamControllerTest {

    @TestConfiguration
    class FakeConfig {
        @Bean
        @Primary
        fun fakeLlm(): LlmPort = FakeLlmPort(streamHandler = { _, _ ->
            flowOf(
                LlmEvent.TextDelta("안"),
                LlmEvent.TextDelta("녕"),
                LlmEvent.Done(CompleteResult("안녕", "fake", UsageSignals(outputTokens = 2))),
            )
        })
    }

    @LocalServerPort
    var port: Int = 0

    @Test
    fun stream_emits_deltas_then_done_in_order() {
        val client = HttpClient.newHttpClient()
        val req = HttpRequest.newBuilder(URI("http://localhost:$port/api/stream?prompt=hi")).build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())

        assertEquals(200, resp.statusCode())
        assertTrue(resp.headers().firstValue("content-type").get().contains("text/event-stream"))

        val body = resp.body()
        assertTrue(body.contains("event:delta"), "delta 이벤트 존재")
        assertTrue(body.contains("안") && body.contains("녕"), "토큰 데이터 존재")
        assertTrue(body.contains("event:done"), "done 이벤트 존재")
        // 순서: 첫 delta가 done보다 앞
        assertTrue(body.indexOf("event:delta") < body.indexOf("event:done"), "delta가 done보다 먼저")
    }
}
