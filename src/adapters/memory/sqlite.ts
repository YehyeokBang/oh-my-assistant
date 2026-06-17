import Database from 'better-sqlite3';
import type { MemoryPort, StoredMessage, TaskRecord, SessionInfo } from '../../ports/memory.ts';
import type { Message } from '../../ports/llm.ts';

const SCHEMA = `
CREATE TABLE IF NOT EXISTS sessions (
  id         TEXT PRIMARY KEY,
  channel    TEXT NOT NULL,
  chat_id    TEXT NOT NULL,
  persona    TEXT,
  created_ts INTEGER NOT NULL,
  last_ts    INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS messages (
  id          INTEGER PRIMARY KEY,
  session_id  TEXT NOT NULL,
  ts          INTEGER NOT NULL,
  direction   TEXT NOT NULL,
  channel     TEXT NOT NULL,
  llm_backend TEXT,
  payload     BLOB
);
CREATE INDEX IF NOT EXISTS idx_messages_session_ts ON messages(session_id, ts);
CREATE TABLE IF NOT EXISTS tasks (
  id         INTEGER PRIMARY KEY,
  parent_id  INTEGER,
  session_id TEXT NOT NULL,
  status     TEXT NOT NULL,
  role       TEXT,
  ts_start   INTEGER,
  ts_end     INTEGER,
  payload    BLOB
);
CREATE INDEX IF NOT EXISTS idx_tasks_session ON tasks(session_id);
CREATE INDEX IF NOT EXISTS idx_tasks_parent ON tasks(parent_id);
`;

export function createSqliteMemory(dbPath: string): MemoryPort {
  const db = new Database(dbPath);
  db.pragma('journal_mode = WAL');
  db.exec(SCHEMA);

  const insMsg = db.prepare(
    `INSERT INTO messages (session_id, ts, direction, channel, llm_backend, payload)
     VALUES (@session_id, @ts, @direction, @channel, @llm_backend, jsonb(@payload))`,
  );
  const selHistory = db.prepare(
    `SELECT direction, json(payload) AS payload FROM messages WHERE session_id = ? ORDER BY ts ASC, id ASC`,
  );
  const insTask = db.prepare(
    `INSERT INTO tasks (parent_id, session_id, status, role, ts_start, ts_end, payload)
     VALUES (@parent_id, @session_id, @status, @role, @ts_start, @ts_end, jsonb(@payload))`,
  );

  return {
    ensureSession(s: SessionInfo) {
      const now = Date.now();
      db.prepare(
        `INSERT INTO sessions (id, channel, chat_id, persona, created_ts, last_ts)
         VALUES (?, ?, ?, ?, ?, ?)
         ON CONFLICT(id) DO UPDATE SET last_ts = excluded.last_ts`,
      ).run(s.id, s.channel, s.chatId, s.persona ?? null, now, now);
    },
    append(msg: StoredMessage) {
      insMsg.run({
        session_id: msg.sessionId,
        ts: msg.ts,
        direction: msg.direction,
        channel: msg.channel,
        llm_backend: msg.llmBackend ?? null,
        payload: JSON.stringify(msg.payload ?? {}),
      });
    },
    getHistory(sessionId: string): Message[] {
      const rows = selHistory.all(sessionId) as { direction: string; payload: string }[];
      return rows.map((r) => ({
        role: r.direction === 'in' ? 'user' : 'assistant',
        content: (JSON.parse(r.payload)?.text ?? '') as string,
      }));
    },
    recordTask(task: TaskRecord): number {
      const info = insTask.run({
        parent_id: task.parentId ?? null,
        session_id: task.sessionId,
        status: task.status,
        role: task.role ?? null,
        ts_start: task.tsStart ?? null,
        ts_end: task.tsEnd ?? null,
        payload: JSON.stringify(task.payload ?? {}),
      });
      return Number(info.lastInsertRowid);
    },
    updateTask(id: number, patch: Partial<TaskRecord>) {
      const sets: string[] = [];
      const vals: unknown[] = [];
      if (patch.status !== undefined) { sets.push('status = ?'); vals.push(patch.status); }
      if (patch.role !== undefined) { sets.push('role = ?'); vals.push(patch.role); }
      if (patch.tsStart !== undefined) { sets.push('ts_start = ?'); vals.push(patch.tsStart); }
      if (patch.tsEnd !== undefined) { sets.push('ts_end = ?'); vals.push(patch.tsEnd); }
      if (patch.payload !== undefined) { sets.push('payload = jsonb(?)'); vals.push(JSON.stringify(patch.payload)); }
      if (sets.length === 0) return;
      vals.push(id);
      db.prepare(`UPDATE tasks SET ${sets.join(', ')} WHERE id = ?`).run(...vals);
    },
  };
}
