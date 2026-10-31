#!/usr/bin/env bash
# Type-checks the Android-specific Kotlin (everything except Jetpack Compose UI) against a real android.jar,
# without Gradle/AGP. Usage: scripts/android_typecheck.sh <kotlinc-dir> <android.jar> <core.jar>
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KOTLINC="${1:?kotlinc dir}/bin/kotlinc"; ANDROID_JAR="${2:?android.jar}"; CORE_JAR="${3:?core.jar}"
OUT="${BUILD_DIR:-$ROOT/build}/typecheck"; mkdir -p "$OUT"
SRC=$(find "$ROOT/app/src/main/kotlin" -name '*.kt' -not -path '*/ui/*')
"$KOTLINC" -nowarn -jvm-target 17 -cp "$ANDROID_JAR:$CORE_JAR" $SRC "$ROOT"/scripts/typecheck-stubs/*.kt -d "$OUT" < /dev/null
echo "android sources (non-Compose) type-check OK: $(echo "$SRC" | wc -w) files"
