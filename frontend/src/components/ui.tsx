import { Ionicons } from "@react-native-vector-icons/ionicons";
import * as Haptics from "expo-haptics";
import React from "react";
import { ActivityIndicator, Pressable, Switch, Text, TextInput, TextInputProps, View, ViewStyle } from "react-native";

import { fonts, radius, space } from "@/src/fonts";
import { makeStyles, useTheme } from "@/src/theme";

export type IconName = React.ComponentProps<typeof Ionicons>["name"];
export type BadgeKind = "ready" | "native" | "error" | "idle" | "busy" | "info";

const BADGE_LABEL: Record<BadgeKind, string> = {
  ready: "Hazır",
  native: "Yerel Derleme Gerektirir",
  error: "Hata",
  idle: "Beklemede",
  busy: "Çalışıyor",
  info: "Bilgi",
};

export function StatusBadge({ kind, label, testID }: { kind: BadgeKind; label?: string; testID?: string }) {
  const styles = useStyles();
  const { colors } = useTheme();
  const map = {
    ready: [colors.success, colors.onSuccess],
    native: [colors.brandTertiary, colors.onBrandTertiary],
    error: [colors.error, colors.onError],
    idle: [colors.surfaceTertiary, colors.onSurfaceTertiary],
    busy: [colors.info, colors.onInfo],
    info: [colors.surfaceTertiary, colors.onSurfaceTertiary],
  } as const;
  const [bg, fg] = map[kind];
  return (
    <View testID={testID} style={[styles.badge, { backgroundColor: bg }]}>
      <View style={[styles.dot, { backgroundColor: fg }]} />
      <Text style={[styles.badgeText, { color: fg }]} numberOfLines={1}>
        {label ?? BADGE_LABEL[kind]}
      </Text>
    </View>
  );
}

export function Card({ children, style, testID }: { children: React.ReactNode; style?: ViewStyle | ViewStyle[]; testID?: string }) {
  const styles = useStyles();
  return (
    <View testID={testID} style={[styles.card, style as ViewStyle]}>
      {children}
    </View>
  );
}

export function SectionTitle({ title, subtitle }: { title: string; subtitle?: string }) {
  const styles = useStyles();
  return (
    <View style={styles.sectionTitleWrap}>
      <Text style={styles.sectionTitle}>{title}</Text>
      {subtitle ? <Text style={styles.sectionSubtitle}>{subtitle}</Text> : null}
    </View>
  );
}

type BtnVariant = "primary" | "secondary" | "ghost" | "danger";
export function Button({
  label,
  onPress,
  variant = "primary",
  icon,
  loading,
  disabled,
  testID,
  style,
}: {
  label: string;
  onPress: () => void;
  variant?: BtnVariant;
  icon?: IconName;
  loading?: boolean;
  disabled?: boolean;
  testID?: string;
  style?: ViewStyle;
}) {
  const styles = useStyles();
  const { colors } = useTheme();
  const palette = {
    primary: [colors.brandPrimary, colors.onBrandPrimary, colors.brandPrimary],
    secondary: [colors.surfaceTertiary, colors.onSurface, colors.borderStrong],
    ghost: ["transparent", colors.brandPrimary, colors.borderStrong],
    danger: ["transparent", colors.error, colors.error],
  }[variant];
  return (
    <Pressable
      testID={testID}
      disabled={disabled || loading}
      onPress={() => {
        Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light).catch(() => {});
        onPress();
      }}
      style={({ pressed }) => [
        styles.btn,
        { backgroundColor: palette[0], borderColor: palette[2], opacity: disabled ? 0.45 : pressed ? 0.8 : 1, transform: [{ scale: pressed ? 0.98 : 1 }] },
        style,
      ]}
    >
      {loading ? <ActivityIndicator color={palette[1]} size="small" /> : icon ? <Ionicons name={icon} size={18} color={palette[1]} /> : null}
      <Text style={[styles.btnText, { color: palette[1] }]} numberOfLines={2}>
        {label}
      </Text>
    </Pressable>
  );
}

export function Field({ label, error, hint, ...props }: TextInputProps & { label: string; error?: string | null; hint?: string }) {
  const styles = useStyles();
  const { colors } = useTheme();
  return (
    <View style={styles.fieldWrap}>
      <Text style={styles.fieldLabel}>{label}</Text>
      <TextInput
        placeholderTextColor={colors.muted}
        {...props}
        style={[styles.input, error ? { borderColor: colors.error } : null, props.style]}
      />
      {error ? <Text style={styles.fieldError}>{error}</Text> : hint ? <Text style={styles.fieldHint}>{hint}</Text> : null}
    </View>
  );
}

export function Stepper({
  label,
  value,
  display,
  onDec,
  onInc,
  testID,
}: {
  label: string;
  value?: number;
  display: string;
  onDec: () => void;
  onInc: () => void;
  testID: string;
}) {
  const styles = useStyles();
  const { colors } = useTheme();
  return (
    <View style={styles.row}>
      <Text style={styles.rowLabel}>{label}</Text>
      <View style={styles.stepper}>
        <Pressable testID={`${testID}-dec`} onPress={onDec} style={styles.stepBtn} hitSlop={6}>
          <Ionicons name="remove" size={18} color={colors.onSurface} />
        </Pressable>
        <Text testID={`${testID}-value`} style={styles.stepValue}>
          {display}
        </Text>
        <Pressable testID={`${testID}-inc`} onPress={onInc} style={styles.stepBtn} hitSlop={6}>
          <Ionicons name="add" size={18} color={colors.onSurface} />
        </Pressable>
      </View>
    </View>
  );
}

