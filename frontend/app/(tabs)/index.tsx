import { Ionicons } from "@react-native-vector-icons/ionicons";
import { router } from "expo-router";
import { Pressable, ScrollView, Text, useWindowDimensions, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import { ScreenHeader } from "@/src/components/ScreenHeader";
import { TvServerPanel } from "@/src/components/TvServerPanel";
import { BadgeKind, Button, Card, IconName, Notice, SectionTitle, StatusBadge } from "@/src/components/ui";
import { fonts, radius, space } from "@/src/fonts";
import { usesNativeTabs } from "@/src/navigation";
import { hasNative, nativeAvailable } from "@/src/native/bridge";
import { useRemotePlay } from "@/src/native/RemotePlayProvider";
import { useSettings } from "@/src/settings";
import { makeStyles, useTheme } from "@/src/theme";
import { useTvServer } from "@/src/tv/TvServerProvider";

export default function Dashboard() {
  const styles = useStyles();
  const { colors } = useTheme();
  const insets = useSafeAreaInsets();
  const bottomChrome = usesNativeTabs ? insets.bottom : 0;
  const { width } = useWindowDimensions();
  const cols = width >= 1000 ? 3 : width >= 640 ? 2 : 1;
  const { status, busy, error, toggle } = useRemotePlay();
  const { settings } = useSettings();
  const tv = useTvServer();
  const tvKind: BadgeKind = !nativeAvailable ? "native" : tv.state?.running ? (tv.state.clientCount > 0 ? "ready" : "busy") : "idle";
  const tvLabel = !nativeAvailable ? undefined : tv.state?.running ? (tv.state.clientCount > 0 ? `${tv.state.clientCount} TV bağlı` : "Sunucu açık, TV bekleniyor") : "Sunucu kapalı";

  const streaming = status?.video.startsWith("H.264") === true;
  const stageKind: BadgeKind = !nativeAvailable ? "native" : status?.active ? "busy" : "idle";
  const cards: { id: string; title: string; icon: IconName; kind: BadgeKind; label?: string; detail?: string }[] = [
    { id: "ps5", title: "PS5 bağlantısı", icon: "game-controller-outline", kind: status?.console === "PS5 bağlı" ? "ready" : stageKind, label: status?.console, detail: status?.host ?? undefined },
    { id: "session", title: "Remote Play oturumu", icon: "radio-outline", kind: streaming ? "ready" : stageKind, label: status?.session },
    { id: "video", title: "Görüntü akışı", icon: "videocam-outline", kind: streaming ? "ready" : stageKind, label: status?.video },
    { id: "ocr", title: "OCR", icon: "scan-outline", kind: streaming ? "ready" : stageKind, label: status?.ocr, detail: `${settings.mode === "fullscreen" ? Math.max(500, settings.ocrIntervalMs) : settings.ocrIntervalMs} ms` },
    { id: "engine", title: "Çeviri motoru", icon: "language-outline", kind: status?.translation === "ML Kit hazır" ? "ready" : stageKind, label: status?.translation, detail: settings.engine === "offline" ? "ML Kit · İngilizce → Türkçe" : settings.engine === "google_cloud" ? "Google Cloud · Basic v2" : "API yapılandırılmadı" },
    { id: "tv", title: "TV bağlantısı", icon: "tv-outline", kind: tvKind, label: tvLabel, detail: `ws://${tv.state?.localIp ?? "TABLET_IP"}:${tv.state?.port ?? settings.tvPort}` },
  ];

  return (
    <View style={styles.root} testID="dashboard-screen">
      <ScreenHeader title="PS5 Çevirmen" subtitle={settings.mode === "fullscreen" ? "Tam ekran çeviri" : "Altyazı çevirisi"} />
      <ScrollView contentContainerStyle={[styles.content, { paddingBottom: bottomChrome + space.xl }]}>
        <Card>
          <Button testID="toggle-live-translation" label={status?.active ? "Çeviriyi Durdur" : "Çeviriyi Başlat"}
            icon={status?.active ? "stop-circle-outline" : "play-outline"} loading={busy}
            disabled={!hasNative("startSession")} onPress={toggle} />
          {settings.mode === "fullscreen" && !status?.overlayClients ?
            <Notice kind="info" text="Tam ekran çeviri için V2 TV uygulamasını bağlayın." /> : null}
          {error || status?.error ? <Notice kind="error" text={(error ?? status?.error)!} /> : null}
        </Card>
        <SectionTitle title="Bağlantı durumu" />
        <View style={styles.grid}>
          {cards.map((c) => (
            <View key={c.id} style={{ width: `${100 / cols}%`, padding: space.xs }}>
              <Card testID={`status-card-${c.id}`}>
                <View style={styles.cardHead}>
                  <View style={styles.iconBox}>
                    <Ionicons name={c.icon} size={20} color={colors.brandPrimary} />
                  </View>
                  <Text style={styles.cardTitle}>{c.title}</Text>
                </View>
                <StatusBadge kind={c.kind} label={c.label} testID={`status-badge-${c.id}`} />
                {c.detail ? <Text style={styles.cardDetail}>{c.detail}</Text> : null}
              </Card>
            </View>
          ))}
        </View>

        <SectionTitle title="TV sunucusu" subtitle="Gerçek durum, Android servisinden okunur" />
        <TvServerPanel idPrefix="dash" />

        <SectionTitle title="Son altyazı" />
        <Card testID="dashboard-current-subtitle">
          <Text style={styles.cardDetail}>Son algılanan İngilizce</Text>
          <Text style={styles.en} numberOfLines={2}>{status?.english || "—"}</Text>
          <Text style={styles.cardDetail}>TV’ye son gönderilen Türkçe</Text>
          <Text style={styles.tr} numberOfLines={3}>{status?.turkish || "—"}</Text>
        </Card>

        <Pressable testID="open-technical-report" onPress={() => router.push("/report")} style={({ pressed }) => [styles.reportLink, pressed && { opacity: 0.8 }]}>
          <Ionicons name="document-text-outline" size={22} color={colors.onBrandTertiary} />
          <View style={{ flex: 1 }}>
            <Text style={styles.reportTitle}>Teknik Rapor</Text>
            <Text style={styles.reportSub}>Yapılabilirlik, Remote Play yöntemleri, lisanslar ve riskler</Text>
          </View>
          <Ionicons name="chevron-forward" size={20} color={colors.onBrandTertiary} />
        </Pressable>
      </ScrollView>
    </View>
  );
}

const useStyles = makeStyles((c) => ({
  root: { flex: 1, backgroundColor: c.surface },
  content: { paddingHorizontal: space.lg, maxWidth: 1200, width: "100%", alignSelf: "center" },
  grid: { flexDirection: "row", flexWrap: "wrap", marginHorizontal: -space.xs },
  cardHead: { flexDirection: "row", alignItems: "center", gap: space.md },
  iconBox: { width: 36, height: 36, borderRadius: radius.sm, backgroundColor: c.surfaceTertiary, alignItems: "center", justifyContent: "center" },
  cardTitle: { fontFamily: fonts.textSemi, fontSize: 15, color: c.onSurface, flex: 1 },
  cardDetail: { fontFamily: fonts.text, fontSize: 12, color: c.muted },
  en: { fontFamily: fonts.text, fontSize: 14, color: c.muted },
  tr: { fontFamily: fonts.textSemi, fontSize: 20, lineHeight: 28, color: c.onSurface },
  reportLink: { marginTop: space.xl, flexDirection: "row", alignItems: "center", gap: space.md, backgroundColor: c.brandTertiary, borderRadius: radius.md, padding: space.lg, minHeight: 64 },
  reportTitle: { fontFamily: fonts.display, fontSize: 20, color: c.onBrandTertiary },
  reportSub: { fontFamily: fonts.text, fontSize: 12, color: c.onBrandTertiary },
}));
