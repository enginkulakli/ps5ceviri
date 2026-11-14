// Bridge to the (future) native Android module "Ps5Translator".
// It will contain: chiaki-ng Remote Play engine (JNI), MediaCodec -> ImageReader decoder,
// ML Kit OCR + on-device translation, the WebSocket server on :8787 and the foreground service.
// In Expo Go / web preview this module does NOT exist, so every capability reports
// "native build required" instead of pretending to work.
import { requireOptionalNativeModule } from "expo";

export type OcrRegion = { x: number; y: number; w: number; h: number };

export type DiscoveredConsole = { name: string; host: string; state: "ready" | "standby" };

export interface Ps5TranslatorNativeModule {
  // TV WebSocket server (tablet is the server, TV connects to ws://TABLET_IP:8787)
  startTvServer(port: number, allowedTvIp: string | null): Promise<void>;
  stopTvServer(): Promise<void>;
  getTvClientCount(): Promise<number>;
  sendSubtitle(text: string): Promise<void>; // sends exactly {"text":"..."}
  // PS5 Remote Play (chiaki-ng)
  discoverConsoles(timeoutMs: number): Promise<DiscoveredConsole[]>;
  registerConsole(host: string, psnAccountIdBase64: string, pin: string): Promise<{ registKey: string; morning: string }>;
  startSession(host: string): Promise<void>;
  stopSession(): Promise<void>;
  // OCR / pipeline
  configureOcr(region: OcrRegion, intervalMs: number, minConfidence: number): Promise<void>;
  // Offline translation (ML Kit)
  ensureOfflineModel(source: string, target: string): Promise<void>;
  translateOffline(text: string, source: string, target: string): Promise<string>;
}

export const nativeModule = requireOptionalNativeModule<Ps5TranslatorNativeModule>("Ps5Translator");
export const nativeAvailable = nativeModule != null;

export const NATIVE_REQUIRED_MESSAGE =
  "Bu özellik yerel Android modülü gerektirir (Remote Play motoru, MediaCodec, ML Kit, WebSocket sunucusu). Expo Go ve web önizlemede çalışmaz; Android derlemesi (Publish → Android build) gereklidir. Modül henüz uygulanmadı (Aşama 3–5).";
