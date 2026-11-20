// Bridge to the native Android module "Ps5Translator" (modules/ps5-translator, Kotlin).
// Implemented now: TV WebSocket server (ws://TABLET_IP:8787) in a foreground service.
// Not implemented yet (later phases): PS5 Remote Play (chiaki-ng), MediaCodec, ML Kit OCR / offline translation.
// In Expo Go / web preview the module does not exist; the UI then says so instead of faking success.
import { requireOptionalNativeModule } from "expo";

export type OcrRegion = { x: number; y: number; w: number; h: number };

export type DiscoveredConsole = { name: string; host: string; state: "ready" | "standby" };

export type TvServerState = {
  running: boolean;
  port: number;
  allowedIp: string | null;
  localIp: string | null;
  wifiConnected: boolean;
  clientCount: number;
  clientIps: string[];
  lastSentText: string | null;
  lastSentAtMs: number;
  lastSentClients: number;
  sentCount: number;
  rejectedCount: number;
  lastError: string | null;
  lastErrorAtMs: number;
  startedAtMs: number;
};

export type SendStatus = "sent" | "duplicate" | "no_clients" | "not_running";
export type SendResult = { status: SendStatus; clients: number };

export interface Ps5TranslatorNativeModule {
  // ---- implemented (Kotlin) ----
  getServerState(): Promise<TvServerState>;
  getLocalIpAddress(): Promise<string | null>;
  startTvServer(port: number, allowedTvIp: string | null): Promise<TvServerState>;
  stopTvServer(): Promise<TvServerState>;
  setAllowedTvIp(ip: string | null): Promise<void>;
  getTvClientCount(): Promise<number>;
  sendSubtitle(text: string, force: boolean): Promise<SendResult>; // sends exactly {"text":"..."}
  setStartOnBoot(enabled: boolean): Promise<void>;
  isIgnoringBatteryOptimizations(): Promise<boolean>;
  requestIgnoreBatteryOptimizations(): Promise<void>;
  addListener(event: "onServerState", cb: (s: TvServerState) => void): { remove: () => void };
  hasOverlayPermission(): Promise<boolean>;
  requestOverlayPermission(): Promise<void>;
  showTranslationOverlay(text: string): Promise<void>;
  startScreenCapture(): Promise<void>;
  startBackgroundTranslation(): Promise<void>;
  stopBackgroundTranslation(): Promise<void>;
  getBackgroundTranslationStatus(): Promise<{enabled: boolean; status: string; english: string; turkish: string}>;
  stopScreenCapture(): Promise<void>;
  recognizeLatestScreen(): Promise<Array<{text: string; left: number; top: number; right: number; bottom: number}>>;
  // ---- later phases (not implemented natively yet) ----
  discoverConsoles?(timeoutMs: number): Promise<DiscoveredConsole[]>;
  registerConsole?(host: string, psnAccountIdBase64: string, pin: string): Promise<{ registKey: string; morning: string }>;
  startSession?(host: string): Promise<void>;
  stopSession?(): Promise<void>;
  configureOcr?(region: OcrRegion, intervalMs: number, minConfidence: number): Promise<void>;
  ensureOfflineModel?(source: string, target: string): Promise<void>;
  translateOffline?(text: string, source: string, target: string): Promise<string>;
}

export const nativeModule = requireOptionalNativeModule<Ps5TranslatorNativeModule>("Ps5Translator");
export const nativeAvailable = nativeModule != null;

export function hasNative(fn: keyof Ps5TranslatorNativeModule): boolean {
  return nativeModule != null && typeof (nativeModule as any)[fn] === "function";
}

export const NATIVE_REQUIRED_MESSAGE =
  "TV sunucusu yerel Android modülünde çalışır. Expo Go ve web önizlemede yoktur; Android APK derlemesi (Publish → Android build) ile kurulan uygulamada çalışır.";

export const NOT_IMPLEMENTED_MESSAGE =
  "Bu özellik henüz uygulanmadı (PS5 Remote Play / chiaki-ng aşaması). Sahte bağlantı gösterilmiyor.";
