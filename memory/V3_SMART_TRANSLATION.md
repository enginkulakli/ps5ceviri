# PS5 Çevirmen V3

V3 Android dalı `feature/v3-smart-translation`, V2 Android referansı `1d0cfac`, mevcut Tizen V2 referansı `7032502`. [Cihaz videosu incelemesi](V3_DEVICE_VIDEO_ANALYSIS.md).

## Uygulama

- **Ayarlar → Çeviri arşivi** üzerinden bir oyun profili oluşturulabilir veya genel arşiv seçilebilir. Oyuna özel eşleşme önceliklidir. Genel arşive dönüş ve motorlar arası yeniden kullanım ayrı ayarlardır. Oyun sözlüğü değişince otomatik kayıtların sürüm anahtarı değişir. Kullanıcının düzenlediği çeviriler ve hazırlanmış paket kayıtları sözlük sürümünden bağımsızdır. Arşiv araması, düzenleme, silme ve Android dosya seçicisiyle dışa veya içe aktarma burada yapılır.
- **Ayarlar → Google Cloud Translation** üzerinden kendi Basic v2 anahtarınızı girip kaydedin, isterseniz görünürlüğünü açın veya anahtarı silin. **Bağlantıyı test et** düğmesi Google'a beş karakterlik bir `Hello` çeviri isteği gönderir ve kullanım sayacına ekler. Başarılı testten sonra Remote Play için Google Cloud motorunu seçebilirsiniz. Varsayılan motor cihazdaki ML Kit'tir. AI sağlayıcısı yapılandırılmayı bekler.
- **Ayarlar → Çeviri ölçümleri** üzerinden her aşama için son süre ile p50 ve p95 değerleri görüntülenebilir. WebSocket ölçümü Android gönderim kuyruğuna kadardır. TV'nin mevcut protokolü çizim onayı içermez.
- Google Cloud sayaçları bu kurulumun UTC gün ve ay toplamlarında, gönderimden hemen önce ayrılan Unicode karakterleri ve istekleri gösterir. Başarısız veya zaman aşımına uğrayan istekler ihtiyatlı biçimde sayılır. Bu sayaçlar Google Cloud üzerinde kesin harcama sınırı oluşturmaz. Anahtar Android Keystore ile şifrelenen ve cihaz yedeği dışında tutulan bir dosyada saklanır.

