import { Text, View } from "react-native";

import { fonts, radius } from "@/src/fonts";
import { OcrRegion } from "@/src/native/bridge";
import { makeStyles } from "@/src/theme";

// 16:9 game frame with the OCR region highlighted.
export function RegionPreview({ region, exclusions = [], testID = "ocr-region-preview" }: { region: OcrRegion; exclusions?: OcrRegion[]; testID?: string }) {
  const styles = useStyles();
  return (
    <View testID={testID} style={styles.frame}>
      <View
        style={[
          styles.region,
          { left: `${region.x * 100}%`, top: `${region.y * 100}%`, width: `${region.w * 100}%`, height: `${region.h * 100}%` },
        ]}
      >
        <Text style={styles.regionText}>ALTYAZI ALANI</Text>
      </View>
      {exclusions.map((area, index) => (
        <View key={index} style={[styles.excluded, {
          left: `${area.x * 100}%`, top: `${area.y * 100}%`, width: `${area.w * 100}%`, height: `${area.h * 100}%`,
        }]} />
      ))}
    </View>
  );
}

const useStyles = makeStyles((c) => ({
  frame: { width: "100%", aspectRatio: 16 / 9, borderRadius: radius.md, overflow: "hidden", backgroundColor: c.surfaceTertiary, borderWidth: 1, borderColor: c.border },
  region: { position: "absolute", borderWidth: 2, borderColor: c.brandPrimary, backgroundColor: c.brandTertiary + "66", alignItems: "center", justifyContent: "center" },
  excluded: { position: "absolute", borderWidth: 2, borderColor: c.error, backgroundColor: c.error + "66" },
  regionText: { fontFamily: fonts.display, fontSize: 16, color: c.onBrandTertiary, letterSpacing: 1 },
}));
