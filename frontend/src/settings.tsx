import React, { createContext, useCallback, useContext, useEffect, useState } from "react";

import { OcrRegion } from "@/src/native/bridge";
import { storage } from "@/src/utils/storage";

export type Console = { id: string; name: string; host: string; registered: boolean };

export type TranslationMode = "subtitle" | "fullscreen";
export type TranslationEngine = "offline" | "google_cloud" | "ai";
export type GlossaryEntry = { source: string; target: string };

export type Settings = {
  sourceLang: string;
  targetLang: string;
  engine: TranslationEngine;
  mode: TranslationMode;
  ocrRegion: OcrRegion;
  exclusions: OcrRegion[];
  confirmations: number;
  minimumVisibleMs: number;
  ocrIntervalMs: number;
  sensitivity: number;
  repeatWindowMs: number;
  clearDelayMs: number;
  autoConnect: boolean;
  foregroundService: boolean;
  keepWifiAwake: boolean;
  glossary: GlossaryEntry[];
  tvPort: number;
  tvIp: string;
  consoles: Console[];
  selectedConsoleId: string | null;
};

export const DEFAULT_SETTINGS: Settings = {
  sourceLang: "en",
  targetLang: "tr",
  engine: "offline",
  mode: "subtitle",
  ocrRegion: { x: 0, y: 0.72, w: 1, h: 0.28 },
  exclusions: [],
  confirmations: 2,
  minimumVisibleMs: 700,
  ocrIntervalMs: 300,
  sensitivity: 0.5,
  repeatWindowMs: 4000,
  clearDelayMs: 2000,
  autoConnect: true,
  foregroundService: true,
  keepWifiAwake: true,
  glossary: [],
  tvPort: 8787,
  tvIp: "",
  consoles: [],
  selectedConsoleId: null,
};

const KEY = "app_settings_v1";
export const PSN_ACCOUNT_KEY = "psn_account_id";

type Ctx = { settings: Settings; ready: boolean; update: (patch: Partial<Settings>) => void };
const SettingsContext = createContext<Ctx | null>(null);

export function SettingsProvider({ children }: { children: React.ReactNode }) {
  const [settings, setSettings] = useState<Settings>(DEFAULT_SETTINGS);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    (async () => {
      const raw = await storage.getItem(KEY, null);
      if (typeof raw === "string") {
        try {
          const saved = JSON.parse(raw);
          const migratedDelay = saved.selectedConsoleId === undefined && saved.clearDelayMs === 1200
            ? DEFAULT_SETTINGS.clearDelayMs : saved.clearDelayMs ?? DEFAULT_SETTINGS.clearDelayMs;
          const glossary = Array.isArray(saved.glossary)
            ? saved.glossary.flatMap((entry: unknown): GlossaryEntry[] => {
              if (typeof entry === "string" && entry.trim()) return [{ source: entry.trim(), target: entry.trim() }];
              const term = entry as Partial<GlossaryEntry> | null;
              return typeof term?.source === "string" && typeof term?.target === "string"
                ? [{ source: term.source, target: term.target }] : [];
            }).slice(0, 80) : [];
          const engine = saved.engine === "google_cloud" || saved.engine === "ai" ? saved.engine : "offline";
          setSettings({ ...DEFAULT_SETTINGS, ...saved, engine, glossary, clearDelayMs: migratedDelay });
        } catch {}
      }
      setReady(true);
    })();
  }, []);

  const update = useCallback((patch: Partial<Settings>) => {
    setSettings((prev) => {
      const next = { ...prev, ...patch };
      storage.setItem(KEY, JSON.stringify(next));
      return next;
    });
  }, []);

  return <SettingsContext.Provider value={{ settings, ready, update }}>{children}</SettingsContext.Provider>;
}

export function useSettings() {
  const ctx = useContext(SettingsContext);
  if (!ctx) throw new Error("useSettings must be used inside SettingsProvider");
  return ctx;
}
