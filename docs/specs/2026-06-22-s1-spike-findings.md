# S1 스파이크 실측 결과 (claude-cli stream-json)

- 상태: **확정** — 추측 아님, 실제 `claude -p` 호출 출력 분석
- 측정일: 2026-06-22
- 측정 환경: claude CLI 2.1.185, 구독 헤드리스, 모델 `claude-opus-4-8[1m]`
- 상위: `docs/specs/2026-06-21-headless-engine-design.md` §6 S1 / §3 / §4.1
- 원시 캡처: 세션 scratchpad `s1_partial_verbose.ndjson` 등 (커밋 안 함, 일회성)

> 이 문서가 엔진 스펙 §3 `ClaudeCliLine` 모양과 §4 `stream()` 설계의 근거다.

---

## 측정한 명령

```
echo "<프롬프트>" | claude -p --output-format stream-json --verbose --include-partial-messages
```

NDJSON(라인당 JSON 1개)로 stdout에 흐른다. "1~10 세기" 프롬프트 1회 = 25라인.

## (a) 입자: **진짜 토큰 델타** (블록 단위 아님) — 확정

`--include-partial-messages` 를 켜면 `type:"stream_event"` 라인이 흐르고, 이는 **Anthropic 원시 스트리밍 이벤트를 그대로 래핑**한다. 1회 호출 내 `stream_event` 하위 분포:

```
6 content_block_delta   ← 진짜 점진 델타
2 content_block_start
2 content_block_stop
1 message_start
1 message_delta         ← usage 동봉(중간)
1 message_stop
```

**실제 답변 텍스트 델타의 경로:**
`type=="stream_event"` → `event.type=="content_block_delta"` → `event.delta.type=="text_delta"` → **`event.delta.text`**

예:
```json
{"type":"stream_event","event":{"type":"content_block_delta","index":1,
  "delta":{"type":"text_delta","text":"1. 하나 — 외로운 하나\n2. 둘 — 다"}}, ...}
```

⚠️ **thinking 블록 주의.** 모델은 답변 전 thinking 블록을 먼저 흘린다 → `delta.type`이 `thinking_delta`(`.thinking`) / `signature_delta`(`.signature`)로도 온다(`index:0`). **이건 답변 텍스트가 아니다.** 엔진의 `LlmEvent.TextDelta`로는 **`text_delta`만** 매핑한다. thinking/signature 델타는 Phase 0에선 무시(Unknown).

## (b) 라인 JSON 스키마 — 6개 wire 타입 확정

| wire `type` | 핵심 필드 | 엔진 매핑 |
|---|---|---|
| `system` | `subtype`(init/status/hook_started/hook_response/thinking_tokens), `session_id`, `uuid` | **무시(Unknown)** — Node `parseStreamJson`엔 없던 신규. default-skip로 흡수(G4) |
| `stream_event` | `event`(원시 이벤트), `session_id`, `parent_tool_use_id`, `uuid`, (선택)`ttft_ms` | `content_block_delta`+`text_delta`만 `TextDelta`, 나머지 하위이벤트 무시 |
| `assistant` | `message.content[]` — `thinking`/`text` 블록 복수 | 완성 블록(중복). 스트리밍은 델타로 받으므로 Phase 0 파서에선 무시 가능 |
| `result` | `subtype`, `is_error`, `api_error_status`, `result`(전체 텍스트), `usage{...}`, `total_cost_usd`, `modelUsage` | `Done` 권위 소스. usage·cost·text 확정 |
| `rate_limit_event` | `rate_limit_info{status,resetsAt,rateLimitType,overageStatus,...}` | `RateLimit` 신호 |

**`result.usage` 실측 구조** (기존 Node `claude-cli.ts:53-58`와 일치 + 추가 필드):
```json
"usage":{"input_tokens":9296,"cache_creation_input_tokens":19253,
  "cache_read_input_tokens":0,"output_tokens":257,
  "output_tokens_details":{"thinking_tokens":91},
  "service_tier":"standard", ...},
"total_cost_usd":0.245435
```

**`rate_limit_event.rate_limit_info` 실측** (기존 Node `claude-cli.ts:40-45`와 일치 + 추가):
```json
{"status":"allowed","resetsAt":1782062400,"rateLimitType":"five_hour",
 "overageStatus":"rejected","overageDisabledReason":"group_zero_credit_limit",
 "isUsingOverage":false}
```
- `status:"allowed"` = 정상. S3에서 한도 임박/초과 시 값 변화를 추가 측정해야 폴백 트리거 임계 확정.

**`message_delta`(stream_event 내부)에도 usage가 실린다** — 단 `result` 라인이 권위 소스이므로 Phase 0은 `result`만 신뢰(YAGNI, 중복 제거).

## (c) `--verbose` 필수성 — **필수 확정**

- `--verbose` 없이 `stream-json`+`-p`: CLI가 거부하고 종료(exit 1):
  `Error: When using --print, --output-format=stream-json requires --verbose`
  → 코드가 증명 못 하던 부분(스펙 §4.1)을 실측으로 확정. **항상 붙인다.**
- `--include-partial-messages` 없이(verbose만): `stream_event` **0건**. `assistant`(완성 블록 통째) + `result`만. = 점진 전달 전혀 없음.
  → **진짜 토큰 스트리밍에는 `--include-partial-messages` 필수** (스펙 §4.1 추정 확정).

---

## 엔진 스펙 §3 확정 반영 (`ClaudeCliLine`)

placeholder였던 `StreamEvent(val delta: String)`를 실측 기반으로 확정:

```kotlin
sealed interface ClaudeCliLine {
    data class Assistant(val text: String) : ClaudeCliLine             // type=assistant: content[] text 블록 join
    data class StreamEvent(val textDelta: String) : ClaudeCliLine      // type=stream_event: content_block_delta+text_delta의 text만
    data class Result(val text: String, val signals: UsageSignals,
                      val isError: Boolean, val apiErrorStatus: Int?) : ClaudeCliLine  // type=result
    data class RateLimit(val signal: RateLimitSignal) : ClaudeCliLine   // type=rate_limit_event
    data object Unknown : ClaudeCliLine                                 // type=system + 미지 + stream_event의 비-text_delta 하위
}
```

- `system` 라인은 신규지만 **Unknown으로 흡수**(파서 안 죽음, G4). 별도 케이스 불필요(YAGNI).
- `StreamEvent`는 **text_delta만** 담는다. thinking/signature/block_start/stop/message_*는 파싱 단계에서 Unknown.

## stream() 설계 확정 (§4.2)

- `--include-partial-messages` 항상 켜서 `stream()` 가동(진짜 델타).
- NDJSON 라인별: `StreamEvent.textDelta` → `LlmEvent.TextDelta`, `Result` → `LlmEvent.Done`, `rate_limit`/`assistant`/`system`/unknown → emit 안 함(또는 신호만 누적).
- `result` 라인의 `usage`·`total_cost_usd`가 `Done`의 권위 소스.
