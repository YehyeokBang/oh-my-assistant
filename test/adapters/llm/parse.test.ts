import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseStreamJson, messagesToPrompt } from '../../../src/adapters/llm/claude-cli.ts';

const SAMPLE = [
  JSON.stringify({ type: 'system', subtype: 'init' }),
  JSON.stringify({ type: 'hook_event' }),                         // 모르는 type → skip
  JSON.stringify({ type: 'assistant', message: { content: [{ type: 'text', text: '부분' }] } }),
  JSON.stringify({ type: 'result', subtype: 'success', is_error: false, result: '최종 답변', total_cost_usd: 0.012, usage: { input_tokens: 12, output_tokens: 34, cache_read_input_tokens: 5 } }),
].join('\n');

test('parseStreamJson은 result 라인의 텍스트/usage/비용을 추출한다', () => {
  const r = parseStreamJson(SAMPLE);
  assert.equal(r.text, '최종 답변');
  assert.equal(r.backend, 'claude-cli');
  assert.equal(r.signals.inputTokens, 12);
  assert.equal(r.signals.outputTokens, 34);
  assert.equal(r.signals.cacheReadInputTokens, 5);
  assert.equal(r.signals.costUsd, 0.012);
});

test('result 라인이 없으면 assistant 텍스트를 이어붙인다', () => {
  const only = JSON.stringify({ type: 'assistant', message: { content: [{ type: 'text', text: '대체' }] } });
  assert.equal(parseStreamJson(only).text, '대체');
});

test('rate_limit_event 라인을 signals.rateLimit으로 수집한다', () => {
  const withRl = [
    JSON.stringify({ type: 'rate_limit_event', rate_limit_info: { status: 'rejected', rateLimitType: 'five_hour', resetsAt: 1750000000 } }),
    JSON.stringify({ type: 'result', subtype: 'success', is_error: false, result: 'ok', usage: {} }),
  ].join('\n');
  const r = parseStreamJson(withRl);
  assert.equal(r.signals.rateLimit?.status, 'rejected');
  assert.equal(r.signals.rateLimit?.rateLimitType, 'five_hour');
});

test('is_error result는 isError 플래그를 세운다', () => {
  const err = JSON.stringify({ type: 'result', subtype: 'error_during_execution', is_error: true, result: '한도 초과', api_error_status: 429, usage: {} });
  const r = parseStreamJson(err);
  assert.equal(r.isError, true);
  assert.equal(r.apiErrorStatus, 429);
});

test('messagesToPrompt는 역할 라벨을 붙여 직렬화한다', () => {
  const p = messagesToPrompt([
    { role: 'user', content: '안녕' },
    { role: 'assistant', content: '네' },
    { role: 'user', content: '이름?' },
  ]);
  assert.match(p, /사용자: 안녕/);
  assert.match(p, /비서: 네/);
  assert.ok(p.trim().endsWith('이름?'));
});