export function ToggleRow({ label, hint, value, onChange, testID }: { label: string; hint?: string; value: boolean; onChange: (v: boolean) => void; testID: string }) {
  const styles = useStyles();
  const { colors } = useTheme();
  return (
    <View style={styles.row}>
      <View style={{ flex: 1, paddingRight: space.md }}>
        <Text style={styles.rowLabel}>{label}</Text>
        {hint ? <Text style={styles.fieldHint}>{hint}</Text> : null}
      </View>
      <Switch
        testID={testID}
        value={value}
        onValueChange={(v) => {
          Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light).catch(() => {});
          onChange(v);
        }}
        trackColor={{ false: colors.surfaceTertiary, true: colors.brandSecondary }}
        thumbColor={value ? colors.brandPrimary : colors.muted}
      />
    </View>
  );
}

export function Chip({ label, selected, onPress, testID }: { label: string; selected: boolean; onPress: () => void; testID: string }) {
  const styles = useStyles();
  const { colors } = useTheme();
  return (
    <Pressable
      testID={testID}
      onPress={onPress}
      accessibilityRole="button"
      accessibilityState={{ selected }}
      hitSlop={{ top: 4, bottom: 4 }}
      style={[styles.chip, { backgroundColor: selected ? colors.brandTertiary : colors.surfaceTertiary, borderColor: selected ? colors.brandPrimary : colors.border }]}
    >
      <Text style={[styles.chipText, { color: selected ? colors.onBrandTertiary : colors.onSurfaceTertiary }]}>{label}</Text>
    </Pressable>
  );
}

export function Notice({ kind, text, testID }: { kind: "warning" | "error" | "success" | "info"; text: string; testID?: string }) {
  const styles = useStyles();
  const { colors } = useTheme();
  const c = { warning: colors.warning, error: colors.error, success: colors.success, info: colors.info }[kind];
  const icon: IconName = { warning: "construct-outline", error: "alert-circle-outline", success: "checkmark-circle-outline", info: "information-circle-outline" }[kind] as IconName;
  return (
    <View testID={testID} style={[styles.notice, { borderLeftColor: c }]}>
      <Ionicons name={icon} size={18} color={c} />
      <Text style={styles.noticeText}>{text}</Text>
    </View>
  );
}

const useStyles = makeStyles((c) => ({
  badge: { flexDirection: "row", alignItems: "center", gap: 6, paddingHorizontal: 10, height: 26, borderRadius: radius.pill, alignSelf: "flex-start", flexShrink: 0 },
  dot: { width: 6, height: 6, borderRadius: 3 },
  badgeText: { fontFamily: fonts.textSemi, fontSize: 12 },
  card: { backgroundColor: c.surfaceSecondary, borderRadius: radius.md, borderWidth: 1, borderColor: c.border, padding: space.lg, gap: space.md },
  sectionTitleWrap: { marginTop: space.xl, marginBottom: space.md },
  sectionTitle: { fontFamily: fonts.display, fontSize: 22, color: c.onSurface, letterSpacing: 0.5, textTransform: "uppercase" },
  sectionSubtitle: { fontFamily: fonts.text, fontSize: 13, color: c.muted, marginTop: 2 },
  btn: { minHeight: 48, borderRadius: radius.md, borderWidth: 1, paddingHorizontal: space.lg, flexDirection: "row", alignItems: "center", justifyContent: "center", gap: space.sm },
  btnText: { fontFamily: fonts.textSemi, fontSize: 14, textAlign: "center", flexShrink: 1 },
  fieldWrap: { gap: 6 },
  fieldLabel: { fontFamily: fonts.textMedium, fontSize: 13, color: c.onSurfaceSecondary },
  input: { minHeight: 48, borderRadius: radius.md, borderWidth: 1, borderColor: c.border, backgroundColor: c.surfaceTertiary, color: c.onSurface, paddingHorizontal: space.md, fontFamily: fonts.text, fontSize: 15 },
  fieldError: { fontFamily: fonts.text, fontSize: 12, color: c.error },
  fieldHint: { fontFamily: fonts.text, fontSize: 12, color: c.muted },
  row: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", minHeight: 48 },
  rowLabel: { fontFamily: fonts.textMedium, fontSize: 14, color: c.onSurface },
  stepper: { flexDirection: "row", alignItems: "center", gap: space.sm },
  stepBtn: { width: 40, height: 40, borderRadius: radius.sm, backgroundColor: c.surfaceTertiary, alignItems: "center", justifyContent: "center", borderWidth: 1, borderColor: c.border },
  stepValue: { fontFamily: fonts.displaySemi, fontSize: 18, color: c.brandPrimary, minWidth: 72, textAlign: "center" },
  chip: { height: 36, paddingHorizontal: 14, borderRadius: radius.pill, borderWidth: 1, alignItems: "center", justifyContent: "center", flexShrink: 0 },
  chipText: { fontFamily: fonts.textMedium, fontSize: 13 },
  notice: { flexDirection: "row", gap: space.sm, backgroundColor: c.surfaceTertiary, borderRadius: radius.sm, borderLeftWidth: 3, padding: space.md, alignItems: "flex-start" },
  noticeText: { flex: 1, fontFamily: fonts.text, fontSize: 13, lineHeight: 19, color: c.onSurfaceSecondary },
}));
