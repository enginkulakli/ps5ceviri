import { useState } from "react";
import { Linking, Text, View } from "react-native";

import { Button, Card, Field, Notice, StatusBadge } from "@/src/components/ui";
import { fonts, radius, space } from "@/src/fonts";
import { NATIVE_REQUIRED_MESSAGE } from "@/src/native/bridge";
import { makeStyles } from "@/src/theme";
import { useTvServer } from "@/src/tv/TvServerProvider";

const fmtTime = (ms: number) => (ms > 0 ? new Date(ms).toLocaleTimeString("tr-TR") : "—");

export function TvServerPanel({ idPrefix, showManual = true }: { idPrefix: string; showManual?: boolean }) {
  const styles = useStyles();
  const tv = useTvServer();
  const s = tv.state;
  const [manual, setManual] = useState("");
  const [explain, setExplain] = useState(false);
  const id = (x: string) => `${idPrefix}-${x}`;

  const onStart = () => {
    if (tv.notifPermission === "unknown") {
      setExplain(true);
      return;
    }
    tv.start();
  };

  const rows: { k: string; label: string; value: string; badge?: "ready" | "error" | "idle" | "native" }[] = [
    { k: "running", label: "Android sunucusu", value: !tv.available ? "Yerel modül yok" : s?.running ? "Çalışıyor" : "Durdu", badge: !tv.available ? "native" : s?.running ? "ready" : "idle" },
    { k: "ip", label: "Tablet yerel IP", value: s?.localIp ?? "—" },
    { k: "port", label: `WebSocket portu ${s?.port ?? 8787}`, value: s?.running ? "Açık" : "Kapalı", badge: s?.running ? "ready" : "idle" },
    { k: "tv", label: "TV bağlı mı", value: s ? (s.clientCount > 0 ? `Evet · ${s.clientIps.join(", ")}` : "Hayır") : "—", badge: s && s.clientCount > 0 ? "ready" : "idle" },
    { k: "wifi", label: "Wi-Fi", value: s ? (s.wifiConnected ? "Bağlı" : "Bağlı değil") : "—", badge: s ? (s.wifiConnected ? "ready" : "error") : undefined },
  ];

  return (
    <Card testID={id("tv-server-panel")}>
      {!tv.available ? <Notice kind="warning" text={NATIVE_REQUIRED_MESSAGE} testID={id("native-missing")} /> : null}

      {rows.map((r) => (
        <View key={r.k} style={styles.row}>
          <Text style={styles.label}>{r.label}</Text>
          {r.badge ? (
            <StatusBadge kind={r.badge} label={r.value} testID={id(`state-${r.k}`)} />
          ) : (
            <Text testID={id(`state-${r.k}`)} style={styles.value} numberOfLines={1}>
              {r.value}
            </Text>
          )}
        </View>
      ))}

      {s?.running && s.localIp ? (
        <View style={styles.urlBox}>
          <Text style={styles.label}>TV uygulamasındaki adres</Text>
          <Text testID={id("ws-url")} style={styles.mono}>{`ws://${s.localIp}:${s.port}`}</Text>
        </View>
      ) : null}

      <View style={styles.block}>
        <Text style={styles.label}>Son gönderilen altyazı</Text>
        <Text testID={id("last-sent")} style={styles.lastSent}>
          {s?.lastSentText == null ? "—" : s.lastSentText === "" ? "(boş altyazı — TV temizlendi)" : s.lastSentText}
        </Text>
        <Text style={styles.meta}>
          {s ? `${fmtTime(s.lastSentAtMs)} · ${s.lastSentClients} TV · toplam ${s.sentCount} mesaj${s.rejectedCount ? ` · ${s.rejectedCount} reddedilen` : ""}` : ""}
        </Text>
      </View>

      <View style={styles.block}>
        <Text style={styles.label}>Son bağlantı hatası</Text>
        <Text testID={id("last-error")} style={[styles.value, s?.lastError || tv.actionError ? styles.err : null]}>
          {tv.actionError ?? s?.lastError ?? "Yok"}
        </Text>
        {s?.lastError ? <Text style={styles.meta}>{fmtTime(s.lastErrorAtMs)}</Text> : null}
      </View>

      {explain ? (
        <View testID={id("notif-explain")} style={{ gap: space.sm }}>
          <Notice kind="info" text="Sunucu arka planda çalışırken Android kalıcı bir bildirim gösterir. Bildirim izni verirseniz sunucunun durumunu ve 'Durdur' düğmesini bildirimde görürsünüz. İzin vermezseniz sunucu yine çalışır ama bildirim görünmez." />
          <View style={styles.btnRow}>
            <Button testID={id("notif-allow-start")} label="İzin ver ve başlat" style={{ flex: 1 }}
              onPress={async () => { setExplain(false); await tv.requestNotifPermission(); tv.start(); }} />
            <Button testID={id("notif-skip-start")} label="Bildirimsiz başlat" variant="secondary" style={{ flex: 1 }}
              onPress={() => { setExplain(false); tv.start(); }} />
          </View>
        </View>
      ) : null}
      {tv.notifPermission === "blocked" ? (
        <View style={{ gap: space.sm }}>
          <Notice kind="warning" text="Bildirim izni kapalı. Sunucu çalışır ancak kalıcı bildirim görünmez." />
          <Button testID={id("open-settings")} label="Ayarları aç" icon="open-outline" variant="secondary" onPress={() => Linking.openSettings()} />
        </View>
      ) : null}

      <View style={styles.btnRow}>
        {s?.running ? (
          <Button testID={id("stop-server")} label="Sunucuyu durdur" icon="stop-circle-outline" variant="danger" style={{ flex: 1 }} loading={tv.busy === "stop"} onPress={tv.stop} disabled={!tv.available} />
        ) : (
          <Button testID={id("start-server")} label="Sunucuyu başlat" icon="power-outline" style={{ flex: 1 }} loading={tv.busy === "start"} onPress={onStart} disabled={!tv.available} />
        )}
        <Button testID={id("test-tv")} label="TV bağlantısını test et" icon="pulse-outline" variant="secondary" style={{ flex: 1 }} loading={tv.busy === "test"} onPress={tv.test} disabled={!tv.available} />
      </View>

      {showManual ? (
        <View style={{ gap: space.sm }}>
          <Field testID={id("manual-text")} label="Manuel altyazı gönder" placeholder="TV'de görünecek Türkçe metin" value={manual} onChangeText={setManual} />
          <View style={styles.btnRow}>
            <Button testID={id("manual-send")} label="Gönder" icon="send-outline" style={{ flex: 1 }} loading={tv.busy === "send"} disabled={!tv.available || !manual.trim()} onPress={() => tv.sendManual(manual.trim())} />
            <Button testID={id("manual-clear")} label="Boş gönder" icon="eye-off-outline" variant="secondary" style={{ flex: 1 }} disabled={!tv.available} onPress={() => tv.sendManual("")} />
          </View>
        </View>
      ) : null}
    </Card>
  );
}

const useStyles = makeStyles((c) => ({
  row: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: space.md, minHeight: 32 },
  label: { fontFamily: fonts.textMedium, fontSize: 12, color: c.muted, textTransform: "uppercase", letterSpacing: 0.6, flexShrink: 1 },
  value: { fontFamily: fonts.textMedium, fontSize: 14, color: c.onSurface, flexShrink: 1, textAlign: "right" },
  err: { color: c.error, textAlign: "left" },
  block: { gap: 4 },
  lastSent: { fontFamily: fonts.textSemi, fontSize: 18, lineHeight: 25, color: c.onSurface },
  meta: { fontFamily: fonts.text, fontSize: 12, color: c.muted },
  urlBox: { backgroundColor: c.surfaceTertiary, borderRadius: radius.sm, padding: space.md, gap: 4 },
  mono: { fontFamily: "SpaceMono", fontSize: 15, color: c.info },
  btnRow: { flexDirection: "row", gap: space.sm },
}));
