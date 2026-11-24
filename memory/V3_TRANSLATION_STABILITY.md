# V3 görüntü ve altyazı kararlılığı

Kod değişikliklerinden önce IMG_9525 (17,30 sn) ve IMG_9526 (15,27 sn) saniyelik örneklerle incelendi. Kayıtlar HDMI ekranını dışarıdan çekiyor. Ham Remote Play karesini, OCR çıktısını ve istek zamanlarını içermiyor.

| Kayıt | Gözlem |
| --- | --- |
| IMG_9525, yaklaşık 0–4 sn | Ayar sayfasındaki kısa etiketlerin zemini metinden birkaç kat geniş. Kutular seçeneklerin arasına ve sağdaki açıklama alanına taşıyor. |
| IMG_9525, yaklaşık 5–7 ve 13–15 sn | Menü kapanırken veya ekran değişirken önceki kutular yeni görüntüde kısa süre görünüyor. |
| IMG_9526, yaklaşık 0–5 sn | Telefon penceresinin soluna ve sağına taşan şeritler görülüyor. Daha sık örneklemede telefon yaklaşık 5,4 sn'de kapanmışken eski satırlar hâlâ görünür durumda. Yaklaşık 5,6 sn'de bu satırlar kalkmış. |
| IMG_9526, yaklaşık 9–14 sn | Telefon ana sayfası ve mesaj listesi değişirken ayrı liste öğelerinin çevirileri uzak noktalarda ve geniş olarak çiziliyor. |

V2, normalize kaynak dikdörtgenini doğrudan yayınlıyordu. V3'te `TvOverlayLayout` 42 px / 1280 px geniş ekran varsayımıyla genişliğin alt sınırını yükseltiyor, sarılan satır sayısını düşürmek için genişliği yüzde 46'ya kadar büyütebiliyor ve kutunun merkezini yüzde 31'e kadar oynatabiliyordu. Bunlar hata payı olan yerleşim tahminleriydi ve aynı genişlik doğrudan `OverlayMessage.w` olarak gidiyordu. Mevcut TV, `w × alan genişliği` ve kendi okunabilir genişliğinden büyük olanını çiziyor. Koyu zemin bu genişliğin tamamını boyuyor. Gönderilen `h` çizim yüksekliğini belirlemiyor. Kamera görüntülerinden ekran ölçeği veya overscan için kesin bir sonuç çıkarılamaz.

V3, komşu hizalı OCR satırlarını farklı ML Kit bloklarından da birleştirebiliyordu. Yeni ve uzun blok önce işlenirse aynı konumdaki daha kısa ama tam metin eşleşmesini yerinden edebiliyordu. Eşleşmeyen kutular varsayılan olarak yaklaşık iki saniye kalıyordu. Kesme tespiti 48 tüm-ekran parlaklık örneğinin 19'unda büyük değişim aradığından telefon bölgesinin değişmesi tek başına yeterli değildi.

İki kayıt tam ekran modunda. Bildirilen yaklaşık yüzde 10 altyazı atlama oranı veya bu oranın OCR ile API arasındaki dağılımı bu videolardan ölçülemez. Kaynakta birbirinden bağımsız üç kayıp yolu bulunuyor. Varsayılan iki eşleşme, tek OCR örneğinde görülen satırı çeviri işlemine hiç sokmuyor. İzlenen satır kaybolduktan sonra geciken SQLite dönüşü canlı isteği başlatmadan sonlanıyor ve aynı metne bir saniye engel koyuyordu. Hazır yeni satır, önceki satırın varsayılan 700 ms görünme süresi dolmadan yayınlanmıyordu ve bu aralıkta üçüncü bir satır tarafından atlanabiliyordu. İki eski istek bütün çalışma kapasitesini kullanabiliyordu. Bunlar koddan yeniden üretilebilir koşullardır.

## Android düzeltmeleri

- `SmartOverlay.kt` her kutunun TV'ye giden genişliğini metne göre hesaplar ve yüzde 22 ile sınırlar. Çakışma hesabı dört mevcut TV yazı boyutunda, 1920 × 1080 CSS alanı varsayımıyla ayrıca yapılır. Yatay merkez kaynakta tutulur. Dikey hareket en fazla ekran yüksekliğinin yüzde 3,6'sıdır. Fiziksel olarak sığmayan kutular elenir. Çakışan komşu gruplar, ayrı OCR blokları ve tamamlanmış cümleler daha ihtiyatlı birleştirilir.
- `OverlayTracker.kt` en yakın bire bir metin eşleşmelerini konumsal yedek eşleşmeden önce yapar. Ekranda kalan metin, yeni görüntüde kimliğini korur. Kaybolan kutular, küresel veya kendi alanındaki belirgin parlaklık değişiminde ilk OCR gözleminde silinir. Benzer görüntüde tek kaçırılan OCR taraması tolere edilir. `OverlayMessage.kt` küçük konum dalgalanmalarını filtreler ve belirgin hareketi doğrudan takip eder. WebSocket JSON'u, altyazı yenilemesi ve TV TTL davranışı korunur.
- `SubtitleTracker.kt` varsayılan iki doğrulama ayarında, ortada algılanan ve satır güveni en az 0,80 olan güvenilir metni tek okumada kabul edebilir. Muhtemel karakter hataları ve düşük güvenli okumalar tekrar gerektirir. Kullanıcının daha katı doğrulama ayarları korunur. `RemoteSubtitlePipeline.kt` normal 300 ms veya daha sık OCR ayarında yeni görülen altyazı civarında 180 ms'ye kadar daha sık tarar. Yeni hazır altyazı gecikmeden yayınlanır. Minimum süre, satır kaybolduğunda koruma sağlar.
- `TranslationAttempts.kt` mevcut metnin çeviri isteğini birleştirir ve kapasite dolduğunda hâlâ görünen metinleri korur. Başlamamış eski arşiv sorgusunun devamı ve eski Google Cloud isteğinin bağlantısı iptal edilebilir. Başlamış ML Kit çevirileri mevcut kapasite içinde tamamlanır. Geç sonuç gösterilmeden önce güncel kaynak tekrar kontrol edilir. RAM önbelleği, SQLite seçimi ve arşiv biçimi korunur.
- `GoogleCloudTranslation.kt` Google Cloud Basic v2 isteğinin gövdesini ve yanıtını doğrular. Mevcut anahtar saklama, kullanım hesabı ve bağlantı testi korunur. `TranslationMetrics.kt` milisaniye kesirlerini korur. RAM ölçümü anahtar sorgularını, Google Cloud ölçümü HTTP yazımından yanıtın ayrıştırılmasına kadar geçen süreyi kapsar.
- `app.json` sürümü 3.0.1 ve sürüm kodu 4 olarak ayarlar. `android-ocr-build.yml` düzeltme dalından APK oluşturur.

