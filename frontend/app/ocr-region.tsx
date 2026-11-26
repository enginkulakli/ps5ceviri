import { useState } from "react";
import { ScrollView, Text, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import { RemoteRegionEditor } from "@/src/components/RemoteRegionEditor";
import { DEFAULT_SUBTITLE_REGION } from "@/src/native/regionGeometry";
import { RegionPreview } from "@/src/components/RegionPreview";
import { ScreenHeader } from "@/src/components/ScreenHeader";
import { Button, Card, Chip, SectionTitle, Stepper } from "@/src/components/ui";
import { fonts, space } from "@/src/fonts";
import { OcrRegion } from "@/src/native/bridge";
import { useSettings } from "@/src/settings";
import { makeStyles } from "@/src/theme";

const PRESETS: { id: string; label: string; r: OcrRegion }[] = [
  { id: "bottom28", label: "Alt %28 (varsayılan)", r: DEFAULT_SUBTITLE_REGION },
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
  const [editing, setEditing] = useState(false);
  const [selected, setSelected] = useState(0);
  const areas = settings.exclusions;
  const index = Math.min(selected, areas.length - 1);
  const area = areas[index];
  const updateArea = (patch: Partial<OcrRegion>) => {
    const next = { ...area, ...patch };
    next.w = Math.min(1, Math.max(.05, round(next.w)));
    next.h = Math.min(1, Math.max(.05, round(next.h)));
    next.x = Math.min(1 - next.w, Math.max(0, round(next.x)));
    next.y = Math.min(1 - next.h, Math.max(0, round(next.y)));
    update({ exclusions: areas.map((current, i) => i === index ? next : current) });
  };

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
      <ScreenHeader title="Altyazı alanı" subtitle="Altyazı modu" back />
      <ScrollView contentContainerStyle={[styles.content, { paddingBottom: insets.bottom + space.xl }]}>
        <View style={{ marginTop: space.lg }}>
          <RegionPreview region={r} exclusions={areas} testID="ocr-region-editor-preview" />
          <View style={{ marginTop: space.md }}>
            <Button testID="edit-remote-region" label="Alanı Düzenle" onPress={() => setEditing(true)} />
          </View>
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
          <Text style={styles.meta}>Yüzdeler PS5 video karesine göre hesaplanır.</Text>
        </Card>
        <SectionTitle title="Dışlama alanları" subtitle="Kırmızı alanlar altyazı OCR’ından çıkarılır" />
        <Card>
          <Button label="Sağ alt alanı ekle" icon="add-circle-outline" variant="secondary" disabled={areas.length >= 8}
            onPress={() => { setSelected(areas.length); update({ exclusions: [...areas, { x: .8, y: .82, w: .2, h: .18 }] }); }} />
          {area ? <>
            <View style={styles.areaList}>
              {areas.map((_, i) => <Chip key={i} testID={`exclusion-area-${i}`} label={`Alan ${i + 1}`} selected={i === index} onPress={() => setSelected(i)} />)}
            </View>
            <Stepper testID="exclusion-top" label="Üst kenar" display={pct(area.y)} onDec={() => updateArea({ y: area.y - .02 })} onInc={() => updateArea({ y: area.y + .02 })} />
            <Stepper testID="exclusion-left" label="Sol kenar" display={pct(area.x)} onDec={() => updateArea({ x: area.x - .02 })} onInc={() => updateArea({ x: area.x + .02 })} />
            <Stepper testID="exclusion-width" label="Genişlik" display={pct(area.w)} onDec={() => updateArea({ w: area.w - .02 })} onInc={() => updateArea({ w: area.w + .02 })} />
            <Stepper testID="exclusion-height" label="Yükseklik" display={pct(area.h)} onDec={() => updateArea({ h: area.h - .02 })} onInc={() => updateArea({ h: area.h + .02 })} />
            <Button label="Seçili alanı kaldır" variant="secondary" onPress={() => update({ exclusions: areas.filter((_, i) => i !== index) })} />
          </> : null}
        </Card>
      </ScrollView>
      {editing ? <RemoteRegionEditor initial={r} onCancel={() => setEditing(false)}
        onSave={next => { update({ ocrRegion: next }); setEditing(false); }} /> : null}
    </View>
  );
}

const useStyles = makeStyles((c) => ({
  root: { flex: 1, backgroundColor: c.surface },
  content: { paddingHorizontal: space.lg, maxWidth: 900, width: "100%", alignSelf: "center" },
  areaList: { flexDirection: "row", flexWrap: "wrap", gap: space.sm },
  chips: { gap: space.sm, alignItems: "center", height: 56 },
  meta: { fontFamily: fonts.text, fontSize: 12, color: c.muted },
}));
