import { useEffect, useState } from "react";
import { Text, View } from "react-native";

import { Button, Card, Notice, StatusBadge } from "@/src/components/ui";
import { fonts, space } from "@/src/fonts";
import { hasNative, nativeModule } from "@/src/native/bridge";
import { useRemotePlay } from "@/src/native/RemotePlayProvider";
import { makeStyles } from "@/src/theme";

const LANGUAGES = { en: "İngilizce", tr: "Türkçe", ja: "Japonca", de: "Almanca", fr: "Fransızca", es: "İspanyolca" };
type Language = keyof typeof LANGUAGES;
type Operation = { language: Language; action: "download" | "remove" };

export function LanguageModelsCard({ source }: { source: string }) {
  const styles = useStyles();
  const { status } = useRemotePlay();
  const [downloaded, setDownloaded] = useState<string[] | null>(null);
  const [operation, setOperation] = useState<Operation | null>(null);
  const [error, setError] = useState<string | null>(null);
  const available = hasNative("getLanguageModels");
  const preparingModel = status?.translation === "ML Kit modeli hazırlanıyor";

  useEffect(() => {
    if (!available) return;
    let disposed = false;
    nativeModule!.getLanguageModels().then((models) => {
      if (!disposed) setDownloaded(models);
    }).catch(() => {
      if (!disposed) setError("Dil paketleri okunamadı.");
    });
    return () => { disposed = true; };
  }, [available, preparingModel]);

  const change = async (language: Language, action: Operation["action"]) => {
    if (!nativeModule || operation) return;
    setError(null);
    setOperation({ language, action });
    try {
      if (action === "download") await nativeModule.downloadLanguageModel(language);
      else await nativeModule.removeLanguageModel(language);
      setDownloaded(await nativeModule.getLanguageModels());
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setOperation(null);
    }
  };

  const shown = (Object.keys(LANGUAGES) as Language[]).filter((language) =>
    language === "en" || language === "tr" || language === source || downloaded?.includes(language)
  );
  return (
    <Card>
      <Text style={styles.help}>PS5 çevirisi İngilizce ve Türkçe paketlerini kullanır. Paketler indirildikten sonra çeviri cihazda çalışır.</Text>
      {!available ? <Notice kind="info" text="Dil paketleri Android uygulamasında yönetilir." /> : shown.map((language) => {
        const ready = downloaded?.includes(language);
        const working = operation?.language === language;
        return (
          <View key={language} style={styles.row}>
            <Text style={styles.language}>{LANGUAGES[language]}</Text>
            <StatusBadge kind={ready ? "ready" : "idle"}
              label={working ? operation.action === "download" ? "İndiriliyor" : "Kaldırılıyor" : downloaded == null ? "Kontrol ediliyor" : ready ? "İndirildi" : "İndirilmedi"} />
            <Button label={ready ? "Kaldır" : "İndir"} variant="secondary" loading={working}
              disabled={!!operation || (ready === true && status?.active)}
              onPress={() => change(language, ready ? "remove" : "download")} />
          </View>
        );
      })}
      {error ? <Notice kind="error" text={error} /> : null}
    </Card>
  );
}

const useStyles = makeStyles((c) => ({
  row: { flexDirection: "row", flexWrap: "wrap", alignItems: "center", gap: space.sm },
  language: { flex: 1, minWidth: 72, fontFamily: fonts.textSemi, color: c.onSurface },
  help: { fontFamily: fonts.text, color: c.muted },
}));
