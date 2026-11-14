import { Ionicons } from "@react-native-vector-icons/ionicons";
import { Tabs } from "expo-router";
import { NativeTabs } from "expo-router/native-tabs";
import { Platform } from "react-native";

import { fonts } from "@/src/fonts";
import { usesNativeTabs } from "@/src/navigation";
import { useTheme } from "@/src/theme";

export default function TabsLayout() {
  const { colors } = useTheme();

  if (usesNativeTabs) {
    return (
      <NativeTabs>
        <NativeTabs.Trigger name="index">
          <NativeTabs.Trigger.Icon sf="gauge.with.dots.needle.67percent" />
          <NativeTabs.Trigger.Label>Durum</NativeTabs.Trigger.Label>
        </NativeTabs.Trigger>
        <NativeTabs.Trigger name="translate">
          <NativeTabs.Trigger.Icon sf="captions.bubble.fill" />
          <NativeTabs.Trigger.Label>Çeviri</NativeTabs.Trigger.Label>
        </NativeTabs.Trigger>
        <NativeTabs.Trigger name="connect">
          <NativeTabs.Trigger.Icon sf="gamecontroller.fill" />
          <NativeTabs.Trigger.Label>Bağlantı</NativeTabs.Trigger.Label>
        </NativeTabs.Trigger>
        <NativeTabs.Trigger name="settings">
          <NativeTabs.Trigger.Icon sf="gearshape.fill" />
          <NativeTabs.Trigger.Label>Ayarlar</NativeTabs.Trigger.Label>
        </NativeTabs.Trigger>
      </NativeTabs>
    );
  }

  const icon = (name: React.ComponentProps<typeof Ionicons>["name"]) =>
    function TabIcon({ color }: { color: string }) {
      return <Ionicons name={name} size={22} color={color} />;
    };

  return (
    <Tabs
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: colors.brandPrimary,
        tabBarInactiveTintColor: colors.muted,
        tabBarStyle: {
          backgroundColor: colors.surfaceSecondary,
          borderTopColor: colors.border,
          ...(Platform.OS === "web" ? { height: 64 } : {}),
        },
        tabBarItemStyle: { alignSelf: "center" },
        tabBarLabelStyle: { fontFamily: fonts.textMedium, fontSize: 12 },
      }}
    >
      <Tabs.Screen name="index" options={{ title: "Durum", tabBarIcon: icon("speedometer-outline"), tabBarButtonTestID: "tab-status" } as any} />
      <Tabs.Screen name="translate" options={{ title: "Çeviri", tabBarIcon: icon("chatbubbles-outline"), tabBarButtonTestID: "tab-translate" } as any} />
      <Tabs.Screen name="connect" options={{ title: "Bağlantı", tabBarIcon: icon("game-controller-outline"), tabBarButtonTestID: "tab-connect" } as any} />
      <Tabs.Screen name="settings" options={{ title: "Ayarlar", tabBarIcon: icon("settings-outline"), tabBarButtonTestID: "tab-settings" } as any} />
    </Tabs>
  );
}
