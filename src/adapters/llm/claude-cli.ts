import { spawn } from 'node:child_process';
import type {
  LLMPort, Message, CompleteOpts, CompleteResult, UsageSignals,
  WorkerTask, WorkerResult, SpawnOpts,
} from '../../ports/llm.ts';

export function messagesToPrompt(messages: Message[]): string {
  const label = (r: string) => (r === 'user' ? '사용자' : r === 'assistant' ? '비서' : '시스템');
  const history = messages.slice(0, -1);
  const last = messages[messages.length - 1];
  const head = history.length
    ? `아래는 이전 대화다.\n${history.map((m) => `${label(m.role)}: ${m.content}`).join('\n')}\n\n이제 질문:\n`
    : '';
  return `${head}${last ? last.content : ''}`;
}

export function parseStreamJson(stdout: string): CompleteResult {
  const lines = stdout.trim().split('\n').filter(Boolean);
  let text = '';
  let isError = false;
  let apiErrorStatus: number | undefined;
  const signals: UsageSignals = {};
  const assistantParts: string[] = [];
  for (const line of lines) {
    let o: unknown;
    try { o = JSON.parse(line); } catch { continue; }
    const obj = o as Record<string, unknown>;
    switch (obj['type']) {                              // 모르는 type은 default로 skip
      case 'assistant': {
        const msg = obj['message'] as Record<string, unknown> | undefined;
        const content = (msg?.['content'] ?? []) as Array<Record<string, unknown>>;
        for (const blk of content) {
          if (blk['type'] === 'text' && typeof blk['text'] === 'string') {
            assistantParts.push(blk['text']);
          }
        }
        break;
      }
      case 'rate_limit_event': {
        const rl = (obj['rate_limit_info'] ?? {}) as Record<string, unknown>;
        signals.rateLimit = {
          status: rl['status'] as string | undefined,
          rateLimitType: rl['rateLimitType'] as string | undefined,
          resetsAt: rl['resetsAt'] as number | undefined,
        };
        break;
      }
      case 'result': {
        if (typeof obj['result'] === 'string') text = obj['result'];
        if (obj['is_error'] || (obj['subtype'] && obj['subtype'] !== 'success')) isError = true;
        if (typeof obj['api_error_status'] === 'number') apiErrorStatus = obj['api_error_status'];
        if (typeof obj['total_cost_usd'] === 'number') signals.costUsd = obj['total_cost_usd'];
        const u = (obj['usage'] ?? {}) as Record<string, unknown>;
        signals.inputTokens = u['input_tokens'] as number | undefined;
        signals.outputTokens = u['output_tokens'] as number | undefined;
        signals.cacheCreationInputTokens = u['cache_creation_input_tokens'] as number | undefined;
        signals.cacheReadInputTokens = u['cache_read_input_tokens'] as number | undefined;
        signals.raw = u;
        break;
      }
      default:
        break;
    }
  }
  if (!text) text = assistantParts.join('');
  return { text, backend: 'claude-cli', signals, isError, apiErrorStatus };
}

export function buildWorkerPrompt(task: { role: string; prompt: string; context?: string }): string {
  const ctx = task.context ? `\n\n참고 컨텍스트:\n${task.context}` : '';
  return `너는 "${task.role}" 전문가다. 아래 작업만 독립적으로 수행하고 결과를 간결히 보고하라.${ctx}\n\n작업:\n${task.prompt}`;
}

async function runWithLimit<T>(items: T[], limit: number, fn: (item: T, index: number) => Promise<unknown>) {
  const results = new Array(items.length);
  let next = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) {
      const i = next++;
      results[i] = await fn(items[i], i);
    }
  });
  await Promise.all(workers);
  return results;
}

export interface ClaudeCliConfig {
  defaultModel?: string;
  allowedTools: string[];
}

type Runner = (args: string[], input: string) => Promise<string>;

const defaultRunner: Runner = (args, input) =>
  new Promise((resolve, reject) => {
    const child = spawn('claude', args, { stdio: ['pipe', 'pipe', 'pipe'] });
    let out = '', err = '';
    child.stdout.on('data', (d: Buffer) => (out += d));
    child.stderr.on('data', (d: Buffer) => (err += d));
    child.on('error', reject);
    child.on('close', (code: number | null) => (code === 0 ? resolve(out) : reject(new Error(`claude exit ${code}: ${err}`))));
    child.stdin.write(input);
    child.stdin.end();
  });

export function createClaudeCliLLM(cfg: ClaudeCliConfig, runner: Runner = defaultRunner): LLMPort {
  const buildArgs = (model: string | undefined, tools: string[], systemPrompt?: string): string[] => {
    const args = ['-p', '--output-format', 'stream-json', '--verbose'];
    if (model) args.push('--model', model);
    if (tools.length) args.push('--allowedTools', tools.join(','));
    if (systemPrompt) args.push('--append-system-prompt', systemPrompt);
    return args;
  };

  return {
    async complete(messages: Message[], opts: CompleteOpts = {}) {
      const args = buildArgs(opts.model ?? cfg.defaultModel, opts.allowedTools ?? cfg.allowedTools, opts.systemPrompt);
      const stdout = await runner(args, messagesToPrompt(messages));
      return parseStreamJson(stdout);
    },
    async spawnWorkers(tasks: WorkerTask[], opts: SpawnOpts = {}): Promise<WorkerResult[]> {
      const concurrency = opts.concurrency ?? 3;
      const timeoutMs = opts.timeoutMs ?? 180000;
      const tools = opts.allowedTools ?? cfg.allowedTools;
      const model = opts.model ?? cfg.defaultModel;

      const runOne = async (task: WorkerTask): Promise<WorkerResult> => {
        const args = buildArgs(model, tools);
        const prompt = buildWorkerPrompt(task);   // 히스토리 없음 — 역할+프롬프트+컨텍스트만
        let timer: ReturnType<typeof setTimeout>;
        const timeout = new Promise<never>((_, reject) => {
          timer = setTimeout(() => reject(new Error(`타임아웃 ${timeoutMs}ms 초과`)), timeoutMs);
        });
        try {
          const stdout = await Promise.race([runner(args, prompt), timeout]);
          const parsed = parseStreamJson(stdout as string);
          return { task, status: 'done', text: parsed.text, signals: parsed.signals };
        } catch (e) {
          return { task, status: 'failed', error: e instanceof Error ? e.message : String(e) };
        } finally {
          clearTimeout(timer!);
        }
      };

      return (await runWithLimit(tasks, concurrency, runOne)) as WorkerResult[];
    },
  };
}
