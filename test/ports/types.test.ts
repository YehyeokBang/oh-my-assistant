import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { Message } from '../../src/ports/llm.ts';
import type { StoredMessage } from '../../src/ports/memory.ts';
import type { IncomingMessage } from '../../src/ports/chat.ts';

test('포트 타입이 타입 스트리핑으로 import 된다', () => {
  const m: Message = { role: 'user', content: 'hi' };
  const s: StoredMessage = { sessionId: 's', ts: 1, direction: 'in', channel: 'slack', payload: {} };
  const i: IncomingMessage = { sessionId: 's', chatId: 'c', userId: 'u', text: 't', ts: '1' };
  assert.equal(m.role, 'user');
  assert.equal(s.direction, 'in');
  assert.equal(i.chatId, 'c');
});
