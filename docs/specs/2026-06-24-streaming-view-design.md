# Phase 2 — 스트리밍 첫 뷰 설계 스펙

- 상태: **확정(v1)** — 브레인스토밍(범위·스택·리스크) 반영
- 작성일: 2026-06-24
- 작성자: 방예혁 + Claude(브레인스토밍)
- 상위 문서: `docs/specs/2026-06-21-ai-workflow-lab-vision.md` (비전 v2) — 로드맵 **Phase 2**의 단독 설계
- 선행: `docs/specs/2026-06-21-headless-engine-design.md`(Phase 0, `LlmPort`+`stream()` 확정) + `c8fcffe` 구현 / Phase 1 병렬(`6de50fd`)

> **범위:** Phase 0 `LlmPort.stream()`(이미 완성) 위에 **웹 계층(Spring Boot 첫 등장)**을 얹어,
> 단발 응답 토큰을 **SSE로 브라우저에 실시간 표시**하는 **가장 단순한 첫 뷰**를 만든다.
> 엔진 코어(claude 어댑터)는 안 건드린다 — `LlmPort`만 소비. 병렬 보드·멀티턴·영속·에이전트 레지스트리는 범위 밖.

---

## 0. 한 줄 요약

> 엔진의 `stream(): Flow<LlmEvent>`를 **SSE로 브라우저에 흘려** 단발 응답 토큰을 실시간으로 본다.
> "엔진 → 이벤트 → 뷰"를 가장 단순한 형태로 처음 실증한다. Spring Boot가 여기서 처음 등장한다.

## 1. 목표 & 성공 기준 (검증 가능)

| # | 성공 기준 | 검증 방법 |
|---|-----------|-----------|
| V1 | `LlmEvent` 3종이 각각 올바른 SSE event 타입·데이터로 매핑된다 | 단위: `SseMapping`에 `TextDelta`/`Done`/`Error` 주입 → event 이름·data 검증 (순수 함수, claude 없음) |
| V2 | `GET /api/stream`이 delta들 + done을 **순서대로** SSE로 방출한다 | 통합: `WebTestClient` + `FakeLlmPort` Bean(델타 N개 후 Done emit) → 수신 이벤트 순서·내용 검증 |
| V3 | 실제 claude로 한 번 스트리밍된다 | 통합(`@Tag("integration")`): 짧은 프롬프트 → delta ≥1 + done, `text/event-stream` |
| V4 | 브라우저에서 토큰이 점진적으로 보이고, 탭을 닫으면 claude 프로세스가 죽는다 | 수동: 브라우저 확인 + 프로세스 관측(취소 안전 = Phase 0 G3 상속) |

비-목표(이 Phase 아님): 멀티턴 대화 누적, 병렬 워커 스트리밍(Phase 4 병렬 보드), 영속(Phase 3 SQLite), 에이전트 레지스트리, 인증/권한, 안정적 이벤트 API 키스톤(뷰 2개 전엔 추출 안 함 — 비전 §3 ⚠️).

## 2. 범위 결정 — 단발 한 방

비전 §4 Phase 2 = "가장 단순한 **첫 뷰**로 엔진→이벤트→뷰를 실증". 인터랙션을 최소로 좁힌다.

- **채택:** 입력창 1개 → 전송 → 토큰 실시간 스트리밍 표시. **멀티턴 없음, 영속 없음, 병렬 없음.**
- 워커는 `complete()`만 쓰므로(Phase 1), 이 뷰가 엔진의 `stream()` 경로를 처음으로 끝까지 소비하는 곳이다.
- **단발만인 이유:** 멀티턴은 대화 상태 관리(messages 누적)를 더하지만 "엔진→이벤트→뷰" 실증엔 불필요. 영속(Phase 3) 없이는 어차피 새로고침하면 사라지므로, 멀티턴의 가치도 반감. YAGNI.

## 3. 아키텍처 — 데이터 흐름 + 격리 단위

새 패키지 `omabang.web`. 엔진(`omabang.engine`)과 분리, `LlmPort`만 의존(엔진 코어 불변).

```
브라우저(EventSource) ──GET /api/stream?prompt=──▶ StreamController
                                                       │ llm.stream(messages, opts)
                                                       ▼
                          Flow<LlmEvent> ──SseMapping──▶ Flow<ServerSentEvent<String>>
   토큰 점진 렌더 ◀──── text/event-stream (delta·done·error) ◀──────────┘
```

- **`OmabangApplication`** — Spring Boot 부트스트랩 + `LlmPort` Bean(`ClaudeCliAdapter`) 등록. `bootRun`으로 기동.
- **`StreamController`** — `GET /api/stream`. `llm.stream()`을 collect해 `SseMapping`으로 변환한 `Flow<ServerSentEvent>` 반환(suspend 컨트롤러).
- **`SseMapping`** — `LlmEvent` → `ServerSentEvent` **순수 매핑 함수**. sealed `when`(Phase 0 패턴 연장). 결정적 단위 테스트의 핵심.
- **`resources/static/index.html`** — 입력창 + 바닐라 JS `EventSource`. delta를 DOM에 append.

각 단위는 "무엇을 하나/어떻게 쓰나/무엇에 의존하나"가 한눈에 답되고, 매핑을 순수 함수로 빼 컨트롤러(I/O)와 변환 로직(순수)을 가른다.

## 4. HTTP 계약 / SSE 매핑

**엔드포인트:** `GET /api/stream?prompt=<텍스트>` → `Content-Type: text/event-stream`
- 브라우저 `EventSource`가 **GET만 지원**하므로 GET + 쿼리 파라미터. (단발이라 프롬프트 1개로 충분.)

