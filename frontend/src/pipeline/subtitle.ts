// Pure subtitle logic shared by the manual test input today and the native OCR stream later.

export function normalizeOcr(raw: string): string {
  return raw
    .replace(/[\r\n]+/g, " ")
    .replace(/\s*\|\s*/g, " I ") // common OCR confusion: | -> I
    .replace(/[_~`^]+/g, "")
    .replace(/\s+/g, " ")
    .trim();
}

// sensitivity 0..1 : higher = stricter filtering of noisy text
export function isMeaningful(text: string, sensitivity: number): { ok: boolean; reason?: string } {
  if (text.length < 2) return { ok: false, reason: "Çok kısa" };
  const letters = (text.match(/\p{L}/gu) ?? []).length;
  const nonSpace = text.replace(/\s/g, "").length || 1;
  const ratio = letters / nonSpace;
  const minRatio = 0.45 + sensitivity * 0.35; // 0.45 .. 0.80
  if (ratio < minRatio) return { ok: false, reason: `Harf oranı düşük (${Math.round(ratio * 100)}%)` };
  const words = text.split(" ").filter((w) => /\p{L}{2,}/u.test(w));
  if (words.length === 0) return { ok: false, reason: "Anlamlı kelime yok" };
  const vowels = (text.match(/[aeiouyAEIOUY]/g) ?? []).length;
  if (letters >= 6 && vowels / letters < 0.15) return { ok: false, reason: "Anlamsız harf dizisi" };
  return { ok: true };
}

function levenshtein(a: string, b: string): number {
  if (a === b) return 0;
  const prev = new Array(b.length + 1).fill(0).map((_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    let diag = prev[0];
    prev[0] = i;
    for (let j = 1; j <= b.length; j++) {
      const tmp = prev[j];
      prev[j] = Math.min(prev[j] + 1, prev[j - 1] + 1, diag + (a[i - 1] === b[j - 1] ? 0 : 1));
      diag = tmp;
    }
  }
  return prev[b.length];
}

export function similarity(a: string, b: string): number {
  const x = a.toLowerCase();
  const y = b.toLowerCase();
  const max = Math.max(x.length, y.length) || 1;
  return 1 - levenshtein(x, y) / max;
}

export type TrackResult =
  | { action: "new"; text: string }
  | { action: "same" }
  | { action: "empty" }
  | { action: "ignored"; reason: string };

// Decides whether an OCR reading is a new subtitle, the same one, or noise.
export class SubtitleTracker {
  private last = "";
  private lastAt = 0;
  constructor(private opts: { repeatWindowMs: number; sensitivity: number }) {}

  configure(opts: { repeatWindowMs: number; sensitivity: number }) {
    this.opts = opts;
  }

  reset() {
    this.last = "";
    this.lastAt = 0;
  }

  process(raw: string, now = Date.now()): TrackResult {
    const text = normalizeOcr(raw);
    if (!text) return { action: "empty" };
    const check = isMeaningful(text, this.opts.sensitivity);
    if (!check.ok) return { action: "ignored", reason: check.reason ?? "Gürültü" };
    const same = this.last && similarity(text, this.last) >= 0.88;
    if (same && now - this.lastAt < this.opts.repeatWindowMs) {
      this.lastAt = now;
      return { action: "same" };
    }
    this.last = text;
    this.lastAt = now;
    return { action: "new", text };
  }
}

// Exact message format expected by the existing Tizen TV app. Do not change.
export function tvPayload(text: string): string {
  return JSON.stringify({ text });
}
