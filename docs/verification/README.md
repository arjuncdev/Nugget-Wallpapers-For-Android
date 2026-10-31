# Verification evidence

Everything here was produced in the sandbox where the project was written (1 CPU, no Google Maven / Maven Central / Gradle access).

| File | What it shows | How to reproduce |
|---|---|---|
| `probe-all-170-archives.txt` | every `.tendies`/`.batter`/`.passthm` of the upstream clone parsed and dry-run through Lock/Home/Sleep with the real engine | `tools probe <repo>/wallpapers` |
| `core-tests.txt` | 12 unit tests passing (real samples, orientation rule, springs, template patching, catalogue) | `scripts/run_core_tests.sh <kotlinc>` or `./gradlew :core:test` |
| `catalog-check.txt` | manifest entries vs the clone (5 absolute URLs, 1 missing file) | `tools catalog <repo>` |
| `android-typecheck.txt` | the non-Compose Android sources type-check against `android.jar` API 35 | `scripts/android_typecheck.sh` |
| `preview_vs_render.jpg` | rows of pairs: upstream preview frame (left) vs this engine's frame (right) at two times: Night City, Super Mario Bros, Sonic Mania, MOJO Carrot, Tinted Cubes (dark) | `tools render …` + the preview GIFs of the upstream repo |

NOT verified: running on a device/emulator, the Compose UI compiling, the Gradle build, battery/performance on real hardware.
Pixel-exactness was never claimed: the comparison is visual (the previews are screen recordings with iOS clock/status UI overlaid).
