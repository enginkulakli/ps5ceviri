import { useEffect, useState } from "react";
import { Ionicons } from "@react-native-vector-icons/ionicons";
import { router } from "expo-router";
import { Linking, Pressable, ScrollView, Text, useWindowDimensions, View } from "react-native";
import { KeyboardAwareScrollView } from "react-native-keyboard-controller";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import { CloudTranslationCard } from "@/src/components/CloudTranslationCard";
import { TranslationMetricsCard } from "@/src/components/TranslationMetricsCard";
import { GameGlossaryEditor } from "@/src/components/GameGlossaryEditor";
import { LanguageModelsCard } from "@/src/components/LanguageModelsCard";
import { RegionPreview } from "@/src/components/RegionPreview";
import { ScreenHeader } from "@/src/components/ScreenHeader";
import { Button, Card, Chip, Notice, SectionTitle, StatusBadge, Stepper, ToggleRow } from "@/src/components/ui";
import { fonts, space } from "@/src/fonts";
import { hasNative, nativeModule } from "@/src/native/bridge";
import { usesNativeTabs } from "@/src/navigation";
import { DEFAULT_SETTINGS, type TranslationEngine, useSettings } from "@/src/settings";
import { makeStyles, useTheme } from "@/src/theme";
import { useTvServer } from "@/src/tv/TvServerProvider";

const SOURCE = [
  { code: "en", label: "İngilizce" },
  { code: "ja", label: "Japonca" },
  { code: "de", label: "Almanca" },
  { code: "fr", label: "Fransızca" },
  { code: "es", label: "İspanyolca" },
];
const ENGINES: { id: TranslationEngine; label: string; detail: string }[] = [
  { id: "offline", label: "Cihaz üzerinde · ML Kit", detail: "İndirilen dil paketleriyle çevrimdışı çeviri." },
  { id: "google_cloud", label: "Google Cloud Translation", detail: "Kendi anahtarınızla Basic v2" },
  { id: "ai", label: "Yapay zekâ API çevirisi", detail: "API yapılandırılmadı" },
];

const TARGET = [{ code: "tr", label: "Türkçe" }];

const clamp = (v: number, a: number, b: number) => Math.min(b, Math.max(a, v));

