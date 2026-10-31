# Nugget Wallpapers for Android

An Android port of the PosterBoard wallpaper collection in
[`SerStars/Nugget-Wallpapers`](https://github.com/SerStars/Nugget-Wallpapers): a Kotlin / Jetpack Compose app plus a custom
`WallpaperService` that plays Apple's **CAML** (Core Animation Markup Language) layer packages natively – layers, springs,
keyframes, particle emitters, Lock/Home/Sleep state transitions, light/dark appearance, gyro parallax and `.batter` templates.

> [!NOTE]
> **Current Status**: The app compiles and runs on Android 10+ (tested on Android 14). Currently, the **Simple Minecraft Chest** wallpaper is fully verified and working with smooth spring lid animations and Minecraft open & close sound effects on lock/unlock. Other wallpapers and features are in active development.
>
> | Part | Status |
> |---|---|
> | Simple Minecraft Chest (`@tayvall`) | ✅ Fully working with spring lid physics & Minecraft chest open/close sound effects |
> | Live Wallpaper Service | ✅ Screen state / keyguard detection (API 29-35), immediate power button lock detection |
> | `:core` format engine | ✅ Compiled; unit tests pass |
> | Android UI & Settings | ✅ Jetpack Compose UI, appearance, zoom/crop, FPS cap, sound toggles |


## What a `.tendies` file really is

Not a video or an image: a **ZIP of Apple PosterBoard descriptors** whose animated content is CAML XML
(`main.caml` + `assets/`), usually as separate background / floating / foreground layer packages with LayerKit state machines.
`.batter` = a template (`config.json` + descriptors); `.passthm` = iOS passcode‑keypad skins (preview only on Android).
Full evidence and census: [`docs/ANALYSIS.md`](docs/ANALYSIS.md).

## Build & install

Requirements: Android Studio Ladybug or newer (or JDK 17+ and the Android SDK, `ANDROID_HOME` set) with **SDK Platform 35**;
a device/emulator with Android 10+ (API 29, `minSdk`).

```bash
./gradlew :app:assembleDebug          # first run downloads Gradle 8.11.1 and all dependencies
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Versions are pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml) (AGP 8.9.1, Kotlin 2.1.20, Compose BOM 2025.04.01,
Coil 2.7, OkHttp 4.12). If Android Studio offers newer versions, accepting is fine. If sync fails: check Gradle JDK ≥ 17 and that
SDK Platform 35 is installed.

Dependency list: AndroidX core/activity/lifecycle, Jetpack Compose (Material 3), Coil (+GIF decoder) for catalogue previews,
OkHttp for downloads, kotlinx‑coroutines. The `:core` module has **no** dependencies.

## Using it

1. **Browse** – the four upstream manifests (Community, Apple, Templates, Passcode themes); download wallpapers (preview GIFs come
   from GitHub). **Library → Import file** (or "Open with…") accepts `.tendies` / `.batter` files you already have.
2. Open an item: live preview (the same renderer as the wallpaper service), situation chips (Lock / Home / Sleep), Light/Dark/System,
   zoom + horizontal/vertical position (crop), FPS cap (15/30/60), tilt‑parallax switch, play/pause.
3. **Set as live wallpaper** → Android's own preview opens; confirm there (on devices that offer it you can choose home / lock / both).
   Static (`Still`) wallpapers are applied directly as an image to Home / Lock / Both.
4. Templates (`.batter`): toggles, sliders and image slots → *Apply options to preview* → *Save as a new wallpaper* (writes a normal `.tendies`).

## Test procedure

**A. Engine on a desktop (no Android needed)** – this is what was actually run for the status table:
```bash
# with a standalone kotlinc (https://github.com/JetBrains/kotlin/releases) …
scripts/jvm_build.sh  /path/to/kotlinc          # core + tools -> build/jvm/*.jar
scripts/run_core_tests.sh /path/to/kotlinc      # 12 tests on samples/ (uses a tiny JUnit stub)
# … or with Gradle:
./gradlew :core:test
./gradlew :tools:run --args="probe $PWD/samples"
./gradlew :tools:run --args="render $PWD/samples/Night_City.tendies $PWD/out --t 0.5,3 --w 390 --h 844"
./gradlew :tools:run --args="render $PWD/samples/TintedCubes.tendies $PWD/out --t 1,4 --dark"
```
More archives: `scripts/fetch_samples.sh wallpapers/custom/Cipher.tendies` (anything in the upstream repo), then `probe`/`render` it.
The whole‑repo probe log is in [`docs/verification/probe-all-170-archives.txt`](docs/verification/probe-all-170-archives.txt).

**B. Android type-check without Gradle** (needs `android.jar`): `scripts/android_typecheck.sh <kotlinc> <android.jar> <core.jar>`.

**C. On a device** (expected results use [`docs/verification/preview_vs_render.jpg`](docs/verification/preview_vs_render.jpg)):
1. `adb push samples/. /sdcard/Download/` → app → **Library → Import file** → `Night_City.tendies`.
   Expect `✓ Animated (layered animation), 1 variant(s)`; the preview shows the teal pixel city with scrolling icon columns.
2. Switch Lock → Home → Sleep: layers animate between states (springs). Switch Light/Dark.
3. Import `RainbowSprinkles.tendies`: the particle field fills in over ~20 s (emitters start empty, as on iOS); Dark gives the black variant.
4. Import `LavaLamp.batter`: open it, *Choose image*, *Apply options*: your image appears as a blurred swirl.
5. `MOJOCARROT.tendies`: eyes follow device tilt; the mustache/eyes blink (animated shape paths).
6. **Set as live wallpaper** and check lock + home, screen off → on (Sleep→Lock transition), unlock (Home transition).
   The in‑app preview does not depend on the system wallpaper picker, so it is also the easiest check on emulators or Waydroid‑style setups
   (live wallpaper support there varies).

## Implemented

- ZIP/`.tendies`/`.batter` reading, binary + XML plist, tolerant XML, descriptor/variant discovery, device-class variant choice
- CAML: `CALayer`, `CATransformLayer` (4×4 chains + depth sort), `CAGradientLayer` (linear/radial), `CAShapeLayer` (+ animated paths),
  `CAEmitterLayer` (stateless particles), `CAReplicatorLayer`, masks, corner radius/clip, blend modes (incl. Porter‑Duff), colour filters
  (matrix, brightness, saturate, contrast, invert, hue, multiply, add), Gaussian blur (down‑sampled approximation), image sequences, nearest filtering
- Animation: basic/keyframe (linear, discrete, paced, cubic, per-segment bezier timing), springs, repeat/autoreverse/fill modes, layer time offsets/speed
- LayerKit states and transitions (set value, add/remove animation), Lock/Home/Sleep + orientation + Light/Dark name resolution, Apple property groups
- Gyro parallax (accelerometer), `.batter` options (picker/remove/replace/set), crop/zoom, FPS cap, pause
- Live wallpaper service: timer‑paced frames, stops when hidden / screen off, hardware canvas, sensors only when needed, live reload when you pick another wallpaper

## Not supported / approximations

- Apple procedural posters (`MercuryPoster`, Bokeh, Stripe) and Photos depth‑effect posters (no layer data); `Live` video wallpapers are detected but not played
- `CABackdropLayer` (12 archives), mesh warps, SDF/material layers, `CATextLayer`, conic gradients, `displacementMap`/`luminanceCurveMap`/`bias`/`colorMonochrome` filters, emitter sprite‑sheets (4 archives)
- **3D scenes are approximate**: perspective/translucency of `TintedCubes` does not match iOS (see the sheet)
- The foreground layer cannot draw above the Android clock/notifications; "depth effect" therefore flattens beneath system UI
- Parallax ranges are applied as absolute values (verified on one wallpaper only); `BlurAmount` of `LavaLamp.batter` has no target in the file and is ignored

## Android / device limitations

- Live wallpapers cannot be applied silently (`SET_WALLPAPER_COMPONENT` is system‑only); lock vs home selection is the system picker's decision
  (`Engine.getWallpaperFlags()` is used to tell the engines apart where the platform provides it, otherwise keyguard state is used)
- Wake/unlock animations depend on `ACTION_SCREEN_ON` / `USER_PRESENT`; Always‑On‑Display is not rendered
- Images are down‑sampled to ≤ 2048 px and cached in a byte‑budgeted LRU; very heavy wallpapers may need the 30 or 15 fps cap
- Manifest entries can be hosted elsewhere (5 Apple files use `atums.world` URLs) or be missing (`WholeLottaRed`): those downloads can fail
- Wallpapers are the authors' work; Apple artwork is downloaded on demand, never bundled

## Layout

```
core/    pure Kotlin engine (+ tests)        app/    Android app (service, canvas backend, Compose UI)
tools/   JVM CLI: probe / render / catalog   samples/ real wallpapers used by tests     docs/  analysis + verification evidence
scripts/ jvm_build, run_core_tests, android_typecheck, fetch_samples
```

## Licence & credits

GPL‑3.0 (same as the upstream repository whose sample files are bundled; see `LICENSE`). Sample credits: `samples/README.md`.
Format knowledge comes from inspecting the real files; Pocket Poster / Nugget / Cowabunga authors defined the `.tendies`/`.batter` conventions.
