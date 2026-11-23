import { useCallback, useState } from "react";
import { useFocusEffect } from "expo-router";
import { Alert, Text, View } from "react-native";

import { Button, Card, Field, Notice, StatusBadge } from "@/src/components/ui";
import { fonts, space } from "@/src/fonts";
import { hasNative, nativeModule } from "@/src/native/bridge";
import { useSettings } from "@/src/settings";
import { makeStyles } from "@/src/theme";

type CloudStatus = {
  configured: boolean; status: string;
  dailyCharacters: number; monthlyCharacters: number;
  dailyRequests: number; monthlyRequests: number;
};

export function CloudTranslationCard() {
  const styles = useStyles();
  const { settings, update } = useSettings();
  const [key, setKey] = useState("");
  const [visible, setVisible] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [cloud, setCloud] = useState<CloudStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const available = hasNative("getCloudStatus");

  const refresh = useCallback(async () => {
    if (hasNative("getCloudStatus")) setCloud(JSON.parse(await nativeModule!.getCloudStatus()));
  }, []);
  useFocusEffect(useCallback(() => {
    void refresh().catch(() => setError("Google Cloud ayarları okunamadı."));
    return () => { setKey(""); setVisible(false); };
  }, [refresh]));

  const act = async (operation: string, action: () => Promise<void>) => {
    if (busy) return;
    setBusy(operation);
    setError(null);
    try { await action(); }
    catch (cause) { setError(cause instanceof Error ? cause.message : String(cause)); }
    finally {
      try { await refresh(); } catch {}
      setBusy(null);
    }
  };

  const confirmDelete = () => Alert.alert("API anahtarını sil", "Bu cihazdaki Google Cloud anahtarı silinecek.", [
    { text: "Vazgeç", style: "cancel" },
    { text: "Sil", style: "destructive", onPress: () => void act("delete", async () => {
      await nativeModule!.deleteCloudKey();
      setKey("");
      setVisible(false);
    }) },
  ]);

  return (
    <Card>
      <StatusBadge kind={cloud?.status === "Google Cloud bağlı" ? "ready" : "idle"} label={cloud?.status ?? "API yapılandırılmadı"} />
      <Field label="Google Cloud API anahtarı" value={key} onChangeText={setKey} secureTextEntry={!visible}
        autoCapitalize="none" autoCorrect={false} maxLength={180} editable={available && !busy}
        placeholder={cloud?.configured ? "Anahtar cihazda kayıtlı" : "Kendi API anahtarınızı girin"} />
      <View style={styles.buttons}>
        <Button label={visible ? "Gizle" : "Göster"} variant="secondary" disabled={!available || !!busy}
          onPress={() => act("reveal", async () => {
            if (!visible && !key && cloud?.configured) setKey(await nativeModule!.revealCloudKey() ?? "");
            setVisible(!visible);
          })} />
        <Button label="Anahtarı kaydet" disabled={!available || !key.trim() || !!busy} loading={busy === "save"}
          onPress={() => act("save", async () => { await nativeModule!.saveCloudKey(key); setKey(""); setVisible(false); })} />
        <Button label="Anahtarı sil" variant="secondary" disabled={!cloud?.configured || !!busy} onPress={confirmDelete} />
      </View>
      <Field label="Günlük karakter sınırı" keyboardType="number-pad" value={String(settings.dailyCharacters)}
        onChangeText={(value) => { if (/^\d{0,8}$/.test(value)) update({ dailyCharacters: Math.min(10000000, Number(value)) }); }} />
      <Field label="Aylık karakter sınırı" keyboardType="number-pad" value={String(settings.monthlyCharacters)}
        onChangeText={(value) => { if (/^\d{0,9}$/.test(value)) update({ monthlyCharacters: Math.min(100000000, Number(value)) }); }} />
      <Text style={styles.detail}>Bugün {cloud?.dailyCharacters ?? 0} karakter · {cloud?.dailyRequests ?? 0} istek</Text>
      <Text style={styles.detail}>Bu ay {cloud?.monthlyCharacters ?? 0} karakter · {cloud?.monthlyRequests ?? 0} istek</Text>
      <Text style={styles.detail}>Bağlantı testi 5 karakterlik bir çeviri isteği gönderir. UTC sayaçları gönderim denemelerini içerir. Yerel sınırlar Google Cloud faturası için kesin harcama sınırı oluşturmaz.</Text>
      <View style={styles.buttons}>
        <Button label="Bağlantıyı test et" variant="secondary" loading={busy === "test"} disabled={!cloud?.configured || !!busy}
          onPress={() => act("test", async () => { await nativeModule!.testCloudConnection(settings.dailyCharacters, settings.monthlyCharacters); })} />
        <Button label="Sayaçları yenile" variant="ghost" disabled={!available || !!busy} onPress={() => act("refresh", refresh)} />
      </View>
      {error ? <Notice kind="error" text={error} /> : null}
    </Card>
  );
}

const useStyles = makeStyles((c) => ({
  buttons: { flexDirection: "row", flexWrap: "wrap", gap: space.sm },
  detail: { fontFamily: fonts.text, color: c.muted },
}));
