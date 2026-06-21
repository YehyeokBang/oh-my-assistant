# omabang-engine (Phase 0 헤드리스 엔진)

UI·HTTP·Spring 없는 **순수 Kotlin 라이브러리**. `LlmPort`(complete/stream) + claude-cli 스트리밍 어댑터를 콘솔/테스트로만 검증한다.
설계 근거: `docs/specs/2026-06-21-headless-engine-design.md`, S1 실측: `docs/specs/2026-06-22-s1-spike-findings.md`.

## 스택
- JDK 25 (Corretto LTS) · Kotlin 2.3.0 (jvmTarget 25) · Gradle 9.5.1 (wrapper)
- kotlinx-coroutines-core 1.11.0 · kotlinx-serialization-json 1.11.0(런타임 파싱) · kotlin-test(JUnit5)

## 쓰기
```bash
./gradlew test                  # 단위 테스트 (claude 호출 없음, 빠름)
./gradlew test -Pintegration    # 통합 테스트 포함 (실제 claude -p 호출, G1~G3)
./gradlew run --args="1부터 5까지 세어줘"   # 콘솔 한 턴 (G5). 인자 없으면 프롬프트 입력
```
> `run`/통합 테스트는 `claude`가 PATH에 있어야 한다(스펙 §6 주). 토큰 델타는 stdout, usage/error는 stderr.

## 구조
```
omabang.engine
├── LlmPort.kt            포트 + 도메인 타입 + LlmEvent(정규화 이벤트, D1)
└── claude/
    ├── ClaudeCliLine.kt      어댑터 내부 와이어 모델(sealed, S1 확정)
    ├── StreamJsonParser.kt   NDJSON 라인 → ClaudeCliLine (미지 라인 흡수, G4)
    ├── ProcessLineStreamer.kt 프로세스 stdout 스트리밍 + 취소→destroyForcibly(좀비 0, G3)
    └── ClaudeCliAdapter.kt   stream()/complete() — 라인→LlmEvent 정규화, D3 합성
```

## 비-범위 (후속 Phase)
웹/SSE, SQLite, 병렬 워커, Anthropic API 폴백 구현(현재 seam만), 안정적 이벤트 API(키스톤).