## Otomatik doğrulama

| Senaryo | Test |
| --- | --- |
| Sabit menü ve aynı metnin sürekli görünmesi | JVM OCR gruplama, sabit kimlik ve RAM erişimi. Tekrar eden metin için işlemdeki istek korunur. |
| Hızlı menü seçimi, hareket, sahne değişimi | Bire bir kimlik, yerinde içerik değişimi, küçük koordinat oynaması, belirgin hareket, yerel ve tüm ekran geçişi. |
| Telefon mesajı ve uzun Türkçe metin | Android'in ürettiği gerçek JSON anlık görüntülerini mevcut TV JavaScript ve CSS'i masaüstü Chromium'da çizer. 1920 × 1080 test alanında iki sahne ve dört yazı boyutunda kutular çakışmadı. |
| Hızlı altyazılar | Tek güvenilir OCR okuması, güveni zayıf ilk okuma, karakter hatası, anlamlı son ek, TTL sınırı ve önceki 700 ms bekleme penceresindeki yeni hazır metin. |
| Google Cloud | Gerçek Kotlin Basic v2 gövde kodu ve temsili JSON yanıtı, eksik yanıt, altı HTTP hata durumu ve eski istek iptalinin güncel sonucu koruması. |
| Arşivden hazır çeviri | Gerçek Android SQL şemasıyla eşleşme ve yokluk, motor kapsamı, paket önceliği, geri alma ve 50.000 satırlık sorgunun indeks planı. |
| TV protokolü | Mevcut Tizen JavaScript'i gerçek JVM WebSocket aktarımıyla mod değiştirir. 38 TCP senaryosu TTL, tekrar yenileme, konumsal kutular, yeniden bağlanma ve 90 saniyelik aktarımı kapsar. |

`tests/native/StabilityChecks.kt`, `test_tv_geometry.py`, `CloudErrorChecks.java`, `test_v3_archive_sql.py`, `test_tv_protocol.cjs`, `test_ws_server.py` ve `run_overlay_benchmark.sh` kullanıldı. Chromium doğrulaması için Python Playwright, yerel Chromium ve derlenmiş JVM test sınıf yolu gerekir.

Uygulamadaki ölçüm ekranı OCR, metin karşılaştırma, RAM, SQLite, Google Cloud ve WebSocket aşamalarını ayrı gösterir. Masaüstü deneyleri gerçek PS5 OCR süresini, tablet pil tüketimini veya Google ağı süresini ölçmez. Android TV'ye gönderim kuyruğunu ölçebilir. Mevcut TV protokolünde televizyonun çizim anı bildirilmez. Fiziksel UE65NU7100'de boyut, yerleşim ve gerçek altyazı atlama oranı yeniden ölçülmelidir.

### Test makinesi ölçümleri

| Aşama | p50 / p95 | Kapsam |
| --- | ---: | --- |
| Metin karşılaştırma ve izleme, sabit menü | 0,037 / 0,427 ms | Hazır OCR girdisi, JVM, sabit yerleşimi yeniden kullanır |
| Metin karşılaştırma ve izleme, telefon | 0,080 / 0,819 ms | Hazır OCR girdisi, JVM, sabit yerleşimi yeniden kullanır |
| RAM erişimi | <0,001 / 0,001 ms | Kare başına üç anahtar, 11.000 denemede sıfır kaçırma |
| SQLite sorgusu | 0,032 / 0,053 ms | 50.000 satır, indeks kullanımını kontrol eden test |
| WebSocket kuyruğuna yazım | 0,112 / 1,058 ms | 90 saniyede 1.746 iletinin tamamı test istemcisine ulaştı |
| OCR ve Google Cloud HTTP | Cihaz/ağ ölçümü bekleniyor | Uygulama, çalışırken ayrı aşamalar olarak kaydeder |

Sentetik menüde Android'in gönderdiği en geniş kutu ekranın yüzde 32,0'sinden yüzde 12,8'ine, telefon senaryosunda yüzde 46,6'sından yüzde 17,6'sına indi. V3 ve düzeltmenin JVM yerleşim p95 süreleri menüde sırasıyla 0,464 ve 0,474 ms, telefonda 0,061 ve 0,059 ms. Bu iki senaryo kamera kayıtlarından türetilen geometrileri kullanır. Yüzdeler WebSocket dikdörtgenleridir. TV çizimi ayrıca kendi okunabilir minimum genişliğini uygular.
