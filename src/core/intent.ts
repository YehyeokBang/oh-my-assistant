const TRIGGERS = ['각각', '조사', '비교', '동시에', '병렬', '후보', '여러'];
const COUNT_RE = /(\d+)\s*(개|가지|건|곳)/;

export function classifyIntent(text: string): { parallel: boolean; reason: string } {
  const t = text.trim();
  const hit = TRIGGERS.find((w) => t.includes(w));
  const countMatch = t.match(COUNT_RE);
  if (countMatch && Number(countMatch[1]) >= 2) {
    return { parallel: true, reason: `복수성 신호(${countMatch[0]})` };
  }
  if (hit) return { parallel: true, reason: `트리거 키워드(${hit})` };
  return { parallel: false, reason: '단발 신호' };
}
