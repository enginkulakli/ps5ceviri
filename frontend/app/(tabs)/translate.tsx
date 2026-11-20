import { Ionicons } from "@react-native-vector-icons/ionicons";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { requireOptionalNativeModule } from "expo";
import { ActivityIndicator, RefreshControl, Text, useWindowDimensions, View } from "react-native";
import { KeyboardAwareScrollView } from "react-native-keyboard-controller";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import { clearHistory, fetchHistory } from "@/src/api";
import { ScreenHeader } from "@/src/components/ScreenHeader";
import { Button, Card, Field, Notice, SectionTitle, StatusBadge } from "@/src/components/ui";
import { fonts, radius, space } from "@/src/fonts";
import { usesNativeTabs } from "@/src/navigation";
import { usePipeline } from "@/src/pipeline/PipelineProvider";
import { makeStyles, useTheme } from "@/src/theme";

export default function TranslateScreen() {
  const styles = useStyles();
  const { colors } = useTheme();
  const insets = useSafeAreaInsets();
  const bottomChrome = usesNativeTabs ? insets.bottom : 0;
  const { width } = useWindowDimensions();
  const wide = width >= 900;
  const { state, ingest, clear } = usePipeline();
  const [input, setInput] = useState("");
  const [captureOn, setCaptureOn] = useState(false);
  const [ocrError, setOcrError] = useState("");
  const [backgroundStatus, setBackgroundStatus] = useState("idle");
  const ingestRef = useRef(ingest);
  ingestRef.current = ingest;
  const nativeCapture = requireOptionalNativeModule<{
    hasOverlayPermission(): Promise<boolean>;
    requestOverlayPermission(): Promise<void>;
    startScreenCapture(): Promise<void>;
    startBackgroundTranslation(): Promise<void>;
    stopBackgroundTranslation(): Promise<void>;
    getBackgroundTranslationStatus(): Promise<{status: string; enabled: boolean; english: string; turkish: string}>;
    stopScreenCapture(): Promise<void>;
    recognizeLatestScreen(): Promise<Array<{ text: string; left: number; top: number; right: number; bottom: number }>>;
  }>("Ps5Translator");

  useEffect(() => {
    if (!captureOn || !nativeCapture) return;
    let busy = false;
    const timer = setInterval(async () => {
      if (busy) return;
      busy = true;
      try {
        const blocks = await nativeCapture.recognizeLatestScreen();
        const text = blocks.map((b) => b.text.trim()).filter(Boolean).join("\n").slice(0, 600);
        if (text) ingestRef.current(text);
      } catch (e) {
        setOcrError(String(e));
      } finally {
        busy = false;
      }
    }, 1600);
    return () => clearInterval(timer);
  }, [captureOn]);

  const qc = useQueryClient();

  const history = useQuery({ queryKey: ["history"], queryFn: fetchHistory });
  const clearMut = useMutation({ mutationFn: clearHistory, onSuccess: () => qc.invalidateQueries({ queryKey: ["history"] }) });

  const badge =
    state.engineState === "translating" ? "busy" : state.engineState === "error" ? "error" : state.engineState === "ok" ? "ready" : "idle";

  const current = (
    <View style={wide ? { flex: 1 } : undefined}>
      <SectionTitle title="Canlı altyazı" />
      <Card testID="current-subtitle-card">
        <View style={styles.rowBetween}>
          <Text style={styles.label}>Algılanan İngilizce</Text>
          <StatusBadge kind={badge} testID="engine-state-badge" />
        </View>
        <Text testID="detected-english-text" style={styles.en}>
          {state.english || "Henüz metin yok"}
        </Text>
        <View style={styles.divider} />
        <Text style={styles.label}>Türkçe çeviri</Text>
        <Text testID="turkish-translation-text" style={styles.tr}>
          {state.engineState === "translating" && !state.turkish ? "Çevriliyor…" : state.turkish || "—"}
        </Text>
        {state.error ? <Notice kind="error" text={state.error} testID="translation-error" /> : null}
        <Text testID="pipeline-last-event" style={styles.meta}>
          {state.lastEvent}
          {state.lastLatencyMs != null ? ` · ${state.lastLatencyMs} ms${state.lastCached ? " (önbellek)" : ""}` : ""}
        </Text>
        <Button testID="clear-translation-button" label="Çeviriyi temizle" icon="close-circle-outline" variant="secondary" onPress={clear} />
      </Card>

      <SectionTitle title="TV'ye giden mesaj" subtitle="Mevcut Tizen formatı, değiştirilmedi" />
      <Card testID="tv-payload-card">
        <Text testID="tv-payload-text" style={styles.mono}>
          {state.lastPayload ?? '{"text":""}'}
        </Text>
        {state.payloadStatus === "sent" ? <Notice kind="success" text="TV'ye gönderildi." testID="tv-payload-sent" /> : null}
        {state.payloadStatus === "duplicate" ? <Notice kind="info" text="Aynı altyazı zaten TV'de; tekrar gönderilmedi." testID="tv-payload-duplicate" /> : null}
        {state.payloadStatus === "no_clients" ? <Notice kind="warning" text="Bağlı TV yok; TV bağlandığında bu altyazı otomatik gönderilecek." testID="tv-payload-no-clients" /> : null}
        {state.payloadStatus === "not_running" ? <Notice kind="warning" text="TV sunucusu kapalı. Durum sekmesinden başlatın." testID="tv-payload-not-running" /> : null}
        {state.payloadStatus === "no_native" ? (
          <Notice kind="warning" text="Gönderilmedi: TV sunucusu yalnızca Android APK'da çalışır (Expo Go / web önizlemede yok)." testID="tv-payload-not-sent" />
        ) : null}
      </Card>

      <SectionTitle title="Ekranı otomatik oku" subtitle="Android ekran paylaşımı izni gerekir" />
      <Card>
        <Button label="Ekran üstü gösterim izni ver" icon="layers-outline"
          disabled={!nativeCapture}
          onPress={async () => {
            if (!(await nativeCapture?.hasOverlayPermission())) await nativeCapture?.requestOverlayPermission();
          }} />
        <View style={styles.btnRow}>
          <Button label="Ekran okumayı başlat" icon="scan-outline"
            disabled={!nativeCapture || captureOn}
            onPress={async () => {
              try {
                await nativeCapture?.startBackgroundTranslation();
                await nativeCapture?.startScreenCapture();
                setOcrError("");
                setCaptureOn(true);
              } catch (e) { setOcrError(String(e)); }
            }} style={{ flex: 1 }} />
          <Button label="Durdur" icon="stop-circle-outline" variant="secondary"
            disabled={!captureOn}
            onPress={async () => {
              setCaptureOn(false);
              await nativeCapture?.stopBackgroundTranslation();
              await nativeCapture?.stopScreenCapture();
            }} style={{ flex: 1 }} />
        </View>
        <Text style={styles.meta}>{captureOn ? `Arka plan çevirisi: ${backgroundStatus}` : "Ekran OCR taraması kapalı"}</Text>
        {!nativeCapture ? <Notice kind="warning" text="Bu özellik için native Android APK gerekir." /> : null}
        {ocrError ? <Notice kind="error" text={ocrError} /> : null}
      </Card>

      <SectionTitle title="Hattı test et" subtitle="Yerel OCR gelene kadar OCR çıktısını buraya elle girin" />
      <Card>
        <Field
          testID="manual-ocr-input"
          label="OCR metni (İngilizce)"
          placeholder="Örn: Kratos, we must reach the summit before nightfall."
          value={input}
          onChangeText={setInput}
          multiline
          style={{ minHeight: 72, paddingTop: space.md, textAlignVertical: "top" }}
        />
        <View style={styles.btnRow}>
          <Button testID="manual-ocr-submit-button" label="Hatta gönder" icon="send-outline" onPress={() => ingest(input)} disabled={!input.trim()} style={{ flex: 1 }} />
          <Button testID="manual-ocr-empty-button" label="Altyazı kayboldu" icon="eye-off-outline" variant="secondary" onPress={() => ingest("")} style={{ flex: 1 }} />
        </View>
        <Text testID="pipeline-stats" style={styles.meta}>
          Alınan {state.stats.received} · Çevrilen {state.stats.translated} · Tekrar atlanan {state.stats.skippedSame} · Filtrelenen {state.stats.ignored}
        </Text>
        <Notice kind="info" text="Bu test gerçek çeviri, tekrar engelleme, gürültü filtresi ve temizleme gecikmesini çalıştırır. Gerçek OCR kaynağı (PS5 görüntüsü) sonraki aşamada bağlanacak." />
      </Card>
    </View>
  );

  const historyView = (
    <View style={wide ? { flex: 1 } : undefined}>
      <View style={[styles.rowBetween, { marginTop: space.xl, marginBottom: space.md }]}>
        <Text style={styles.sectionTitle}>Son altyazılar</Text>
        <Button testID="clear-history-button" label="Geçmişi temizle" variant="danger" icon="trash-outline" onPress={() => clearMut.mutate()} loading={clearMut.isPending} style={{ minHeight: 44 }} />
      </View>
      {history.isLoading ? (
        <ActivityIndicator color={colors.brandPrimary} />
      ) : history.isError ? (
        <Notice kind="error" text={`Geçmiş yüklenemedi: ${(history.error as Error).message}`} testID="history-error" />
      ) : !history.data?.length ? (
        <Card testID="history-empty">
          <Ionicons name="chatbubble-ellipses-outline" size={28} color={colors.muted} />
          <Text style={styles.meta}>Henüz çeviri yok. Oyun içi metin bekleniyor.</Text>
        </Card>
      ) : (
        <View style={{ gap: space.sm }} testID="history-list">
          {history.data.map((h) => (
            <View key={h.id} style={styles.histItem} testID={`history-item-${h.id}`}>
              <Text style={styles.histEn}>{h.source_text}</Text>
              <Text style={styles.histTr}>{h.translated_text}</Text>
              <Text style={styles.meta}>
                {new Date(h.created_at).toLocaleTimeString("tr-TR")} · {h.latency_ms} ms{h.cached ? " · önbellek" : ""}
              </Text>
            </View>
          ))}
        </View>
      )}
    </View>
  );

  return (
    <View style={styles.root} testID="translate-screen">
      <ScreenHeader title="Çeviri" subtitle="EN → TR" />
      <KeyboardAwareScrollView
        bottomOffset={24}
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={[styles.content, { paddingBottom: bottomChrome + space.xl }]}
        refreshControl={<RefreshControl refreshing={history.isRefetching} onRefresh={() => history.refetch()} tintColor={colors.brandPrimary} />}
      >
        {wide ? (
          <View style={{ flexDirection: "row", gap: space.xl }}>
            {current}
            {historyView}
          </View>
        ) : (
          <>
            {current}
            {historyView}
          </>
        )}
      </KeyboardAwareScrollView>
    </View>
  );
}

