import { Ionicons } from "@react-native-vector-icons/ionicons";
import { router } from "expo-router";
import { useState } from "react";
import { Linking, Pressable, ScrollView, Text, useWindowDimensions, View } from "react-native";
import { KeyboardAwareScrollView } from "react-native-keyboard-controller";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import { RegionPreview } from "@/src/components/RegionPreview";
import { ScreenHeader } from "@/src/components/ScreenHeader";
import { Button, Card, Chip, Field, Notice, SectionTitle, StatusBadge, Stepper, ToggleRow } from "@/src/components/ui";
import { fonts, space } from "@/src/fonts";
import { usesNativeTabs } from "@/src/navigation";
import { nativeAvailable } from "@/src/native/bridge";
import { DEFAULT_SETTINGS, useSettings } from "@/src/settings";
import { makeStyles, useTheme } from "@/src/theme";

const SOURCE = [
  { code: "en", label: "İngilizce" },
  { code: "ja", label: "Japonca" },
  { code: "de", label: "Almanca" },
  { code: "fr", label: "Fransızca" },
  { code: "es", label: "İspanyolca" },
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
  const { settings: s, update } = useSettings();
  const [glossary, setGlossary] = useState(s.glossary.join(", "));

  const left = (
    <View style={wide ? { flex: 1 } : undefined}>
      <SectionTitle title="Dil" />
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
        <Pressable testID="engine-online-option" onPress={() => update({ engine: "online" })} style={[styles.option, s.engine === "online" && styles.optionOn]}>
          <Ionicons name={s.engine === "online" ? "radio-button-on" : "radio-button-off"} size={20} color={colors.brandPrimary} />
          <View style={{ flex: 1, gap: 4 }}>
            <Text style={styles.optTitle}>Çevrimiçi yapay zekâ (yüksek kalite)</Text>
            <Text style={styles.meta}>İnternet gerekir. Altyazı metni çeviri sunucusuna gönderilir. Kullanım başına kredi harcar.</Text>
            <StatusBadge kind="ready" />
          </View>
        </Pressable>
        <Pressable testID="engine-offline-option" onPress={() => update({ engine: "offline" })} style={[styles.option, s.engine === "offline" && styles.optionOn]}>
          <Ionicons name={s.engine === "offline" ? "radio-button-on" : "radio-button-off"} size={20} color={colors.brandPrimary} />
          <View style={{ flex: 1, gap: 4 }}>
            <Text style={styles.optTitle}>Cihaz içi çevrimdışı (ML Kit, ücretsiz)</Text>
            <Text style={styles.meta}>İnternet gerekmez, veri cihazdan çıkmaz. Kalite orta. ~30 MB model bir kez indirilir.</Text>
            <StatusBadge kind={nativeAvailable ? "ready" : "native"} />
          </View>
        </Pressable>
        <Field
          testID="glossary-input"
          label="Çevrilmeyecek isimler / terimler"
          hint="Virgülle ayırın. Örn: Kratos, Atreus, Midgard"
          value={glossary}
          onChangeText={setGlossary}
          onEndEditing={() => update({ glossary: glossary.split(",").map((g) => g.trim()).filter(Boolean) })}
          onBlur={() => update({ glossary: glossary.split(",").map((g) => g.trim()).filter(Boolean) })}
        />
      </Card>
    </View>
  );

  const right = (
    <View style={wide ? { flex: 1 } : undefined}>
      <SectionTitle title="OCR ve altyazı algılama" />
      <Card>
        <Pressable testID="open-ocr-region-button" onPress={() => router.push("/ocr-region")}>
          <RegionPreview region={s.ocrRegion} />
          <Text style={[styles.meta, { marginTop: space.sm }]}>
            Altyazı alanı: üstten %{Math.round(s.ocrRegion.y * 100)}, yükseklik %{Math.round(s.ocrRegion.h * 100)} · düzenlemek için dokunun
          </Text>
        </Pressable>
        <Stepper testID="ocr-interval" label="OCR kontrol sıklığı" display={`${s.ocrIntervalMs} ms`}
          onDec={() => update({ ocrIntervalMs: clamp(s.ocrIntervalMs - 50, 100, 2000) })} onInc={() => update({ ocrIntervalMs: clamp(s.ocrIntervalMs + 50, 100, 2000) })} />
        <Stepper testID="ocr-sensitivity" label="Algılama hassasiyeti" display={`%${Math.round(s.sensitivity * 100)}`}
          onDec={() => update({ sensitivity: clamp(Math.round((s.sensitivity - 0.1) * 10) / 10, 0, 1) })} onInc={() => update({ sensitivity: clamp(Math.round((s.sensitivity + 0.1) * 10) / 10, 0, 1) })} />
        <Stepper testID="repeat-window" label="Aynı metni tekrar gönderme" display={`${(s.repeatWindowMs / 1000).toFixed(1)} sn`}
          onDec={() => update({ repeatWindowMs: clamp(s.repeatWindowMs - 500, 500, 30000) })} onInc={() => update({ repeatWindowMs: clamp(s.repeatWindowMs + 500, 500, 30000) })} />
        <Stepper testID="clear-delay" label="Altyazı temizleme gecikmesi" display={`${(s.clearDelayMs / 1000).toFixed(1)} sn`}
          onDec={() => update({ clearDelayMs: clamp(s.clearDelayMs - 200, 0, 10000) })} onInc={() => update({ clearDelayMs: clamp(s.clearDelayMs + 200, 0, 10000) })} />
      </Card>

      <SectionTitle title="Bağlantı ve arka plan" />
      <Card>
        <ToggleRow testID="auto-connect-toggle" label="Otomatik bağlantı" hint="Açılışta kayıtlı PS5'e ve TV sunucusuna bağlan" value={s.autoConnect} onChange={(v) => update({ autoConnect: v })} />
        <ToggleRow testID="foreground-service-toggle" label="Arka planda çalış" hint="Kalıcı bildirimli foreground service; ekran kapalıyken de devam eder" value={s.foregroundService} onChange={(v) => update({ foregroundService: v })} />
        <ToggleRow testID="wifi-lock-toggle" label="Wi-Fi düşük gecikme kilidi" hint="Oturum sırasında Wi-Fi güç tasarrufunu engeller" value={s.keepWifiAwake} onChange={(v) => update({ keepWifiAwake: v })} />
        <Notice kind="warning" text="Arka plan çalışma ayarları yerel modül yüklendiğinde uygulanır. Samsung cihazlarda Ayarlar → Pil → Arka plan kullanım sınırları bölümünden uygulamayı 'Hiç uyutma' listesine ekleyin." />
        <Button testID="open-app-settings-button" label="Uygulama ayarlarını aç" icon="open-outline" variant="secondary" onPress={() => Linking.openSettings()} />
        <Button testID="reset-settings-button" label="Varsayılanlara dön" icon="refresh-outline" variant="ghost"
          onPress={() => { update({ ...DEFAULT_SETTINGS, consoles: s.consoles, tvIp: s.tvIp }); setGlossary(""); }} />
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
      </KeyboardAwareScrollView>
    </View>
  );
}

const useStyles = makeStyles((c) => ({
  root: { flex: 1, backgroundColor: c.surface },
  content: { paddingHorizontal: space.lg, maxWidth: 1300, width: "100%", alignSelf: "center" },
  label: { fontFamily: fonts.textMedium, fontSize: 12, color: c.muted, textTransform: "uppercase", letterSpacing: 0.6 },
  chips: { gap: space.sm, alignItems: "center", height: 56 },
  option: { flexDirection: "row", gap: space.md, padding: space.md, borderRadius: 12, borderWidth: 1, borderColor: c.border },
  optionOn: { borderColor: c.brandPrimary, backgroundColor: c.surfaceTertiary },
  optTitle: { fontFamily: fonts.textSemi, fontSize: 15, color: c.onSurface },
  meta: { fontFamily: fonts.text, fontSize: 12, color: c.muted, lineHeight: 17 },
}));
