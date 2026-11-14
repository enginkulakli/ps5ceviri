import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import { AppState, PermissionsAndroid, Platform } from "react-native";

import { nativeModule, SendResult, TvServerState } from "@/src/native/bridge";
import { useSettings } from "@/src/settings";
import { storage } from "@/src/utils/storage";

export type NotifPermission = "granted" | "denied" | "blocked" | "unknown" | "not_needed";

type Ctx = {
  available: boolean;
  state: TvServerState | null;
  busy: "start" | "stop" | "test" | "send" | null;
  actionError: string | null;
  lastSend: (SendResult & { text: string; at: number }) | null;
  notifPermission: NotifPermission;
  batteryExempt: boolean | null;
  start: () => Promise<void>;
  stop: () => Promise<void>;
  test: () => Promise<void>;
  sendManual: (text: string) => Promise<void>;
  send: (text: string) => Promise<SendResult | null>;
  requestNotifPermission: () => Promise<NotifPermission>;
  requestBatteryExemption: () => Promise<void>;
  refresh: () => Promise<void>;
};

const TvCtx = createContext<Ctx | null>(null);

const needsNotifPermission = Platform.OS === "android" && Number(Platform.Version) >= 33;

export function TvServerProvider({ children }: { children: React.ReactNode }) {
  const { settings, ready } = useSettings();
  const [state, setState] = useState<TvServerState | null>(null);
  const [busy, setBusy] = useState<Ctx["busy"]>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [lastSend, setLastSend] = useState<Ctx["lastSend"]>(null);
  const [notifPermission, setNotifPermission] = useState<NotifPermission>(needsNotifPermission ? "unknown" : "not_needed");
  const [batteryExempt, setBatteryExempt] = useState<boolean | null>(null);
  const autoStarted = useRef(false);

  const refresh = useCallback(async () => {
    if (!nativeModule) return;
    try {
      setState(await nativeModule.getServerState());
      setBatteryExempt(await nativeModule.isIgnoringBatteryOptimizations());
      if (needsNotifPermission) {
        const ok = await PermissionsAndroid.check(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
        const asked = await storage.getItem("notif_perm_answer", null);
        setNotifPermission(ok ? "granted" : asked === "blocked" ? "blocked" : asked === "denied" ? "denied" : "unknown");
      }
    } catch (e: any) {
      setActionError(e?.message ?? String(e));
    }
  }, []);

  useEffect(() => {
    if (!nativeModule) return;
    const sub = nativeModule.addListener("onServerState", (s) => setState((prev) => ({ ...(prev ?? s), ...s, localIp: s.localIp ?? prev?.localIp ?? null })));
    refresh();
    const app = AppState.addEventListener("change", (st) => st === "active" && refresh());
    return () => {
      sub.remove();
      app.remove();
    };
  }, [refresh]);

  const requestNotifPermission = useCallback(async (): Promise<NotifPermission> => {
    if (!needsNotifPermission) return "not_needed";
    const r = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS);
    const v: NotifPermission = r === "granted" ? "granted" : r === "never_ask_again" ? "blocked" : "denied";
    setNotifPermission(v);
    if (v !== "granted") storage.setItem("notif_perm_answer", v);
    return v;
  }, []);

  const start = useCallback(async () => {
    if (!nativeModule) return;
    setBusy("start");
    setActionError(null);
    try {
      setState(await nativeModule.startTvServer(settings.tvPort, settings.tvIp || null));
    } catch (e: any) {
      setActionError(e?.message ?? "Sunucu başlatılamadı");
    } finally {
      setBusy(null);
    }
  }, [settings.tvPort, settings.tvIp]);

  const stop = useCallback(async () => {
    if (!nativeModule) return;
    setBusy("stop");
    setActionError(null);
    try {
      setState(await nativeModule.stopTvServer());
    } catch (e: any) {
      setActionError(e?.message ?? "Sunucu durdurulamadı");
    } finally {
      setBusy(null);
    }
  }, []);

  const send = useCallback(async (text: string, force = false): Promise<SendResult | null> => {
    if (!nativeModule) return null;
    try {
      const r = await nativeModule.sendSubtitle(text, force);
      setLastSend({ ...r, text, at: Date.now() });
      return r;
    } catch (e: any) {
      setActionError(e?.message ?? "Gönderim hatası");
      return null;
    }
  }, []);

  const test = useCallback(async () => {
    setBusy("test");
    setActionError(null);
    const r = await send("Bağlantı testi başarılı", true);
    if (r && r.status !== "sent") {
      setActionError(r.status === "not_running" ? "Sunucu çalışmıyor. Önce sunucuyu başlatın." : "Bağlı TV yok. TV uygulamasında tablet adresini kontrol edin.");
    }
    setBusy(null);
  }, [send]);

  const sendManual = useCallback(
    async (text: string) => {
      setBusy("send");
      setActionError(null);
      const r = await send(text, true);
      if (r && r.status === "not_running") setActionError("Sunucu çalışmıyor. Önce sunucuyu başlatın.");
      else if (r && r.status === "no_clients") setActionError("Bağlı TV yok; altyazı TV bağlandığında gönderilecek.");
      setBusy(null);
    },
    [send],
  );

  const requestBatteryExemption = useCallback(async () => {
    if (!nativeModule) return;
    await nativeModule.requestIgnoreBatteryOptimizations();
  }, []);

  // Sync allow-list + boot setting to native; auto-start when enabled.
  useEffect(() => {
    if (!nativeModule || !ready) return;
    nativeModule.setAllowedTvIp(settings.tvIp || null).catch(() => {});
    nativeModule.setStartOnBoot(settings.autoConnect && settings.foregroundService).catch(() => {});
  }, [ready, settings.tvIp, settings.autoConnect, settings.foregroundService]);

  useEffect(() => {
    if (!nativeModule || !ready || autoStarted.current || !state) return;
    autoStarted.current = true;
    if (settings.autoConnect && !state.running) start();
  }, [ready, state, settings.autoConnect, start]);

  return (
    <TvCtx.Provider
      value={{
        available: nativeModule != null,
        state,
        busy,
        actionError,
        lastSend,
        notifPermission,
        batteryExempt,
        start,
        stop,
        test,
        sendManual,
        send,
        requestNotifPermission,
        requestBatteryExemption,
        refresh,
      }}
    >
      {children}
    </TvCtx.Provider>
  );
}

export function useTvServer() {
  const ctx = useContext(TvCtx);
  if (!ctx) throw new Error("useTvServer must be used inside TvServerProvider");
  return ctx;
}