const useStyles = makeStyles((c) => ({
  root: { flex: 1, backgroundColor: c.surface },
  content: { paddingHorizontal: space.lg, maxWidth: 1300, width: "100%", alignSelf: "center" },
  rowBetween: { flexDirection: "row", justifyContent: "space-between", alignItems: "center", gap: space.md },
  label: { fontFamily: fonts.textMedium, fontSize: 12, color: c.muted, textTransform: "uppercase", letterSpacing: 0.6 },
  en: { fontFamily: fonts.text, fontSize: 16, lineHeight: 23, color: c.onSurfaceSecondary },
  tr: { fontFamily: fonts.textSemi, fontSize: 24, lineHeight: 32, color: c.onSurface },
  divider: { height: 1, backgroundColor: c.divider },
  meta: { fontFamily: fonts.text, fontSize: 12, color: c.muted },
  mono: { fontFamily: "SpaceMono", fontSize: 13, color: c.info },
  btnRow: { flexDirection: "row", gap: space.sm },
  sectionTitle: { fontFamily: fonts.display, fontSize: 22, color: c.onSurface, letterSpacing: 0.5, textTransform: "uppercase" },
  histItem: { backgroundColor: c.surfaceSecondary, borderRadius: radius.md, borderWidth: 1, borderColor: c.border, padding: space.md, gap: 4 },
  histEn: { fontFamily: fonts.text, fontSize: 13, color: c.muted },
  histTr: { fontFamily: fonts.textSemi, fontSize: 16, color: c.onSurface },
}));  useEffect(() => {
    if (!captureOn || !nativeCapture) return;
    const timer = setInterval(async () => {
      try {
        const result = await nativeCapture.getBackgroundTranslationStatus();
        setBackgroundStatus(result.status);
      } catch (e) { setOcrError(String(e)); }
    }, 2500);
    return () => clearInterval(timer);
  }, [captureOn]);

  const qc = useQueryClient();

  const history = useQuery({ queryKey: ["history"], queryFn: fetchHistory });
  const clearMut = useMutation({ mutationFn: clearHistory, onSuccess: () => qc.invalidateQueries({ queryKey: ["history"] }) });

  const badge =
    state.engineState === "translating" ? "busy" : state.engineState === "error" ? "error" : state.engineState === "ok" ? "ready" : "idle";

  const current = (
    <View style={wide ? { flex: 1 } : undefined}>
      <SectionTitle title="Canlı altyazı" />
      <Card testID="current-subtitle-card">
        <View style={styles.rowBetween}>
          <Text style={styles.label}>Algılanan İngilizce</Text>
          <StatusBadge kind={badge} testID="engine-state-badge" />
        </View>
        <Text testID="detected-english-text" style={styles.en}>
          {state.english || "Henüz metin yok"}
        </Text>
        <View style={styles.divider} />
        <Text style={styles.label}>Türkçe çeviri</Text>
        <Text testID="turkish-translation-text" style={styles.tr}>
          {state.engineState === "translating" && !state.turkish ? "Çevriliyor…" : state.turkish || "—"}
        </Text>
        {state.error ? <Notice kind="error" text={state.error} testID="translation-error" /> : null}
        <Text testID="pipeline-last-event" style={styles.meta}>
          {state.lastEvent}
          {state.lastLatencyMs != null ? ` · ${state.lastLatencyMs} ms${state.lastCached ? " (önbellek)" : ""}` : ""}
        </Text>
        <Button testID="clear-translation-button" label="Çeviriyi temizle" icon="close-circle-outline" variant="secondary" onPress={clear} />
      </Card>

      <SectionTitle title="TV'ye giden mesaj" subtitle="Mevcut Tizen formatı, değiştirilmedi" />
      <Card testID="tv-payload-card">
        <Text testID="tv-payload-text" style={styles.mono}>
          {state.lastPayload ?? '{"text":""}'}
        </Text>
        {state.payloadStatus === "sent" ? <Notice kind="success" text="TV'ye gönderildi." testID="tv-payload-sent" /> : null}
        {state.payloadStatus === "duplicate" ? <Notice kind="info" text="Aynı altyazı zaten TV'de; tekrar gönderilmedi." testID="tv-payload-duplicate" /> : null}
        {state.payloadStatus === "no_clients" ? <Notice kind="warning" text="Bağlı TV yok; TV bağlandığında bu altyazı otomatik gönderilecek." testID="tv-payload-no-clients" /> : null}
        {state.payloadStatus === "not_running" ? <Notice kind="warning" text="TV sunucusu kapalı. Durum sekmesinden başlatın." testID="tv-payload-not-running" /> : null}
        {state.payloadStatus === "no_native" ? (
          <Notice kind="warning" text="Gönderilmedi: TV sunucusu yalnızca Android APK'da çalışır (Expo Go / web önizlemede yok)." testID="tv-payload-not-sent" />
        ) : null}
      </Card>

      <SectionTitle title="Ekranı otomatik oku" subtitle="Android ekran paylaşımı izni gerekir" />
      <Card>
        <Button label="Ekran üstü gösterim izni ver" icon="layers-outline"
          disabled={!nativeCapture}
          onPress={async () => {
            if (!(await nativeCapture?.hasOverlayPermission())) await nativeCapture?.requestOverlayPermission();
          }} />
        <View style={styles.btnRow}>
          <Button label="Ekran okumayı başlat" icon="scan-outline"
            disabled={!nativeCapture || captureOn}
            onPress={async () => {
              try {
                await nativeCapture?.startScreenCapture();
                setOcrError("");
                setCaptureOn(true);
              } catch (e) { setOcrError(String(e)); }
            }} style={{ flex: 1 }} />
          <Button label="Durdur" icon="stop-circle-outline" variant="secondary"
            disabled={!captureOn}
            onPress={async () => {
              setCaptureOn(false);
              await nativeCapture?.stopScreenCapture();
            }} style={{ flex: 1 }} />
        </View>
        <Text style={styles.meta}>{captureOn ? "Ekran OCR taraması etkin" : "Ekran OCR taraması kapalı"}</Text>
        {!nativeCapture ? <Notice kind="warning" text="Bu özellik için native Android APK gerekir." /> : null}
        {ocrError ? <Notice kind="error" text={ocrError} /> : null}
      </Card>

      <SectionTitle title="Hattı test et" subtitle="Yerel OCR gelene kadar OCR çıktısını buraya elle girin" />
      <Card>
        <Field
          testID="manual-ocr-input"
          label="OCR metni (İngilizce)"
          placeholder="Örn: Kratos, we must reach the summit before nightfall."
          value={input}
          onChangeText={setInput}
          multiline
          style={{ minHeight: 72, paddingTop: space.md, textAlignVertical: "top" }}
        />
        <View style={styles.btnRow}>
          <Button testID="manual-ocr-submit-button" label="Hatta gönder" icon="send-outline" onPress={() => ingest(input)} disabled={!input.trim()} style={{ flex: 1 }} />
          <Button testID="manual-ocr-empty-button" label="Altyazı kayboldu" icon="eye-off-outline" variant="secondary" onPress={() => ingest("")} style={{ flex: 1 }} />
        </View>
        <Text testID="pipeline-stats" style={styles.meta}>
          Alınan {state.stats.received} · Çevrilen {state.stats.translated} · Tekrar atlanan {state.stats.skippedSame} · Filtrelenen {state.stats.ignored}
        </Text>
        <Notice kind="info" text="Bu test gerçek çeviri, tekrar engelleme, gürültü filtresi ve temizleme gecikmesini çalıştırır. Gerçek OCR kaynağı (PS5 görüntüsü) sonraki aşamada bağlanacak." />
      </Card>
    </View>
  );

  const historyView = (
    <View style={wide ? { flex: 1 } : undefined}>
      <View style={[styles.rowBetween, { marginTop: space.xl, marginBottom: space.md }]}>
        <Text style={styles.sectionTitle}>Son altyazılar</Text>
        <Button testID="clear-history-button" label="Geçmişi temizle" variant="danger" icon="trash-outline" onPress={() => clearMut.mutate()} loading={clearMut.isPending} style={{ minHeight: 44 }} />
      </View>
      {history.isLoading ? (
        <ActivityIndicator color={colors.brandPrimary} />
      ) : history.isError ? (
        <Notice kind="error" text={`Geçmiş yüklenemedi: ${(history.error as Error).message}`} testID="history-error" />
      ) : !history.data?.length ? (
        <Card testID="history-empty">
          <Ionicons name="chatbubble-ellipses-outline" size={28} color={colors.muted} />
          <Text style={styles.meta}>Henüz çeviri yok. Oyun içi metin bekleniyor.</Text>
        </Card>
      ) : (
        <View style={{ gap: space.sm }} testID="history-list">
          {history.data.map((h) => (
            <View key={h.id} style={styles.histItem} testID={`history-item-${h.id}`}>
              <Text style={styles.histEn}>{h.source_text}</Text>
              <Text style={styles.histTr}>{h.translated_text}</Text>
              <Text style={styles.meta}>
                {new Date(h.created_at).toLocaleTimeString("tr-TR")} · {h.latency_ms} ms{h.cached ? " · önbellek" : ""}
              </Text>
            </View>
          ))}
        </View>
      )}
    </View>
  );

  return (
    <View style={styles.root} testID="translate-screen">
      <ScreenHeader title="Çeviri" subtitle="EN → TR" />
      <KeyboardAwareScrollView
        bottomOffset={24}
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={[styles.content, { paddingBottom: bottomChrome + space.xl }]}
        refreshControl={<RefreshControl refreshing={history.isRefetching} onRefresh={() => history.refetch()} tintColor={colors.brandPrimary} />}
      >
        {wide ? (
          <View style={{ flexDirection: "row", gap: space.xl }}>
            {current}
            {historyView}
          </View>
        ) : (
          <>
            {current}
            {historyView}
          </>
        )}
      </KeyboardAwareScrollView>
    </View>
  );
}

