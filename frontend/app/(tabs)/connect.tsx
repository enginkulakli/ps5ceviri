import * as Network from "expo-network";
import { useEffect, useState } from "react";
import { Platform, Text, useWindowDimensions, View } from "react-native";
import { KeyboardAwareScrollView } from "react-native-keyboard-controller";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import { ScreenHeader } from "@/src/components/ScreenHeader";
import { Button, Card, Field, Notice, SectionTitle, StatusBadge } from "@/src/components/ui";
import { fonts, radius, space } from "@/src/fonts";
import { usesNativeTabs } from "@/src/navigation";
import { NATIVE_REQUIRED_MESSAGE, nativeAvailable, nativeModule } from "@/src/native/bridge";
import { PSN_ACCOUNT_KEY, useSettings } from "@/src/settings";
import { makeStyles, useTheme } from "@/src/theme";
import { storage } from "@/src/utils/storage";

const IP_RE = /^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$/;

type Msg = { kind: "warning" | "error" | "success" | "info"; text: string } | null;

export default function ConnectScreen() {
  const styles = useStyles();
  const { colors } = useTheme();
  const insets = useSafeAreaInsets();
  const bottomChrome = usesNativeTabs ? insets.bottom : 0;
  const { width } = useWindowDimensions();
  const wide = width >= 900;
  const { settings, update } = useSettings();

  const [tabletIp, setTabletIp] = useState<string | null>(null);
  const [wifi, setWifi] = useState<string>("");
  const [name, setName] = useState("");
  const [host, setHost] = useState("");
  const [hostErr, setHostErr] = useState<string | null>(null);
  const [accountId, setAccountId] = useState("");
  const [accountSaved, setAccountSaved] = useState(false);
  const [pin, setPin] = useState("");
  const [tvIpErr, setTvIpErr] = useState<string | null>(null);
  const [tvIpDraft, setTvIpDraft] = useState<string | null>(null);
  const [psMsg, setPsMsg] = useState<Msg>(null);
  const [tvMsg, setTvMsg] = useState<Msg>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const loadNetwork = async () => {
    try {
      const [ip, st] = await Promise.all([Network.getIpAddressAsync(), Network.getNetworkStateAsync()]);
      setTabletIp(ip && ip !== "0.0.0.0" ? ip : null);
      setWifi(st.type === Network.NetworkStateType.WIFI ? "Wi-Fi" : String(st.type ?? "Bilinmiyor"));
    } catch {
      setTabletIp(null);
    }
  };

  useEffect(() => {
    loadNetwork();
    storage.secureGet(PSN_ACCOUNT_KEY, null).then((v) => setAccountSaved(!!v));
  }, []);

  const needNative = async (key: string, setMsg: (m: Msg) => void, fn: () => Promise<string>) => {
    if (!nativeModule) {
      setMsg({ kind: "warning", text: NATIVE_REQUIRED_MESSAGE });
      return;
    }
    setBusy(key);
    try {
      setMsg({ kind: "success", text: await fn() });
    } catch (e: any) {
      setMsg({ kind: "error", text: e?.message ?? "Bilinmeyen hata" });
    } finally {
      setBusy(null);
    }
  };

  const addConsole = () => {
    if (!IP_RE.test(host.trim())) {
      setHostErr("Geçerli bir IPv4 adresi girin (örn. 192.168.1.40)");
      return;
    }
    setHostErr(null);
    update({ consoles: [...settings.consoles, { id: String(Date.now()), name: name.trim() || "PS5", host: host.trim(), registered: false }] });
    setName("");
    setHost("");
    setPsMsg({ kind: "success", text: "Konsol eklendi. Bağlanmak için cihaz kaydı gerekir." });
  };

  const saveAccount = async () => {
    const v = accountId.trim();
    if (!/^[A-Za-z0-9+/=]{8,}$/.test(v)) {
      setPsMsg({ kind: "error", text: "PSN Account ID base64 formatında olmalıdır (kullanıcı adı veya şifre DEĞİL)." });
      return;
    }
    const ok = await storage.secureSet(PSN_ACCOUNT_KEY, v);
    setAccountSaved(ok);
    setAccountId("");
    setPsMsg(ok ? { kind: "success", text: "PSN Account ID cihazın güvenli deposuna kaydedildi." } : { kind: "error", text: "Güvenli depoya yazılamadı." });
  };

  const wsUrl = `ws://${tabletIp ?? "TABLET_IP"}:${settings.tvPort}`;

  const ps5 = (
    <View style={wide ? { flex: 1 } : undefined}>
      <SectionTitle title="PS5 bağlantısı" subtitle="chiaki-ng motoru · yerel modül" />
      <Card testID="ps5-section">
        <View style={styles.rowBetween}>
          <Text style={styles.label}>Remote Play motoru</Text>
          <StatusBadge kind={nativeAvailable ? "ready" : "native"} testID="ps5-engine-badge" />
        </View>
        <Button testID="discover-consoles-button" label="Ağda konsol bul" icon="search-outline" variant="secondary" loading={busy === "discover"}
          onPress={() => needNative("discover", setPsMsg, async () => {
            const list = await nativeModule!.discoverConsoles(3000);
            return list.length ? `${list.length} konsol bulundu: ${list.map((c) => `${c.name} (${c.host})`).join(", ")}` : "Konsol bulunamadı. PS5 açık ve aynı ağda mı?";
          })}
        />
        <View style={styles.divider} />
        <Text style={styles.label}>Manuel ekle</Text>
        <Field testID="console-name-input" label="Ad" placeholder="Salon PS5" value={name} onChangeText={setName} />
        <Field testID="console-ip-input" label="PS5 IP adresi" placeholder="192.168.1.40" keyboardType="numbers-and-punctuation" autoCapitalize="none" value={host} onChangeText={setHost} error={hostErr} />
        <Button testID="add-console-button" label="PS5 ekle" icon="add-circle-outline" onPress={addConsole} />
        {settings.consoles.length ? (
          <View style={{ gap: space.sm }} testID="console-list">
            {settings.consoles.map((c) => (
              <View key={c.id} style={styles.consoleRow} testID={`console-item-${c.id}`}>
                <View style={{ flex: 1 }}>
                  <Text style={styles.consoleName}>{c.name}</Text>
                  <Text style={styles.meta}>{c.host} · {c.registered ? "Kayıtlı" : "Kayıtsız"}</Text>
                </View>
                <Button testID={`remove-console-${c.id}`} label="Sil" variant="danger" style={{ minHeight: 44 }} onPress={() => update({ consoles: settings.consoles.filter((x) => x.id !== c.id) })} />
              </View>
            ))}
          </View>
        ) : null}
      </Card>

      <SectionTitle title="PSN kimliği ve cihaz kaydı" />
      <Card>
        <Notice kind="info" text="PSN şifreniz bu uygulamada istenmez ve saklanmaz. Kayıt için yalnızca sayısal PSN Account ID (base64) ve PS5'te Ayarlar → Sistem → Remote Play → Cihazı Eşle ekranındaki 8 haneli PIN gerekir." />
        <View style={styles.rowBetween}>
          <Text style={styles.label}>PSN Account ID</Text>
          <StatusBadge kind={accountSaved ? "ready" : "idle"} label={accountSaved ? "Güvenli depoda" : "Girilmedi"} testID="psn-account-badge" />
        </View>
        <Field testID="psn-account-id-input" label="Account ID (base64)" placeholder="ör. AbCdEfGh12A=" autoCapitalize="none" secureTextEntry value={accountId} onChangeText={setAccountId} />
        <Button testID="save-psn-account-button" label="Güvenli kaydet" icon="lock-closed-outline" variant="secondary" onPress={saveAccount} disabled={!accountId.trim()} />
        <Field testID="register-pin-input" label="PS5 eşleme PIN'i" placeholder="12345678" keyboardType="number-pad" maxLength={8} value={pin} onChangeText={setPin} />
        <View style={styles.btnRow}>
          <Button testID="register-console-button" label="Cihaz kaydı" icon="key-outline" style={{ flex: 1 }} loading={busy === "register"}
            onPress={() => needNative("register", setPsMsg, async () => {
              const c = settings.consoles[0];
              if (!c) throw new Error("Önce bir PS5 ekleyin.");
              const acc = await storage.secureGet(PSN_ACCOUNT_KEY, null);
              if (!acc) throw new Error("Önce PSN Account ID kaydedin.");
              if (!/^\d{8}$/.test(pin)) throw new Error("PIN 8 haneli olmalıdır.");
              const r = await nativeModule!.registerConsole(c.host, String(acc), pin);
              await storage.secureSet(`regist_${c.id}`, JSON.stringify(r));
              update({ consoles: settings.consoles.map((x) => (x.id === c.id ? { ...x, registered: true } : x)) });
              return "Kayıt başarılı.";
            })}
          />
        </View>
        <View style={styles.btnRow}>
          <Button testID="connect-ps5-button" label="Bağlan" icon="play-outline" style={{ flex: 1 }} loading={busy === "connect"}
            onPress={() => needNative("connect", setPsMsg, async () => {
              const c = settings.consoles.find((x) => x.registered);
              if (!c) throw new Error("Kayıtlı konsol yok.");
              await nativeModule!.startSession(c.host);
              return "Remote Play oturumu başladı (görüntü tablette gösterilmez).";
            })}
          />
          <Button testID="disconnect-ps5-button" label="Bağlantıyı kes" icon="stop-outline" variant="secondary" style={{ flex: 1 }}
            onPress={() => needNative("disconnect", setPsMsg, async () => { await nativeModule!.stopSession(); return "Oturum kapatıldı."; })}
          />
        </View>
        {psMsg ? <Notice kind={psMsg.kind} text={psMsg.text} testID="ps5-message" /> : null}
      </Card>
    </View>
  );

  const tv = (
    <View style={wide ? { flex: 1 } : undefined}>
      <SectionTitle title="TV bağlantısı" subtitle="Samsung Tizen · WebSocket" />
      <Card testID="tv-section">
        <View style={styles.rowBetween}>
          <Text style={styles.label}>Tablet IP adresi</Text>
          <Button testID="refresh-ip-button" label="Yenile" icon="refresh-outline" variant="ghost" style={{ minHeight: 44 }} onPress={loadNetwork} />
        </View>
        <Text testID="tablet-ip-text" style={styles.ip}>{tabletIp ?? "Alınamadı"}</Text>
        <Text style={styles.meta}>Ağ: {wifi || "—"}</Text>
        {Platform.OS === "web" ? <Notice kind="info" text="Web önizlemede gösterilen IP sunucunun IP'sidir, TV erişemez. Gerçek yerel IP tablette görünür." /> : null}
        <View style={styles.urlBox}>
          <Text style={styles.label}>TV uygulamasında kullanılacak adres</Text>
          <Text testID="ws-url-text" style={styles.mono}>{wsUrl}</Text>
          <Text style={styles.mono}>{'{"text":"Türkçe altyazı"}'}</Text>
        </View>
        <View style={styles.rowBetween}>
          <Text style={styles.label}>WebSocket sunucusu</Text>
          <StatusBadge kind={nativeAvailable ? "idle" : "native"} testID="ws-server-badge" />
        </View>
        <Field testID="tv-ip-input" label="TV IP adresi (sadece bu IP kabul edilir)" placeholder="192.168.1.50 — boş: herkes" keyboardType="numbers-and-punctuation" autoCapitalize="none"
          value={tvIpDraft ?? settings.tvIp} error={tvIpErr}
          onChangeText={(v) => { setTvIpDraft(v); const t = v.trim(); if (!t || IP_RE.test(t)) { update({ tvIp: t }); setTvIpErr(null); } else setTvIpErr("Geçersiz IPv4 adresi (kaydedilmedi)"); }}
        />
        <View style={styles.btnRow}>
          <Button testID="start-ws-server-button" label="Sunucuyu başlat" icon="power-outline" style={{ flex: 1 }} loading={busy === "ws"} disabled={!!tvIpErr}
            onPress={() => needNative("ws", setTvMsg, async () => { await nativeModule!.startTvServer(settings.tvPort, settings.tvIp || null); return `Sunucu ${wsUrl} adresinde dinliyor.`; })}
          />
          <Button testID="test-tv-button" label="Bağlantıyı test et" icon="pulse-outline" variant="secondary" style={{ flex: 1 }} loading={busy === "test"}
            onPress={() => needNative("test", setTvMsg, async () => {
              const n = await nativeModule!.getTvClientCount();
              if (n === 0) throw new Error("Bağlı TV yok. TV uygulamasında adresi kontrol edin.");
              await nativeModule!.sendSubtitle("Bağlantı testi başarılı");
              return `${n} TV bağlı, test altyazısı gönderildi.`;
            })}
          />
        </View>
        {tvMsg ? <Notice kind={tvMsg.kind} text={tvMsg.text} testID="tv-message" /> : null}
        <Notice kind="info" text="Bağlantı kopması durumunda yerel sunucu açık kalır ve TV uygulaması yeniden bağlandığında son altyazı tekrar gönderilir." />
      </Card>
    </View>
  );

  return (
    <View style={styles.root} testID="connect-screen">
      <ScreenHeader title="Bağlantı" subtitle="PS5 Remote Play ve Tizen TV" />
      <KeyboardAwareScrollView bottomOffset={24} keyboardShouldPersistTaps="handled" contentContainerStyle={[styles.content, { paddingBottom: bottomChrome + space.xl }]}>
        {wide ? (
          <View style={{ flexDirection: "row", gap: space.xl }}>
            {ps5}
            {tv}
          </View>
        ) : (
          <>
            {ps5}
            {tv}
          </>
        )}
        <View style={{ height: 1, backgroundColor: colors.divider, marginTop: space.xl }} />
      </KeyboardAwareScrollView>
    </View>
  );
}

