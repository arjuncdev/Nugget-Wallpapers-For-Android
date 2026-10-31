#!/usr/bin/env bash
# Runs core/src/test WITHOUT Gradle/Maven: compiles the tests against a tiny JUnit stub and executes them.
# (With network access the normal way is `./gradlew :core:test`, which uses the real JUnit 4.)
# Usage: scripts/run_core_tests.sh <kotlinc-dir>
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KDIR="${1:-${KOTLINC_HOME:-$HOME/kotlinc}}"
OUT="${BUILD_DIR:-$ROOT/build/jvm}"
"$ROOT/scripts/jvm_build.sh" "$KDIR" >/dev/null
"$KDIR/bin/kotlinc" -nowarn -jvm-target 17 -cp "$OUT/core.jar" "$ROOT/core/src/test/kotlin/dev/tendies/core/EngineTests.kt" "$ROOT/scripts/stubs/JunitStub.kt" -d "$OUT/core-tests.jar" < /dev/null
java -Xmx1500m -Djava.awt.headless=true -Dtendies.samples="$ROOT/samples" \
  -cp "$OUT/core.jar:$OUT/core-tests.jar:$KDIR/lib/kotlin-stdlib.jar" org.junit.StubRunner dev.tendies.core.EngineTests
