export interface IncomingMessage {
  sessionId: string;  // thread_ts (루트면 자기 ts)
  chatId: string;     // channel id
  userId: string;
  text: string;
  ts: string;
}

export type MessageHandler = (msg: IncomingMessage) => Promise<void>;

export interface ChatPort {
  onMessage(handler: MessageHandler): void;
  send(chatId: string, text: string): Promise<void>;
  sendLong(chatId: string, text: string): Promise<void>;
  replyInThread(chatId: string, threadTs: string, text: string): Promise<void>;
  // 메시지에 이모지 리액션 추가/제거(접수 👀 / 완료 ✅ 신호용)
  addReaction(chatId: string, ts: string, name: string): Promise<void>;
  removeReaction(chatId: string, ts: string, name: string): Promise<void>;
  start(): Promise<void>;
}
