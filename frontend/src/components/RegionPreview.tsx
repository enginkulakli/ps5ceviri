import { Image } from "expo-image";
import { Text, View } from "react-native";

import { fonts, radius } from "@/src/fonts";
import { OcrRegion } from "@/src/native/bridge";
import { makeStyles } from "@/src/theme";

const BG = "https://images.unsplash.com/photo-1741883203039-04549101a206?crop=entropy&cs=srgb&fm=jpg&q=60&w=1200";

// 16:9 game frame with the OCR region highlighted.
export function RegionPreview({ region, testID = "ocr-region-preview" }: { region: OcrRegion; testID?: string }) {
  const styles = useStyles();
  return (
    <View testID={testID} style={styles.frame}>
      <Image source={{ uri: BG }} style={styles.bg} contentFit="cover" />
      <View style={styles.dim} />
      <View
        style={[
          styles.region,
          { left: `${region.x * 100}%`, top: `${region.y * 100}%`, width: `${region.w * 100}%`, height: `${region.h * 100}%` },
        ]}
      >
        <Text style={styles.regionText}>ALTYAZI ALANI</Text>
      </View>
    </View>
  );
}

const useStyles = makeStyles((c) => ({
  frame: { width: "100%", aspectRatio: 16 / 9, borderRadius: radius.md, overflow: "hidden", backgroundColor: c.surfaceTertiary, borderWidth: 1, borderColor: c.border },
  bg: { position: "absolute", left: 0, top: 0, right: 0, bottom: 0 },
  dim: { position: "absolute", left: 0, top: 0, right: 0, bottom: 0, backgroundColor: c.surface, opacity: 0.55 },
  region: { position: "absolute", borderWidth: 2, borderColor: c.brandPrimary, backgroundColor: c.brandTertiary + "66", alignItems: "center", justifyContent: "center" },
  regionText: { fontFamily: fonts.display, fontSize: 16, color: c.onBrandTertiary, letterSpacing: 1 },
}));
