# Android performance evidence

The benchmark installs a separate target app, `io.openwaters.slackwater.android.benchmark`, signed with the debug key. Its build enables R8 optimization and shell profiling, disables debugging, and uses full ART compilation. The current release build ships without R8 minification, so its timing can differ. The station benchmarks exercise the production browsing screen, focused search, and named prediction shortcuts.

| Benchmark | Start | End |
| --- | --- | --- |
| coldStationList | Launch after Macrobenchmark stops the process | Friday Harbor appears as a clickable station row |
| warmStationList | Relaunch the activity with the process warm | The same station row is actionable |
| focusStationSearch | Tap Search on the list | The editable search field has focus |
| firstStationSearch | Set the first query to Port Townsend | The clickable Port Townsend result appears |
| repeatStationSearch | Replace a successful Seattle query with Port Townsend | The clickable Port Townsend result appears |
| firstNamedPrediction | Tap **Show prediction** after entering Friday Harbor in a freshly launched **Next low tide** shortcut | The answer displays the time remaining until low tide |
| repeatNamedPrediction | Tap **Show prediction** for Friday Harbor after a successful Port Townsend query in the same activity | The new answer displays the time remaining until low tide |

List and search measurements end when the matching station can be opened. Tide readings and curves load independently. Each run collects five iterations. The custom trace sections include UI Automator observation latency. Startup timing reports the platform’s first-frame metric separately. Frame timing spans the measured interaction. The named prediction benchmarks launch the production query activity through an alias in the benchmark variant, using the same low-tide URI as the launcher shortcut. Navigation, typing, and the prior warm-up query occur outside their measured interval. The list search benchmarks include entering the query, the 120 ms debounce and observation of the matching row. A fresh process still performs the shortcut’s default nearby lookup before the first named submission.

## Physical-device run

Use a dedicated, unlocked API 34+ phone for repeatable compilation without reinstalling the prepared target app with English UI and airplane mode enabled. After clearing benchmark app data, launch it once, deny location access, skip the first-run tour at Friday Harbor and return to the list. Verify the Friday Harbor row is visible and leave the series filter on All. This establishes the same returning-user state for every iteration. Record the device model, OS build, app Git SHA, locale, display refresh rate, battery and thermal state. Keep device settings and station data identical when comparing revisions.

```sh
adb devices -l
# Replace SERIAL with the phone’s adb serial.
export ANDROID_SERIAL=SERIAL
./gradlew :app:assembleBenchmark :benchmark:assembleBenchmark
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/benchmark/app-benchmark.apk
adb -s "$ANDROID_SERIAL" install -r benchmark/build/outputs/apk/benchmark/benchmark-benchmark.apk
adb -s "$ANDROID_SERIAL" shell pm clear io.openwaters.slackwater.android.benchmark
# Launch and prepare the returning-user state described above.
adb -s "$ANDROID_SERIAL" shell am instrument -w \
  -e class io.openwaters.slackwater.benchmark.StationBenchmark \
  -e androidx.benchmark.output.enable true \
  io.openwaters.slackwater.benchmark/androidx.test.runner.AndroidJUnitRunner
```

Pull the benchmark output from `/sdcard/Android/media/io.openwaters.slackwater.benchmark/` on the device after each run. Report median and range of each duration, frame CPU time percentiles and frame overrun where supported. Retain the traces and record the run conditions alongside each result. Repeat independent runs on the same handset to estimate normal variation before setting a regression budget. [Android Macrobenchmark metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)

## Historical shell and shortcut run, November 6, 2026

All four tests passed on the API 37 arm64 Android 17 Medium Phone AVD (Google Play image `CE2A.260420.051.A2/16418371`, 1080 × 2400, 420 dpi). The run used the issue #27 working tree based on `9601dd6b24d97382b8fa2c7f9e6f4dab83d81cee`, including the separate benchmark application ID. The measured target APK had SHA-256 `504f6b748b23a1bfb2dae0150a35527af02ada402a727aa808b2d8e51388f64a`. Airplane mode was off. Each test produced five duration samples, frame CPU and overrun samples, and five Perfetto traces. The shell tests also reported time to initial display. This run verifies the harness. Physical-device measurements will supply the performance baselines.

## Production list and search, November 7, 2026

The API 37 arm64 phone emulator ran all seven station benchmarks with full ART compilation and five iterations each. The elapsed times include UI Automator observation latency. The named shortcut setup now launches the query activity directly. Its timings cannot be compared directly with the historical run through the browsing activity. These emulator measurements verify the journeys. Physical-device regression budgets remain open.

| Journey | Minimum (ms) | Median (ms) | Maximum (ms) |
| --- | ---: | ---: | ---: |
| coldStationList | 1,564.3 | 1,604.5 | 1,841.3 |
| warmStationList | 1,194.2 | 1,411.7 | 1,436.5 |
| focusStationSearch | 1,193.3 | 1,241.5 | 1,339.4 |
| firstStationSearch | 651.3 | 682.3 | 1,240.4 |
| repeatStationSearch | 178.3 | 204.5 | 815.4 |
| firstNamedPrediction | 635.6 | 640.3 | 648.0 |
| repeatNamedPrediction | 95.9 | 99.2 | 129.4 |

The emulator ran build CE2A.260420.051.A2 with airplane mode off. The run records the base commit, APK hash and measured ranges. [Benchmark results](evidence/station-benchmarks-api37.json)

## Open measurements for issue #27

| Required journey | Evidence needed |
| --- | --- |
| Cold and warm first usable station list | The launch harness is implemented. Collect physical-device cold and warm baselines. |
| Complete station detail by capability | Open each supported capability through the production detail screen and wait for its reading, schedule and chart. Capture cold and warm separately. |
| Interactive map with and without offline packs | Measure first responsive station selection with recorded network and pack state. |
| Focused station search, first and repeat queries | The focus, first-query and repeat-query harnesses are implemented. Collect physical-device baselines. |
| Memory and frames during long chart scrubs | Exercise sustained gesture input on the production scrubber, capture memory over time and correlate frame stalls in the trace. |

The list and search benchmarks supply repeatable boundaries for physical measurements. The map computation benchmark has separate [camera-specific evidence](map-performance.md). Physical-device timings and regression budgets remain pending.
