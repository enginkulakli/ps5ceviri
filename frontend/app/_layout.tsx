import { QueryClientProvider } from "@tanstack/react-query";
import { useFonts } from "expo-font";
import { Stack } from "expo-router";
import { StatusBar } from "expo-status-bar";
import { ActivityIndicator, LogBox, StyleSheet, View } from "react-native";
import { GestureHandlerRootView } from "react-native-gesture-handler";
import { KeyboardProvider } from "react-native-keyboard-controller";

import { ErrorBoundary } from "@/src/components/error-boundary";
import { fontAssets } from "@/src/fonts";
import { RemotePlayProvider } from "@/src/native/RemotePlayProvider";
import { PipelineProvider } from "@/src/pipeline/PipelineProvider";
import { queryClient } from "@/src/query-client";
import { SettingsProvider } from "@/src/settings";
import { useTheme } from "@/src/theme";
import { TvServerProvider } from "@/src/tv/TvServerProvider";

LogBox.ignoreAllLogs(true);

export default function RootLayout() {
  const { colors } = useTheme();
  const [loaded, fontError] = useFonts(fontAssets);

  if (!loaded && !fontError) {
    return (
      <View style={[styles.root, { backgroundColor: colors.surface, alignItems: "center", justifyContent: "center" }]}>
        <ActivityIndicator color={colors.brandPrimary} />
      </View>
    );
  }

  return (
    <GestureHandlerRootView style={[styles.root, { backgroundColor: colors.surface }]}>
      <ErrorBoundary>
        <QueryClientProvider client={queryClient}>
          <SettingsProvider>
            <TvServerProvider>
              <RemotePlayProvider>
                <PipelineProvider>
                  <KeyboardProvider>
                    <StatusBar style="light" />
                    <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: colors.surface } }}>
                      <Stack.Screen name="(tabs)" />
                      <Stack.Screen name="report" />
                      <Stack.Screen name="ocr-region" />
                      <Stack.Screen name="translation-archive" />
                    </Stack>
                  </KeyboardProvider>
                </PipelineProvider>
              </RemotePlayProvider>
            </TvServerProvider>
          </SettingsProvider>
        </QueryClientProvider>
      </ErrorBoundary>
    </GestureHandlerRootView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
});