Google [kimlik doğrulama belgelerinde](https://docs.cloud.google.com/translate/docs/authentication) API anahtarıyla kullanımın Cloud Translation Basic v2 için desteklendiğini belirtir. Kullanılan uç nokta [Basic v2 translate](https://docs.cloud.google.com/translate/docs/reference/rest/v2/translate) kaynağıdır. İstek Google'a doğrudan HTTPS üzerinden gider.

## Arşiv biçimi

UTF-8 JSON, üst düzey `format: "ps5ceviri-interchange"` ve `version: 1` alanları ile `games` ve `records` listeleri. Oyun kimliği ve adı oyun listesinde bulunur. Kayıtlar oyun kimliğini, kaynak dili `en`, kaynağı, Türkçe karşılığı, motoru, Unix milisaniye oluşturulma zamanını, çeviri sürümünü, isteğe bağlı bağlamı ve kullanıcı düzenlemesi bayrağını taşır. Normalize anahtar içe aktarırken kaynaktan yeniden hesaplanır. Hazır paket çevirileri `engine: "package"` ile işaretlenebilir. Kimlik bilgileri ve kullanım sayaçları bu biçimin alanları arasında yer almaz.

İçe aktarma tek SQLite işlemi içinde birleştirir. Eşleşen oyun kimliği korunur. Oyun, dil, normalize kaynak, motor ve sürüm eşleşen çeviri atlanır. Geçersiz kayıt paketin tamamını geri alır. Bir içe aktarma 24 MiB, 100.000 kayıt ve 10.000 oyunla sınırlıdır. Bu sınırı aşan arşivleri oyun bazında dışa aktarıp aktarın. Tek oyun da sınırı aşabiliyorsa büyük paketleri bölme işlemi sonraki sürüm çalışmasıdır.

Canlı iş hattı RAM'deki 180 öğelik LRU ile başlar. Ayrı bir iş parçacığı indeksli SQLite araması yapar. ML Kit için 28 ms sonra canlı motor devralabilir. Google Cloud önce kesin arşiv sonucu bekler. Bu davranış yavaş bir disk aramasında arşiv kaydına rağmen ücretli istek gönderilmesini önler. Yeni sonuç, görüntüleme işlemi ele alındıktan sonra arka planda kaydedilir.

## Ana makine doğrulaması

| Sentetik ekran | V2 p50 / p95 | V3 p50 / p95 | V2 → V3 kaynak adayı | V2 → V3 kutu |
| --- | ---: | ---: | ---: | ---: |
| Sabit menü | 0,009 / 0,026 ms | 0,019 / 0,075 ms | 6 → 3 | 5 → 3 |
| Telefon mesajları | 0,010 / 0,023 ms | 0,041 / 0,115 ms | 4 → 3 | 5 → 3 |

JVM mikro ölçümü aynı sentetik OCR girişlerini ve V2'nin gerçek takip kodunu kullanır. V3'e satır gruplama ve yerleşim dahil edilmiştir. p50 ve p95, 300 ısınma karesinden sonraki 1.200 örnektendir. V3'ün bu örneklerde OCR sonrası CPU işi daha uzun sürerken kaynak adayları ve kutu sayısı azalmıştır. 50.000 satırlık ana makine SQLite arşivinde indeksli arama p50 0,032 ms ve p95 0,055 ms ölçülmüştür. Bu sayılar cihaz OCR, çeviri, ağ, pil veya HDMI çizim sürelerini ölçmez.

- `tests/native/Harness.kt` ve `SmartOverlayChecks.kt` hızlı altyazı değişimini, menü ikon temizliğini, telefon satırlarını, konum güncellemesini, tekrarı, eski blokların kalkmasını ve çakışma tahminini sınar.
- `tests/native/test_ws_server.py` TCP üzerinden TTL, yenileme, yeniden bağlanma, eski istemci ve 90 saniyelik aktarım testlerini çalıştırır. `tests/native/test_tv_protocol.cjs` mevcut Tizen JavaScript'ini Android JVM sunucusuyla çalıştırarak iki mod arasında geçişi ve bağlantı kesilmesini sınar.
- `python3 tests/native/test_v3_archive_sql.py` gerçek arşiv şemasını ve arama koşullarını kullanır. Büyük arşiv, eşleşme ile yokluk, oyun sınırı, motor seçimi, yayım paketi, yinelenen kayıt ve geri alma durumlarını sınar.
- `tests/native/CloudErrorChecks.java` HTTP 400, 401, 403, 429, 503 ve bilinmeyen durum kodunun kullanıcıya gösterilen metnini sınar. Çalıştırmak için derlenmiş Android Kotlin sınıflarını ve Kotlin stdlib'i sınıf yoluna ekleyin.
- JVM kıyaslaması için `KOTLIN_COMPILER_DIR` ve `KOTLIN_STDLIB` değerlerini girip `bash tests/native/run_overlay_benchmark.sh` çalıştırın.

## Cihazda doğrulama

Aynı PS5 menüleri ve telefon mesajlarında V2/V3 karşılaştırması, UE65NU7100'de farklı yazı boyutları, ağ kesintisi sonrası devam, Android arka planı, uzun oturum kaynak tüketimi, arşiv SAF dosya gidiş dönüşü ve gerçek Google Cloud anahtarının bağlantısı cihaz doğrulaması gerektirir. WebSocket'ten HDMI ekrandaki ilk çizime kadar süre TV protokolünden ölçülemediğinden cihaz videosu veya ayrı TV enstrümantasyonu gerekir. Yüksek yoğunluklu sahnelerde mevcut TV'nin yazı yüksekliği ve yerleşimi kesin olarak Android'e bildirilmediği için düşük öncelikli kutular sığdırılamadığında elenir. Konsol veya TV'nin HDMI ölçek/overscan ayarı ile fiziksel görüntü alanı ayrıca doğrulanmalıdır.
