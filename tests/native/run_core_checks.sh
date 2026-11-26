#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
: "${KOTLIN_COMPILER_DIR:?Set this to the Kotlin compiler lib directory}"
: "${NATIVE_TEST_CLASSPATH:?Set this to Kotlin stdlib, Java-WebSocket, and SLF4J API jars}"
build_dir=$(mktemp -d)
trap 'rm -rf "$build_dir"' EXIT
core=frontend/modules/ps5-translator/android/src/main/java/expo/modules/ps5translator/core

java -cp "$KOTLIN_COMPILER_DIR/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$NATIVE_TEST_CLASSPATH" -d "$build_dir/harness.jar" \
  "$core"/*.kt tests/native/{Harness,StabilityChecks,SmartOverlayChecks,V32SubtitleChecks,V32FullScreenChecks,V32DiscoveryChecks}.kt
printf 'QUIT\n' | java -cp "$build_dir/harness.jar:$NATIVE_TEST_CLASSPATH" HarnessKt
node tests/native/console_records.test.mjs
