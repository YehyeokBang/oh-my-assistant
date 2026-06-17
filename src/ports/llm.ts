export type Role = 'user' | 'assistant' | 'system';

export interface Message {
  role: Role;
  content: string;
}

export interface RateLimitSignal {
  status?: string;        // rate_limit_event.rate_limit_info.status
  rateLimitType?: string; // 예: 'five_hour'
  resetsAt?: number;      // epoch (sec)
}

export interface UsageSignals {
  // M-1 스파이크 확정: 토큰/비용은 result.usage·result.total_cost_usd,
  // 한도는 별도 type:"rate_limit_event" 라인에서 수집(잔여 수치는 미노출).
  inputTokens?: number;
  outputTokens?: number;
  cacheCreationInputTokens?: number;
  cacheReadInputTokens?: number;
  costUsd?: number;             // result.total_cost_usd (구독 한도 소진율 대용 지표)
  rateLimit?: RateLimitSignal;
  raw?: unknown;
}

export interface CompleteOpts {
  model?: string;
  systemPrompt?: string;
  allowedTools?: string[];
}

export interface CompleteResult {
  text: string;
  backend: string;        // 'claude-cli' | 'anthropic-api'
  signals: UsageSignals;
  isError?: boolean;      // claude-cli result.is_error
  apiErrorStatus?: number; // claude-cli result.api_error_status (예: 429)
}

export interface WorkerTask {
  role: string;     // 주입할 역할 (예: '카카오 조사 전문가')
  prompt: string;
  context?: string; // 워커에 줄 최소 컨텍스트 (히스토리 아님)
}

export interface WorkerResult {
  task: WorkerTask;
  status: 'done' | 'failed';
  text?: string;
  error?: string;
  signals?: UsageSignals;
}

export interface SpawnOpts {
  concurrency?: number;
  timeoutMs?: number;
  model?: string;
  allowedTools?: string[];
}

export interface LLMPort {
  complete(messages: Message[], opts?: CompleteOpts): Promise<CompleteResult>;
  spawnWorkers(tasks: WorkerTask[], opts?: SpawnOpts): Promise<WorkerResult[]>;
}
