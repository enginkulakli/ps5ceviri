#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
: "${KOTLIN_COMPILER_DIR:?Set this to the Kotlin compiler lib directory}"
: "${KOTLIN_STDLIB:?Set this to the Kotlin stdlib JAR}"
v2="${BASE_COMMIT:-1d0cfac}"
v3="${V3_BASE_COMMIT:-8fcae2d}"
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
core=frontend/modules/ps5-translator/android/src/main/java/expo/modules/ps5translator/core

git show "$v2:$core/OverlayTracker.kt" |
  sed -e '/^internal data class DetectedText/d' -e 's/OverlayTracker/BaselineOverlayTracker/g' > "$temp/BaselineOverlay.kt"
cat >> "$temp/BaselineOverlay.kt" <<'KOTLIN'

private fun FrameRect.containsCenter(other: FrameRect) =
  other.cx in x..(x + w) && other.cy in y..(y + h)
KOTLIN
git show "$v2:$core/SubtitleTracker.kt" |
  sed -e 's/SubtitleTracker/BaselineSubtitleTracker/g' > "$temp/BaselineText.kt"

{
  printf 'package expo.modules.ps5translator.core\nimport kotlin.math.abs\nimport kotlin.math.ceil\n'
  git show "$v3:$core/SmartOverlay.kt" | sed -n '/^internal object TvOverlayLayout/,$p' |
    sed 's/TvOverlayLayout/BaselineV3Layout/g'
} > "$temp/BaselineV3Layout.kt"

java -cp "$KOTLIN_COMPILER_DIR/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$KOTLIN_STDLIB" -d "$temp/benchmark.jar" \
  "$core"/{OverlayTracker,SmartOverlay,SubtitleTracker,TranslationText,OverlayMessage,SubtitleMessage,TranslationAttempts}.kt \
  "$temp"/*.kt tests/native/{OverlayBenchmark,StabilityChecks}.kt

java -cp "$temp/benchmark.jar:$KOTLIN_STDLIB" OverlayBenchmarkKt
