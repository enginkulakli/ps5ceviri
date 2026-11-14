# PS5 Gerçek Zamanlı Türkçe Altyazı Çevirmeni — Teknik Rapor (Aşama 1)

## 1. Yapılabilirlik
Kişisel kullanım için teknik olarak yapılabilir, AMA yalnızca yerel Android modülüyle (Kotlin + NDK/C).
Sony Remote Play protokolü belgelenmemiştir; çalışan tek açık uygulama chiaki / chiaki-ng ailesidir.
Expo Go ve web önizlemesinde Remote Play, MediaCodec, ML Kit ve tabletteki WebSocket sunucusu çalışmaz.

## 2. Remote Play yöntem karşılaştırması
| Yöntem | Sonuç | Not |
|---|---|---|
| Sony resmi PS Remote Play uygulaması | Uygun değil | Herkese açık API/Intent/SDK yok. Sadece görünür ekranı MediaProjection ile kaydetmek mümkün → "tablette görünmesin" şartını bozar; FLAG_SECURE varsa kareler siyah gelir. |
| Protokolü sıfırdan yazmak | Çok riskli | Kayıt (AES/ECDH), Takion UDP, Senkusha, FEC, şifreli akış. Pratikte chiaki'yi yeniden yazmak; aylar sürer. |
| **chiaki-ng çekirdeğini (lib/, C) JNI ile gömmek** | **Seçildi** | PS5, H.264/HEVC, PIN + PSN Account ID kaydı, PSN üzerinden holepunch. AGPL-3.0 (+OpenSSL istisnası). Kullanıcı ayrı uygulama açmaz. |
| Mevcut Android istemcilerinden JNI katmanı (chiaki-ng-android-extended, Pleikkari, Momiji) | Referans | Aynı motor; entegrasyonu hızlandırır. Hepsi AGPL-3.0. |
| HDMI yakalama kartı (USB UVC) | Plan B | PS5 → HDMI splitter → TV + UVC kart → tablet. PSN/protokol riski yok; ek donanım + HDCP kapatma gerekir. |

## 3. Görüntüyü ekranda göstermeden işleme
chiaki video callback (NAL) → MediaCodec (KEY_LOW_LATENCY) → `ImageReader(YUV_420_888, maxImages=2).surface` (görünür View yok)
→ HandlerThread'de `acquireLatestImage()` + OCR sıklığına göre kare atlama → sadece ROI'nin Y düzlemi önceden ayrılmış tek ByteArray'e
→ değişim algılama (küçültülmüş parlaklık özeti) → ML Kit `InputImage.fromByteArray(NV21)` → çeviri → WebSocket.
Bitmap yok, UI thread yok.

## 4. Kütüphaneler ve lisanslar
- chiaki-ng lib — AGPL-3.0 + OpenSSL istisnası
- OpenSSL 3 — Apache-2.0
- Opus (BSD-3), Jerasure/gf-complete (BSD-3), nanopb (zlib), json-c (MIT), curl (curl/MIT), miniupnpc (BSD)
- Google ML Kit Text Recognition v2 — ücretsiz, ML Kit Şartları, cihaz içi
- Google ML Kit Translation — ücretsiz, cihaz içi (~30 MB EN→TR modeli)
- Java-WebSocket (TooTallNate) — MIT (tablette :8787 sunucu)
- Expo / React Native — MIT
- Çevrimiçi YZ çeviri (Claude Haiku, Emergent LLM anahtarı) — isteğe bağlı, kullanım başına kredi, internet gerekir

## 5. Emergent'te yapılabilenler / native gerektirenler
Hazır (bu projede): arayüz, ayarlar, altyazı karar mantığı, çevrimiçi çeviri + önbellek + geçmiş, tablet IP gösterimi, `{"text":"..."}` formatı.
Native gerektirir (Expo yerel modülü, Publish → Android build ile derlenir, gerçek PS5 ile test edilir):
WebSocket sunucusu, PS5 keşif/kayıt/oturum, MediaCodec+ImageReader, ML Kit OCR ve çevrimdışı çeviri, foreground service (connectedDevice), WakeLock/WifiLock.

## 6. Riskler
- PS5 yazılım güncellemeleri protokolü bozabilir (yüksek).
- PSN Account ID gereklidir; şifre uygulamada saklanmaz.
- Resmi olmayan istemci → mağazada yayınlanması uygun değil; kişisel kullanım.
- Bazı içeriklerde PS5 Remote Play görüntüsünü kısıtlayabilir.
- Gecikme tahmini: 0,3–1,8 sn (çevrimdışı çeviriyle alt sınıra yakın).
- Samsung pil yöneticisi: uygulama "Hiç uyutma" listesine eklenmeli.
- Tizen tarafı ws:// (şifresiz): sunucu Wi-Fi arayüzüne bağlanır, isteğe bağlı TV IP izin listesi.

## 7. Native modül sözleşmesi
`frontend/src/native/bridge.ts` → `Ps5Translator` modülü: startTvServer, stopTvServer, getTvClientCount, sendSubtitle,
discoverConsoles, registerConsole, startSession, stopSession, configureOcr, ensureOfflineModel, translateOffline.
