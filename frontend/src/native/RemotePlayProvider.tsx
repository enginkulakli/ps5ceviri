import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { AppState } from "react-native";

import { hasNative, nativeModule, type RemotePlayStatus } from "@/src/native/bridge";
import { applyPairingRecords, credentialSlot, mergeByConsoleIdentity } from "@/src/native/consoleRecords";
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
    sensitivity, confirmations, glossary, gameId, generalArchive, shareEngines, dailyCharacters, monthlyCharacters } = settings;
  const settingsJson = useMemo(() => JSON.stringify({
    mode, engine, region: ocrRegion, exclusions, intervalMs: ocrIntervalMs, ttlMs: clearDelayMs,
    minimumMs: minimumVisibleMs, sensitivity, confirmations, glossary, gameId, generalArchive, shareEngines, dailyCharacters, monthlyCharacters,
  }), [mode, engine, ocrRegion, exclusions, ocrIntervalMs, clearDelayMs, minimumVisibleMs, sensitivity, confirmations, glossary, gameId, generalArchive, shareEngines, dailyCharacters, monthlyCharacters]);
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
    if (!ready || !bridge || !hasNative("getConsolePairing")) return;
    let alive = true;
    const references = new Set(settings.consoles.map(credentialSlot));
    Promise.allSettled([...references].map(reference => bridge.getConsolePairing(reference))).then(results => {
      if (!alive) return;
      const pairings = results.flatMap(result => result.status === "fulfilled" ? [result.value] : []);
      update(current => {
        const next = applyPairingRecords(current, pairings);
        return next.selectedConsoleId !== current.selectedConsoleId || next.consoles.length !== current.consoles.length ||
          next.consoles.some((console, index) => console !== current.consoles[index]) ? next : null;
      });
    }).catch(() => {});
    return () => { alive = false; };
  }, [ready, settings.consoles, update]);

  const { host, pairingReference, consoleIdentity } = status ?? {};
  useEffect(() => {
    if (!ready || !host || !pairingReference || !consoleIdentity) return;
    update(current => {
      const selected = current.consoles.find(console => credentialSlot(console) === pairingReference);
      if (!selected || (selected.host === host && selected.identity === consoleIdentity)) return null;
      const consoles = current.consoles.map(console => console.id === selected.id
        ? { ...console, host, pairingReference, identity: consoleIdentity } : console);
      return mergeByConsoleIdentity({ consoles, selectedConsoleId: current.selectedConsoleId });
    });
  }, [ready, host, pairingReference, consoleIdentity, update]);

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
        await nativeModule.startSession(selected.host, { settingsJson, keepWifiAwake: settings.keepWifiAwake, pairingReference: credentialSlot(selected) });
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
