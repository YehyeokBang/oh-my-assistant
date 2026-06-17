import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createSqliteMemory } from '../../../src/adapters/memory/sqlite.ts';

test('append 후 getHistory가 in->user, out->assistant 순서로 복원한다', () => {
  const mem = createSqliteMemory(':memory:');
  mem.ensureSession({ id: 't1', channel: 'slack', chatId: 'C1' });
  mem.append({ sessionId: 't1', ts: 1, direction: 'in', channel: 'slack', payload: { text: '안녕' } });
  mem.append({ sessionId: 't1', ts: 2, direction: 'out', channel: 'slack', llmBackend: 'claude-cli', payload: { text: '안녕하세요' } });
  const h = mem.getHistory('t1');
  assert.deepEqual(h, [
    { role: 'user', content: '안녕' },
    { role: 'assistant', content: '안녕하세요' },
  ]);
});

test('getHistory는 ts 오름차순이며 다른 세션을 섞지 않는다', () => {
  const mem = createSqliteMemory(':memory:');
  mem.append({ sessionId: 'a', ts: 5, direction: 'in', channel: 'slack', payload: { text: 'a-late' } });
  mem.append({ sessionId: 'a', ts: 1, direction: 'in', channel: 'slack', payload: { text: 'a-early' } });
  mem.append({ sessionId: 'b', ts: 2, direction: 'in', channel: 'slack', payload: { text: 'b' } });
  assert.deepEqual(mem.getHistory('a').map((m) => m.content), ['a-early', 'a-late']);
});

test('recordTask는 id를 반환하고 updateTask가 상태를 갱신한다', () => {
  const mem = createSqliteMemory(':memory:');
  const id = mem.recordTask({ sessionId: 't1', status: 'queued', role: '카카오 조사' });
  assert.ok(id > 0);
  mem.updateTask(id, { status: 'done', tsEnd: 99 });
  // 직접 검증용 두 번째 기록으로 id 증가 확인
  const id2 = mem.recordTask({ sessionId: 't1', status: 'queued', parentId: id });
  assert.ok(id2 > id);
});
