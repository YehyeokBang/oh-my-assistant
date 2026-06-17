import type { Message } from './llm.ts';

export type Direction = 'in' | 'out';
export type TaskStatus = 'queued' | 'running' | 'done' | 'failed';

export interface StoredMessage {
  sessionId: string;
  ts: number;          // epoch ms
  direction: Direction;
  channel: string;     // 'slack'
  llmBackend?: string;
  payload: unknown;    // JSONB: { text, ... }
}

export interface TaskRecord {
  id?: number;
  parentId?: number | null;
  sessionId: string;
  status: TaskStatus;
  role?: string;
  tsStart?: number;
  tsEnd?: number;
  payload?: unknown;
}

export interface SessionInfo {
  id: string;          // = thread_ts
  channel: string;
  chatId: string;
  persona?: string;
}

export interface MemoryPort {
  ensureSession(s: SessionInfo): void;
  append(msg: StoredMessage): void;
  getHistory(sessionId: string): Message[];
  recordTask(task: TaskRecord): number;       // returns new id
  updateTask(id: number, patch: Partial<TaskRecord>): void;
}
