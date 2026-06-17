import type { LLMPort, Message, WorkerResult } from '../ports/llm.ts';
import type { MemoryPort } from '../ports/memory.ts';
import type { ChatPort, IncomingMessage } from '../ports/chat.ts';
import { classifyIntent } from './intent.ts';
import { parseTasks, formatWorkerReport, formatSummaryHeader } from './format.ts';

export interface OrchestratorDeps {
  llm: LLMPort;
  memory: MemoryPort;
  chat: ChatPort;
  persona?: string;       // 시스템 프롬프트
  orchestratorModel?: string;
  workerModel?: string;
  workerConcurrency?: number;
  workerTimeoutMs?: number;
}

const ACK = 'eyes';            // 접수 신호 👀
const DONE = 'white_check_mark'; // 완료 신호 ✅

const DECOMPOSE_INSTRUCTION =
  '다음 요청을 독립적으로 병렬 수행 가능한 작업으로 분해하라. ' +
  'JSON 배열만 출력하라. 각 원소는 {"role": 역할이름, "prompt": 작업지시, "context"?: 최소컨텍스트}. 요청:\n';

const SUMMARY_INSTRUCTION =
  '아래 병렬 작업 결과들을 검증하라. 충돌/불일치가 있으면 지적하고, 없으면 종합하라. 결과:\n';

export function createOrchestrator(deps: OrchestratorDeps) {
  const { llm, memory, chat } = deps;

  async function handle(msg: IncomingMessage): Promise<void> {
    memory.ensureSession({ id: msg.sessionId, channel: 'slack', chatId: msg.chatId });
    memory.append({ sessionId: msg.sessionId, ts: Date.now(), direction: 'in', channel: 'slack', payload: { text: msg.text } });

    await chat.addReaction(msg.chatId, msg.ts, ACK).catch(() => {});  // 👀 접수 표시(해당 메시지에)

    try {
      const intent = classifyIntent(msg.text);

      if (!intent.parallel) {
        // 단발 경로 (기존 로직)
        const history: Message[] = memory.getHistory(msg.sessionId);
        const result = await llm.complete(history, { systemPrompt: deps.persona, model: deps.orchestratorModel });
        await chat.replyInThread(msg.chatId, msg.sessionId, result.text);
        memory.append({
          sessionId: msg.sessionId, ts: Date.now(), direction: 'out', channel: 'slack',
          llmBackend: result.backend, payload: { text: result.text, signals: result.signals },
        });
      } else {
        // 병렬 경로
        const parentId = memory.recordTask({ sessionId: msg.sessionId, status: 'running', role: 'orchestrator', tsStart: Date.now() });
        const decomp = await llm.complete(
          [{ role: 'user', content: DECOMPOSE_INSTRUCTION + msg.text }],
          { model: deps.orchestratorModel },
        );
        const tasks = parseTasks(decomp.text);

        if (tasks.length === 0) {
          // 분해 실패 → 단발로 폴백
          const history: Message[] = memory.getHistory(msg.sessionId);
          const result = await llm.complete(history, { systemPrompt: deps.persona, model: deps.orchestratorModel });
          await chat.replyInThread(msg.chatId, msg.sessionId, result.text);
          memory.append({
            sessionId: msg.sessionId, ts: Date.now(), direction: 'out', channel: 'slack',
            llmBackend: result.backend, payload: { text: result.text },
          });
          memory.updateTask(parentId, { status: 'done', tsEnd: Date.now() });
        } else {
          await chat.replyInThread(msg.chatId, msg.sessionId, formatSummaryHeader(tasks.length));
          for (const t of tasks) {
            memory.recordTask({ sessionId: msg.sessionId, parentId, status: 'queued', role: t.role });
          }

          const results: WorkerResult[] = await llm.spawnWorkers(tasks, {
            concurrency: deps.workerConcurrency,
            timeoutMs: deps.workerTimeoutMs,
            model: deps.workerModel,
          });
          for (const r of results) {
            await chat.replyInThread(msg.chatId, msg.sessionId, formatWorkerReport(r));
          }

          const done = results.filter((r) => r.status === 'done');
          const merged = await llm.complete(
            [{ role: 'user', content: SUMMARY_INSTRUCTION + done.map((r) => `## ${r.task.role}\n${r.text}`).join('\n\n') }],
            { model: deps.orchestratorModel },
          );
          await chat.replyInThread(msg.chatId, msg.sessionId, `✅ ${merged.text}`);

          memory.updateTask(parentId, { status: 'done', tsEnd: Date.now(), payload: { results } });
          memory.append({
            sessionId: msg.sessionId, ts: Date.now(), direction: 'out', channel: 'slack',
            llmBackend: merged.backend, payload: { text: merged.text, parallel: true },
          });
        }
      }
    } finally {
      // 👀 → ✅ 전환(완료 표시) — 모든 경로(단발/병렬/폴백)에서 실행
      await chat.removeReaction(msg.chatId, msg.ts, ACK).catch(() => {});
      await chat.addReaction(msg.chatId, msg.ts, DONE).catch(() => {});
    }
  }

  return { handle };
}
