import * as Network from "expo-network";
import { useEffect, useRef, useState } from "react";
import { Platform, Text, useWindowDimensions, View } from "react-native";
import { KeyboardAwareScrollView } from "react-native-keyboard-controller";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import { ScreenHeader } from "@/src/components/ScreenHeader";
import { TvServerPanel } from "@/src/components/TvServerPanel";
import { Button, Card, Field, Notice, SectionTitle, StatusBadge } from "@/src/components/ui";
import { fonts, radius, space } from "@/src/fonts";
import { usesNativeTabs } from "@/src/navigation";
import { hasNative, nativeModule, NATIVE_REQUIRED_MESSAGE, Ps5TranslatorNativeModule } from "@/src/native/bridge";
import { useRemotePlay } from "@/src/native/RemotePlayProvider";
import { PSN_ACCOUNT_KEY, useSettings } from "@/src/settings";
import { makeStyles, useTheme } from "@/src/theme";
import { useTvServer } from "@/src/tv/TvServerProvider";
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
  const { settings, ready, update } = useSettings();
  const { status } = useRemotePlay();
  const settingsRef = useRef(settings);
  settingsRef.current = settings;
  const automaticDiscovery = useRef(false);

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
    if (Platform.OS === "android") storage.secureGet(PSN_ACCOUNT_KEY, null).then((v) => setAccountSaved(!!v));
  }, []);

  const needNative = async (key: string, setMsg: (m: Msg) => void, fn: () => Promise<string>, nativeFn: keyof Ps5TranslatorNativeModule) => {
    if (!hasNative(nativeFn)) {
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
    if (!IP_RE.test(host.trim()) || /^(0|127|22[4-9]|2[3-5]\d)\./.test(host.trim())) {
      setHostErr("Geçerli bir IPv4 adresi girin (örn. 192.168.1.40)");
      return;
    }
    setHostErr(null);
    const existing = settings.consoles.find((console) => console.host === host.trim());
    const console = existing ?? { id: host.trim(), name: name.trim() || "PS5", host: host.trim(), registered: false };
    update({ consoles: existing ? settings.consoles : [...settings.consoles, console], selectedConsoleId: console.id });
    setName("");
    setHost("");
    setPsMsg({ kind: "success", text: "Konsol eklendi. Bağlanmak için cihaz kaydı gerekir." });
  };

  const saveAccount = async () => {
    if (!hasNative("registerConsole")) return;
    const v = accountId.trim();
    if (!/^[A-Za-z0-9+/]{11}=?$/.test(v)) {
      setPsMsg({ kind: "error", text: "PSN Account ID, Base64 biçiminde 8 bayt olmalıdır." });
      return;
    }
    const ok = await storage.secureSet(PSN_ACCOUNT_KEY, v);
    setAccountSaved(ok);
    setAccountId("");
    setPsMsg(ok ? { kind: "success", text: "PSN Account ID cihazın güvenli deposuna kaydedildi." } : { kind: "error", text: "Güvenli depoya yazılamadı." });
  };

  const discover = () => needNative("discover", setPsMsg, async () => {
    const found = await nativeModule!.discoverConsoles(3000);
    const current = settingsRef.current;
    const known = new Set(current.consoles.map((console) => console.host));
    const added = await Promise.all(found.filter((console) => !known.has(console.host)).map(async (console) => ({
      id: console.host, name: console.name, host: console.host,
      registered: await nativeModule!.isConsoleRegistered(console.host),
    })));
    const latest = settingsRef.current;
    const newConsoles = added.filter((console) => !latest.consoles.some((existing) => existing.host === console.host));
    if (newConsoles.length) {
      update({ consoles: [...latest.consoles, ...newConsoles],
        selectedConsoleId: latest.selectedConsoleId ?? latest.consoles[0]?.id ?? newConsoles[0].id });
    }
    return found.length
      ? found.map((console) => `${console.name} (${console.host}) · ${console.state === "ready" ? "Açık" : "Dinlenme modu"}`).join("\n")
      : "PS5 bulunamadı. Konsolun açık ve tabletle aynı yerel ağda olduğunu kontrol edin.";
  }, "discoverConsoles");

  useEffect(() => {
    if (!ready || automaticDiscovery.current || !hasNative("discoverConsoles")) return;
    automaticDiscovery.current = true;
    void discover();
    // Run once when the connection screen first opens.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready]);

  const selected = settings.consoles.find((console) => console.id === settings.selectedConsoleId)
    ?? settings.consoles.find((console) => console.registered) ?? settings.consoles[0];

  const tvServer = useTvServer();
  const shownIp = tvServer.state?.localIp ?? tabletIp;
  const wsUrl = `ws://${shownIp ?? "TABLET_IP"}:${settings.tvPort}`;

  const ps5 = (
    <View style={wide ? { flex: 1 } : undefined}>
      <SectionTitle title="PS5 bağlantısı" subtitle="Yerel ağ · Remote Play" />
      <Card testID="ps5-section">
        <View style={styles.rowBetween}>
          <Text style={styles.label}>Remote Play oturumu</Text>
          <StatusBadge kind={status?.error ? "error" : status?.active ? "ready" : "idle"}
            label={status?.session ?? "Kapalı"} testID="ps5-engine-badge" />
        </View>
        <Button testID="discover-consoles-button" label="Ağda konsol bul" icon="search-outline" variant="secondary"
          loading={busy === "discover"} disabled={busy !== null} onPress={discover} />
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
                <Button testID={`select-console-${c.id}`} label={selected?.id === c.id ? "Seçili" : "Seç"}
                  variant="secondary" disabled={selected?.id === c.id || status?.active}
                  onPress={() => update({ selectedConsoleId: c.id })} />
                <Button testID={`remove-console-${c.id}`} label="Sil" variant="danger" style={{ minHeight: 44 }}
                  disabled={status?.host === c.host || busy !== null}
                  onPress={() => needNative("forget", setPsMsg, async () => {
                    await nativeModule!.forgetConsole(c.host);
                    const latest = settingsRef.current;
                    update({ consoles: latest.consoles.filter((item) => item.id !== c.id),
                      selectedConsoleId: latest.selectedConsoleId === c.id ? null : latest.selectedConsoleId });
                    return "Konsol kaldırıldı.";
                  }, "forgetConsole")} />
              </View>
            ))}
          </View>
        ) : null}
      </Card>

      <SectionTitle title="PSN kimliği ve cihaz kaydı" />
      <Card>
        <Notice kind="info" text="PSN Account ID ve PS5’in Ayarlar → Sistem → Remote Play → Cihazı Eşle ekranındaki 8 haneli PIN gerekir." />
        <View style={styles.rowBetween}>
          <Text style={styles.label}>PSN Account ID</Text>
          <StatusBadge kind={accountSaved ? "ready" : "idle"} label={accountSaved ? "Güvenli depoda" : "Girilmedi"} testID="psn-account-badge" />
        </View>
        <Field testID="psn-account-id-input" label="Account ID (base64)" placeholder="ör. AbCdEfGh12A=" autoCapitalize="none" secureTextEntry value={accountId} onChangeText={setAccountId} editable={hasNative("registerConsole")} />
        <Button testID="save-psn-account-button" label="Güvenli kaydet" icon="lock-closed-outline" variant="secondary" onPress={saveAccount} disabled={!accountId.trim() || !hasNative("registerConsole")} />
        <Field testID="register-pin-input" label="PS5 eşleme PIN'i" placeholder="12345678" keyboardType="number-pad" maxLength={8} secureTextEntry value={pin} onChangeText={setPin} />
        <Button testID="register-console-button" label={selected ? `${selected.name} ile eşleştir` : "PS5 ile eşleştir"}
          icon="key-outline" loading={busy === "register"} disabled={!selected || busy !== null || status?.active}
          onPress={() => needNative("register", setPsMsg, async () => {
            if (!selected) throw new Error("Önce bir PS5 seçin.");
            const account = await storage.secureGet(PSN_ACCOUNT_KEY, null);
            if (typeof account !== "string") throw new Error("Önce PSN Account ID kaydedin.");
            if (!/^\d{8}$/.test(pin)) throw new Error("PIN 8 haneli olmalıdır.");
            setPin("");
            await nativeModule!.registerConsole(selected.host, account, pin);
            update({ consoles: settingsRef.current.consoles.map((console) => console.id === selected.id
              ? { ...console, registered: true } : console), selectedConsoleId: selected.id });
            return "Eşleştirme tamamlandı. Durum sekmesinden çeviriyi başlatabilirsiniz.";
          }, "registerConsole")} />
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
          <Button testID="refresh-ip-button" label="Yenile" icon="refresh-outline" variant="ghost" style={{ minHeight: 44 }} onPress={() => { loadNetwork(); tvServer.refresh(); }} />
        </View>
        <Text testID="tablet-ip-text" style={styles.ip}>{shownIp ?? "Alınamadı"}</Text>
        <Text style={styles.meta}>Ağ: {wifi || "—"}</Text>
        {Platform.OS === "web" ? <Notice kind="info" text="Web önizlemede gösterilen IP sunucunun IP'sidir, TV erişemez. Gerçek yerel IP tablette görünür." /> : null}
        <Field testID="tv-ip-input" label="TV IP adresi (sadece bu IP kabul edilir)" placeholder="192.168.1.50 — boş: herkes" keyboardType="numbers-and-punctuation" autoCapitalize="none"
          value={tvIpDraft ?? settings.tvIp} error={tvIpErr}
          onChangeText={(v) => { setTvIpDraft(v); const t = v.trim(); if (!t || IP_RE.test(t)) { update({ tvIp: t }); setTvIpErr(null); } else setTvIpErr("Geçersiz IPv4 adresi (kaydedilmedi)"); }}
        />
        <Text style={styles.meta}>TV uygulaması şu adrese bağlanır: {wsUrl}</Text>
      </Card>
      <View style={{ height: space.md }} />
      <TvServerPanel idPrefix="connect" />
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
  consoleRow: { flexDirection: "row", alignItems: "center", gap: space.md, backgroundColor: c.surfaceTertiary, borderRadius: radius.sm, padding: space.md },
  consoleName: { fontFamily: fonts.textSemi, fontSize: 15, color: c.onSurface },
  ip: { fontFamily: fonts.display, fontSize: 34, color: c.brandPrimary, letterSpacing: 1 },
  urlBox: { backgroundColor: c.surfaceTertiary, borderRadius: radius.sm, padding: space.md, gap: 4 },
  mono: { fontFamily: "SpaceMono", fontSize: 13, color: c.info },
}));
