# Station map validation

## National catalog conformance

On November 6, 2026, the Android sweep compared the pinned iOS catalog at the same 20 lunar-month instants used by iOS NationalScaleTests. Across 5,028 tide stations, the hybrid calculation resolved 100,034 of 100,560 directions (99.48%). It disagreed with the exact next turn 212 times (0.21% of resolved directions). Every disagreement occurred within 30 minutes of a turn. The iOS limits are greater than 99% coverage, less than 1% near-turn lag and zero disagreements away from turns.

The grid test uses the shipped harmonic and subordinate tide and current positions. The East Coast box (25°–45° N, 95°–66° W) retains 29, 138 and 610 stations at zooms 3, 5 and 7, below the 900-station limit. The Charleston harbour box (32.70°–32.87° N, 80.05°–79.83° W) retains 58 of 61 stations at zoom 12.5 (95.1%, above the 80% minimum).

```sh
rtk ./gradlew :core:test :core:nationalTideDirectionSweep
```

## Optimized map calculations, November 6, 2026

The API 37 Google Play arm64 emulator (Android 17, build CE2A.260420.051.A2/16418371, 1080 × 2400, 420 dpi) ran the R8-optimized benchmark target with the harness requesting full ART compilation. The recorded APK predates the OpenGL renderer change. These timings cover warm pin calculations only. Airplane mode was off. The measured APK's SHA-256 was `de43e70ee4946c491b4a127f895e2e32c3a1196fc39da5cf6438b1fa2e5b20e0`.

| Camera | Zoom | Stations | Five rebuilds (ms) | Median (ms) |
| --- | ---: | ---: | --- | ---: |
| Charleston harbour | 11.5 | 47 | 140.3, 180.8, 140.7, 181.6, 178.5 | 178.5 |
| East Coast | 4.2 | 72 | 110.1, 157.1, 143.6, 156.4, 279.8 | 156.4 |

For each iteration the harness starts a fresh process, builds the visible stations once, then times a second call to the production `StationMapModel.pins` at epoch 1785000000. The trace spans catalog scanning, grid selection and prediction of every retained pin with warm caches. The harbour zoom also computes the detailed readings. Basemap loading and drawing fall outside the trace. Nine of ten rebuilds fell inside the iOS 250 ms reference budget. The slowest East Coast iteration took 279.8 ms. These emulator measurements leave physical-device performance unverified. [Macrobenchmark output](evidence/map-macrobenchmark-api37.json)

To reproduce on one dedicated device, replace `SERIAL` with its adb serial. For a physical device, omit the emulator suppression argument. The last command copies the JSON and per-iteration Perfetto traces into the local build output.

```sh
rtk ./gradlew :app:assembleBenchmark :benchmark:assembleBenchmark
rtk adb -s SERIAL install -r app/build/outputs/apk/benchmark/app-benchmark.apk
rtk adb -s SERIAL install -r -t benchmark/build/outputs/apk/benchmark/benchmark-benchmark.apk
rtk adb -s SERIAL shell am instrument -w \
  -e class io.openwaters.slackwater.benchmark.MapBenchmark \
  -e androidx.benchmark.suppressErrors EMULATOR \
  -e androidx.benchmark.output.enable true \
  io.openwaters.slackwater.benchmark/androidx.test.runner.AndroidJUnitRunner
rtk mkdir -p benchmark/build/outputs/map-profile
rtk adb -s SERIAL pull /sdcard/Android/media/io.openwaters.slackwater.benchmark/. benchmark/build/outputs/map-profile/
```
