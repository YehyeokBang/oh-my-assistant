# Phase 2 스트리밍 첫 뷰 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 엔진의 `stream(): Flow<LlmEvent>`를 SSE로 브라우저에 흘려 단발 응답 토큰을 실시간 표시하는 첫 웹 뷰를 만든다.

**Architecture:** 새 패키지 `omabang.web`에 Spring Boot(MVC) 웹 계층을 얹는다. 엔진 코어(`omabang.engine`)는 불변 — 컨트롤러가 `LlmPort`만 주입받아 `stream()`을 collect하고, 순수 함수 `SseMapping`으로 `LlmEvent`를 `ServerSentEvent`로 변환해 `text/event-stream`으로 방출한다. 프론트는 정적 HTML + 바닐라 JS `EventSource`.

**Tech Stack:** Kotlin 2.3.0 / JDK 25 / Gradle KTS, Spring Boot(starter-web, MVC), `kotlin("plugin.spring")`, `kotlinx-coroutines-reactor`(Flow↔Reactive 어댑터), kotlinx-serialization-json(기존). 통합 테스트는 JDK 내장 `java.net.http.HttpClient`.

## Global Constraints

- **Kotlin 2.3.0 / `jvmTarget` JVM_25 고정.** 25가 24로 조용히 폴백되면 빌드 실패가 정상(폴백 금지 — 비전 기각 사항).
- **엔진 코어 불변.** `omabang.engine`(claude 어댑터·LlmPort)는 건드리지 않는다. 웹은 `LlmPort`만 소비.
- **보안 read-only first.** `allowedTools` 기본 빈 값(콘솔 `Main`과 동일).
- **WebFlux 도입 금지.** SSE는 `starter-web`(MVC) + 코루틴 `Flow` + `coroutines-reactor`로 한다. (불가피하게 막히면 멈추고 사용자 확인 — 비전 결정.)
- **키스톤(안정적 이벤트 API) 추출 금지.** 뷰 1개뿐 — 컨트롤러가 `LlmPort` 직접 호출.
- **커밋:** main 직접 커밋, 한글 커밋 메시지(YehyeokBang 계정).
- **통합 테스트 태그:** 실제 claude 호출은 `@Tag("integration")`, 기본 제외(`./gradlew test -Pintegration`로 실행).

---

### Task 1: Spring Boot 도입 + 앱 부팅 (리스크 R1 검증)

이 태스크의 본질은 **R1 스파이크**다: Spring Boot가 JDK 25 + Kotlin 2.3.0과 호환되는가. `@SpringBootTest` 컨텍스트 로드가 통과하면 호환 확인. **호환 버전을 못 찾으면 여기서 멈추고 사용자에게 보고**(JDK/Kotlin 폴백은 비전이 금지).

**Files:**
- Modify: `backend/build.gradle.kts`
- Create: `backend/src/main/kotlin/omabang/web/OmabangApplication.kt`
- Test: `backend/src/test/kotlin/omabang/web/ContextLoadTest.kt`

**Interfaces:**
- Consumes: `omabang.engine.LlmPort`, `omabang.engine.claude.ClaudeCliAdapter` (기존)
- Produces: `omabang.web.OmabangApplication`(`@SpringBootApplication`), Bean `llmPort(): LlmPort`. main 클래스 `omabang.web.OmabangApplicationKt`.

- [ ] **Step 1: 최신 호환 Spring Boot 버전 확인**

JDK 25 + Kotlin 2.3.0을 지원하는 최신 안정 Spring Boot 버전을 확인한다(Spring Boot 4.0.x 계열이 JDK 25 baseline 후보). 확인:

Run: `curl -s "https://api.github.com/repos/spring-projects/spring-boot/releases/latest" | grep -m1 tag_name`
Expected: 최신 안정 태그(예: `v4.0.x`). 이 버전을 아래 `<SPRING_BOOT_VERSION>`에 사용. JDK 25 지원이 릴리스 노트에 없으면 멈추고 보고.

- [ ] **Step 2: build.gradle.kts에 Spring 플러그인·의존성 추가**

`plugins` 블록에 추가(기존 `kotlin("jvm")`/`application` 유지):

```kotlin
plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.spring") version "2.3.0"
    id("org.springframework.boot") version "<SPRING_BOOT_VERSION>"
    id("io.spring.dependency-management") version "1.1.7"
    application
}
```

`dependencies` 블록에 추가(기존 항목 유지):

```kotlin
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.11.0")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
```

`application` 블록 아래에 bootRun main 클래스 지정(기존 콘솔 `run`은 그대로 `engine.MainKt`):

```kotlin
springBoot {
    mainClass.set("omabang.web.OmabangApplicationKt")
}
```

- [ ] **Step 3: OmabangApplication 작성**

```kotlin
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
```

