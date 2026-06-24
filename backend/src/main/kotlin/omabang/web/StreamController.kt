package omabang.web

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import omabang.engine.LlmPort
import omabang.engine.Message
import omabang.engine.Role
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 단발 스트리밍 SSE 엔드포인트 (스펙 §4 DW2). 컨트롤러가 LlmPort 직접 호출(키스톤 미추출). */
@RestController
class StreamController(private val llm: LlmPort) {

    @GetMapping("/api/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@RequestParam prompt: String): Flow<ServerSentEvent<String>> =
        llm.stream(listOf(Message(Role.USER, prompt)))
            .map { SseMapping.toSse(it) }
}
