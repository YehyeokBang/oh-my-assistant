import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseTasks, formatWorkerReport, formatSummaryHeader } from '../../src/core/format.ts';

test('parseTasks는 코드펜스로 감싼 JSON도 파싱', () => {
  const raw = '```json\n[{"role":"카카오 조사","prompt":"카카오 프리뷰 조사"}]\n```';
  const tasks = parseTasks(raw);
  assert.equal(tasks.length, 1);
  assert.equal(tasks[0].role, '카카오 조사');
});

test('parseTasks는 배열이 아니면 빈 배열', () => {
  assert.deepEqual(parseTasks('설명만 있고 JSON 없음'), []);
});

test('formatWorkerReport: 실패는 사유를 명시한다', () => {
  const s = formatWorkerReport({ task: { role: '라인 조사', prompt: 'x' }, status: 'failed', error: '타임아웃' });
  assert.match(s, /라인 조사/);
  assert.match(s, /실패/);
  assert.match(s, /타임아웃/);
});

test('formatSummaryHeader', () => {
  assert.match(formatSummaryHeader(3), /3건/);
});