- [ ] **Step 4: 컨텍스트 로드 테스트 작성 (R1 핵심 검증)**

```kotlin
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
```

- [ ] **Step 5: 테스트 실행 — 컨텍스트가 뜨는지 확인**

Run: `cd backend && ./gradlew test --tests "omabang.web.ContextLoadTest"`
Expected: PASS. (실패 시 — 특히 JDK25/Kotlin2.3 비호환 에러면 — 멈추고 사용자 보고.)

- [ ] **Step 6: 앱 기동 수동 확인**

Run: `cd backend && ./gradlew bootRun` (별도 터미널에서 `curl -s http://localhost:8080/` → 404면 정상: 서버는 떴고 라우트만 없음). 확인 후 종료.
Expected: 부팅 로그에 `Started OmabangApplication`, 포트 8080 listen.

- [ ] **Step 7: Commit**

```bash
git add backend/build.gradle.kts backend/src/main/kotlin/omabang/web/OmabangApplication.kt backend/src/test/kotlin/omabang/web/ContextLoadTest.kt
git commit -m "feat(web): Spring Boot 도입 + 앱 부팅 (JDK25/Kotlin2.3 호환 확인)"
```

---

### Task 2: SseMapping 순수 함수 (V1)

**Files:**
- Create: `backend/src/main/kotlin/omabang/web/SseMapping.kt`
- Test: `backend/src/test/kotlin/omabang/web/SseMappingTest.kt`

**Interfaces:**
- Consumes: `omabang.engine.LlmEvent`(sealed: `TextDelta`/`Done`/`Error`), `CompleteResult`, `UsageSignals` (기존)
- Produces: `object SseMapping { fun toSse(event: LlmEvent): org.springframework.http.codec.ServerSentEvent<String> }`. event 이름: `delta`/`done`/`error`.

- [ ] **Step 1: 실패하는 테스트 작성**

```kotlin
package omabang.web

import omabang.engine.CompleteResult
import omabang.engine.LlmEvent
import omabang.engine.UsageSignals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SseMappingTest {
    @Test
    fun textDelta_maps_to_delta_event() {
        val sse = SseMapping.toSse(LlmEvent.TextDelta("안녕"))
        assertEquals("delta", sse.event())
        assertEquals("안녕", sse.data())
    }

    @Test
    fun done_maps_to_done_event_with_usage_json() {
        val r = CompleteResult(
            text = "안녕",
            backend = "claude-cli",
            signals = UsageSignals(inputTokens = 3, outputTokens = 5, costUsd = 0.01),
        )
        val sse = SseMapping.toSse(LlmEvent.Done(r))
        assertEquals("done", sse.event())
        val data = sse.data()!!
        assertTrue(data.contains("claude-cli"))
        assertTrue(data.contains("\"outputTokens\":5"))
    }

    @Test
    fun error_maps_to_error_event() {
        val sse = SseMapping.toSse(LlmEvent.Error(429, "rate limited"))
        assertEquals("error", sse.event())
        val data = sse.data()!!
        assertTrue(data.contains("429"))
        assertTrue(data.contains("rate limited"))
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `cd backend && ./gradlew test --tests "omabang.web.SseMappingTest"`
Expected: FAIL — `SseMapping` 미해결(unresolved reference).

- [ ] **Step 3: SseMapping 구현**

```kotlin
package omabang.web

import omabang.engine.CompleteResult
import omabang.engine.LlmEvent
import org.springframework.http.codec.ServerSentEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** LlmEvent → SSE event 순수 매핑 (스펙 §4 DW3). sealed exhaustive when. */
object SseMapping {
    fun toSse(event: LlmEvent): ServerSentEvent<String> = when (event) {
        is LlmEvent.TextDelta ->
            ServerSentEvent.builder(event.text).event("delta").build()
        is LlmEvent.Done ->
            ServerSentEvent.builder(doneJson(event.result)).event("done").build()
        is LlmEvent.Error ->
            ServerSentEvent.builder(errorJson(event.status, event.message)).event("error").build()
    }

    private fun doneJson(r: CompleteResult): String = buildJsonObject {
        put("backend", r.backend)
        put("inputTokens", r.signals.inputTokens)
        put("outputTokens", r.signals.outputTokens)
        put("costUsd", r.signals.costUsd)
    }.toString()

