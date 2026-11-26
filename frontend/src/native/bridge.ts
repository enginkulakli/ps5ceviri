import { requireOptionalNativeModule } from "expo";
import type { ConsolePairing, DiscoveredConsole } from "@/src/native/consoleRecords";

export type RemotePlayStatus = {
  active: boolean;
  host: string | null;
  pairingReference: string | null;
  consoleIdentity: string | null;
  console: string;
  session: string;
  video: string;
  ocr: string;
  translation: string;
  english: string;
  turkish: string;
  error: string | null;
  overlayClients: number;
};

export type OcrRegion = { x: number; y: number; w: number; h: number };


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

export type TvDiscoveryState = {
  available: boolean;
  error: string | null;
  waiting: Array<{ id: string; ip: string }>;
  paired: Array<{ id: string; ip: string | null }>;
};

export type SendStatus = "sent" | "duplicate" | "no_clients" | "not_running";
export type SendResult = { status: SendStatus; clients: number };

export interface Ps5TranslatorNativeModule {
  getTvDiscovery(): Promise<TvDiscoveryState>;
  approveDiscoveredTv(id: string, code: string): Promise<void>;
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
  openExternalRemotePlay(): Promise<boolean>;
  discoverConsoles(timeoutMs: number): Promise<DiscoveredConsole[]>;
  registerConsole(host: string, psnAccountIdBase64: string, pin: string): Promise<ConsolePairing>;
  getConsolePairing(reference: string): Promise<ConsolePairing>;
  getInstalledVersionName(): Promise<string | null>;
  isConsoleRegistered(host: string): Promise<boolean>;
  forgetConsole(reference: string): Promise<void>;
  startSession(host: string, options: { settingsJson: string; keepWifiAwake: boolean; pairingReference?: string }): Promise<void>;
  updateTranslation(settingsJson: string): Promise<void>;
  getCloudStatus(): Promise<string>;
  saveCloudKey(key: string): Promise<void>;
  revealCloudKey(): Promise<string | null>;
  deleteCloudKey(): Promise<void>;
  testCloudConnection(daily: number, monthly: number): Promise<string>;
  getTranslationMetrics(): Promise<string>;
  setScreenOcrDiagnostics(enabled: boolean): Promise<void>;
  getScreenOcrDiagnostics(): Promise<string>;
  isScreenOcrDiagnosticsEnabled(): Promise<boolean>;
  getArchiveOverview(): Promise<string>;
  createArchiveGame(name: string): Promise<string>;
  getArchivePage(game: string, search: string, before: number): Promise<string>;
  editArchiveRecord(id: number, translated: string): Promise<void>;
  deleteArchiveRecord(id: number): Promise<void>;
  exportTranslationArchive(game: string | null): Promise<string>;
  importTranslationArchive(): Promise<string>;
  getLanguageModels(): Promise<string[]>;
  downloadLanguageModel(language: string): Promise<void>;
  removeLanguageModel(language: string): Promise<void>;
  stopSession(): Promise<void>;
  getRemotePlayStatus(): Promise<RemotePlayStatus>;
  captureRemoteRegionPreview(): Promise<{ data: string; width: number; height: number } | null>;
  translateOffline(text: string, source: string, target: string): Promise<string>;
}

export const nativeModule = requireOptionalNativeModule<Ps5TranslatorNativeModule>("Ps5Translator");
export const nativeAvailable = nativeModule != null;

export function hasNative(fn: keyof Ps5TranslatorNativeModule): boolean {
  return nativeModule != null && typeof nativeModule[fn] === "function";
}

export const NATIVE_REQUIRED_MESSAGE =
  "PS5 bağlantısı ve TV sunucusu Android APK ile kullanılabilir.";
