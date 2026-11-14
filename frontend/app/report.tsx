import { ScrollView, Text, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";

import { ScreenHeader } from "@/src/components/ScreenHeader";
import { Card, SectionTitle, StatusBadge } from "@/src/components/ui";
import { REPORT } from "@/src/data/report";
import { fonts, space } from "@/src/fonts";
import { makeStyles } from "@/src/theme";

export default function ReportScreen() {
  const styles = useStyles();
  const insets = useSafeAreaInsets();
  return (
    <View style={styles.root} testID="report-screen">
      <ScreenHeader title="Teknik Rapor" subtitle="Yapılabilirlik, yöntemler, lisanslar, riskler" back />
      <ScrollView contentContainerStyle={[styles.content, { paddingBottom: insets.bottom + space.xl }]}>
        {REPORT.map((section) => (
          <View key={section.id} testID={`report-section-${section.id}`}>
            <SectionTitle title={section.title} />
            {section.intro ? <Text style={styles.intro}>{section.intro}</Text> : null}
            <Card style={{ gap: 0, paddingVertical: space.sm }}>
              {section.rows.map((row, i) => (
                <View key={row.title} style={[styles.row, i > 0 && styles.rowBorder]}>
                  <View style={styles.rowHead}>
                    <Text style={styles.rowTitle}>{row.title}</Text>
                    <StatusBadge kind={row.badge} label={row.badgeLabel} />
                  </View>
                  <Text style={styles.rowBody}>{row.body}</Text>
                </View>
              ))}
            </Card>
          </View>
        ))}
      </ScrollView>
    </View>
  );
}

const useStyles = makeStyles((c) => ({
  root: { flex: 1, backgroundColor: c.surface },
  content: { paddingHorizontal: space.lg, maxWidth: 1100, width: "100%", alignSelf: "center" },
  intro: { fontFamily: fonts.text, fontSize: 14, lineHeight: 21, color: c.onSurfaceSecondary, marginBottom: space.md },
  row: { paddingVertical: space.md, gap: space.sm },
  rowBorder: { borderTopWidth: 1, borderTopColor: c.divider },
  rowHead: { flexDirection: "row", alignItems: "flex-start", justifyContent: "space-between", gap: space.md, flexWrap: "wrap" },
  rowTitle: { flex: 1, minWidth: 180, fontFamily: fonts.textSemi, fontSize: 15, color: c.onSurface },
  rowBody: { fontFamily: fonts.text, fontSize: 13, lineHeight: 20, color: c.muted },
}));