    private fun errorJson(status: Int?, message: String): String = buildJsonObject {
        put("status", status)
        put("message", message)
    }.toString()
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `cd backend && ./gradlew test --tests "omabang.web.SseMappingTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/omabang/web/SseMapping.kt backend/src/test/kotlin/omabang/web/SseMappingTest.kt
git commit -m "feat(web): LlmEvent→ServerSentEvent 순수 매핑 SseMapping (V1)"
```

---

### Task 3: FakeLlmPort에 stream() 지원 추가

기존 `FakeLlmPort.stream()`은 `TODO()`다(워커는 complete만 사용). Task 4 컨트롤러 통합 테스트가 `stream()`을 쓰므로 fake에 stream 람다를 추가한다. 기존 complete 기반 테스트(Phase 1)는 깨지지 않아야 한다.

**Files:**
- Modify: `backend/src/test/kotlin/omabang/engine/orchestrate/FakeLlmPort.kt`

**Interfaces:**
- Consumes: `LlmPort`, `LlmEvent`, `CompleteResult`, `Message`, `CompleteOpts` (기존)
- Produces: `FakeLlmPort(handler = ..., streamHandler = ...)` — 두 파라미터 모두 기본값(TODO). `stream()`이 `streamHandler` 호출.

- [ ] **Step 1: 기존 Phase 1 테스트가 통과하는지 베이스라인 확인**

Run: `cd backend && ./gradlew test --tests "omabang.engine.orchestrate.*"`
Expected: PASS (기존 통과 상태 확인 — 변경 전 베이스라인).

- [ ] **Step 2: FakeLlmPort 수정 — streamHandler 파라미터 추가**

기존 파일을 아래로 교체(complete 호출자 호환 유지 — `handler`가 여전히 첫 파라미터):

```kotlin
package omabang.engine.orchestrate

import kotlinx.coroutines.flow.Flow
import omabang.engine.CompleteOpts
import omabang.engine.CompleteResult
import omabang.engine.LlmEvent
import omabang.engine.LlmPort
import omabang.engine.Message
import omabang.engine.UsageSignals

/**
 * 단위 테스트용 fake. complete/stream 각각 람다 주입(미주입 시 TODO).
 * Phase 1 워커 테스트는 complete만, Phase 2 웹 테스트는 stream만 쓴다. (스펙 §8)
 */
class FakeLlmPort(
    private val handler: suspend (List<Message>, CompleteOpts) -> CompleteResult =
        { _, _ -> TODO("complete 미사용 — 필요 시 handler 주입") },
    private val streamHandler: (List<Message>, CompleteOpts) -> Flow<LlmEvent> =
        { _, _ -> TODO("stream 미사용 — 필요 시 streamHandler 주입") },
) : LlmPort {
    override suspend fun complete(messages: List<Message>, opts: CompleteOpts): CompleteResult =
        handler(messages, opts)

    override fun stream(messages: List<Message>, opts: CompleteOpts): Flow<LlmEvent> =
        streamHandler(messages, opts)
}

fun fakeResult(text: String, isError: Boolean = false, apiErrorStatus: Int? = null) =
    CompleteResult(
        text = text,
        backend = "fake",
        signals = UsageSignals(outputTokens = 1),
        isError = isError,
        apiErrorStatus = apiErrorStatus,
    )

fun sig() = UsageSignals(outputTokens = 1)
```

- [ ] **Step 3: 기존 테스트가 여전히 통과하는지 확인 (회귀 없음)**

Run: `cd backend && ./gradlew test --tests "omabang.engine.orchestrate.*"`
Expected: PASS (Step 1과 동일 — `FakeLlmPort { ... }` trailing-lambda 호출이 여전히 `handler`로 바인딩).

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/kotlin/omabang/engine/orchestrate/FakeLlmPort.kt
git commit -m "test(web): FakeLlmPort에 stream() 람다 지원 추가 (Phase 2 통합 테스트용)"
```

---

### Task 4: StreamController + fake 통합 테스트 (V2)

**Files:**
- Create: `backend/src/main/kotlin/omabang/web/StreamController.kt`
- Test: `backend/src/test/kotlin/omabang/web/StreamControllerTest.kt`

**Interfaces:**
- Consumes: `omabang.engine.LlmPort`, `Message`, `Role`, `SseMapping.toSse` (Task 2), `FakeLlmPort(streamHandler=...)` (Task 3)
- Produces: `StreamController` — `GET /api/stream?prompt=...` → `Flow<ServerSentEvent<String>>` (`produces = text/event-stream`).

- [ ] **Step 1: 실패하는 통합 테스트 작성**

JDK 내장 HttpClient로 SSE 본문을 받아 검증(webflux 의존성 없이). fake Bean은 `@Primary`로 실제 어댑터 대신 주입.

```kotlin
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
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `cd backend && ./gradlew test --tests "omabang.web.StreamControllerTest"`
Expected: FAIL — `/api/stream` 라우트 없음(404) 또는 `StreamController` 미해결.

- [ ] **Step 3: StreamController 구현**

```kotlin
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
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `cd backend && ./gradlew test --tests "omabang.web.StreamControllerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/omabang/web/StreamController.kt backend/src/test/kotlin/omabang/web/StreamControllerTest.kt
git commit -m "feat(web): GET /api/stream SSE 엔드포인트 + fake 통합 테스트 (V2)"
```

---

### Task 5: 실제 claude 통합 테스트 (V3)

**Files:**
- Test: `backend/src/test/kotlin/omabang/web/StreamControllerIntegrationTest.kt`

**Interfaces:**
- Consumes: 실제 `OmabangApplication`(fake 없이 — 진짜 `ClaudeCliAdapter` Bean), `GET /api/stream` (Task 4)
- Produces: 없음(검증 전용).

- [ ] **Step 1: 실제 claude 통합 테스트 작성**

```kotlin
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
        ).build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())

        assertEquals(200, resp.statusCode())
        val body = resp.body()
        assertTrue(body.contains("event:delta"), "delta 1개 이상")
        assertTrue(body.contains("event:done"), "done 존재")
    }
}
```

- [ ] **Step 2: 통합 테스트 실행 (실제 claude — 구독 슬롯 소모)**

Run: `cd backend && ./gradlew test --tests "omabang.web.StreamControllerIntegrationTest" -Pintegration`
Expected: PASS (실제 claude 응답에서 delta + done 수신). 기본 `./gradlew test`에서는 `@Tag("integration")`으로 제외됨.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/kotlin/omabang/web/StreamControllerIntegrationTest.kt
git commit -m "test(web): 실제 claude SSE 스트리밍 통합 테스트 (V3, @Tag integration)"
```

