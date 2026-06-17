import { test } from 'node:test';
import assert from 'node:assert/strict';
import { splitForSlack } from '../../../src/adapters/chat/slack.ts';

test('한도 이하 텍스트는 한 조각', () => {
  assert.deepEqual(splitForSlack('hello', 100), ['hello']);
});

test('한도 초과 시 한도 길이를 넘지 않게 분할', () => {
  const parts = splitForSlack('a'.repeat(250), 100);
  assert.equal(parts.length, 3);
  assert.ok(parts.every((p) => p.length <= 100));
  assert.equal(parts.join(''), 'a'.repeat(250));
});

test('빈 문자열은 빈 조각 하나로 보존', () => {
  assert.deepEqual(splitForSlack('', 100), ['']);
});
