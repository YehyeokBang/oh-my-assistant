package omabang.web

import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.Test

@SpringBootTest
class ContextLoadTest {
    @Test
    fun contextLoads() {
        // Spring 컨텍스트가 JDK25+Kotlin2.3에서 뜨면 통과 = R1 호환 확인
    }
}
