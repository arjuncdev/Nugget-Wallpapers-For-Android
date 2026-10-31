# Phase 1 – Analysis of `SerStars/Nugget-Wallpapers`

Everything below was measured on a real clone of the repository (commit `c531aa2`, 2026‑10‑23) with scripts that
are reproducible from this project (`tools` module, `scripts/`). Nothing here is inferred from file extensions.

## 1. What the repository is

A **content catalogue, not an application**: no source code, 4 JSON manifests, preview media and the wallpaper archives.

| Path | Content |
|---|---|
| `wallpapers-{apple,custom,passthemes,template}.json` | arrays of `{id, name, description, url, preview, authors?, contest?}`; `url`/`preview` are repo-relative paths |
| `wallpapers/apple` (27 files, 574 MB) | Apple's default posters repackaged |
| `wallpapers/custom` (111 files, 1.0 GB) | community wallpapers (`.tendies`) |
| `wallpapers/template` (3 files) | `.batter` templates |
| `wallpapers/passtheme` (29 files) | `.passthm` passcode‑keypad skins |
| `previews/` (639 MB) | GIF/PNG previews (+ source MOV/MP4) – these are the ground truth I compared my renderer against |
| `other/` | FAQ + a Pocket Poster source description |

Licence: GPL‑3.0. Two catalogue quirks found by checking every manifest entry against the clone (`tools catalog`):
5 Apple entries (iPhone 11 Pro … 13 Pro, listed in the upstream `.gitignore` as "too large to be uploaded to GitHub") carry an
**absolute URL on a third‑party host** (`https://atums.world/raw/…`) instead of a repo path, and one custom entry
(`WholeLottaRed.tendies`) is listed but absent from the repository. The downloader therefore passes absolute URLs through
unchanged and reports HTTP errors instead of assuming every entry exists.

## 2. The real formats

### `.tendies` – a ZIP of Apple PosterBoard descriptors (all 138 verified to be valid ZIPs, stored or deflated)

```
descriptors/<UUID>/versions/N/contents/<id>.<Name>-<W>w-<H>h@<S>x~<idiom>.wallpaper/
    Wallpaper.plist                      binary plist: what kind of wallpaper this is
    <name>.ca/  main.caml  index.xml  assetManifest.caml  assets/…    one Core Animation package per layer role
com.apple.posterkit.*  providerInfo.plist  *.userinfo  *.atx            PosterBoard metadata / iOS render caches (ignored)
```
`Wallpaper.plist` → `assets.lockAndHome.default.type`. Census over all archives (slot entries):

| type | count | meaning | this port |
|---|---|---|---|
| `LayeredAnimation` | 157 | background / floating / foreground `.ca` packages | **full engine** |
| `ParameterizedCA` | 17 | one `.ca` + `wallpaperPropertyGroups` (`v_home/v_lock/v_sleep`) + parallax groups | **full engine** |
| `Still` | 45 (+45 dark) | HEIC/JPEG with light/dark variant | static image |
| `Live` | 6 | MP4 + HEIC | detected, not played |
| `LayeredStripe`, `Bokeh` | 1 each | Apple native procedural renderers | unsupported |

Seven archives contain no `*.wallpaper` at all: six Apple `com.apple.MercuryPoster` sets (parametric, shader‑rendered, no layer data)
and one `PhotosPosterProvider` depth‑effect poster (`.HEIC` + settling `.MOV` + Apple Archive matte). They are classified, not parsed.

`.tendies` are **not** videos or images. The animated ones are **CAML** (Core Animation Markup Language): XML that
declares a `CALayer` tree, `CAAnimation`s and LayerKit **state machines**. All 46 embedded `.js` files are the identical stock
template (no scripting needed).

### CAML census (112 archives contain animation data)

| element | count (archives) | element | count (archives) |
|---|---|---|---|
| `LKStateTransitionElement` | 17 041 (98) | `CATransformLayer` | 166 (23) |
| `animation` | 13 822 (112) | `CAEmitterLayer` / `CAEmitterCell` | 156 (38) / 165 |
| `LKStateSetValue` | 9 175 (103) | `CAFilter` | 205 (39) |
| `CGImage` (image refs) | 2 649 (28) | `compositingFilter` | 84 (25) |
| `CALayer` | 2 507 (122) | `CAShapeLayer` | 104 (7) |
| `LKStateAddAnimation` | 1 322 (9) | `CAGradientLayer` | 40 (10) |
| `CABackdropLayer` | 37 (14) | `wallpaperParallaxGroups` entries | 158 (8) |

Animation classes: `CASpringAnimation` 9 677 (91 archives, almost all inside state transitions), `CABasicAnimation` 2 341,
`CAKeyframeAnimation` 1 803, `CAMatchMoveAnimation` 1. Key paths: opacity, position(.x/.y), `transform`, `transform.scale.*`,
`transform.rotation.*`, `filters.colorMatrix.inputColorMatrix` (744), `contents` (frame sequences), `bounds.size.*`,
`timeOffset`, `contentsRect`, `hidden`, `path` (shape morph)…
States: `Locked`/`Unlock`/`Sleep` (legacy) and `Lock|Home|Sleep <PortraitUp|LandscapeLeft…> <Light|Dark>` (iOS 26 style).

