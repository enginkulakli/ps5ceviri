import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { AppState } from "react-native";

import { hasNative, nativeModule, type RemotePlayStatus } from "@/src/native/bridge";
import { useSettings } from "@/src/settings";
import { useTvServer } from "@/src/tv/TvServerProvider";

const RemotePlayContext = createContext<{
  status: RemotePlayStatus | null;
  busy: boolean;
  error: string | null;
  toggle: () => Promise<void>;
} | null>(null);

export function RemotePlayProvider({ children }: { children: ReactNode }) {
  const { settings, ready, update } = useSettings();
  const tv = useTvServer();
  const [status, setStatus] = useState<RemotePlayStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const { mode, engine, ocrRegion, exclusions, ocrIntervalMs, clearDelayMs, minimumVisibleMs,
    sensitivity, confirmations, glossary } = settings;
  const settingsJson = useMemo(() => JSON.stringify({
    mode, engine, region: ocrRegion, exclusions, intervalMs: ocrIntervalMs, ttlMs: clearDelayMs,
    minimumMs: minimumVisibleMs, sensitivity, confirmations, glossary,
  }), [mode, engine, ocrRegion, exclusions, ocrIntervalMs, clearDelayMs, minimumVisibleMs, sensitivity, confirmations, glossary]);
  const active = status?.active;

  useEffect(() => {
    if (active && hasNative("updateTranslation")) void nativeModule!.updateTranslation(settingsJson).catch((cause) => setError(cause instanceof Error ? cause.message : String(cause)));
  }, [active, settingsJson]);

  useEffect(() => {
    const bridge = nativeModule;
    if (!bridge || !hasNative("getRemotePlayStatus")) return;
    let alive = true;
    let fetching = false;
    const refresh = async () => {
      if (!alive || fetching || AppState.currentState === "background") return;
      fetching = true;
      try {
        const snapshot = await bridge.getRemotePlayStatus();
        if (alive) setStatus(snapshot);
      } catch {
        // An RN bridge reload can interrupt a poll. The next poll reads the native service.
      } finally {
        fetching = false;
      }
    };
    void refresh();
    const timer = setInterval(refresh, 650);
    const subscription = AppState.addEventListener("change", (state) => {
      if (state === "active") void refresh();
    });
    return () => { alive = false; clearInterval(timer); subscription.remove(); };
  }, []);

  useEffect(() => {
    const bridge = nativeModule;
    if (!ready || !bridge || !hasNative("isConsoleRegistered")) return;
    let alive = true;
    const consoles = settings.consoles;
    Promise.all(consoles.map(async (console) => ({
      ...console,
      registered: await bridge.isConsoleRegistered(console.host),
    }))).then((verified) => {
      if (alive && verified.some((console, index) => console.registered !== consoles[index].registered)) {
        update({ consoles: verified });
      }
    }).catch(() => {});
    return () => { alive = false; };
  }, [ready, settings.consoles, update]);

  const toggle = async () => {
    if (!nativeModule || busy) return;
    setBusy(true);
    setError(null);
    try {
      if (status?.active) {
        await nativeModule.stopSession();
      } else {
        const selected = settings.consoles.find((console) => console.id === settings.selectedConsoleId)
          ?? settings.consoles.find((console) => console.registered);
        if (!selected?.registered) throw new Error("Bağlantı sekmesinde bir PS5 seçip eşleştirin.");
        if (engine !== "offline") throw new Error("API yapılandırılmadı");
        await nativeModule.startSession(selected.host, { settingsJson, keepWifiAwake: settings.keepWifiAwake });
        if (!tv.state?.running && tv.busy !== "start") void tv.start();
      }
      setStatus(await nativeModule.getRemotePlayStatus());
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setBusy(false);
    }
  };

  return <RemotePlayContext.Provider value={{ status, busy, error, toggle }}>{children}</RemotePlayContext.Provider>;
}

export function useRemotePlay() {
  const state = useContext(RemotePlayContext);
  if (!state) throw new Error("RemotePlayProvider is required");
  return state;
}
