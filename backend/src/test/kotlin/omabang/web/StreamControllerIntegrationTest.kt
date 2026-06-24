package omabang.web

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.junit.jupiter.api.Tag
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamControllerIntegrationTest {

    @LocalServerPort
    var port: Int = 0

    @Test
    fun real_claude_streams_deltas_and_done() {
        val client = HttpClient.newHttpClient()
        val req = HttpRequest.newBuilder(
            URI("http://localhost:$port/api/stream?prompt=" + java.net.URLEncoder.encode("1부터 3까지 세어줘", "UTF-8"))
        ).timeout(java.time.Duration.ofSeconds(60)).build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())

        assertEquals(200, resp.statusCode())
        val body = resp.body()
        assertTrue(body.contains("event:delta"), "delta 1개 이상")
        assertTrue(body.contains("event:done"), "done 존재")
    }
}
