import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createOrchestrator } from '../../src/core/orchestrator.ts';
import type { LLMPort } from '../../src/ports/llm.ts';
import { createSqliteMemory } from '../../src/adapters/memory/sqlite.ts';

function fakeLLM(answer: string): LLMPort {
  return {
    async complete(messages) {
      return { text: `${answer}|hist=${messages.length}`, backend: 'fake', signals: {} };
    },
    async spawnWorkers() { return []; },
  };
}

test('단발: 히스토리 적재→complete→스레드 회신→in/out 저장', async () => {
  const memory = createSqliteMemory(':memory:');
  const sent: { chatId: string; threadTs: string; text: string }[] = [];
  const chat: any = { replyInThread: async (chatId: string, threadTs: string, text: string) => { sent.push({ chatId, threadTs, text }); }, sendTyping: async () => {} };
  const orch = createOrchestrator({ llm: fakeLLM('답'), memory, chat });

  await orch.handle({ sessionId: 't1', chatId: 'C1', userId: 'U1', text: '질문1', ts: '1' });
  assert.equal(sent.length, 1);
  assert.match(sent[0].text, /^답\|hist=1/);            // 첫 호출은 히스토리 1개(현재 메시지)
  assert.equal(sent[0].threadTs, 't1');

  await orch.handle({ sessionId: 't1', chatId: 'C1', userId: 'U1', text: '질문2', ts: '2' });
  assert.match(sent[1].text, /hist=3/);                 // user1,assistant1,user2
});
