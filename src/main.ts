import { loadConfig } from './config/index.ts';
import { createSqliteMemory } from './adapters/memory/sqlite.ts';
import { createSlackChat } from './adapters/chat/slack.ts';
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

const cfg = loadConfig(process.env);
mkdirSync(dirname(cfg.dbPath), { recursive: true });

const memory = createSqliteMemory(cfg.dbPath);
const chat = createSlackChat(cfg.slack);

chat.onMessage(async (msg) => {
  memory.ensureSession({ id: msg.sessionId, channel: 'slack', chatId: msg.chatId });
  memory.append({ sessionId: msg.sessionId, ts: Date.now(), direction: 'in', channel: 'slack', payload: { text: msg.text } });

  const reply = `echo: ${msg.text}`;            // M0: 에코. M1에서 LLM 응답으로 교체
  await chat.replyInThread(msg.chatId, msg.sessionId, reply);
  memory.append({ sessionId: msg.sessionId, ts: Date.now(), direction: 'out', channel: 'slack', payload: { text: reply } });
});

await chat.start();
console.log('oh-my-assistant: Slack Socket Mode 시작됨');
