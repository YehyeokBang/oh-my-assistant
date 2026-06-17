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
  sendTyping(chatId: string): Promise<void>;
  start(): Promise<void>;
}
