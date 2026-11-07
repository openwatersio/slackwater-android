# Station map validation

## National catalog conformance

On November 6, 2026, the Android sweep compared the pinned iOS catalog at the same 20 lunar-month instants used by iOS NationalScaleTests. Across 5,028 tide stations, the hybrid calculation resolved 100,034 of 100,560 directions (99.48%). It disagreed with the exact next turn 212 times (0.21% of resolved directions). Every disagreement occurred within 30 minutes of a turn. The iOS limits are greater than 99% coverage, less than 1% near-turn lag and zero disagreements away from turns.

The grid test uses the shipped harmonic and subordinate tide and current positions. The East Coast box (25°–45° N, 95°–66° W) retains 29, 138 and 610 stations at zooms 3, 5 and 7, below the 900-station limit. The Charleston harbour box (32.70°–32.87° N, 80.05°–79.83° W) retains 58 of 61 stations at zoom 12.5 (95.1%, above the 80% minimum).

```sh
rtk ./gradlew :core:test :core:nationalTideDirectionSweep
```

## Optimized map calculations, November 6, 2026

The API 37 Google Play arm64 emulator (Android 17, build CE2A.260420.051.A2/16418371, 1080 × 2400, 420 dpi) ran the R8-optimized APK under full ART compilation. Its SHA-256 was `8364bbc829e414a2df610b660dd85a89e0e6ccf699e4f9a4954d605bfe2cf2be`. Airplane mode was off. Each of five iterations starts a fresh target process. The cold trace includes model construction, first catalog access, ranking, grid selection and pin predictions. The warm trace repeats the same camera at epoch 1785000000. Both calculations exclude app launch, basemap loading and drawing.

| Camera | Zoom | Stations | Cold medians, runs 1 / 2 (ms) | Warm medians, runs 1 / 2 (ms) |
| --- | ---: | ---: | ---: | ---: |
| Charleston harbour | 11.5 | 47 | 969.6 / 989.5 | 154.8 / 277.1 |
| East Coast | 4.2 | 72 | 1548.1 / 1077.1 | 276.4 / 159.9 |
| Salish Sea, empty model store | 9.5 | 144 | 1174.2 / 532.3 | 200.3 / 145.6 |

Each median contains five samples. The repeat used the same APK and emulator after the native device test suite finished. East Coast warm time varied from 72.0 to 378.9 ms across both runs. Its second-run median of 159.9 ms was close to the previous build’s 156.4 ms. The Charleston and East Coast warm medians each exceeded the iOS 250 ms reference budget once. All six cold medians exceeded it. [First run](evidence/map-macrobenchmark-api37.json) · [Repeat](evidence/map-macrobenchmark-api37-repeat.json)

The Salish Sea camera spans 48.3°–49.5° N and 124.2°–122.7° W. The empty-store run includes Canadian model lookups on a fresh install. A separate fitted run seeded the 12-constituent Victoria test fixture into the model store for each Canadian tide station in the camera before measurement. All 66 retained Canadian tide gauges produced readings among the 144 pins. Five iterations measured **455.5 ms cold** (410.0–608.7 ms) and **244.1 ms warm** (202.6–264.2 ms). The fitted benchmark APK SHA-256 was `e0e1d93a681b59b38c51e5ed3ef9b374e26781d6792677d71d827d9f04d1e581`. This measures local fitted-tide reads and predictions. Canadian current fits, online windows and physical-device timing remain unmeasured. [Fitted run](evidence/map-macrobenchmark-api37-fitted.json)

In a separate optimized East Coast process, `adb shell dumpsys meminfo` reported total proportional set size (PSS) of 29,896 KiB with the benchmark button ready and 42,364 KiB after the cold and warm calculations returned 72 pins. Java heap PSS rose from 2,212 to 5,956 KiB, native heap PSS from 7,496 to 10,444 KiB. These two snapshots cover the whole process. Peak allocation during parsing was unmeasured.

The build generates an 80,668-byte index covering all 2,572 current rows. Map ranking uses the index directly. Prediction decodes individual rows from the memory-mapped catalog and retains up to 32 per reader. The core parity test compares the cached subordinate curve against engine speeds at 144 probes across six NOAA stations, including the largest time offset in the asset. All agreed within 0.002 knots.

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
