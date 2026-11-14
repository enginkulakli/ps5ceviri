import { ScrollView, Text, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import { RegionPreview } from "@/src/components/RegionPreview";
import { ScreenHeader } from "@/src/components/ScreenHeader";
import { Card, Chip, Notice, SectionTitle, Stepper } from "@/src/components/ui";
import { fonts, space } from "@/src/fonts";
import { OcrRegion } from "@/src/native/bridge";
import { useSettings } from "@/src/settings";
import { makeStyles } from "@/src/theme";

const PRESETS: { id: string; label: string; r: OcrRegion }[] = [
  { id: "bottom28", label: "Alt %28 (varsayılan)", r: { x: 0, y: 0.72, w: 1, h: 0.28 } },
  { id: "bottom20", label: "Alt %20", r: { x: 0, y: 0.8, w: 1, h: 0.2 } },
  { id: "bottom35", label: "Alt %35", r: { x: 0, y: 0.65, w: 1, h: 0.35 } },
  { id: "center-bottom", label: "Orta alt", r: { x: 0.15, y: 0.74, w: 0.7, h: 0.22 } },
  { id: "top", label: "Üst bant", r: { x: 0, y: 0, w: 1, h: 0.2 } },
];

const round = (v: number) => Math.round(v * 100) / 100;

export default function OcrRegionScreen() {
  const styles = useStyles();
  const insets = useSafeAreaInsets();
  const { settings, update } = useSettings();
  const r = settings.ocrRegion;

  const set = (patch: Partial<OcrRegion>) => {
    const n = { ...r, ...patch };
    n.w = Math.min(1, Math.max(0.1, round(n.w)));
    n.h = Math.min(1, Math.max(0.05, round(n.h)));
    n.x = Math.min(1 - n.w, Math.max(0, round(n.x)));
    n.y = Math.min(1 - n.h, Math.max(0, round(n.y)));
    update({ ocrRegion: n });
  };
  const pct = (v: number) => `%${Math.round(v * 100)}`;

  return (
    <View style={styles.root} testID="ocr-region-screen">
      <ScreenHeader title="Altyazı alanı" subtitle="OCR yalnızca bu bölgede çalışır" back />
      <ScrollView contentContainerStyle={[styles.content, { paddingBottom: insets.bottom + space.xl }]}>
        <View style={{ marginTop: space.lg }}>
          <RegionPreview region={r} testID="ocr-region-editor-preview" />
        </View>
        <SectionTitle title="Hazır alanlar" />
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.chips}>
          {PRESETS.map((p) => (
            <Chip key={p.id} testID={`region-preset-${p.id}`} label={p.label}
              selected={p.r.x === r.x && p.r.y === r.y && p.r.w === r.w && p.r.h === r.h} onPress={() => update({ ocrRegion: p.r })} />
          ))}
        </ScrollView>
        <SectionTitle title="İnce ayar" />
        <Card>
          <Stepper testID="region-top" label="Üst kenar" display={pct(r.y)} onDec={() => set({ y: r.y - 0.02 })} onInc={() => set({ y: r.y + 0.02 })} />
          <Stepper testID="region-height" label="Yükseklik" display={pct(r.h)} onDec={() => set({ h: r.h - 0.02 })} onInc={() => set({ h: r.h + 0.02 })} />
          <Stepper testID="region-left" label="Sol kenar" display={pct(r.x)} onDec={() => set({ x: r.x - 0.02 })} onInc={() => set({ x: r.x + 0.02 })} />
          <Stepper testID="region-width" label="Genişlik" display={pct(r.w)} onDec={() => set({ w: r.w - 0.02 })} onInc={() => set({ w: r.w + 0.02 })} />
          <Text style={styles.meta}>Değerler video karesine göre oranlıdır; 720p ve 1080p akışta aynı alan kullanılır.</Text>
        </Card>
        <View style={{ marginTop: space.lg }}>
          <Notice kind="info" text="Gerçek oyun karesi üzerinde canlı önizleme, Remote Play yerel modülü bağlandığında bu ekrana eklenecek." />
        </View>
      </ScrollView>
    </View>
  );
}

const useStyles = makeStyles((c) => ({
  root: { flex: 1, backgroundColor: c.surface },
  content: { paddingHorizontal: space.lg, maxWidth: 900, width: "100%", alignSelf: "center" },
  chips: { gap: space.sm, alignItems: "center", height: 56 },
  meta: { fontFamily: fonts.text, fontSize: 12, color: c.muted },
}));
