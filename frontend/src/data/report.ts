import type { BadgeKind } from "@/src/components/ui";

export type ReportRow = { title: string; badge: BadgeKind; badgeLabel?: string; body: string };
export type ReportSection = { id: string; title: string; intro?: string; rows: ReportRow[] };

export const REPORT: ReportSection[] = [
  {
    id: "remote-play",
    title: "PS5 ve Remote Play",
    rows: [
      { title: "Yerel ağ ve cihaz kaydı", badge: "native", body: "Tablet LAN üzerinden PS5’i keşfeder. IP adresiyle elle ekleme de kullanılabilir. Cihaz kaydı için PSN Account ID ve PS5’in gösterdiği 8 haneli PIN gerekir. Anahtarlar Android Keystore ile şifrelenir." },
      { title: "Arka planda görüntü", badge: "native", body: "chiaki-ng’nin yerel kütüphanesi H.264 akışını alır. MediaCodec kareleri bir ekran yüzeyi olmadan çözer. Android hizmeti bildirim üzerinden de durdurulabilir." },
      { title: "Gerçek cihaz doğrulaması", badge: "info", body: "Eşleştirme, HDMI açıkken DualSense ile oyun oynama, tablet başka uygulamaya geçtiğinde oturumun devam etmesi ve kopma sonrası yeniden bağlantı gerçek cihazlarla test edilmelidir." },
    ],
  },
  {
    id: "translation",
    title: "OCR ve çeviri",
    rows: [
      { title: "Altyazı bölgesi", badge: "native", body: "ML Kit varsayılan olarak karenin alt %28’ini okur. Bölge ve tarama aralığı Ayarlar’dan değiştirilebilir." },
      { title: "Cihaz içi İngilizce → Türkçe", badge: "native", body: "İlk kullanımda dil modeli internetten alınır. Sonraki çeviriler tablette çalışır. Deneysel API motoru manuel metin testinde seçilebilir." },
    ],
  },
  {
    id: "television",
    title: "Samsung TV",
    rows: [
      { title: "HDMI ve WebSocket", badge: "native", body: "Tizen 4.0 uygulaması mevcut HDMI kaynağını kullanır. TV, tabletin yerel adresine bağlanır. Altyazı varsayılan olarak son algılamadan iki saniye sonra temizlenir." },
      { title: "Lisanslar", badge: "info", body: "chiaki-ng AGPL-3.0 ve OpenSSL bağlantı istisnasıyla kullanılır. Android kaynak kodu ve bağımlılık bildirimleri proje deposunda yer alır." },
    ],
  },
];
