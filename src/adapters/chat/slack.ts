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

  const dispatch = async (f: { channel: string; user?: string; text: string; ts: string; threadTs?: string }) => {
    if (!f.user || !allowed.has(f.user)) return;  // 화이트리스트 외 무시
    if (!handler) return;
    const incoming: IncomingMessage = {
      sessionId: f.threadTs ?? f.ts,              // 스레드 루트면 자기 ts
      chatId: f.channel,
      userId: f.user,
      text: f.text,
      ts: f.ts,
    };
    await handler(incoming);
  };

  // DM (message.im 구독)
  app.message(async ({ message }) => {
    const m = message as any;
    if (m.subtype) return;                        // 봇/시스템 메시지 무시
    await dispatch({ channel: m.channel, user: m.user, text: m.text ?? '', ts: m.ts, threadTs: m.thread_ts });
  });

  // 채널 멘션 (app_mention 구독). 멘션 토큰(<@BOTID>)은 제거해 본문만 전달.
  app.event('app_mention', async ({ event }) => {
    const e = event as any;
    const text = (e.text ?? '').replace(/<@[^>]+>/g, '').trim();
    await dispatch({ channel: e.channel, user: e.user, text, ts: e.ts, threadTs: e.thread_ts });
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
    async addReaction(chatId, ts, name) {
      await app.client.reactions.add({ channel: chatId, timestamp: ts, name });
    },
    async removeReaction(chatId, ts, name) {
      await app.client.reactions.remove({ channel: chatId, timestamp: ts, name });
    },
    async start() { await app.start(); },
  };
}
