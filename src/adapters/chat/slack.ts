import pkg from '@slack/bolt';
const { App } = pkg;
import type { ChatPort, IncomingMessage, MessageHandler } from '../../ports/chat.ts';

const SLACK_LIMIT = 40000;

export function splitForSlack(text: string, limit = SLACK_LIMIT): string[] {
  if (text.length <= limit) return [text];
  const parts: string[] = [];
  for (let i = 0; i < text.length; i += limit) parts.push(text.slice(i, i + limit));
  return parts;
}

export interface SlackConfig {
  botToken: string;
  appToken: string;
  allowedUserIds: string[];
}

export function createSlackChat(cfg: SlackConfig): ChatPort {
  const app = new App({ token: cfg.botToken, appToken: cfg.appToken, socketMode: true });
  const allowed = new Set(cfg.allowedUserIds);
  let handler: MessageHandler | null = null;

  app.message(async ({ message }) => {
    const m = message as any;
    if (m.subtype) return;                       // 봇/시스템 메시지 무시
    if (!allowed.has(m.user)) return;            // 화이트리스트 외 무시
    if (!handler) return;
    const incoming: IncomingMessage = {
      sessionId: m.thread_ts ?? m.ts,            // 스레드 루트면 자기 ts
      chatId: m.channel,
      userId: m.user,
      text: m.text ?? '',
      ts: m.ts,
    };
    await handler(incoming);
  });

  return {
    onMessage(h) { handler = h; },
    async send(chatId, text) {
      for (const part of splitForSlack(text)) {
        await app.client.chat.postMessage({ channel: chatId, text: part });
      }
    },
    async sendLong(chatId, text) {
      for (const part of splitForSlack(text)) {
        await app.client.chat.postMessage({ channel: chatId, text: part });
      }
    },
    async replyInThread(chatId, threadTs, text) {
      for (const part of splitForSlack(text)) {
        await app.client.chat.postMessage({ channel: chatId, thread_ts: threadTs, text: part });
      }
    },
    async sendTyping(chatId) {
      await app.client.chat.postMessage({ channel: chatId, text: '🔎 받았어요…' });
    },
    async start() { await app.start(); },
  };
}
