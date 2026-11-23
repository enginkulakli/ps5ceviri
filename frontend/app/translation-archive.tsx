import { useCallback, useEffect, useState } from "react";
import { useFocusEffect } from "expo-router";
import { Alert, FlatList, Text, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import { ScreenHeader } from "@/src/components/ScreenHeader";
import { Button, Card, Field, Notice, SectionTitle } from "@/src/components/ui";
import { fonts, space } from "@/src/fonts";
import { hasNative, nativeModule } from "@/src/native/bridge";
import { useSettings } from "@/src/settings";
import { makeStyles } from "@/src/theme";

type Game = { id: string; name: string; count: number };
type Entry = { id: number; source: string; translated: string; engine: string; created: number; curated: boolean };

export default function TranslationArchiveScreen() {
  const styles = useStyles();
  const { settings, update } = useSettings();
  const insets = useSafeAreaInsets();
  const available = hasNative("getArchiveOverview");
  const [games, setGames] = useState<Game[]>([]);
  const [bytes, setBytes] = useState(0);
  const [viewGame, setViewGame] = useState(settings.gameId);
  const [newGame, setNewGame] = useState("");
  const [draftSearch, setDraftSearch] = useState("");
  const [search, setSearch] = useState("");
  const [entries, setEntries] = useState<Entry[]>([]);
  const [more, setMore] = useState(false);
  const [pageToken, setPageToken] = useState(0);
  const [busy, setBusy] = useState(false);
  const [info, setInfo] = useState<{ kind: "info" | "error"; text: string } | null>(null);
  const [edit, setEdit] = useState<{ id: number; text: string } | null>(null);

  const overview = useCallback(async () => {
    if (!hasNative("getArchiveOverview")) return;
    const data = JSON.parse(await nativeModule!.getArchiveOverview()) as { games: Game[]; bytes: number };
    setGames(data.games);
    setBytes(data.bytes);
  }, []);

  useFocusEffect(useCallback(() => {
    if (!available) return;
    void overview().then(() => setPageToken((value) => value + 1))
      .catch(() => setInfo({ kind: "error", text: "Arşiv okunamadı." }));
  }, [available, overview]));

  useEffect(() => {
    if (!available) return;
    let active = true;
    nativeModule!.getArchivePage(viewGame, search, 0).then((result) => {
      if (!active) return;
      const page = JSON.parse(result) as Entry[];
      setEntries(page);
      setMore(page.length === 50);
    }).catch(() => {
      if (active) setInfo({ kind: "error", text: "Arşiv kayıtları okunamadı." });
    });
    return () => { active = false; };
  }, [available, viewGame, search, pageToken]);

  const selected = games.find((game) => game.id === viewGame);
  const count = games.reduce((total, game) => total + game.count, 0);
  const act = async (action: () => Promise<void>, reload = true) => {
    if (!available || busy) return;
    setBusy(true);
    setInfo(null);
    try {
      await action();
      if (reload) { await overview(); setPageToken((value) => value + 1); }
    } catch (cause) {
      setInfo({ kind: "error", text: cause instanceof Error ? cause.message : String(cause) });
    } finally { setBusy(false); }
  };
  const select = (id: string) => {
    setViewGame(id);
    setEntries([]);
    setMore(false);
    setEdit(null);
  };
  const remove = (entry: Entry) => Alert.alert("Çeviriyi sil", "Bu kayıt arşivden kalıcı olarak kaldırılacak.", [
    { text: "Vazgeç", style: "cancel" },
    { text: "Sil", style: "destructive", onPress: () => void act(() => nativeModule!.deleteArchiveRecord(entry.id)) },
  ]);
  const exportArchive = (game: string | null) => act(async () => {
    const result = JSON.parse(await nativeModule!.exportTranslationArchive(game));
    if (result.exported) setInfo({ kind: "info", text: "Arşiv dosyası kaydedildi." });
  }, false);
  const importArchive = () => act(async () => {
    const result = JSON.parse(await nativeModule!.importTranslationArchive());
    if (!result.cancelled) setInfo({ kind: "info", text: `${result.added} kayıt eklendi. ${result.skipped} yinelenen kayıt atlandı.` });
  });

  return (
    <View style={styles.root}>
      <ScreenHeader title="Çeviri Arşivi" back />
      <FlatList data={entries} keyExtractor={(item) => String(item.id)} extraData={edit}
        contentContainerStyle={[styles.content, { paddingBottom: insets.bottom + space.xl }]}
        keyboardShouldPersistTaps="handled"
        ListHeaderComponent={<>
          <SectionTitle title="Oyun profilleri" />
          <Card>
            {!available ? <Notice kind="info" text="Yerel arşiv Android APK’da kullanılabilir." /> : null}
            <Text style={styles.detail}>{count} kayıt · {(bytes / (1024 * 1024)).toFixed(2)} MB veritabanı ve işlem günlüğü</Text>
            <View style={styles.buttons}>
              {games.map((game) => <Button key={game.id}
                label={`${game.name} (${game.count})${game.id === settings.gameId ? " · Aktif" : ""}`}
                variant={viewGame === game.id ? "primary" : "secondary"} disabled={busy} onPress={() => select(game.id)} />)}
            </View>
            <Button label="Bu oyunu aktif yap" variant="secondary" disabled={!selected || selected.id === settings.gameId || busy}
              onPress={() => update({ gameId: viewGame })} />
            <Field label="Yeni oyun adı" value={newGame} onChangeText={setNewGame} maxLength={100} />
            <Button label="Oyun profili oluştur" disabled={!available || !newGame.trim() || busy}
              onPress={() => act(async () => {
                const id = await nativeModule!.createArchiveGame(newGame);
                select(id);
                update({ gameId: id });
                setNewGame("");
              })} />
          </Card>
          <SectionTitle title="Yedekleme ve aktarım" />
          <Card>
            <View style={styles.buttons}>
              <Button label="Oyunu dışa aktar" variant="secondary" disabled={!selected || busy} onPress={() => exportArchive(viewGame)} />
              <Button label="Tüm arşivi yedekle" variant="secondary" disabled={!available || busy} onPress={() => exportArchive(null)} />
              <Button label="Arşivi içe aktar" variant="secondary" disabled={!available || busy} onPress={importArchive} />
            </View>
            {info ? <Notice kind={info.kind} text={info.text} /> : null}
          </Card>
          <SectionTitle title={selected?.name ?? "Kayıtlar"} />
          <Card>
            <Field label="İngilizce veya Türkçe metin ara" value={draftSearch} onChangeText={setDraftSearch} maxLength={120} />
            <Button label="Ara" variant="secondary" disabled={!available || busy} onPress={() => {
              setSearch(draftSearch.trim());
              setPageToken((value) => value + 1);
            }} />
          </Card>
        </>}
        renderItem={({ item }) => <Card>
          <Text style={styles.source}>{item.source}</Text>
          {edit?.id === item.id ? <Field label="Türkçe çeviri" value={edit.text} maxLength={1200} multiline
            onChangeText={(text) => setEdit({ id: item.id, text })} /> :
            <Text style={styles.translation}>{item.translated}</Text>}
          <Text style={styles.detail}>{item.engine} · {new Date(item.created).toLocaleDateString("tr-TR")}{item.curated ? " · Düzenlendi" : ""}</Text>
          <View style={styles.buttons}>
            {edit?.id === item.id ? <>
              <Button label="Kaydet" disabled={busy || !edit.text.trim()} onPress={() => act(async () => {
                await nativeModule!.editArchiveRecord(item.id, edit.text);
                setEdit(null);
              })} />
              <Button label="Vazgeç" variant="ghost" onPress={() => setEdit(null)} />
            </> : <Button label="Düzenle" variant="secondary" disabled={busy} onPress={() => setEdit({ id: item.id, text: item.translated })} />}
            <Button label="Sil" variant="danger" disabled={busy} onPress={() => remove(item)} />
          </View>
        </Card>}
        ListEmptyComponent={<Text style={styles.detail}>Bu görünümde kayıt bulunamadı.</Text>}
        ListFooterComponent={more && entries.length > 0 ? <Button label="Daha fazla göster" variant="secondary" disabled={busy}
          onPress={() => act(async () => {
            const page = JSON.parse(await nativeModule!.getArchivePage(viewGame, search, entries.at(-1)!.id)) as Entry[];
            setEntries((current) => [...current, ...page]);
            setMore(page.length === 50);
          }, false)} /> : undefined}
      />
    </View>
  );
}

const useStyles = makeStyles((c) => ({
  root: { flex: 1, backgroundColor: c.surface },
  content: { paddingHorizontal: space.lg, gap: space.sm, maxWidth: 1050, width: "100%", alignSelf: "center" },
  buttons: { flexDirection: "row", flexWrap: "wrap", gap: space.sm },
  source: { fontFamily: fonts.textSemi, color: c.onSurface },
  translation: { fontFamily: fonts.text, color: c.onSurface },
  detail: { fontFamily: fonts.text, color: c.muted },
}));
