# PS5 Çevirmen V2

Her iki depoda geliştirme dalı `feature/v2-fullscreen-translation`. Android başlangıcı `e1d0e45` ve TV başlangıcı `6e62448`.

## Kullanım

- **Ayarlar → Çeviri modu** bölümünde varsayılan **Altyazı çevirisi** veya **Tam ekran çeviri** seçilir. Modu değiştirince ekrandaki önceki çeviriler temizlenir ve aynı Remote Play oturumu kullanılır.
- Altyazı modunda tarama alanına ek olarak sekiz dışlama alanı ayarlanabilir. Sağ alt köşe düğmesi bir maske ekler. Altyazı için OCR sıklığı, hassasiyet, 1–4 uyumlu gözlem, en kısa gösterim süresi ve kaybolma gecikmesi ayarlanabilir. Varsayılan değerler iki gözlem, 700 ms minimum ve 2 saniye kaybolma gecikmesidir.
- Tam ekran modu V2 TV uygulaması gerektirir. Bir karede en fazla 24 metin bloğu işlenir. En kısa tarama aralığı 500 ms’dir. Kutuların ayrı kimlikleri ve süreleri vardır. Daha önce gösterilmiş bir kutu kısa bir OCR atlamasında görünür kalır. Sonradan tamamlanan bir çeviri, o metin son karede görülmediyse yeni bir kutu açmaz.
- **Dil paketleri** bölümünde ML Kit paketleri indirilebilir, denetlenebilir ve çeviri durdurulduktan sonra kaldırılabilir. ML Kit’in bildirdiği indirme durumu gösterilir. Varsayılan yerel motor İngilizce → Türkçe çalışır.
- **Oyun sözlüğü** bölümüne her satırda bir `İngilizce = Türkçe` eşlemesi yazılır. V1’de saklanmış özel adlar aynı yazımla taşınır. Sözlük canlı çevirinin her iki moduna uygulanır.
- Google Cloud Translation ve yapay zekâ API seçenekleri yapılandırma bekleyen sağlayıcılardır. Bu sürüm anahtar almaz veya çeviri çağrısı yapmaz. İleride sağlayıcı adaptörü eklenirken oturum bağlamı ve kullanım bütçesi kullanılabilir.

## TV protokolü

`text`, `ttlMs` ve `refresh` sürüm 2 davranışı korunur. V2 TV, sürüm 2 el sıkışmasının ardından `{"type":"hello","subtitleVersion":3}` gönderir. Android konumlu çeviri anlık görüntülerini sadece sürüm 3 istemcilerine gönderir.

```json
{"type":"overlay","frameWidth":1280,"frameHeight":720,"blocks":[{"id":"7","text":"Göreve devam et","x":0.20,"y":0.13,"w":0.24,"h":0.06,"ttlMs":1900}]}
```

Koordinatlar Remote Play karesine göre 0–1 aralığındadır. TV görüntü oranını korur ve ekrana sığmayan kutuları görüntü alanında tutar. HDMI tam alana geriliyorsa hizalama ayarı değiştirilebilir. Kullanılan oturumda farklı bir oyun içi letterbox, TV overscan veya kaynak kırpması varsa UE65NU7100 üzerinde hizalamayı denetle. Yeniden bağlanmada Android yalnızca süresi kalmış kutuları yollar.

## Çeviri kalitesi

Boşluk, satır sonu ve bazı OCR noktalama hataları düzeltilir. Sözlük, tanınan terimleri hedef yazımla korur. Sözlük eşleştiğinde kalan parçalar ayrı çevrildiği için bazı Türkçe ekler veya kelime sırası elle düzeltme gerektirebilir. ML Kit, karakterler arası bağlama, önceki repliklere ve oyun terminolojisine kendi başına güvenilir biçimde erişemez. Karmaşık cümleler, deyimler, çok küçük yazılar ve yoğun HUD düzenleri oyuna göre test edilmelidir.

## Paket ve cihaz doğrulaması

Android paket kimliği `com.emergent.psaltyazicevirmen.g218pl`, V2 sürümü `2.0.0` ve versionCode `2`. APK’nın mevcut kurulumun üzerine yüklenmesi için önceki APK ile aynı imza anahtarı gerekir. Tizen WGT, TV’ye yetki veren Samsung geliştirici sertifikasıyla imzalanır.

Gerçek cihazda önce altyazı modunda kayıtlı PS5’e bağlan, ardından aynı oturum içinde tam ekrana geçip tekrar altyazı moduna dön. Hareketli menü yazıları, kısa replikler, köşe logoları, aynı metnin iki konumu, oyun ve TV’de farklı görüntü oranları, ekran kapalı çalışma ve ağ kesintisi sonrası temizleme senaryolarını doğrula.