export default function SettingsScreen() {
  const styles = useStyles();
  const { colors } = useTheme();
  const insets = useSafeAreaInsets();
  const bottomChrome = usesNativeTabs ? insets.bottom : 0;
  const { width } = useWindowDimensions();
  const wide = width >= 900;
  const { settings: s, ready, update } = useSettings();
  const tv = useTvServer();
  const [versionName, setVersionName] = useState<string | null>(null);
  useEffect(() => {
    if (!hasNative("getInstalledVersionName")) return;
    let mounted = true;
    void nativeModule!.getInstalledVersionName().then(version => {
      if (mounted) setVersionName(version);
    }).catch(() => {});
    return () => { mounted = false; };
  }, []);

  const left = (
    <View style={wide ? { flex: 1 } : undefined}>
      <SectionTitle title="Çeviri modu" />
      <Card>
        <View style={styles.row}>
          <Chip testID="translation-mode-subtitle" label="Altyazı Çeviri" selected={s.mode === "subtitle"} onPress={() => update({ mode: "subtitle" })} />
          <Chip testID="translation-mode-fullscreen" label="Tam Ekran Çeviri" selected={s.mode === "fullscreen"} onPress={() => update({ mode: "fullscreen" })} />
          <Chip testID="translation-mode-phone" label="Telefon Çeviri" selected={s.mode === "phone"} onPress={() => update({ mode: "phone" })} />
        </View>
        <Text style={styles.meta}>{s.mode === "subtitle" ? "Seçili altyazı alanı taranır." :
          s.mode === "phone" ? "Sabit mesajlar ekranda kalır. Görüntü değişince ilgili alanlar okunur." :
          "Tam kare düzenli olarak taranır."}</Text>
      </Card>

      <SectionTitle title="Manuel test dili" />
      <Card>
        <Text style={styles.label}>Kaynak dil</Text>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.chips}>
          {SOURCE.map((l) => (
            <Chip key={l.code} testID={`source-lang-${l.code}`} label={l.label} selected={s.sourceLang === l.code} onPress={() => update({ sourceLang: l.code })} />
          ))}
        </ScrollView>
        <Text style={styles.label}>Hedef dil</Text>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.chips}>
          {TARGET.map((l) => (
            <Chip key={l.code} testID={`target-lang-${l.code}`} label={l.label} selected={s.targetLang === l.code} onPress={() => update({ targetLang: l.code })} />
          ))}
        </ScrollView>
      </Card>

      <SectionTitle title="Çeviri motoru" />
      <Card>
        {ENGINES.map(({ id, label, detail }) => (
          <Pressable key={id} testID={`engine-${id}-option`} onPress={() => update({ engine: id })}
            accessibilityRole="radio" accessibilityState={{ checked: s.engine === id }}
            style={[styles.option, s.engine === id && styles.optionOn]}>
            <Ionicons name={s.engine === id ? "radio-button-on" : "radio-button-off"} size={20} color={colors.brandPrimary} />
            <View style={{ flex: 1, gap: 4 }}>
              <Text style={styles.optTitle}>{label}</Text>
              <Text style={styles.meta}>{detail}</Text>
            </View>
          </Pressable>
        ))}
      </Card>
      <SectionTitle title="Google Cloud ayarları" />
      <CloudTranslationCard />
      <SectionTitle title="Çeviri arşivi" />
      <Card>
        <Button label="Çeviri arşivini aç" icon="albums-outline" onPress={() => router.push("/translation-archive")} />
        <ToggleRow testID="archive-general" label="Genel arşivi kullan" hint="Aktif oyunda eşleşme bulunamazsa genel arşive bakılır." value={s.generalArchive} onChange={(v) => update({ generalArchive: v })} />
        <ToggleRow testID="archive-cross-engine" label="Motorlar arası kayıtları kullan" hint="Uygun yerel çeviriler seçili motordan bağımsız kullanılabilir." value={s.shareEngines} onChange={(v) => update({ shareEngines: v })} />
      </Card>
      <SectionTitle title="Dil paketleri" />
      <LanguageModelsCard source={s.sourceLang} />
      <SectionTitle title="Oyun sözlüğü" />
      {ready ? <GameGlossaryEditor key={JSON.stringify(s.glossary)} entries={s.glossary} onSave={(glossary) => update({ glossary })} /> : null}
    </View>
  );

  const right = (
    <View style={wide ? { flex: 1 } : undefined}>
      <SectionTitle title="OCR ve altyazı algılama" />
      <Card>
        <Pressable testID="open-ocr-region-button" onPress={() => router.push("/ocr-region")}>
          <RegionPreview region={s.ocrRegion} exclusions={s.exclusions} />
          <Text style={[styles.meta, { marginTop: space.sm }]}>
            Altyazı alanı: üstten %{Math.round(s.ocrRegion.y * 100)}, yükseklik %{Math.round(s.ocrRegion.h * 100)} · düzenlemek için dokunun
          </Text>
        </Pressable>
        <Stepper testID="ocr-interval" label="OCR kontrol sıklığı" display={`${s.ocrIntervalMs} ms`}
          onDec={() => update({ ocrIntervalMs: clamp(s.ocrIntervalMs - 50, 50, 2000) })} onInc={() => update({ ocrIntervalMs: clamp(s.ocrIntervalMs + 50, 50, 2000) })} />
        <Stepper testID="ocr-sensitivity" label="Algılama hassasiyeti" display={`%${Math.round(s.sensitivity * 100)}`}
          onDec={() => update({ sensitivity: clamp(Math.round((s.sensitivity - 0.1) * 10) / 10, 0, 1) })} onInc={() => update({ sensitivity: clamp(Math.round((s.sensitivity + 0.1) * 10) / 10, 0, 1) })} />
        <Stepper testID="repeat-window" label="Manuel testte tekrar engelleme" display={`${(s.repeatWindowMs / 1000).toFixed(1)} sn`}
          onDec={() => update({ repeatWindowMs: clamp(s.repeatWindowMs - 500, 500, 30000) })} onInc={() => update({ repeatWindowMs: clamp(s.repeatWindowMs + 500, 500, 30000) })} />
        <Stepper testID="ocr-confirmations" label="Altyazı kararlılığı" display={`${s.confirmations} gözlem`}
          onDec={() => update({ confirmations: clamp(s.confirmations - 1, 1, 4) })} onInc={() => update({ confirmations: clamp(s.confirmations + 1, 1, 4) })} />
        <Stepper testID="subtitle-minimum-time" label="En kısa görünme süresi" display={`${s.minimumVisibleMs} ms`}
          onDec={() => update({ minimumVisibleMs: clamp(s.minimumVisibleMs - 100, 0, 3000) })} onInc={() => update({ minimumVisibleMs: clamp(s.minimumVisibleMs + 100, 0, 3000) })} />
        <Stepper testID="clear-delay" label="Altyazı temizleme gecikmesi" display={`${(s.clearDelayMs / 1000).toFixed(1)} sn`}
          onDec={() => update({ clearDelayMs: clamp(s.clearDelayMs - 200, 500, 10000) })} onInc={() => update({ clearDelayMs: clamp(s.clearDelayMs + 200, 500, 10000) })} />
      </Card>

      <SectionTitle title="Çeviri performansı" />
      <TranslationMetricsCard />
      <SectionTitle title="Bağlantı ve arka plan" />
      <Card>
        <ToggleRow testID="auto-connect-toggle" label="Otomatik bağlantı" hint="Uygulama açılınca TV sunucusunu başlat; arka planda çalış açıksa cihaz yeniden başlayınca da başlatır" value={s.autoConnect} onChange={(v) => update({ autoConnect: v })} />
        <ToggleRow testID="foreground-service-toggle" label="Arka planda çalış" hint="TV sunucusu kalıcı bildirimli foreground service olarak çalışır; ekran kapalıyken de devam eder" value={s.foregroundService} onChange={(v) => update({ foregroundService: v })} />
        <ToggleRow testID="wifi-lock-toggle" label="Wi-Fi düşük gecikme kilidi" hint="Oturum sırasında Wi-Fi güç tasarrufunu engeller" value={s.keepWifiAwake} onChange={(v) => update({ keepWifiAwake: v })} />
        <View style={{ gap: space.sm }}>
          <View style={{ flexDirection: "row", justifyContent: "space-between", alignItems: "center" }}>
            <Text style={styles.optTitle}>Pil optimizasyonu</Text>
            <StatusBadge testID="battery-exempt-badge"
              kind={!tv.available ? "native" : tv.batteryExempt ? "ready" : "idle"}
              label={!tv.available ? undefined : tv.batteryExempt ? "Muaf" : "Kısıtlı olabilir"} />
          </View>
          <Text style={styles.meta}>Samsung pil yöneticisi uzun süre arka planda kalan uygulamaları uyutabilir. Muaf tutarsanız TV sunucusu ekran kapalıyken de kesintisiz çalışır.</Text>
          {tv.available && !tv.batteryExempt ? (
            <Button testID="battery-exempt-button" label="Pil optimizasyonundan muaf tut" icon="battery-charging-outline" variant="secondary" onPress={tv.requestBatteryExemption} />
          ) : null}
          <Notice kind="info" text="Samsung cihazlarda ayrıca: Ayarlar → Pil → Arka plan kullanım sınırları → 'Hiç uyutulmayan uygulamalar' listesine ekleyin." />
        </View>
        <Button testID="open-app-settings-button" label="Uygulama ayarlarını aç" icon="open-outline" variant="secondary" onPress={() => Linking.openSettings()} />
        <Button testID="reset-settings-button" label="Varsayılanlara dön" icon="refresh-outline" variant="ghost"
          onPress={() => update({ ...DEFAULT_SETTINGS, consoles: s.consoles, selectedConsoleId: s.selectedConsoleId, tvIp: s.tvIp })} />
      </Card>
    </View>
  );

  return (
    <View style={styles.root} testID="settings-screen">
      <ScreenHeader title="Ayarlar" subtitle="Değişiklikler otomatik kaydedilir" />
      <KeyboardAwareScrollView bottomOffset={24} keyboardShouldPersistTaps="handled" contentContainerStyle={[styles.content, { paddingBottom: bottomChrome + space.xl }]}>
        {wide ? (
          <View style={{ flexDirection: "row", gap: space.xl }}>
            {left}
            {right}
          </View>
        ) : (
          <>
            {left}
            {right}
          </>
        )}
        {versionName ? <Text style={styles.meta} testID="app-version">v{versionName}</Text> : null}
      </KeyboardAwareScrollView>
    </View>
  );
}

const useStyles = makeStyles((c) => ({
  root: { flex: 1, backgroundColor: c.surface },
  content: { paddingHorizontal: space.lg, maxWidth: 1300, width: "100%", alignSelf: "center" },
  label: { fontFamily: fonts.textMedium, fontSize: 12, color: c.muted, textTransform: "uppercase", letterSpacing: 0.6 },
  row: { flexDirection: "row", flexWrap: "wrap", gap: space.sm },
  chips: { gap: space.sm, alignItems: "center", height: 56 },
  option: { flexDirection: "row", gap: space.md, padding: space.md, borderRadius: 12, borderWidth: 1, borderColor: c.border },
  optionOn: { borderColor: c.brandPrimary, backgroundColor: c.surfaceTertiary },
  optTitle: { fontFamily: fonts.textSemi, fontSize: 15, color: c.onSurface },
  meta: { fontFamily: fonts.text, fontSize: 12, color: c.muted, lineHeight: 17 },
}));
