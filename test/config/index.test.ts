import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadConfig } from '../../src/config/index.ts';

test('필수 env 누락 시 명확한 에러', () => {
  assert.throws(() => loadConfig({}), /SLACK_BOT_TOKEN/);
});

test('값 파싱: 화이트리스트 분리와 숫자 변환', () => {
  const cfg = loadConfig({
    SLACK_BOT_TOKEN: 'xoxb', SLACK_APP_TOKEN: 'xapp',
    SLACK_ALLOWED_USER_IDS: 'U1, U2 ,U3',
    DB_PATH: '/tmp/a.db', WORKER_CONCURRENCY: '5',
  });
  assert.deepEqual(cfg.slack.allowedUserIds, ['U1', 'U2', 'U3']);
  assert.equal(cfg.worker.concurrency, 5);
  assert.equal(cfg.worker.timeoutMs, 180000); // 기본값
});
