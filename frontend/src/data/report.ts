import type { BadgeKind } from "@/src/components/ui";

export type ReportRow = { title: string; badge: BadgeKind; badgeLabel?: string; body: string };
export type ReportSection = { id: string; title: string; intro?: string; rows: ReportRow[] };

export const REPORT: ReportSection[] = [
  {
    id: "feasibility",
    title: "1. Yapılabilirlik",
    intro:
      "Kişisel kullanım için teknik olarak YAPILABİLİR, ancak yalnızca yerel (native) Android modülüyle. Remote Play protokolü Sony tarafından belgelenmemiştir; çalışan tek açık uygulama chiaki / chiaki-ng ailesidir. Expo Go veya web önizlemede Remote Play, MediaCodec, ML Kit ve tabletin WebSocket sunucusu ÇALIŞMAZ.",
    rows: [
      { title: "Uçtan uca hedef", badge: "native", body: "PS5 → Remote Play → MediaCodec → ImageReader → ROI OCR → Çeviri → ws://TABLET_IP:8787 → Tizen TV. Gerçek cihaz + gerçek PS5 + Android derlemesi ile doğrulanmalı." },
      { title: "Şu an çalışan kısım", badge: "ready", body: "Arayüz, ayarlar, altyazı karar mantığı (tekrar engelleme, gürültü filtresi, temizleme gecikmesi, eski çevirinin yenisinin üzerine yazmaması), çevrimiçi yapay zekâ çevirisi, önbellek ve geçmiş." },
    ],
  },
  {
    id: "methods",
    title: "2. Remote Play yöntem karşılaştırması",
    rows: [
      { title: "Sony resmi PS Remote Play uygulaması", badge: "error", badgeLabel: "Uygun değil", body: "Başka uygulamalara görüntü/kare veren herkese açık bir API, Intent veya SDK yok. Görüntüyü almak için ekranda görünür olması ve MediaProjection ile ekran kaydı gerekir; bu hem 'tablette görünmesin' şartını bozar hem de içerik FLAG_SECURE ise siyah kare verir." },
      { title: "Protokolü sıfırdan yazmak", badge: "error", badgeLabel: "Çok riskli", body: "Kayıt (AES + ECDH), Takion (UDP), Senkusha, FEC, şifreli video akışı ve PS5'e özel el sıkışma tersine mühendislik gerektirir. Pratikte chiaki'yi yeniden yazmak demektir; aylar sürer ve PS5 güncellemelerinde kırılır." },
      { title: "chiaki-ng çekirdeğini (C, lib/) JNI ile gömmek", badge: "ready", badgeLabel: "Seçilen yöntem", body: "PS4/PS5, H.264/HEVC, 8 haneli PIN + PSN Account ID ile kayıt, PSN üzerinden uzaktan bağlantı (holepunch) destekli ve kanıtlanmış. Kullanıcı ayrıca bir program açmaz; motor uygulamanın içinde çalışır. Lisans: AGPL-3.0 (+OpenSSL istisnası) → kişisel kullanımda sorun yok, dağıtırsanız kaynak kodu açmanız gerekir." },
      { title: "Mevcut Android istemcilerinden JNI katmanı almak", badge: "info", badgeLabel: "Yardımcı", body: "chiaki-ng-android-extended, Pleikkari, Momiji (hepsi AGPL-3.0) JNI köprüsü ve Android derleme betikleri için referans. Yöntem 3 ile aynı motor; sadece entegrasyon süresini kısaltır." },
      { title: "Alternatif: HDMI yakalama kartı (USB UVC)", badge: "info", badgeLabel: "Plan B", body: "PS5 → HDMI bölücü → TV + USB yakalama kartı → tablet. PSN/protokol riski yok, gecikme daha düşük. Ek donanım gerekir; PS5 ayarlarından HDCP kapatılmalıdır. Remote Play bloke olursa en güvenilir yedek yol." },
    ],
  },
  {
    id: "hidden",
    title: "3. Görüntüyü ekranda göstermeden işleme",
    intro: "MediaCodec bir View'a bağlı olmak zorunda değildir. Çıkış Surface'i görünür olmayan bir ImageReader'dan alınır.",
    rows: [
      { title: "Kod çözme", badge: "native", body: "chiaki video callback'i → H.264/HEVC NAL birimleri → MediaCodec (low-latency, KEY_LOW_LATENCY) → ImageReader(YUV_420_888, maxImages=2).getSurface(). Hiçbir SurfaceView/TextureView yok." },
      { title: "Kare seçimi", badge: "native", body: "Ayrı HandlerThread üzerinde acquireLatestImage(); OCR sıklığına göre (örn. 300 ms) kareler atlanır, kare hemen close() edilir. UI thread kullanılmaz." },
      { title: "Bellek", badge: "native", body: "Bitmap oluşturulmaz. Yalnızca altyazı bölgesinin (varsayılan alt %28) Y düzlemi önceden ayrılmış tek bir ByteArray'e kopyalanır; ML Kit InputImage.fromByteArray (NV21, nötr kroma) ile beslenir." },
      { title: "Değişim algılama", badge: "native", body: "Bölgenin küçültülmüş parlaklık özeti önceki kareyle karşılaştırılır; değişim yoksa OCR çalıştırılmaz → pil ve gecikme tasarrufu." },
    ],
  },
  {
    id: "libs",
    title: "4. Kütüphaneler ve lisanslar",
    rows: [
      { title: "chiaki-ng (lib/)", badge: "info", badgeLabel: "AGPL-3.0", body: "Remote Play motoru. OpenSSL istisnası içerir." },
      { title: "OpenSSL 3", badge: "info", badgeLabel: "Apache-2.0", body: "chiaki kriptografisi." },
      { title: "Opus, Jerasure/gf-complete, nanopb, json-c, curl, miniupnpc", badge: "info", badgeLabel: "BSD / zlib / MIT", body: "chiaki bağımlılıkları (ses, FEC, protobuf, PSN holepunch)." },
      { title: "Google ML Kit Text Recognition v2 (Latin)", badge: "info", badgeLabel: "Ücretsiz, ML Kit Şartları", body: "Cihaz içi OCR, internet gerekmez." },
      { title: "Google ML Kit Translation", badge: "info", badgeLabel: "Ücretsiz, ML Kit Şartları", body: "Çevrimdışı EN→TR (~30 MB model, bir kez indirilir). Kalite orta düzey." },
      { title: "Java-WebSocket (TooTallNate)", badge: "info", badgeLabel: "MIT", body: "Tablette :8787 WebSocket sunucusu. Mesaj formatı {\"text\":\"...\"} değişmeden." },
      { title: "Expo / React Native", badge: "info", badgeLabel: "MIT", body: "Arayüz ve ayarlar." },
      { title: "Çevrimiçi yapay zekâ çevirisi (Claude, Emergent anahtarı)", badge: "info", badgeLabel: "Kullanım başına kredi", body: "İsteğe bağlı, daha kaliteli. İnternet gerekir ve altyazı metni sunucuya gönderilir. Zorunlu değildir." },
    ],
  },
  {
    id: "scope",
    title: "5. Emergent'te ne yapılır, ne native gerektirir",
    rows: [
      { title: "Arayüz, ayarlar, çeviri hattı mantığı", badge: "ready", body: "Burada geliştirildi ve önizlemede test edilebilir." },
      { title: "Çevrimiçi çeviri + önbellek + geçmiş", badge: "ready", body: "Sunucu uç noktası /api/translate gerçek çeviri yapar." },
      { title: "Tablet IP adresi gösterimi", badge: "ready", body: "Expo Network ile Expo Go'da da çalışır." },
      { title: "WebSocket sunucusu :8787", badge: "native", body: "Telefonda TCP sunucusu açmak yerel modül ister. Kotlin modül kodu burada yazılabilir, ancak test için Android derlemesi gerekir." },
      { title: "PS5 keşif, kayıt, oturum (chiaki-ng)", badge: "native", body: "C/NDK + CMake + JNI. Burada kod yazılabilir; derleme Publish → Android build ile, test gerçek PS5 ile yapılmalıdır." },
      { title: "MediaCodec + ImageReader + ML Kit OCR", badge: "native", body: "Yerel Kotlin modülü." },
      { title: "Foreground service, Wake/Wi-Fi lock, pil optimizasyonu", badge: "native", body: "foregroundServiceType=connectedDevice, kalıcı bildirim, WifiLock(LOW_LATENCY)." },
    ],
  },
  {
    id: "risks",
    title: "6. Riskler ve engeller (dürüst değerlendirme)",
    rows: [
      { title: "Sony güncellemeleri", badge: "error", badgeLabel: "Yüksek", body: "PS5 yazılım güncellemeleri protokolü değiştirip chiaki-ng'yi geçici olarak bozabilir." },
      { title: "PSN Account ID gerekliliği", badge: "native", badgeLabel: "Orta", body: "Kayıt için sayısal PSN Account ID (base64) gerekir; chiaki-ng'nin PSN giriş akışı veya betiğiyle alınır. Şifre uygulamada saklanmaz." },
      { title: "Sony kullanım şartları", badge: "native", badgeLabel: "Orta", body: "Resmi olmayan istemci. Kişisel kullanımda yaygın, mağazada yayınlanması uygun değil." },
      { title: "Remote Play kısıtlı içerik", badge: "native", badgeLabel: "Orta", body: "Bazı oyun/sahnelerde PS5 Remote Play görüntüsünü engelleyebilir; bu durumda OCR karesi gelmez (Plan B: yakalama kartı)." },
      { title: "Gecikme", badge: "info", badgeLabel: "Tahmini", body: "Akış ~50–120 ms + OCR ~40–120 ms + çeviri (çevrimdışı ~50 ms / çevrimiçi ~400–1500 ms) + Wi-Fi. Toplam ~0,3–1,8 sn." },
      { title: "Android arka plan sınırları", badge: "info", badgeLabel: "Yönetilebilir", body: "Foreground service + kalıcı bildirim ile ekran kapalıyken çalışır. Samsung'un pil yöneticisi uygulamayı 'Uyutma' listesinden çıkarmayı gerektirebilir." },
      { title: "Ağ güvenliği", badge: "info", badgeLabel: "Yönetilebilir", body: "Tizen tarafı ws:// (şifresiz) kullanır. Sunucu sadece Wi-Fi arayüzüne bağlanır ve isteğe bağlı olarak sadece kayıtlı TV IP'sini kabul eder." },
    ],
  },
  {
    id: "phases",
    title: "7. Aşama durumu",
    rows: [
      { title: "Aşama 1 · Teknik mimari ve araştırma", badge: "ready", badgeLabel: "Tamamlandı", body: "Bu rapor." },
      { title: "Aşama 2 · Arayüz, mimari, TV protokolü", badge: "busy", badgeLabel: "Kısmen", body: "Arayüz, ayarlar, altyazı hattı ve {\"text\"} formatı hazır. Tablette WebSocket sunucusu yerel modül bekliyor." },
      { title: "Aşama 3 · Gerçek PS5 Remote Play", badge: "native", badgeLabel: "Başlanmadı", body: "chiaki-ng'nin Expo yerel modülüne gömülmesi." },
      { title: "Aşama 4 · Görünmez video çözümleme", badge: "native", badgeLabel: "Başlanmadı", body: "MediaCodec → ImageReader." },
      { title: "Aşama 5 · OCR + çeviri", badge: "busy", badgeLabel: "Kısmen", body: "Çevrimiçi çeviri hazır. ML Kit OCR ve çevrimdışı çeviri yerel modül bekliyor." },
      { title: "Aşama 6–7 · TV testi, performans", badge: "native", badgeLabel: "Başlanmadı", body: "Gerçek cihazlarla yapılmalıdır." },
    ],
  },
];
