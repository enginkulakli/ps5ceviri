import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Image } from "expo-image";
import { Modal, PanResponder, Text, View, type ViewStyle } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import { ScreenHeader } from "@/src/components/ScreenHeader";
import { Button } from "@/src/components/ui";
import { fonts, space } from "@/src/fonts";
import { hasNative, nativeModule, type OcrRegion } from "@/src/native/bridge";
import { containFrame, DEFAULT_SUBTITLE_REGION, dragRegion, projectCrop, type RegionHandle } from "@/src/native/regionGeometry";
import { makeStyles } from "@/src/theme";

const handles = [
  ["nw", 0, 0], ["n", .5, 0], ["ne", 1, 0], ["w", 0, .5],
  ["e", 1, .5], ["sw", 0, 1], ["s", .5, 1], ["se", 1, 1],
] as const;
const size = 36;
const position = ({ x, y, w, h }: OcrRegion): ViewStyle => ({ left: x, top: y, width: w, height: h });

export function RemoteRegionEditor({ initial, onSave, onCancel }: {
  initial: OcrRegion;
  onSave: (region: OcrRegion) => void;
  onCancel: () => void;
}) {
  const styles = useStyles();
  const insets = useSafeAreaInsets();
  const [frame, setFrame] = useState<{ data: string; width: number; height: number } | null>(null);
  const [loading, setLoading] = useState(false);
  const [draft, setDraft] = useState(initial);
  const [stage, setStage] = useState({ width: 0, height: 0 });
  const mounted = useRef(true);
  const current = useRef(initial);
  const dragStart = useRef(initial);
  const dimensions = useRef({ w: 0, h: 0 });

  const sourceWidth = frame?.width ?? 1280;
  const sourceHeight = frame?.height ?? 720;
  const viewport = containFrame(stage.width, stage.height, sourceWidth, sourceHeight);
  const box = projectCrop(draft, viewport, sourceWidth, sourceHeight);
  dimensions.current = viewport;

  const capture = useCallback(async () => {
    if (!hasNative("captureRemoteRegionPreview")) return;
    setLoading(true);
    try {
      const next = await nativeModule!.captureRemoteRegionPreview();
      if (mounted.current && next?.data && next.width >= 2 && next.height >= 2) setFrame(next);
    } catch {
      // Stay on the last captured frame if the stream is temporarily unavailable.
    } finally {
      if (mounted.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    mounted.current = true;
    void capture();
    return () => { mounted.current = false; };
  }, [capture]);

  const moveDraft = useCallback((next: OcrRegion) => {
    current.current = next;
    setDraft(next);
  }, []);

  const responders = useMemo(() => {
    const create = (handle: RegionHandle) => PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onPanResponderGrant: () => { dragStart.current = current.current; },
      onPanResponderMove: (_, gesture) => {
        const frameSize = dimensions.current;
        if (frameSize.w > 0 && frameSize.h > 0) {
          moveDraft(dragRegion(dragStart.current, handle, gesture.dx / frameSize.w, gesture.dy / frameSize.h));
        }
      },
    }).panHandlers;
    return { move: create("move"), edges: handles.map(([handle]) => create(handle)) };
  }, [moveDraft]);

  const shades = [
    { x: viewport.x, y: viewport.y, w: viewport.w, h: box.y - viewport.y },
    { x: viewport.x, y: box.y + box.h, w: viewport.w, h: viewport.y + viewport.h - box.y - box.h },
    { x: viewport.x, y: box.y, w: box.x - viewport.x, h: box.h },
    { x: box.x + box.w, y: box.y, w: viewport.x + viewport.w - box.x - box.w, h: box.h },
  ];

  return (
    <Modal visible animationType="fade" onRequestClose={onCancel} supportedOrientations={["portrait", "landscape"]}>
      <View style={[styles.root, { paddingTop: insets.top, paddingBottom: insets.bottom + space.md }]}>
        <ScreenHeader title="Altyazı alanını düzenle" />
        <View testID="region-editor-stage" style={styles.stage}
          onLayout={({ nativeEvent: { layout } }) => setStage({ width: layout.width, height: layout.height })}>
          {viewport.w > 0 && viewport.h > 0 ? <>
            <View pointerEvents="none" style={[styles.canvas, position(viewport)]}>
              {frame ? <Image source={{ uri: `data:image/jpeg;base64,${frame.data}` }} style={styles.image} contentFit="fill" /> : null}
            </View>
            {shades.filter(area => area.w > 0 && area.h > 0).map((area, index) => (
              <View key={index} pointerEvents="none" style={[styles.shade, position(area)]} />
            ))}
            <View testID="region-editor-selection" accessibilityLabel="Altyazı alanını taşı"
              style={[styles.selection, position(box)]} {...responders.move} />
            {handles.map(([handle, x, y], i) => (
              <View key={handle} testID={`region-editor-handle-${handle}`} style={[styles.handle, {
                left: box.x + box.w * x - size / 2, top: box.y + box.h * y - size / 2,
              }]} {...responders.edges[i]}>
                <View pointerEvents="none" style={styles.dot} />
              </View>
            ))}
          </> : null}
        </View>
        <Text style={styles.help}>{frame ? "Alanı taşıyın veya kenar ve köşelerden boyutlandırın." : loading
          ? "PS5 karesi alınıyor…" : "PS5 karesi alınamadı. Remote Play’i başlatıp kareyi yenileyin."}</Text>
        <View style={styles.actions}>
          <Button label="Kareyi Yenile" variant="secondary" loading={loading}
            disabled={loading || !hasNative("captureRemoteRegionPreview")} onPress={capture} />
          <Button label="Varsayılana Sıfırla" variant="secondary" onPress={() => moveDraft(DEFAULT_SUBTITLE_REGION)} />
          <Button label="İptal" variant="secondary" onPress={onCancel} />
          <Button label="Kaydet" testID="save-remote-region" onPress={() => onSave(current.current)} />
        </View>
      </View>
    </Modal>
  );
}

const useStyles = makeStyles(c => ({
  root: { flex: 1, backgroundColor: c.surface, paddingHorizontal: space.lg, gap: space.sm },
  stage: { flex: 1, margin: size / 2, minHeight: 82 },
  canvas: { position: "absolute", backgroundColor: c.surfaceTertiary },
  image: { width: "100%", height: "100%" },
  shade: { position: "absolute", backgroundColor: "rgba(0,0,0,.58)" },
  selection: { position: "absolute", borderWidth: 2, borderColor: c.brandPrimary, backgroundColor: "rgba(100,150,250,.14)" },
  handle: { position: "absolute", width: size, height: size, alignItems: "center", justifyContent: "center" },
  dot: { width: 12, height: 12, borderRadius: 6, borderWidth: 2, borderColor: c.surface, backgroundColor: c.brandPrimary },
  help: { fontFamily: fonts.text, color: c.muted, textAlign: "center" },
  actions: { flexDirection: "row", justifyContent: "center", flexWrap: "wrap", gap: space.sm },
}));
