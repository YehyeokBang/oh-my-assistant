# 사전 준비 가이드 (수동)

코드와 병렬로 미리 해둘 것: ① Slack 앱/봇 생성 ② 인스턴스 런타임/도구 설치.
값은 설계 문서 §2.1, §10 기준. 토큰·자격증명은 절대 커밋하지 말 것(.env는 .gitignore에 있음).

---

## 1. Slack 앱/봇 생성

Socket Mode를 쓰므로 공개 HTTP 엔드포인트·도메인이 필요 없다.

1. **앱 생성**: https://api.slack.com/apps → **Create New App → From scratch**. 이름(예: `oh-my-assistant`) + 워크스페이스 선택.

2. **Socket Mode 켜기**: 좌측 **Settings → Socket Mode → Enable Socket Mode**.
   - 이때 App-Level Token 생성 창이 뜸 → 토큰 이름 아무거나, 스코프 **`connections:write`** 부여 → 생성.
   - 생성된 **`xapp-...`** 토큰을 복사 → `.env`의 `SLACK_APP_TOKEN`.

3. **Bot 권한(OAuth & Permissions → Scopes → Bot Token Scopes)**:
   - `app_mentions:read` — 멘션 수신
   - `chat:write` — 메시지/스레드 전송
   - `im:history` — DM 내용 읽기
   - `im:read` — DM 채널 목록
   - `im:write` — DM 보내기
   - (선택) `channels:history` — 공개 채널에서도 쓸 거면

4. **Event Subscriptions 켜기**: 좌측 **Event Subscriptions → Enable Events** (Socket Mode라 Request URL 입력란 없음).
   **Subscribe to bot events** 에 추가:
   - `message.im` — DM 메시지
   - `app_mention` — 채널 멘션

5. **DM 허용**: 좌측 **App Home → Show Tabs → Messages Tab** 켜고, 그 아래
   **"Allow users to send Slash commands and messages from the messages tab"** 체크.

6. **워크스페이스에 설치**: **OAuth & Permissions → Install to Workspace** → 승인.
   - 발급된 **Bot User OAuth Token `xoxb-...`** 복사 → `.env`의 `SLACK_BOT_TOKEN`.

7. **본인 Slack user ID 확보(화이트리스트용)**:
   - Slack 클라이언트에서 본인 프로필 → **⋯ → Copy member ID** (예: `U01ABCDEF`).
   - `.env`의 `SLACK_ALLOWED_USER_IDS`에 넣기(여러 명이면 쉼표 구분).

8. **검증**: 봇을 DM으로 찾아 메시지 한 번 보내보고, 앱이 워크스페이스에 보이는지 확인.
   (실제 왕복은 M0 Task 5 실행 시 확인.)

> 토큰 3종 요약 → `.env`:
> ```
> SLACK_BOT_TOKEN=xoxb-...
> SLACK_APP_TOKEN=xapp-...
> SLACK_ALLOWED_USER_IDS=U01ABCDEF
> ```

---

## 2. 인스턴스 런타임/도구 설치 (Linux, 2 vCPU / ~10GB)

대상 OS는 Fedora/RHEL/Amazon Linux 2023 계열 가정(`dnf`). 다른 배포판이면 패키지명만 치환.

### 2.1 시스템 패키지 (better-sqlite3 네이티브 빌드 + 백업 도구)

```bash
sudo dnf install -y gcc gcc-c++ make python3 git sqlite gzip tar curl unzip
# 또는 개발도구 묶음으로:
# sudo dnf groupinstall -y "Development Tools"
# sudo dnf install -y python3 sqlite gzip tar curl unzip git
```
- `gcc`/`gcc-c++`/`make`/`python3`: `better-sqlite3`가 네이티브 모듈이라 빌드(node-gyp)에 필요. (apt의 `build-essential` 대응)
- `sqlite`: 백업 스크립트의 `VACUUM INTO`용 CLI(`sqlite3` 명령 제공). dnf에선 패키지명이 `sqlite`.

### 2.2 Node — fnm + Node 24 LTS

```bash
# fnm 설치
curl -fsSL https://fnm.vercel.app/install | bash
# 셸 재시작하거나:
export PATH="$HOME/.local/share/fnm:$PATH" && eval "$(fnm env --use-on-cd)"

# 최신 LTS 라인 확인 후 24 설치 (2026-06 기준 Active LTS는 24)
fnm ls-remote --lts | tail -5     # 최신 LTS 라벨 확인
fnm install 24
fnm default 24
fnm use 24
node -v        # v24.x 확인
```
> 서버는 **LTS(24)** 권장. Node 25는 Current(비LTS)라 운영엔 부적합.
> 프로젝트 루트의 `.node-version`(=24) 덕에 디렉터리 진입 시 자동 전환됨.
> 타입 스트리핑(`node app.ts`)은 Node 24+에서 빌드 없이 동작.

### 2.3 pnpm (corepack)

```bash
corepack enable
corepack prepare pnpm@latest --activate
pnpm -v
```

### 2.4 claude CLI (claude-cli 어댑터 전제)

```bash
npm i -g @anthropic-ai/claude-code
claude --version
# 헤드리스 로그인: 구독 계정으로 1회 인증 (브라우저 코드 플로우)
claude   # 안내에 따라 로그인. 인증 토큰이 ~/.config 에 저장됨
```
> ⚠️ **M-1 스파이크의 ToS 항목과 직결**: 스크립트가 `claude -p`를 상시 구동하는 것이 구독 약관상 허용 경로인지 확인 후 본격 운영. (이 항목은 별도 확인 대상으로 남겨둠.)

### 2.5 AWS CLI v2 (백업용, M3)

```bash
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o awscliv2.zip
unzip awscliv2.zip && sudo ./aws/install
aws --version
# IAM 역할(권장) 또는 최소권한 자격증명: 대상 S3 prefix에 PutObject만
```

### 2.6 앱 배치 + systemd (M0/이후)

```bash
# 코드 배치 위치(설계 기준): /opt/ai-agent
sudo mkdir -p /opt/ai-agent && sudo chown "$USER" /opt/ai-agent
# git clone 또는 배포로 소스 배치 후:
cd /opt/ai-agent && pnpm install
cp .env.example .env   # 위에서 모은 토큰/설정 채우기

# systemd 유닛은 레포 deploy/ai-agent.service 참고.
# ExecStart의 node 절대경로는 `which node`(fnm) 결과로 교체.
which node    # 예: /home/<user>/.local/share/fnm/aliases/default/bin/node
```

---

## 지금 당장 직접 해두면 좋은 것 (병렬)

- [ ] Slack 앱 생성 + 토큰 3종 확보 (위 1번) — **M0 Task 5에서 바로 필요**
- [ ] 인스턴스에 시스템 패키지 + fnm/Node 24 + pnpm + claude CLI 설치 (2.1~2.4)
- [ ] claude CLI 구독 로그인 + (가능하면) ToS 확인
- [ ] S3 버킷/IAM 최소권한 준비 (M3에서 필요, 급하지 않음)
