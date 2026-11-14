import React, { createContext, useCallback, useContext, useEffect, useState } from "react";

import { OcrRegion } from "@/src/native/bridge";
import { storage } from "@/src/utils/storage";

export type Console = { id: string; name: string; host: string; registered: boolean };

export type Settings = {
  sourceLang: string;
  targetLang: string;
  engine: "online" | "offline";
  translateQuality: "fast" | "best";
  ocrRegion: OcrRegion;
  ocrIntervalMs: number;
  sensitivity: number;
  repeatWindowMs: number;
  clearDelayMs: number;
  autoConnect: boolean;
  foregroundService: boolean;
  keepWifiAwake: boolean;
  glossary: string[];
  tvPort: number;
  tvIp: string;
  consoles: Console[];
};

export const DEFAULT_SETTINGS: Settings = {
  sourceLang: "en",
  targetLang: "tr",
  engine: "online",
  translateQuality: "best",
  ocrRegion: { x: 0, y: 0.72, w: 1, h: 0.28 },
  ocrIntervalMs: 300,
  sensitivity: 0.5,
  repeatWindowMs: 4000,
  clearDelayMs: 1200,
  autoConnect: true,
  foregroundService: true,
  keepWifiAwake: true,
  glossary: [],
  tvPort: 8787,
  tvIp: "",
  consoles: [],
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
          setSettings({ ...DEFAULT_SETTINGS, ...JSON.parse(raw) });
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
