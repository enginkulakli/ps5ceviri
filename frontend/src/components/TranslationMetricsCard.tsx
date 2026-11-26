import { useState } from "react";
import { Share, Text } from "react-native";

import { Button, Card, Notice } from "@/src/components/ui";
import { fonts } from "@/src/fonts";
import { hasNative, nativeModule } from "@/src/native/bridge";
import { makeStyles } from "@/src/theme";

const LABELS = {
  framePreparation: "Kare hazırlığı", ocr: "ML Kit OCR", ocrDelivery: "OCR sonucu işleme kuyruğu",
  stability: "İlk okuma → altyazı onayı", comparison: "Metin karşılaştırma", ram: "RAM", sqlite: "SQLite",
  mlkit: "Yerel çeviri", googleCloud: "Google Cloud HTTP", googlePipeline: "Google Cloud çeviri işlemi", websocket: "WebSocket gönderim kuyruğu",
  ocrToWebSocket: "Kare → WebSocket", localSubtitleTotal: "Yerel altyazı · kare → WebSocket",
  cloudSubtitleTotal: "Google Cloud altyazı · kare → WebSocket",
};

export function TranslationMetricsCard() {
  const styles = useStyles();
  const [metrics, setMetrics] = useState<Record<string, { samples: number; lastMs: number; p50Ms: number; p95Ms: number }>>({});
  const [error, setError] = useState(false);
  const [diagnostics, setDiagnostics] = useState(false);
  const [diagnosticError, setDiagnosticError] = useState(false);
  const diagnosticAvailable = hasNative("getScreenOcrDiagnostics") && hasNative("setScreenOcrDiagnostics");
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
    <Button label={diagnostics ? "OCR tanılamasını kapat" : "Tam ekran OCR tanılamasını aç"}
      variant="secondary" disabled={!diagnosticAvailable} onPress={async () => {
        try { await nativeModule!.setScreenOcrDiagnostics(!diagnostics); setDiagnostics(!diagnostics); setDiagnosticError(false); }
        catch { setDiagnosticError(true); }
      }} />
    {diagnostics ? <>
      <Text style={styles.detail}>Son üç tam ekran OCR karesi algılanan metinlerle birlikte bellekte tutulur.</Text>
      <Button label="OCR tanılamasını paylaş" variant="secondary" onPress={async () => {
        try { await Share.share({ message: await nativeModule!.getScreenOcrDiagnostics() }); setDiagnosticError(false); }
        catch { setDiagnosticError(true); }
      }} />
    </> : null}
    {diagnosticError ? <Notice kind="error" text="OCR tanılaması okunamadı." /> : null}
  </Card>;
}

const useStyles = makeStyles((c) => ({ detail: { fontFamily: fonts.text, color: c.muted } }));
