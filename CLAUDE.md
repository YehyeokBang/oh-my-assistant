# oh-my-assistant

개인용 Slack AI 비서. 단일 사용자(백엔드 개발자 본인)를 위한 자가호스팅 에이전트.
병렬화 가능한 작업(조사/분석/초안)을 일회성 서브에이전트에 위임하고 결과만 보고받는다.

## 문서

- 설계(승인 완료): `docs/2026-06-17-personal-ai-agent-design.md`
- 구현 계획: `docs/superpowers/plans/2026-06-17-oh-my-assistant.md`

설계는 이전 세션에서 확정·승인됐다. **재논의·재제안 금지.** 핵심 결정은 설계 문서 §0 참고.

## 확정된 핵심 결정 (변경 금지)

1. 병렬화 = 일회성 서브에이전트 + 역할 주입 (상주 전문가단 기각)
2. Slack 스레드 = 세션. 새 메시지 = 새 세션. 병렬 결과는 같은 스레드 안 여러 메시지
3. 대화 기억 원본 = SQLite. `claude -p`는 매번 무상태 호출 + 히스토리 재구성 (`--resume` 기각)
4. 히스토리는 오케스트레이터만 받고, 병렬 워커는 "작업 조각 + 역할 프롬프트"만 받는다

## 아키텍처

헥사고날(포트 & 어댑터). 모든 외부 의존성을 어댑터로 격리.

- **Chat Port** → Slack 어댑터 (`@slack/bolt`, Socket Mode)
- **LLM Port** → 1차 `claude-cli`, 폴백 `anthropic-api` (Vercel AI SDK)
- **Memory Port** → SQLite (`better-sqlite3`, WAL, JSONB)
- **Core/Orchestrator** → 세션 식별, 단발/병렬 판단, 분해, 머지/검증

## 기술 스택 / 환경

- Node 24 LTS (fnm) — `.node-version`에 `24`
- pnpm (corepack)
- TypeScript 네이티브 타입 스트리핑: `node app.ts` (빌드 단계 없음). `enum`/`namespace` 금지 → `as const`/유니온 사용
- `better-sqlite3` (WAL 모드)
- `@slack/bolt` (Socket Mode)
- 운영: systemd, 백업 cron → S3(ap-northeast-2)

## 개발 명령

```bash
node --watch src/main.ts   # 개발 (워치 모드)
node src/main.ts           # 실행
node --test                # 테스트 (node:test 내장 러너)
```

## 작업 방식

- TDD로 구현 (테스트 먼저). 설계 문서의 마일스톤 M-1 → M4 순서.
- **main에 바로 커밋**한다 (브랜치 PR 흐름 아님).
- 커밋·푸시는 반드시 **YehyeokBang** 개인 계정으로 (`gh auth switch --user YehyeokBang` 확인).
  git config: `Yehyeok Bang` / `qkddpgur318@gmail.com`.

## 커밋 메시지 포맷 (한글)

```
feat: 한글 작업 내용

- 본문 쓸 거 있으면 이렇게
- as is -> to be 이런식으로나.
```

## 보안 (협상 불가)

- `claude -p` 도구 권한은 `--allowedTools` 화이트리스트, 읽기 전용부터 시작
- `--dangerously-skip-permissions`는 격리 환경에서만
- Slack user ID 화이트리스트로 본인만 응답
- 시크릿은 환경변수/SSM. 코드·DB에 평문 금지
