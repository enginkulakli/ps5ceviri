# V3 gerçek cihaz video incelemesi

Kod değişikliklerinden önce üç kayıt ikişer saniyelik örneklerle incelendi. Zamanlar yaklaşık ve kaydın başlangıcına göredir.

| Kayıt | Gözlem |
|---|---|
| IMG_9522 | Telefon ekranındaki üst mesaj yaklaşık 0 ve 6. saniyelerde aynı konumda çakışan satır parçaları gösteriyor. Alt mesaj yaklaşık 2. saniyede parçalara ayrılıyor, diğer karelerde uzun bir kutuya dönüşüyor. Son satırlar konum etiketine taşıyor. Üstte Remote Play bildirimi ve sağ altta kontrol ipuçları da çevriliyor. |
| IMG_9523 | TV ayar paneli açıkken küçük yazıyla da üst telefon mesajı yaklaşık 4. saniyede çakışıyor. Alt çevirinin çevresinde İngilizce satırlar görünüyor. Yeni satır yüksekliği ve OCR gruplaması tek başına yazı boyutuyla düzeltilemiyor. |
| IMG_9524 | Sabit sol menüde harita etiketi yaklaşık 0–6. saniyelerde farklı çeviriler alıyor. Menü simgeleri harf, sayı veya noktalama olarak metne karışıyor. Üstteki Roma rakamlı logo da kutuya dönüşüyor. |

Kaynak koordinatları Remote Play karesine göre 0–1 aralığında. Mevcut TV kodu bu oranları HDMI alanına çeviriyor ancak gerçek kutu genişliğini kendi alt genişlik sınırına, yüksekliği ise sarılan metne ve kullanıcı yazı boyutuna göre hesaplıyor. TV yerleşimde gelen yüksekliği kullanmıyor ve diğer kutulara bakmıyor. Telefonun eğik çekiminden HDMI overscan veya kesin piksel hatası çıkarılamaz. Android tarafında okunabilir genişlik ve yükseklik tahminiyle çakışma çözümü uygulanır. Kesin font ölçümü, kutu başına punto ve ekranda çizildi bildirimi mevcut protokolün dışında kalır.
