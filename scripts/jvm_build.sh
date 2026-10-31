#!/usr/bin/env bash
# Builds the pure-Kotlin core (and the JVM tools) WITHOUT Gradle, using only a standalone kotlinc.
# This exists so the format engine can be compiled and tested on any machine - including ones that
# cannot reach Google Maven (no Android SDK needed).  Usage: scripts/jvm_build.sh [kotlinc-dir]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KOTLINC="${1:-${KOTLINC_HOME:-$HOME/kotlinc}}/bin/kotlinc"
OUT="${BUILD_DIR:-$ROOT/build/jvm}"
mkdir -p "$OUT"
echo "== compiling core"
"$KOTLINC" -nowarn -jvm-target 17 $(find "$ROOT/core/src/main" -name '*.kt') -d "$OUT/core.jar" < /dev/null
if [ -n "$(find "$ROOT/tools/src/main" -name '*.kt' 2>/dev/null | head -1)" ]; then
  echo "== compiling tools"
  "$KOTLINC" -nowarn -jvm-target 17 -cp "$OUT/core.jar" $(find "$ROOT/tools/src/main" -name '*.kt') -d "$OUT/tools.jar" < /dev/null
fi
echo "== built into $OUT"