const useStyles = makeStyles((c) => ({
  root: { flex: 1, backgroundColor: c.surface },
  content: { paddingHorizontal: space.lg, maxWidth: 1300, width: "100%", alignSelf: "center" },
  rowBetween: { flexDirection: "row", justifyContent: "space-between", alignItems: "center", gap: space.md },
  label: { fontFamily: fonts.textMedium, fontSize: 12, color: c.muted, textTransform: "uppercase", letterSpacing: 0.6 },
  divider: { height: 1, backgroundColor: c.divider },
  meta: { fontFamily: fonts.text, fontSize: 12, color: c.muted },
  btnRow: { flexDirection: "row", gap: space.sm },
  consoleRow: { flexDirection: "row", alignItems: "center", gap: space.md, backgroundColor: c.surfaceTertiary, borderRadius: radius.sm, padding: space.md },
  consoleName: { fontFamily: fonts.textSemi, fontSize: 15, color: c.onSurface },
  ip: { fontFamily: fonts.display, fontSize: 34, color: c.brandPrimary, letterSpacing: 1 },
  urlBox: { backgroundColor: c.surfaceTertiary, borderRadius: radius.sm, padding: space.md, gap: 4 },
  mono: { fontFamily: "SpaceMono", fontSize: 13, color: c.info },
}));
