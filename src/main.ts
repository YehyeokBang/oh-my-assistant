import { loadConfig } from './config/index.ts';
import { createSqliteMemory } from './adapters/memory/sqlite.ts';
import { createSlackChat } from './adapters/chat/slack.ts';
import { createClaudeCliLLM } from './adapters/llm/claude-cli.ts';
import { createOrchestrator } from './core/orchestrator.ts';
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

const cfg = loadConfig(process.env);
mkdirSync(dirname(cfg.dbPath), { recursive: true });

const memory = createSqliteMemory(cfg.dbPath);
const chat = createSlackChat(cfg.slack);
const llm = createClaudeCliLLM({ defaultModel: cfg.llm.orchestratorModel, allowedTools: cfg.llm.allowedTools });
const orchestrator = createOrchestrator({
  llm, memory, chat,
  orchestratorModel: cfg.llm.orchestratorModel,
  workerModel: cfg.llm.workerModel,
  workerConcurrency: cfg.worker.concurrency,
  workerTimeoutMs: cfg.worker.timeoutMs,
});

chat.onMessage(async (msg) => {
  await orchestrator.handle(msg);
});

await chat.start();
console.log('oh-my-assistant: Slack Socket Mode 시작됨');