**`LlmEvent` → SSE event 매핑 (`SseMapping`):**

| LlmEvent | SSE event 이름 | data |
|----------|----------------|------|
| `TextDelta(text)` | `delta` | `text` 그대로 (`ServerSentEvent`가 개행 인코딩 처리) |
| `Done(result)` | `done` | usage 요약 JSON (`backend`, `inputTokens`, `outputTokens`, `costUsd`) |
| `Error(status, message)` | `error` | `{status, message}` JSON |

- 프론트는 `EventSource.addEventListener('delta'|'done'|'error')`로 분기. `done`/`error` 수신 시 `EventSource.close()`.

## 5. Spring 구성 / 빌드

- **`spring-boot` Gradle 플러그인** + **`kotlin("plugin.spring")`**(all-open: Spring 프록시용).
- **`spring-boot-starter-web`** (MVC) — 비전 §2 "WebFlux 불요, suspend 컨트롤러 + Flow로 충분".
- **`org.jetbrains.kotlinx:kotlinx-coroutines-reactor`** — Spring의 코루틴 `Flow` ↔ Reactive 어댑터(Flow를 SSE Publisher로).
- `jvmTarget` 25 유지(폴백 검증은 Phase 0 그대로). 기존 콘솔 진입점(`run`=Phase 0 `Main`, `runOrchestrator`=Phase 1)은 **JavaExec 태스크로 보존**. 웹은 `bootRun`으로 별도 기동.

## 6. 프론트 (최소)

- `static/index.html` 한 장 + 인라인 바닐라 JS. **빌드툴·프레임워크 없음** — 학습 목표는 Kotlin이지 프론트가 아니다.
- 입력창 + 전송 버튼 → `new EventSource('/api/stream?prompt=' + encodeURIComponent(q))` → `delta`마다 출력 영역에 append, `done`에서 usage 표시 + close.
- **키스톤(안정적 이벤트 API)은 만들지 않는다**(비전 §3 ⚠️): 뷰가 1개뿐이므로 추측 추상화 금지. 컨트롤러가 `LlmPort`를 직접 호출한다. 뷰 2개째(병렬 보드)에서 공통 모양을 귀납 추출한다.

## 7. 테스트 전략

- **단위(claude 없음, 결정적):** `SseMapping` 순수 함수 — `LlmEvent` 3종 → event 이름·data 검증(V1).
- **통합(fake, claude 없음):** `WebTestClient` + `FakeLlmPort` Bean(delta N개 후 Done을 emit하는 `stream()` 구현 — 기존 `FakeLlmPort`는 `stream()`이 `TODO()`라 **이 Phase에서 `stream()` 지원을 추가**). `/api/stream` 수신 이벤트 순서·내용 검증(V2).
- **통합(`@Tag("integration")`, 실제 claude):** 짧은 프롬프트 → delta ≥1 + done(V3). 기본 제외(`-Pintegration`).
- **수동:** 브라우저 토큰 점진 표시 + 탭 닫기 시 프로세스 종료(V4, G3 상속).

## 8. 확정 결정 (DW1~DW5)

- **DW1 — Spring Boot `starter-web`(MVC) + suspend 컨트롤러 + `Flow` SSE.** `kotlin("plugin.spring")` all-open, `kotlinx-coroutines-reactor` 어댑터. (비전 §2 "WebFlux 불요" 준수.)
- **DW2 — `GET /api/stream?prompt=` → `text/event-stream`.** `EventSource`가 GET만 지원.
- **DW3 — `LlmEvent → ServerSentEvent` 매핑은 순수 함수(`SseMapping`)로 분리.** event 3종(delta/done/error). 컨트롤러 I/O와 변환 로직 분리 → 결정적 테스트.
- **DW4 — 최소 프론트(정적 HTML + 바닐라 JS), 키스톤 추출 안 함.** 컨트롤러가 `LlmPort` 직접 호출(뷰 1개, 비전 §3 ⚠️).
- **DW5 — 보안 read-only first.** `allowedTools` 기본 빈 값(콘솔 `Main`과 동일, conventions.md).

## 9. 리스크 (구현 전 검증 — 계획 1번 태스크 = 스파이크)

- **R1 (최대 미지수) — Spring Boot가 JDK 25 + Kotlin 2.3.0과 호환되는가.** 최신 Spring Boot 버전의 호환 매트릭스를 먼저 확인한다. 호환 버전이 없으면 **멈추고 사용자에게 보고**(JDK 폴백은 비전이 금지 — Kotlin 2.2.x/25→24 폴백 기각). 
- **R2 — Spring MVC에서 코루틴 `Flow` SSE가 버퍼링 없이 점진 방출되는가.** `coroutines-reactor` 어댑터가 토큰을 모아 한 번에 내보내지 않고 흘리는지 통합/수동으로 확인. 만약 MVC로 점진 방출이 안 되면 **WebFlux 재론 전 사용자 확인**(비전 결정 뒤집기).

## 10. 비-범위 / 후속

- 멀티턴 대화·영속(Phase 3 SQLite), 병렬 워커 스트리밍(Phase 4 병렬 보드), 에이전트 레지스트리(Phase 3), 인증/권한.
- **B 플래너**(`request → tasks` LLM 분해)는 여전히 미구현 — Phase 1 seam(`runParallel`의 `tasks`)에 후속으로 붙는다.
- 안정적 이벤트 API **키스톤**은 뷰 2개째 이후 귀납 추출(비전 §3 ⚠️).

## 11. 이번 Phase 산출물

- 이 설계 스펙
- 다음: 구현 계획(writing-plans) → TDD 구현(V1~V4). 계획 1번 태스크 = R1(Spring/JDK25 호환) 스파이크.
