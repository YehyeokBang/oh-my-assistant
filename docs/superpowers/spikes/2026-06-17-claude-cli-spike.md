# M-1 스파이크 findings — `claude -p`(CLI) 1차 LLM 백엔드 실측

- 작성일: 2026-06-17
- 범위: 로컬 Mac 실측 가능분 **(b)**, **(c)** 만 수행. **(a)** 인스턴스 병렬 자원 실측과 **(d)** ToS 확인은 deferred(아래 deferred 섹션).
- 목적: `parseStreamJson`(M1/Task7), `UsageSignals` 스키마(M2), 호출 래퍼(M4)에 넘길 **확정값**을 사실로 닫는다.

## 실측 환경

| 항목 | 값 |
| --- | --- |
| claude CLI | 2.1.179 (Claude Code) |
| node | v25.6.1 |
| OS | macOS 15.6.1 (Build 24G90) |
| 모델(실측 시) | `claude-opus-4-8[1m]` (contextWindow 1,000,000 / maxOutputTokens 64,000) |

> 주의: 실측 시 활성 모델은 Opus 4.8(1M)이었다. 실제 운영 백엔드 모델은 호출 시점/계정 설정에 따라 달라질 수 있다. usage/한도 신호 **구조**는 모델과 무관하게 동일하다고 본다.

## (b) 무상태 단발 + 히스토리 재구성

### 무상태 단발
- 호출: `claude -p "다음 한 단어로만 답해: 사과" --output-format stream-json --verbose --allowedTools ""`
- 결과: **정상 동작**(exit 0). 세션 ID는 호출마다 새로 발급되어 서로 다름(무상태 확인). 최종 답변 `result` 필드 = `"사과"`.

### `--verbose` 필요 여부 — **필수**
- `--verbose`를 빼고 `--output-format stream-json`만 주면 호출이 **실패(exit 1)**:
  - 에러: `Error: When using --print, --output-format=stream-json requires --verbose`
- 결론: `claude -p`(= `--print`) + `--output-format stream-json` 조합에서는 **`--verbose` 반드시 동반**해야 한다.

### 히스토리 재구성으로 맥락 유지 — **성립**
- 호출 프롬프트(이전 Q/A를 문자열에 직접 주입):
  ```
  아래는 이전 대화다.
  사용자: 내 이름은 방예혁
  비서: 알겠습니다.

  이제 질문: 내 이름이 뭐였지?
  ```
- 최종 답변(`result` 필드, 원문 인용):
  > 방예혁이라고 하셨습니다. 다음 세션에서도 기억하도록 저장해뒀습니다.
- "방예혁" 포함 확인됨. → **무상태(서버 세션 의존 X) + 프롬프트에 히스토리를 직접 실어 맥락 유지** 패턴 성립.

## (c) stream-json 구조 분석

### 라인 type 시퀀스(single.jsonl, 8줄 관측)
순서대로:
1. `system` (subtype `hook_started`) ×2 — SessionStart 훅
2. `system` (subtype `hook_response`) ×2
3. `system` (subtype `init`) — cwd, session_id, tools 목록, 모델 등 초기화
4. `assistant` — 모델 응답 메시지(증분/최종 텍스트가 `message.content[]`에 담김)
5. `rate_limit_event` — 레이트리밋 상태
6. `result` (subtype `success`) — **최종 결과 요약 라인(맨 마지막 1줄)**

> 주의(예상과 다른 점): 응답 외에도 **`system`/`rate_limit_event` 라인이 섞여 나온다.** 특히 이 환경은 SessionStart 훅(superpowers)이 걸려 있어 `system/hook_*` 라인과 함께 init 토큰이 부풀어 보인다(input_tokens 9843, cache_creation 19515). 파서는 **`type` 기준으로 라인을 분기**해야 하며, 알 수 없는 type은 무시(skip)하는 방식이 안전하다.

### 최종 답변 텍스트 추출 경로
- **권장(확정): `type === "result"` 라인의 `result` 필드(string).**
  - single: `result` = `"사과"`
  - history: `result` = `"방예혁이라고 하셨습니다. ..."`
  - 이 라인은 스트림 **맨 마지막 1줄**이며 전체 최종 텍스트가 평문 string으로 들어있다(content 블록 조립 불필요).
- 보조 경로: `type === "assistant"` 라인의 `message.content[].text`(`content[i].type === "text"`인 항목). 스트리밍 중간 표시용으로 쓸 수 있으나, 단발 최종 텍스트는 `result.result`가 단일 소스로 가장 견고.
- 에러 판별: `result` 라인의 `subtype`(`"success"` 등), `is_error`(boolean), `api_error_status`로 판단.