### `.batter` – a "Pocket Poster/Nugget" **template**

ZIP containing `<Title> Template/config.json` plus normal descriptors. Option types seen: `picker` (keep one descriptor dir),
`remove` (delete layer files; `inverted` flips the meaning), `replace` (swap a placeholder image), `set` (patch attribute `key`
on elements whose CAML carries `nuggetId="<identifier>"`). Implemented exactly that and tested on `LavaLamp`.
(One shipped option, `BlurAmount`, has no matching `nuggetId` in the file – reported as a warning, not guessed.)

### `.passthm`

ZIP of keypad button PNGs for the iOS passcode screen. Android cannot skin its PIN pad, so these are **preview‑only**.

## 3. Rules that had to be discovered from the data

Each of these was a visible bug first, then fixed and (where possible) unit‑tested:

1. **Orientation.** Layers live in raw Core Animation space, which is **y‑up**; `geometryFlipped="1"` makes the sublayer space y‑down
   (182 of 213 document roots do). Night City's roots are `0`, and its preview video proves it (icon columns at the top for `y≈590`
   of 844). Orientation is cumulative; images are never mirrored. (`geometryFlippedZeroMeansYUpAndOneMeansYDown` test.)
2. **`bounds.origin`** shifts the visible window of the sublayer space (e.g. `0 -552.96 393 339`); its y sign flips in y‑up space.
3. **Each `.ca` is cover‑fitted on its own.** Real wallpapers combine a 3176×3176 background with a 428×926 foreground.
4. **Parallax groups are absolute values**: for MOJO Carrot the midpoints of `mapMinTo/mapMaxTo` equal the authored positions.
5. **Animations can be written as `<p …>`** instead of `<animation …>` (114 of them, incl. Super Mario Bros' sprite sheet animation).
6. **`<backgroundColor type="_NSTaggedPointerColor"/>`** has no value = no colour (not black).
7. **Trailing junk after `</caml>`** (an unterminated comment in `Waves_Bundle_Gyro.tendies`) must not invalidate the file.
8. Shape `path` is a postfix string (`x y m … c … h`) and is animated by `CGPath` keyframes.
9. CATransform3D / `matrix(...)` use the **row‑vector** layout (translation in m41/m42).
10. Light/Dark are *states*, so the system night mode selects `… Dark` states (TintedCubes' black background is the Dark state).

## 4. What Android can and cannot reproduce

| PosterBoard feature | Android |
|---|---|
| Animated layer content | ✔ own CAML engine on `Canvas` (HW canvas via `lockHardwareCanvas`) |
| Lock / Home / Sleep reactions | ✔ mapped to keyguard state, `ACTION_USER_PRESENT`, `ACTION_SCREEN_OFF/ON`, `Engine.getWallpaperFlags()` |
| Gyro parallax | ✔ accelerometer, registered only for wallpapers that declare it |
| Depth effect / foreground above the clock | ✘ Android draws system UI over wallpapers, so the foreground layer can only sit *under* the clock |
| Per‑screen (lock vs home) live wallpaper | ◐ not settable via API: `setWallpaperComponentWithFlags` requires the system‑only `SET_WALLPAPER_COMPONENT`; the user confirms in the system picker |
| Backdrop blur / mesh warp / SDF / material layers | ✘ (12 archives use backdrop layers; skipped and reported) |
| Apple procedural posters | ✘ no layer data to port |

Platform facts were checked against the AOSP sources/`android.jar` (API 34/35): `Engine.getWallpaperFlags()`,
`isPreview()`, `WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER`, and the permission above.

## 5. Chosen architecture

```
:core  (pure Kotlin/JVM – no Android dependency)
   Vfs/Zip • binary+XML plist • tolerant XML • CAML parser → scene graph • timing/spring/keyframe evaluator
   • LayerKit state machine • .batter engine • catalogue parser • SceneRenderer → Canvas2D (interface)
:app   Android: AndroidCanvas (Canvas2D→android.graphics) • WallpaperService • Compose UI
:tools JVM:   Java2DCanvas (Canvas2D→BufferedImage) • probe / render / catalog / batter commands
```
The `Canvas2D` seam is what made real verification possible without an Android SDK: the *same* engine code that the
service uses was run on all 170 real archives and rendered to PNG for comparison with the repository's own previews.

## 6. Build‑environment limits (why the APK is not included)

The sandbox this was built in can reach GitHub and Ubuntu mirrors but **not** Google Maven, Maven Central or Gradle's hosts,
so the Android Gradle Plugin, Compose and the Android SDK cannot be downloaded: **no APK was built and the Compose UI was never
compiled**. What was done instead is listed, with logs, in `docs/verification/`.