---

### Task 6: 최소 프론트 index.html + 수동 검증 (V4)

**Files:**
- Create: `backend/src/main/resources/static/index.html`

**Interfaces:**
- Consumes: `GET /api/stream?prompt=` (Task 4), SSE event `delta`/`done`/`error`.
- Produces: 없음(정적 자원).

- [ ] **Step 1: index.html 작성**

```html
<!DOCTYPE html>
<html lang="ko">
<head>
  <meta charset="UTF-8">
  <title>oh-my-assistant — 스트리밍</title>
  <style>
    body { font-family: sans-serif; max-width: 720px; margin: 40px auto; padding: 0 16px; }
    #out { white-space: pre-wrap; border: 1px solid #ccc; border-radius: 8px; padding: 16px; min-height: 120px; margin-top: 16px; }
    #meta { color: #888; font-size: 0.85em; margin-top: 8px; }
    input { width: 100%; padding: 8px; font-size: 1em; box-sizing: border-box; }
  </style>
</head>
<body>
  <h1>스트리밍 첫 뷰</h1>
  <input id="q" placeholder="프롬프트 입력 후 Enter" autofocus>
  <div id="out"></div>
  <div id="meta"></div>
  <script>
    const q = document.getElementById('q');
    const out = document.getElementById('out');
    const meta = document.getElementById('meta');
    let es = null;
    q.addEventListener('keydown', (e) => {
      if (e.key !== 'Enter' || !q.value.trim()) return;
      if (es) es.close();
      out.textContent = '';
      meta.textContent = '';
      es = new EventSource('/api/stream?prompt=' + encodeURIComponent(q.value.trim()));
      es.addEventListener('delta', (ev) => { out.textContent += ev.data; });
      es.addEventListener('done', (ev) => { meta.textContent = 'done: ' + ev.data; es.close(); });
      es.addEventListener('error', (ev) => { meta.textContent = 'error: ' + (ev.data || '(연결 종료)'); es.close(); });
    });
  </script>
</body>
</html>
```

- [ ] **Step 2: 앱 기동 후 브라우저 수동 검증 (V4)**

Run: `cd backend && ./gradlew bootRun` → 브라우저에서 `http://localhost:8080/` 열기.
검증:
1. 프롬프트 입력 후 Enter → **토큰이 점진적으로** 출력 영역에 나타난다(한 번에 와르르가 아니라 흘러야 함 — R2 점진 방출 확인).
2. 완료 시 `done:` usage 표시.
3. 응답 중 브라우저 탭을 닫고 → 터미널에서 `ps aux | grep claude`로 claude 프로세스가 남지 않는지 확인(G3 취소 안전 상속).

만약 1번에서 토큰이 점진적으로 안 흐르고 끝나서 한 번에 나오면 → R2 실패. **멈추고 사용자에게 보고**(MVC Flow SSE 버퍼링 문제 — WebFlux 재론 필요).

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/static/index.html
git commit -m "feat(web): 최소 스트리밍 프론트 index.html (V4 수동 검증)"
```

---

## 완료 기준

- V1: `SseMappingTest` 통과 (Task 2)
- V2: `StreamControllerTest` 통과 (Task 4)
- V3: `StreamControllerIntegrationTest` 통과(`-Pintegration`) (Task 5)
- V4: 브라우저 점진 토큰 표시 + 취소 시 프로세스 종료 (Task 6)
- 전체 회귀: `cd backend && ./gradlew test` → Phase 0/1 기존 테스트 + 신규 단위/통합(integration 제외) 전부 통과.
