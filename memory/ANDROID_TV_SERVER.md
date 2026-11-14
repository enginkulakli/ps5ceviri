# Görev 1 — Android TV Sunucusu: Yapı, Testler, Kurulum

## Oluşturulan native Android kodu (Kotlin) — `frontend/modules/ps5-translator/`
| Dosya | Görev |
|---|---|
| `expo-module.config.json` | Expo yerel modülü (otomatik bağlanır, `modules/` klasörü) |
| `android/build.gradle` | Kütüphane modülü, bağımlılık: `org.java-websocket:Java-WebSocket:1.6.0` (MIT) |
| `android/src/main/AndroidManifest.xml` | İzinler + `SubtitleServerService` (foregroundServiceType=connectedDevice) + `BootReceiver` |
| `core/SubtitleMessage.kt` | `{"text":"..."}` kodlayıcı (format değişmedi, UTF-8, JSON kaçışları) |
| `core/SubtitleServerCore.kt` | WebSocket sunucusu: 0.0.0.0:8787, SO_REUSEADDR, ping/pong 15 sn, TV IP izin listesi, aynı IP'den yeniden bağlanınca eski bağlantıyı kapatma, yeni bağlanan TV'ye son altyazıyı tekrar gönderme, aynı altyazıyı tekrar göndermeme, port hatası raporu |
| `ServerController.kt` | Süreç genelinde tek sunucu + durum dinleyicileri |
| `SubtitleServerService.kt` | Foreground service, kalıcı bildirim ("Durdur" düğmeli), WifiLock (LOW_LATENCY), PARTIAL_WAKE_LOCK, Wi-Fi/IP değişimi izleme, START_STICKY (sistem öldürürse yeniden başlar), ayrı HandlerThread |
| `BootReceiver.kt` | Kullanıcı açtıysa yeniden başlatma / uygulama güncellemesi sonrası sunucuyu başlatır |
| `ServerPrefs.kt` | Port, izinli TV IP, açılışta başlat tercihi |
| `NetworkUtils.kt` | Wi-Fi/Ethernet IPv4 adresini bulur |
| `Ps5TranslatorModule.kt` | JS köprüsü: getServerState, startTvServer, stopTvServer, sendSubtitle(text, force), getTvClientCount, setAllowedTvIp, setStartOnBoot, isIgnoringBatteryOptimizations, requestIgnoreBatteryOptimizations, `onServerState` olayı |

## İzinler (hepsi gerekçeli, gereksiz izin yok)
INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_NETWORK_STATE (connectedDevice tipi için ön koşul, normal izin),
FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE, WAKE_LOCK, POST_NOTIFICATIONS (Android 13+, çalışma anında istenir),
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (kullanıcı düğmeye basınca), RECEIVE_BOOT_COMPLETED.
Konum, kamera, mikrofon, depolama İSTENMEZ.

## Yapılan testler
### Gerçekten çalıştırılan (bu ortamda)
- `SubtitleServerCore` + `SubtitleMessage` Kotlin kodu, uygulamaya giren dosyaların AYNISI, JVM üzerinde derlendi ve gerçek
  WebSocket istemcileriyle test edildi: `tests/native/test_ws_server.py` → **24/24 başarılı**
  (başlatma, TV yokken gönderim, bağlanan TV'ye son altyazı, Türkçe UTF-8, tam format, tekrar engelleme, zorla test,
  boş altyazı `{"text":""}`, JSON kaçışları, sıra, 2 cihaz yayını, kopma algılama, yeniden bağlanma, eski bağlantının
  kapatılması, izin listesi ret/kabul, durdur/yeniden başlat aynı port, port meşgul hatası, **90 sn kararlılık: 1786 mesaj, 0 kayıp**).
  İstemciler konteynerin ağ IP'sinden (loopback değil) bağlandı.
- Android'e özel dosyalar (Service, BootReceiver, NetworkUtils, ServerPrefs, ServerController) `android-36` SDK'sına
  karşı `kotlinc` ile **hatasız derlendi**.
- Çeviri: "Get to the boat, quickly!" → "Tekneye git, çabuk!" (her iki kalite modunda).

### Bu ortamda YAPILAMAYAN (dürüst not)
- Tam APK derlemesi: konteyner ARM64; Android build-tools (aapt2) ve NDK burada çalışmıyor. APK, Emergent derleme hattında
  (Publish → Android build) üretilir.
- `Ps5TranslatorModule.kt` expo-modules-core'a bağlı olduğu için tek başına derlenemedi; API kullanımı SDK 58'in
  expo-network modülüyle birebir aynı kalıpta yazıldı. İlk APK derlemesinde doğrulanacak.
- Gerçek tablet, gerçek TV, gerçek Wi-Fi, arka plan / ekran kapalı, Wi-Fi değişimi, yeniden başlatma testleri:
  fiziksel cihaz gerektirir. Aşağıdaki kontrol listesiyle yapılmalı.

## APK nasıl alınır ve kurulur
1. Sağ üstte **Publish → Deploy**, ardından **Android build** oluşturun (APK seçeneği).
2. Derleme bitince APK bağlantısını tablette açın (veya bilgisayara indirip USB ile kopyalayın).
3. Tablette: Ayarlar → Uygulamalar → (kullandığınız tarayıcı/Dosyalarım) → **Bilinmeyen uygulamaları yükle** → izin ver.
4. APK dosyasına dokunup **Yükle**.
5. Uygulamayı açın → Durum → **Sunucuyu başlat** → bildirim izni açıklaması → izin verin.
6. Ayarlar → **Pil optimizasyonundan muaf tut**; Samsung: Ayarlar → Pil → Arka plan kullanım sınırları → Hiç uyutulmayan uygulamalar.
7. TV uygulamasında `ws://<ekranda görünen tablet IP>:8787` adresini kullanın.

## Gerçek cihaz test listesi
1. Sunucu başlat → bildirimde "TV sunucusu çalışıyor · ws://IP:8787".
2. Aynı Wi-Fi'deki bilgisayardan: `python3 -c "import asyncio,websockets;asyncio.run((lambda: None)())"` yerine
   `websocat ws://TABLET_IP:8787` veya tarayıcı konsolunda `new WebSocket('ws://TABLET_IP:8787').onmessage=e=>console.log(e.data)`.
3. Manuel altyazı gönder → TV'de görünür. "Boş gönder" → TV temizlenir.
4. TV uygulamasını kapat/aç → panelde TV sayısı 0 → 1, son altyazı TV'ye yeniden gelir.
5. Ana ekrana dön, ekranı kapat, 10 dk bekle → Çeviri sekmesinden metin gönder → TV'de görünür.
6. Wi-Fi'yi kapat/aç → panelde "Son bağlantı hatası" Wi-Fi mesajı, TV yeniden bağlanır.
7. Tableti yeniden başlat (Ayarlar'da Otomatik bağlantı + Arka planda çalış açıksa) → sunucu kendiliğinden başlar.
