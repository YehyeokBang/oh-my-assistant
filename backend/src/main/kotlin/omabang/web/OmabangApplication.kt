package omabang.web

import omabang.engine.LlmPort
import omabang.engine.claude.ClaudeCliAdapter
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
class OmabangApplication {
    /** 보안 read-only first: 기본 도구 없음(콘솔 Main과 동일). */
    @Bean
    fun llmPort(): LlmPort = ClaudeCliAdapter()
}

fun main(args: Array<String>) {
    runApplication<OmabangApplication>(*args)
}