const useStyles = makeStyles((c) => ({
  root: { flex: 1, backgroundColor: c.surface },
  content: { paddingHorizontal: space.lg, maxWidth: 1300, width: "100%", alignSelf: "center" },
  rowBetween: { flexDirection: "row", justifyContent: "space-between", alignItems: "center", gap: space.md },
  label: { fontFamily: fonts.textMedium, fontSize: 12, color: c.muted, textTransform: "uppercase", letterSpacing: 0.6 },
  en: { fontFamily: fonts.text, fontSize: 16, lineHeight: 23, color: c.onSurfaceSecondary },
  tr: { fontFamily: fonts.textSemi, fontSize: 24, lineHeight: 32, color: c.onSurface },
  divider: { height: 1, backgroundColor: c.divider },
  meta: { fontFamily: fonts.text, fontSize: 12, color: c.muted },
  mono: { fontFamily: "SpaceMono", fontSize: 13, color: c.info },
  btnRow: { flexDirection: "row", gap: space.sm },
  sectionTitle: { fontFamily: fonts.display, fontSize: 22, color: c.onSurface, letterSpacing: 0.5, textTransform: "uppercase" },
  histItem: { backgroundColor: c.surfaceSecondary, borderRadius: radius.md, borderWidth: 1, borderColor: c.border, padding: space.md, gap: 4 },
  histEn: { fontFamily: fonts.text, fontSize: 13, color: c.muted },
  histTr: { fontFamily: fonts.textSemi, fontSize: 16, color: c.onSurface },
}));
