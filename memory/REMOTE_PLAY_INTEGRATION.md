# PS5 Remote Play, tablet ve Tizen

Android dalı `feature/ps5-remote-play-subtitles`, `feature/screen-ocr-foundation` dalındaki `05b22cd` commit’inden açıldı. TV dalı `feature/tizen-subtitle-lifecycle`, TV deposunun `ce5705a` commit’inden açıldı. Android’de mevcut Expo arayüzü, tablet ekranı OCR seçeneği, manuel API çevirisi ve yerel TV sunucusu kullanılıyordu. Gömülü PS5 kaydı ve video oturumu eksikti. TV’nin mevcut `TVWindow` HDMI katmanı ve WebSocket bağlantısı cihazda daha önce çalışıyordu.

## Kullanım

1. PS5’te Remote Play’i etkinleştir. Tablet ve Samsung TV aynı yerel ağda olsun.
2. Android’de **Bağlantı** sekmesini aç. Ağda bulunan PS5’i seç veya IPv4 adresiyle ekle. PSN Account ID’yi 8 baytlık değerin Base64 gösterimi olarak kaydet. PS5’te **Ayarlar → Sistem → Remote Play → Cihazı Eşle** ekranındaki sekiz haneli PIN’i gir ve konsolu eşleştir.
3. **Durum → Çeviriyi Başlat** seçeneğine bas. İlk kullanımda ML Kit çeviri modeli için internet gerekir. Sonraki kullanımlarda PS5 ve TV ile yerel ağ bağlantısı yeterlidir.
4. Tizen uygulamasında doğru HDMI girişini seç. Tabletin **Bağlantı** ekranındaki IP adresi ve portla tablete bağlan. Altyazı görünümünü TV kumandasıyla ayarla.
5. Tablette başka uygulamaya geç. Çeviriyi uygulamadan veya Android bildiriminden durdurabilirsin. TV sunucusunu ayrı olarak yönetebilirsin.

PS5’te yerel DualSense ile oynamaya devam etmek için, gerekirse Remote Play oturumunu PS5’te etkin olan ikinci bir PSN hesabıyla eşleştir. Aynı hesapla DualSense kullanımı Remote Play’i sonlandırabilirse gerçek cihaz testinde bu senaryoyu ayrı doğrula. Eşleştirme anahtarları PS5’in girilen IPv4 adresine bağlıdır. Konsolun IP adresi değişirse yeni adresle tekrar eşleştir.

## Mimari

- chiaki-ng’nin sabitlenmiş sürümü yerel PS5 kaydı ve H.264 720p/30 oturumu için kullanılır. PSN Account ID Expo SecureStore’da, eşleştirme anahtarları Android Keystore tabanlı AES-GCM şifreli dosyada saklanır. PIN kalıcı depoya yazılmaz.
- Görüntü Android foreground service’inde MediaCodec ile çözülür. OCR yalnızca seçilen bölgenin parlaklık düzlemini bellekte ML Kit’e verir. Varsayılan bölge alt %28, aralık 300 ms’dir. Ayarlar bir sonraki oturumda okunur.
- Bir altyazının kabul edilmesi için iki uyumlu OCR gözlemi gerekir. Aktif metnin yeniden görülmesi zaman aşımını uzatır. Eski çevirinin sonucu sonradan dönerse yayınlanmaz. Çeviriler oturum içinde önbelleğe alınır.
- TV’ye `text` alanı ve isteğe bağlı `ttlMs` gönderilir. Sürüm 2 TV istemcileri aynı altyazının kalma süresini `refresh` mesajıyla uzatır. Varsayılan TTL 2 saniyedir. Boş `text` temizler. Süresi geçen altyazı tekrar bağlanan istemciye gönderilmez.
- Doğrudan akış ML Kit EN→TR kullanır. Deneysel API seçeneği manuel metin testinde kullanılabilir.

## Android ARM64 derlemesi

Node.js 22+, Yarn Classic, JDK 21, Python 3 ve Protobuf derleyicisi gerekir. Android SDK’ya `platforms;android-37.0`, `build-tools;37.0.0`, `ndk;28.2.13676358` ve `cmake;3.22.1` kur. Python ortamına `protobuf` yükle ve `protoc` komutunu PATH’e ekle.

```sh
git submodule update --init third_party/chiaki-ng
git -C third_party/chiaki-ng submodule update --init \
  third-party/nanopb third-party/jerasure third-party/gf-complete third-party/curl
cd frontend
yarn install --frozen-lockfile
CI=1 yarn expo prebuild --platform android
cd android
./gradlew assembleRelease -PreactNativeArchitectures=arm64-v8a \
  -Pandroid.enableMinifyInReleaseBuilds=false
```

APK yolu `frontend/android/app/build/outputs/apk/release/`. Expo release yapılandırması kurulum testi için Android’in geliştirme anahtarını kullanır. Mevcut APK farklı bir anahtarla imzalanmışsa Android aynı paket adıyla bunun üzerine kuruluma izin vermez.

Android uygulamasının kaynak lisansı [AGPL-3.0-only](../frontend/LICENSE). chiaki-ng’nin OpenSSL bağlantı istisnası ve bağımlılık bildirimleri [frontend/licenses](../frontend/licenses) altında yer alır.

## Gerçek cihaz doğrulaması

- PS5 eşleştirme, PIN’siz sonraki bağlantı ve konsol yazılımı ile oturum uyumluluğu.
- Tablet başka bir uygulamadayken ve ekran kapalıyken kare akışı, OCR ve çeviri.
- HDMI üzerinde yerel DualSense ile oynarken Remote Play oturumunun devam etmesi.
- İngilizce replik görünürken Türkçe metnin kalması, replik bittiğinde temizleme ve ağ kesintisinden sonra yeniden bağlantı.
- UE65NU7100’de Samsung TV sertifikası ile WGT kurulumu, HDMI arka katmanı, kumanda ve görünüm ayarları.
- Farklı oyunların yazı boyutları, OCR gecikmesi, pil tüketimi ve uzun oturumlarda kararlılık.
