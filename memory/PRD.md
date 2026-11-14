# PRD — PS5 Çevirmen (PS5 Real-Time Turkish Subtitle Translator)

## Original problem statement
Android tablet (Galaxy Tab S9 Ultra) app: connect to PS5 via Remote Play in background (video not shown), OCR English subtitles in bottom region, translate to Turkish, send `{"text":"..."}` to existing Samsung Tizen TV app via WebSocket at ws://TABLET_IP:8787 (format must not change). No fake connections; honest about what's not implemented. Phased plan (1–7). First output: feasibility, method comparison, hidden video processing, libraries/licenses, Emergent vs native scope, risks, then phased development.

## User choices
- Personal use only
- Remote Play: embed chiaki-ng engine (AGPL-3.0) via native module
- Translation: offline ML Kit (native) + online AI (implemented, Claude Haiku via Emergent LLM key)
- First delivery: technical report + working skeleton

## Architecture
- Backend FastAPI: `/api/translate` (Claude Haiku 4.5, glossary-preserving prompt, 15s timeout, Mongo cache), `/api/history` GET/DELETE (consecutive duplicate guard)
- Frontend Expo Router, dark theme, 4 tabs (Durum, Çeviri, Bağlantı, Ayarlar) + /report + /ocr-region
- `src/pipeline/subtitle.ts`: OCR normalize, noise filter (sensitivity), similarity dedupe, repeat window; `PipelineProvider`: latest-wins translation (AbortController + seq), clear-delay → `{"text":""}`
- `src/native/bridge.ts`: optional `Ps5Translator` native module contract; absent in Expo Go → honest "Yerel Derleme Gerektirir"
- Settings in local storage; PSN Account ID in secure store
- Full report: /app/memory/TECHNICAL_REPORT.md

## Implemented (2026-06)
- Phase 1 report (in-app + md)
- Phase 2 partial: UI, settings, OCR region editor, pipeline logic, TV payload format, online translation + cache + history, tablet IP display
- Tested: iteration 1 & 2 all pass

## Backlog
- P0: Expo local native module (Kotlin): Java-WebSocket server :8787 + foreground service (connectedDevice) + WifiLock
- P0: chiaki-ng lib via NDK/CMake + JNI: discovery, registration (Account ID + PIN), session
- P0: MediaCodec → ImageReader hidden decode, ROI Y-plane copy, change detection, ML Kit OCR
- P1: ML Kit offline EN→TR translation; PSN sign-in flow for Account ID
- P1: Delete single history item; confirm before clearing all; better model option for translation quality
- P2: HDMI capture card (UVC) Plan B input source
