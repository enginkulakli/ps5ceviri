# V3 görüntü ve altyazı kararlılığı

Kod değişikliklerinden önce IMG_9525 (17,30 sn) ve IMG_9526 (15,27 sn) saniyelik örneklerle incelendi. Kayıtlar HDMI ekranını dışarıdan çekiyor. Ham Remote Play karesini, OCR çıktısını ve istek zamanlarını içermiyor.

| Kayıt | Gözlem |
| --- | --- |
| IMG_9525, yaklaşık 0–4 sn | Ayar sayfasındaki kısa etiketlerin zemini metinden birkaç kat geniş. Kutular seçeneklerin arasına ve sağdaki açıklama alanına taşıyor. |
| IMG_9525, yaklaşık 5–7 ve 13–15 sn | Menü kapanırken veya ekran değişirken önceki kutular yeni görüntüde kısa süre görünüyor. |
| IMG_9526, yaklaşık 0–5 sn | Telefon penceresinin soluna ve sağına taşan şeritler, telefon kapandıktan hemen sonra da görülüyor. |
| IMG_9526, yaklaşık 9–14 sn | Telefon ana sayfası ve mesaj listesi değişirken ayrı liste öğelerinin çevirileri uzak noktalarda ve geniş olarak çiziliyor. |

V2, normalize kaynak dikdörtgenini doğrudan yayınlıyordu. V3'te `TvOverlayLayout` 42 px / 1280 px geniş ekran varsayımıyla genişliğin alt sınırını yükseltiyor, sarılan satır sayısını düşürmek için genişliği yüzde 46'ya kadar büyütebiliyor ve kutunun merkezini yüzde 31'e kadar oynatabiliyordu. Bunlar hata payı olan yerleşim tahminleriydi ve aynı genişlik doğrudan `OverlayMessage.w` olarak gidiyordu. Mevcut TV, `w × alan genişliği` ve kendi okunabilir genişliğinden büyük olanını çiziyor. Koyu zemin bu genişliğin tamamını boyuyor. Gönderilen `h` çizim yüksekliğini belirlemiyor. Kamera görüntülerinden ekran ölçeği veya overscan için kesin bir sonuç çıkarılamaz.

V3, komşu hizalı OCR satırlarını farklı ML Kit bloklarından da birleştirebiliyordu. Yeni ve uzun blok önce işlenirse aynı konumdaki daha kısa ama tam metin eşleşmesini yerinden edebiliyordu. Eşleşmeyen kutular varsayılan olarak yaklaşık iki saniye kalıyordu. Kesme tespiti 48 tüm-ekran parlaklık örneğinin 19'unda büyük değişim aradığından telefon bölgesinin değişmesi tek başına yeterli değildi.

İki kayıt tam ekran modunda. Bildirilen yaklaşık yüzde 10 altyazı atlama oranı veya bu oranın OCR ile API arasındaki dağılımı bu videolardan ölçülemez. Kaynakta birbirinden bağımsız üç kayıp yolu bulunuyor. Varsayılan iki eşleşme, tek OCR örneğinde görülen satırı çeviri işlemine hiç sokmuyor. İzlenen satır kaybolduktan sonra geciken SQLite dönüşü canlı isteği başlatmadan sonlanıyor ve aynı metne bir saniye engel koyuyordu. Hazır yeni satır, önceki satırın varsayılan 700 ms görünme süresi dolmadan yayınlanmıyordu ve bu aralıkta üçüncü bir satır tarafından atlanabiliyordu. İki eski istek bütün çalışma kapasitesini kullanabiliyordu. Bunlar koddan yeniden üretilebilir koşullardır.
