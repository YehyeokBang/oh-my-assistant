export interface AppConfig {
  slack: { botToken: string; appToken: string; allowedUserIds: string[] };
  dbPath: string;
  llm: { backend: string; orchestratorModel?: string; workerModel?: string; allowedTools: string[] };
  worker: { concurrency: number; timeoutMs: number };
  anthropicApiKey?: string;
}

type Env = Record<string, string | undefined>;

function required(env: Env, key: string): string {
  const v = env[key];
  if (!v) throw new Error(`필수 환경변수 누락: ${key}`);
  return v;
}

function list(v: string | undefined): string[] {
  return (v ?? '').split(',').map((s) => s.trim()).filter(Boolean);
}

export function loadConfig(env: Env): AppConfig {
  return {
    slack: {
      botToken: required(env, 'SLACK_BOT_TOKEN'),
      appToken: required(env, 'SLACK_APP_TOKEN'),
      allowedUserIds: list(env.SLACK_ALLOWED_USER_IDS),
    },
    dbPath: env.DB_PATH ?? './data/agent.db',
    llm: {
      backend: env.LLM_BACKEND ?? 'claude-cli',
      orchestratorModel: env.ORCHESTRATOR_MODEL || undefined,
      workerModel: env.WORKER_MODEL || undefined,
      allowedTools: list(env.CLAUDE_ALLOWED_TOOLS),
    },
    worker: {
      concurrency: Number(env.WORKER_CONCURRENCY ?? '3'),
      timeoutMs: Number(env.WORKER_TIMEOUT_MS ?? '180000'),
    },
    anthropicApiKey: env.ANTHROPIC_API_KEY || undefined,
  };
}
