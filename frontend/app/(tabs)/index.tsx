import { Ionicons } from "@react-native-vector-icons/ionicons";
import { router } from "expo-router";
import { Pressable, ScrollView, Text, useWindowDimensions, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import { ScreenHeader } from "@/src/components/ScreenHeader";
import { BadgeKind, Card, IconName, SectionTitle, StatusBadge } from "@/src/components/ui";
import { fonts, radius, space } from "@/src/fonts";
import { usesNativeTabs } from "@/src/navigation";
import { nativeAvailable } from "@/src/native/bridge";
import { usePipeline } from "@/src/pipeline/PipelineProvider";
import { useSettings } from "@/src/settings";
import { makeStyles, useTheme } from "@/src/theme";

export default function Dashboard() {
  const styles = useStyles();
  const { colors } = useTheme();
  const insets = useSafeAreaInsets();
  const bottomChrome = usesNativeTabs ? insets.bottom : 0;
  const { width } = useWindowDimensions();
  const cols = width >= 1000 ? 3 : width >= 640 ? 2 : 1;
  const { state } = usePipeline();
  const { settings } = useSettings();

  const engineKind: BadgeKind =
    settings.engine === "offline" && !nativeAvailable
      ? "native"
      : state.engineState === "error"
        ? "error"
        : state.engineState === "translating"
          ? "busy"
          : "ready";

  const cards: { id: string; title: string; icon: IconName; kind: BadgeKind; label?: string; detail: string }[] = [
    { id: "ps5", title: "PS5 bağlantısı", icon: "game-controller-outline", kind: nativeAvailable ? "idle" : "native", detail: settings.consoles.length ? `${settings.consoles.length} konsol kayıtlı (yerel ayar)` : "Konsol eklenmedi" },
    { id: "session", title: "Remote Play oturumu", icon: "radio-outline", kind: nativeAvailable ? "idle" : "native", detail: "chiaki-ng motoru yerel modülde çalışacak" },
    { id: "tv", title: "TV bağlantısı", icon: "tv-outline", kind: nativeAvailable ? "idle" : "native", detail: `ws://TABLET_IP:${settings.tvPort} sunucusu` },
    { id: "ocr", title: "OCR", icon: "scan-outline", kind: nativeAvailable ? "idle" : "native", detail: `Alan: alt %${Math.round(settings.ocrRegion.h * 100)} · ${settings.ocrIntervalMs} ms` },
    {
      id: "engine",
      title: "Çeviri motoru",
      icon: "language-outline",
      kind: engineKind,
      detail:
        settings.engine === "online"
          ? `Çevrimiçi YZ · ${state.lastLatencyMs != null ? `${state.lastLatencyMs} ms` : "hazır"}`
          : "Çevrimdışı ML Kit",
    },
  ];

  const flow: { label: string; ok: boolean }[] = [
    { label: "PS5", ok: nativeAvailable },
    { label: "Decoder", ok: nativeAvailable },
    { label: "OCR", ok: nativeAvailable },
    { label: "Çeviri", ok: settings.engine === "online" || nativeAvailable },
    { label: "TV", ok: nativeAvailable },
  ];

  return (
    <View style={styles.root} testID="dashboard-screen">
      <ScreenHeader title="PS5 Çevirmen" subtitle="İngilizce → Türkçe gerçek zamanlı altyazı" />
      <ScrollView contentContainerStyle={[styles.content, { paddingBottom: bottomChrome + space.xl }]}>
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
                <Text style={styles.cardDetail}>{c.detail}</Text>
              </Card>
            </View>
          ))}
        </View>

        <SectionTitle title="İşlem hattı" subtitle="Yeşil: çalışıyor · Kehribar: yerel derleme bekliyor" />
        <Card testID="pipeline-flow">
          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.flow}>
            {flow.map((f, i) => (
              <View key={f.label} style={styles.flowItem}>
                <View style={[styles.flowNode, { borderColor: f.ok ? colors.success : colors.warning }]}>
                  <Text style={[styles.flowText, { color: f.ok ? colors.success : colors.warning }]}>{f.label}</Text>
                </View>
                {i < flow.length - 1 ? <Ionicons name="arrow-forward" size={16} color={colors.muted} /> : null}
              </View>
            ))}
          </ScrollView>
        </Card>

        <SectionTitle title="Son altyazı" />
        <Card testID="dashboard-current-subtitle">
          <Text style={styles.en} numberOfLines={2}>
            {state.english || "Henüz algılanan metin yok"}
          </Text>
          <Text style={styles.tr} numberOfLines={3}>
            {state.turkish || "—"}
          </Text>
          <Text style={styles.cardDetail}>Son olay: {state.lastEvent}</Text>
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
  flow: { alignItems: "center", gap: space.sm },
  flowItem: { flexDirection: "row", alignItems: "center", gap: space.sm },
  flowNode: { borderWidth: 1, borderRadius: radius.sm, paddingHorizontal: space.md, height: 36, justifyContent: "center" },
  flowText: { fontFamily: fonts.displaySemi, fontSize: 16, letterSpacing: 0.5 },
  en: { fontFamily: fonts.text, fontSize: 14, color: c.muted },
  tr: { fontFamily: fonts.textSemi, fontSize: 20, lineHeight: 28, color: c.onSurface },
  reportLink: { marginTop: space.xl, flexDirection: "row", alignItems: "center", gap: space.md, backgroundColor: c.brandTertiary, borderRadius: radius.md, padding: space.lg, minHeight: 64 },
  reportTitle: { fontFamily: fonts.display, fontSize: 20, color: c.onBrandTertiary },
  reportSub: { fontFamily: fonts.text, fontSize: 12, color: c.onBrandTertiary },
}));
