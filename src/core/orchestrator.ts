import type { LLMPort, Message } from '../ports/llm.ts';
import type { MemoryPort } from '../ports/memory.ts';
import type { ChatPort, IncomingMessage } from '../ports/chat.ts';

export interface OrchestratorDeps {
  llm: LLMPort;
  memory: MemoryPort;
  chat: ChatPort;
  persona?: string;       // 시스템 프롬프트
  orchestratorModel?: string;
}

export function createOrchestrator(deps: OrchestratorDeps) {
  const { llm, memory, chat } = deps;

  async function handle(msg: IncomingMessage): Promise<void> {
    memory.ensureSession({ id: msg.sessionId, channel: 'slack', chatId: msg.chatId });
    memory.append({ sessionId: msg.sessionId, ts: Date.now(), direction: 'in', channel: 'slack', payload: { text: msg.text } });

    const history: Message[] = memory.getHistory(msg.sessionId);
    const result = await llm.complete(history, { systemPrompt: deps.persona, model: deps.orchestratorModel });

    await chat.replyInThread(msg.chatId, msg.sessionId, result.text);
    memory.append({
      sessionId: msg.sessionId, ts: Date.now(), direction: 'out', channel: 'slack',
      llmBackend: result.backend, payload: { text: result.text, signals: result.signals },
    });
  }

  return { handle };
}
