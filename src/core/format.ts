import type { WorkerTask, WorkerResult } from '../ports/llm.ts';

export function parseTasks(raw: string): WorkerTask[] {
  const cleaned = raw.replace(/```(?:json)?/gi, '').trim();
  const start = cleaned.indexOf('[');
  const end = cleaned.lastIndexOf(']');
  if (start === -1 || end === -1 || end < start) return [];
  try {
    const arr = JSON.parse(cleaned.slice(start, end + 1));
    if (!Array.isArray(arr)) return [];
    return arr
      .filter((x) => x && typeof x.role === 'string' && typeof x.prompt === 'string')
      .map((x) => ({ role: x.role, prompt: x.prompt, context: typeof x.context === 'string' ? x.context : undefined }));
  } catch {
    return [];
  }
}

export function formatSummaryHeader(count: number): string {
  return `🔎 조사 시작 (${count}건)`;
}

export function formatWorkerReport(r: WorkerResult): string {
  if (r.status === 'failed') {
    return `⚠️ *${r.task.role}* 실패: ${r.error ?? '알 수 없는 오류'}`;
  }
  return `📊 *${r.task.role}*\n${r.text ?? ''}`;
}
