import { test } from 'node:test';
import assert from 'node:assert/strict';
import { classifyIntent } from '../../src/core/intent.ts';

test('명시 트리거 "각각/조사"는 병렬', () => {
  assert.equal(classifyIntent('카카오 라인 왓츠앱 각각 조사해줘').parallel, true);
});
test('"후보 3개"처럼 복수성 신호는 병렬', () => {
  assert.equal(classifyIntent('이름 후보 3개 만들어줘').parallel, true);
});
test('단순 단발 질문은 비병렬', () => {
  assert.equal(classifyIntent('이 PR 설명 한 줄로 써줘').parallel, false);
});
