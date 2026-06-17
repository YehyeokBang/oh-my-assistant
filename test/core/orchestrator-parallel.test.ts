import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createOrchestrator } from '../../src/core/orchestrator.ts';
import type { LLMPort } from '../../src/ports/llm.ts';
import { createSqliteMemory } from '../../src/adapters/memory/sqlite.ts';

// 분해 호출엔 작업 JSON을, 머지 호출엔 종합문을 돌려준다.
function scriptedLLM(): LLMPort {
  let calls = 0;
  return {
    async complete(messages) {
      calls++;
      const last = messages[messages.length - 1]?.content ?? '';
      if (last.includes('작업으로 분해')) {
        return { text: '[{"role":"카카오 조사","prompt":"a"},{"role":"라인 조사","prompt":"b"}]', backend: 'fake', signals: {} };
      }
      return { text: '종합 결과', backend: 'fake', signals: {} };
    },
    async spawnWorkers(tasks) {
      return tasks.map((t) => ({ task: t, status: 'done' as const, text: `${t.role} 결과` }));
    },
  };
}

test('병렬: 헤더+워커별 메시지+종합을 같은 스레드에 게시', async () => {
  const memory = createSqliteMemory(':memory:');
  const posts: string[] = [];
  const chat: any = {
    replyInThread: async (_c: string, _t: string, text: string) => { posts.push(text); },
    sendTyping: async () => {},
    addReaction: async () => {},
    removeReaction: async () => {},
  };
  const orch = createOrchestrator({ llm: scriptedLLM(), memory, chat });
  await orch.handle({ sessionId: 't1', chatId: 'C1', userId: 'U1', text: '카카오 라인 각각 조사해줘', ts: '1' });

  assert.match(posts[0], /2건/);                       // 헤더
  assert.ok(posts.some((p) => /카카오 조사 결과/.test(p)));
  assert.ok(posts.some((p) => /라인 조사 결과/.test(p)));
  assert.match(posts[posts.length - 1], /종합 결과/);   // 마지막은 종합
});