result 라인 샘플(축약, 민감정보 없음):
```json
{ "type": "result", "subtype": "success", "is_error": false,
  "result": "사과", "stop_reason": "end_turn", "num_turns": 1,
  "duration_ms": 7616, "total_cost_usd": 0.244465, "terminal_reason": "completed" }
```

### usage / 토큰 / 한도 신호 경로
**토큰 usage** — `result` 라인의 `usage` 객체:
- `usage.input_tokens`
- `usage.output_tokens`
- `usage.cache_creation_input_tokens`
- `usage.cache_read_input_tokens`
- (추가) `result.total_cost_usd`(비용), `result.modelUsage["<model>"].contextWindow` / `.maxOutputTokens`(컨텍스트 한도/최대출력)

usage 샘플:
```json
"usage": { "input_tokens": 9843, "output_tokens": 4,
  "cache_creation_input_tokens": 19515, "cache_read_input_tokens": 0 }
```

**레이트리밋/잔량 신호** — 별도 `type === "rate_limit_event"` 라인의 `rate_limit_info`:
```json
{ "type": "rate_limit_event",
  "rate_limit_info": {
    "status": "allowed",
    "resetsAt": 1781716800,
    "rateLimitType": "five_hour",
    "overageStatus": "rejected",
    "overageDisabledReason": "group_zero_credit_limit",
    "isUsingOverage": false } }
```
- 즉 "한도/잔량" 신호는 **`result` 라인이 아니라 별도 `rate_limit_event` 라인**에 있다. 잔여 토큰 수치(remaining)는 노출되지 않고, `status`(allowed/limited 등) + `resetsAt`(epoch sec) + `rateLimitType`(예 `five_hour`) 형태로만 제공된다.

> 주의(예상과 다른 점): 처음 가정처럼 "result.usage에 한도/잔량이 같이 있다"가 **아니다.** 한도 신호는 `rate_limit_event` 라인에서 별도로 수집해야 한다.

---

## M1/M2/M4에 넘기는 확정값

### parseStreamJson(M1/Task7) — 최종 텍스트 추출
- 입력 jsonl을 줄 단위 파싱, `type`로 분기, **모르는 type은 skip.**
- 최종 텍스트 = **`type:"result"` 라인의 `result` 필드(string).**
- 성공/에러 판별 = 같은 라인의 `subtype`, `is_error`, `api_error_status`.
- (옵션) 스트리밍 중간 표시가 필요하면 `type:"assistant"`의 `message.content[].text`(text 블록) 사용.

### UsageSignals 스키마(M2) — 채울 필드 경로
입력 토큰계:
- `inputTokens`   ← `result.usage.input_tokens`
- `outputTokens`  ← `result.usage.output_tokens`
- `cacheCreationInputTokens` ← `result.usage.cache_creation_input_tokens`
- `cacheReadInputTokens`     ← `result.usage.cache_read_input_tokens`
- `costUsd`(옵션) ← `result.total_cost_usd`
- `contextWindow`/`maxOutputTokens`(옵션) ← `result.modelUsage["<model>"].contextWindow` / `.maxOutputTokens`

한도 신호(별도 라인에서 수집):
- `rateLimitStatus`   ← `rate_limit_event.rate_limit_info.status`
- `rateLimitResetsAt` ← `rate_limit_event.rate_limit_info.resetsAt`(epoch sec)
- `rateLimitType`     ← `rate_limit_event.rate_limit_info.rateLimitType`
- 잔여 토큰 수치(remaining/quota)는 **노출되지 않음** — UsageSignals에 두지 말 것(있다고 가정 금지).

### 호출 래퍼(M4) — claude -p 호출 규약
- `--output-format stream-json` 사용 시 **`--verbose` 필수**(없으면 exit 1로 실패).
- 도구 권한 프롬프트 회피 위해 `--allowedTools ""`(빈 화이트리스트).
- 무상태 단발 OK. 멀티턴 맥락은 **프롬프트 문자열에 이전 Q/A를 직접 직렬화**해서 전달(서버 세션 의존 X).
- 파서는 jsonl에 `system`/`rate_limit_event` 등 응답 외 라인이 **섞여 나오는 것을 전제**로 작성할 것.

---

## deferred 섹션

- **(a) 2vCPU/10GB 인스턴스 병렬 자원 실측**: deferred — 로컬 Mac은 대상 운영 인스턴스 환경이 아니라 자원 한계/병렬 동시성 수치가 무의미. 실제 배포 대상 인스턴스에서 별도 측정 필요.
- **(d) ToS 확인**: deferred — 약관/사용권 검토는 코드 실측 범위 밖이며 별도(법무/정책) 확인 트랙에서 처리.
