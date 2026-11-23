import { useState } from "react";
import { Text } from "react-native";

import { Button, Card, Notice } from "@/src/components/ui";
import { fonts } from "@/src/fonts";
import { hasNative, nativeModule } from "@/src/native/bridge";
import { makeStyles } from "@/src/theme";

const LABELS = {
  ocr: "OCR", comparison: "Metin karşılaştırma", ram: "RAM", sqlite: "SQLite",
  mlkit: "ML Kit", googleCloud: "Google Cloud", websocket: "WebSocket gönderim kuyruğu",
  ocrToWebSocket: "OCR sonucu → WebSocket",
};

export function TranslationMetricsCard() {
  const styles = useStyles();
  const [metrics, setMetrics] = useState<Record<string, { samples: number; lastMs: number; p50Ms: number; p95Ms: number }>>({});
  const [error, setError] = useState(false);
  return <Card>
    <Button label="Ölçümleri yenile" variant="secondary" disabled={!hasNative("getTranslationMetrics")} onPress={async () => {
      try { setMetrics(JSON.parse(await nativeModule!.getTranslationMetrics())); setError(false); }
      catch { setError(true); }
    }} />
    {Object.entries(LABELS).map(([key, label]) => {
      const row = metrics[key];
      return row ? <Text key={key} style={styles.detail}>{label} · son {row.lastMs} ms · p50 {row.p50Ms} ms · p95 {row.p95Ms} ms · {row.samples} örnek</Text> : null;
    })}
    <Text style={styles.detail}>TV ekranda çizim bildirimi göndermediği için son ölçüm Android’in gönderim kuyruğunda biter.</Text>
    {error ? <Notice kind="error" text="Ölçümler okunamadı." /> : null}
  </Card>;
}

const useStyles = makeStyles((c) => ({ detail: { fontFamily: fonts.text, color: c.muted } }));
