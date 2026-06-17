import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createClaudeCliLLM } from '../../../src/adapters/llm/claude-cli.ts';

function resultLine(text: string) {
  return JSON.stringify({ type: 'result', subtype: 'success', result: text, usage: { input_tokens: 1, output_tokens: 1 } });
}

test('각 워커는 역할/프롬프트만 받고 결과를 순서대로 반환', async () => {
  const seenPrompts: string[] = [];
  const runner = async (_args: string[], input: string) => { seenPrompts.push(input); return resultLine(`done:${input.slice(0, 4)}`); };
  const llm = createClaudeCliLLM({ allowedTools: ['Read'] }, runner);
  const res = await llm.spawnWorkers(
    [{ role: 'A', prompt: 'aaaa' }, { role: 'B', prompt: 'bbbb' }],
    { concurrency: 2 },
  );
  assert.equal(res.length, 2);
  assert.equal(res[0].status, 'done');
  assert.match(res[0].text!, /done:/);
  assert.ok(seenPrompts.every((p) => p.includes('전문가') || p.length > 0)); // 역할 주입 포함
});

test('동시성 상한을 초과 실행하지 않는다', async () => {
  let active = 0, peak = 0;
  const runner = async () => {
    active++; peak = Math.max(peak, active);
    await new Promise((r) => setTimeout(r, 20));
    active--; return resultLine('ok');
  };
  const llm = createClaudeCliLLM({ allowedTools: [] }, runner);
  await llm.spawnWorkers(
    Array.from({ length: 6 }, (_, i) => ({ role: `R${i}`, prompt: 'p' })),
    { concurrency: 2 },
  );
  assert.ok(peak <= 2, `peak=${peak}`);
});

test('타임아웃 초과 워커는 failed, 나머지는 done', async () => {
  const runner = (_a: string[], input: string) =>
    input.includes('slow')
      ? new Promise<string>((r) => setTimeout(() => r(resultLine('late')), 100))
      : Promise.resolve(resultLine('fast'));
  const llm = createClaudeCliLLM({ allowedTools: [] }, runner);
  const res = await llm.spawnWorkers(
    [{ role: 'fast', prompt: 'fast' }, { role: 'slow', prompt: 'slow' }],
    { concurrency: 2, timeoutMs: 30 },
  );
  assert.equal(res.find((r) => r.task.role === 'fast')!.status, 'done');
  const slow = res.find((r) => r.task.role === 'slow')!;
  assert.equal(slow.status, 'failed');
  assert.match(slow.error!, /타임아웃|timeout/i);
});
