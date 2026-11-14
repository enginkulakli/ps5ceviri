const BASE = `${process.env.EXPO_PUBLIC_BACKEND_URL}/api`;

export type TranslateResult = {
  source_text: string;
  translated_text: string;
  cached: boolean;
  latency_ms: number;
  engine: string;
  history_id: string | null;
};

export type HistoryItem = {
  id: string;
  source_text: string;
  translated_text: string;
  source_lang: string;
  target_lang: string;
  cached: boolean;
  latency_ms: number;
  created_at: string;
};

async function handle<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let detail = res.status === 422 ? "Geçersiz metin (boş veya 600 karakterden uzun)." : `Sunucu hatası (HTTP ${res.status})`;
    if (res.status === 422) throw new Error(detail);
    try {
      const body = await res.json();
      if (body?.detail) detail = typeof body.detail === "string" ? body.detail : JSON.stringify(body.detail);
    } catch {}
    throw new Error(detail);
  }
  return res.json();
}

export async function translateOnline(
  text: string,
  source: string,
  target: string,
  glossary: string[],
  quality: "fast" | "best",
  signal?: AbortSignal,
): Promise<TranslateResult> {
  const res = await fetch(`${BASE}/translate`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ text, source_lang: source, target_lang: target, glossary, quality }),
    signal,
  });
  return handle(res);
}

export async function fetchHistory(): Promise<HistoryItem[]> {
  return handle(await fetch(`${BASE}/history?limit=50`));
}

export async function clearHistory(): Promise<{ deleted: number }> {
  return handle(await fetch(`${BASE}/history`, { method: "DELETE" }));
}
