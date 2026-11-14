import { Ionicons } from "@react-native-vector-icons/ionicons";
import { router } from "expo-router";
import { Pressable, Text, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import { fonts, space } from "@/src/fonts";
import { nativeAvailable } from "@/src/native/bridge";
import { usePipeline } from "@/src/pipeline/PipelineProvider";
import { makeStyles, useTheme } from "@/src/theme";

// Sticky header + thin global pipeline banner.
export function ScreenHeader({ title, subtitle, back }: { title: string; subtitle?: string; back?: boolean }) {
  const insets = useSafeAreaInsets();
  const styles = useStyles();
  const { colors } = useTheme();
  const { state } = usePipeline();

  const banner = !nativeAvailable
    ? { color: colors.warning, text: "Önizleme modu · TV sunucusu yalnızca Android APK'da çalışır" }
    : state.engineState === "error"
      ? { color: colors.error, text: "Çeviri hatası" }
      : { color: colors.success, text: "Android yerel modülü yüklü · TV sunucusu kullanılabilir" };

  return (
    <View style={[styles.wrap, { paddingTop: insets.top + space.sm }]}>
      <View style={styles.row}>
        {back ? (
          <Pressable testID="header-back-button" onPress={() => router.back()} style={styles.back} hitSlop={8}>
            <Ionicons name="chevron-back" size={22} color={colors.onSurface} />
          </Pressable>
        ) : null}
        <View style={{ flex: 1 }}>
          <Text style={styles.title} numberOfLines={1}>
            {title}
          </Text>
          {subtitle ? (
            <Text style={styles.subtitle} numberOfLines={1}>
              {subtitle}
            </Text>
          ) : null}
        </View>
      </View>
      <View testID="pipeline-banner" style={styles.banner}>
        <View style={[styles.bannerDot, { backgroundColor: banner.color }]} />
        <Text style={[styles.bannerText, { color: banner.color }]} numberOfLines={1}>
          {banner.text}
        </Text>
      </View>
    </View>
  );
}

const useStyles = makeStyles((c) => ({
  wrap: { backgroundColor: c.surface, paddingHorizontal: space.lg, paddingBottom: space.sm, borderBottomWidth: 1, borderBottomColor: c.divider },
  row: { flexDirection: "row", alignItems: "center", gap: space.sm },
  back: { width: 44, height: 44, alignItems: "center", justifyContent: "center", marginLeft: -space.sm },
  title: { fontFamily: fonts.display, fontSize: 30, color: c.onSurface, letterSpacing: 0.5 },
  subtitle: { fontFamily: fonts.text, fontSize: 13, color: c.muted },
  banner: { flexDirection: "row", alignItems: "center", gap: 6, marginTop: space.sm },
  bannerDot: { width: 6, height: 6, borderRadius: 3 },
  bannerText: { fontFamily: fonts.textMedium, fontSize: 12 },
}));
