import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";

import { hasNative, nativeModule, SendStatus } from "@/src/native/bridge";
import { SubtitleTracker, tvPayload } from "@/src/pipeline/subtitle";
import { useSettings } from "@/src/settings";
import { useTvServer } from "@/src/tv/TvServerProvider";

export type EngineState = "idle" | "translating" | "ok" | "error";

export type PipelineState = {
  english: string;
  turkish: string;
  engineState: EngineState;
  error: string | null;
  lastLatencyMs: number | null;
  lastCached: boolean;
  lastPayload: string | null;
  payloadDelivered: boolean;
  payloadStatus: SendStatus | "no_native" | null;
  lastEvent: string;
  stats: { received: number; translated: number; skippedSame: number; ignored: number };
};

const INITIAL: PipelineState = {
  english: "",
  turkish: "",
  engineState: "idle",
  error: null,
  lastLatencyMs: null,
  lastCached: false,
  lastPayload: null,
  payloadDelivered: false,
  payloadStatus: null,
  lastEvent: "Bekleniyor",
  stats: { received: 0, translated: 0, skippedSame: 0, ignored: 0 },
};

type Ctx = { state: PipelineState; ingest: (raw: string) => void; clear: () => void };
const PipelineContext = createContext<Ctx | null>(null);

export function PipelineProvider({ children }: { children: React.ReactNode }) {
  const { settings } = useSettings();
  const [state, setState] = useState<PipelineState>(INITIAL);
  const tracker = useRef(new SubtitleTracker({ repeatWindowMs: settings.repeatWindowMs, sensitivity: settings.sensitivity }));
  const seq = useRef(0);
  const clearTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const settingsRef = useRef(settings);
  useEffect(() => { settingsRef.current = settings; }, [settings]);

  useEffect(() => {
    tracker.current.configure({ repeatWindowMs: settings.repeatWindowMs, sensitivity: settings.sensitivity });
  }, [settings.repeatWindowMs, settings.sensitivity]);

  const tv = useTvServer();
  const tvSend = tv.send;
  const sendToTv = useCallback(
    async (text: string) => {
      const payload = tvPayload(text);
      const r = await tvSend(text);
      setState((s) => ({
        ...s,
        lastPayload: payload,
        payloadStatus: r ? r.status : "no_native",
        payloadDelivered: r?.status === "sent" || r?.status === "duplicate",
      }));
    },
    [tvSend],
  );

  const runTranslation = useCallback(
    async (text: string) => {
      const mySeq = ++seq.current;
      const s = settingsRef.current;
      setState((st) => ({ ...st, english: text, engineState: "translating", error: null, lastEvent: "Çevriliyor" }));
      try {
        if (s.engine !== "offline") throw new Error("API yapılandırılmadı");
        if (!nativeModule?.translateOffline) throw new Error("Çevrimdışı çeviri için Android APK gerekir.");
        const startedAt = Date.now();
        const translated = await nativeModule.translateOffline(text, s.sourceLang, s.targetLang);
        const latency = Date.now() - startedAt;
        if (mySeq !== seq.current) return; // an older translation must never overwrite a newer one
        setState((st) => ({
          ...st,
          turkish: translated,
          engineState: "ok",
          lastLatencyMs: latency,
          lastCached: false,
          lastEvent: "Çevrildi",
          stats: { ...st.stats, translated: st.stats.translated + 1 },
        }));
        if (hasNative("showTranslationOverlay" as any)) {
          await nativeModule?.showTranslationOverlay?.(translated).catch(() => {});
        }
        await sendToTv(translated); // empty result (OCR noise) clears the TV instead of leaving a stale line
      } catch (e: any) {
        if (mySeq !== seq.current) return;
        setState((st) => ({ ...st, engineState: "error", error: e?.message ?? "Bilinmeyen çeviri hatası", lastEvent: "Hata" }));
      }
    },
    [sendToTv],
  );

  const ingest = useCallback(
    (raw: string) => {
      const res = tracker.current.process(raw);
      setState((st) => ({ ...st, stats: { ...st.stats, received: st.stats.received + 1 } }));
      if (res.action === "empty") {
        if (clearTimer.current) return;
        clearTimer.current = setTimeout(() => {
          clearTimer.current = null;
          seq.current++;
          tracker.current.reset();
          setState((st) => ({ ...st, english: "", turkish: "", engineState: "idle", lastLatencyMs: null, lastEvent: "Altyazı kayboldu, TV temizlendi" }));
          nativeModule?.showTranslationOverlay?.("").catch(() => {});
          sendToTv("");
        }, settingsRef.current.clearDelayMs);
        return;
      }
      if (clearTimer.current) {
        clearTimeout(clearTimer.current);
        clearTimer.current = null;
      }
      if (res.action === "same") {
        setState((st) => ({ ...st, lastEvent: "Aynı altyazı, atlandı", lastLatencyMs: null, stats: { ...st.stats, skippedSame: st.stats.skippedSame + 1 } }));
        return;
      }
      if (res.action === "ignored") {
        setState((st) => ({ ...st, lastEvent: `Filtrelendi: ${res.reason}`, lastLatencyMs: null, stats: { ...st.stats, ignored: st.stats.ignored + 1 } }));
        return;
      }
      runTranslation(res.text);
    },
    [runTranslation, sendToTv],
  );

  const clear = useCallback(() => {
    seq.current++;
    tracker.current.reset();
    setState((st) => ({ ...INITIAL, stats: st.stats, lastEvent: "Temizlendi" }));
    nativeModule?.showTranslationOverlay?.("").catch(() => {});
    sendToTv("");
  }, [sendToTv]);

  return <PipelineContext.Provider value={{ state, ingest, clear }}>{children}</PipelineContext.Provider>;
}

export function usePipeline() {
  const ctx = useContext(PipelineContext);
  if (!ctx) throw new Error("usePipeline must be used inside PipelineProvider");
  return ctx;
}
