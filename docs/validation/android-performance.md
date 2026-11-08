# Android performance evidence

The benchmark installs a separate target app, `io.openwaters.slackwater.android.benchmark`, signed with the debug key. Its build enables R8 optimization and shell profiling, disables debugging, and the benchmark harness requests full ART compilation. The current release build ships without R8 minification, so its timing can differ. The station benchmarks exercise the production browsing screen, focused search, and named prediction shortcuts.

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
adb -s "$ANDROID_SERIAL" install -r -t benchmark/build/outputs/apk/benchmark/benchmark-benchmark.apk
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

The emulator ran build CE2A.260420.051.A2 with airplane mode off. The target was built on `e990a1da4b2ee2fe71028b9aa5d5c7e325d7dc82`. Its APK SHA-256 was `709fb3098917b33b1f528678d840aaf0cef57af90d86c357b46664cfb8f925f4`. The raw results include the five samples, startup metrics and frame timings. [Benchmark results](evidence/station-benchmarks-api37.json)

## Bundled station detail

`DetailBenchmark` opens Friday Harbor and Flat Point through dated public links. Both links select November 6, 2025 at 08:00 Pacific, outside the current opening window, so the schedule starts on the linked day. Each station runs five times with a cold process and five times with a warm process. Use a compact portrait phone in US English with heights in feet and speeds in knots.

- `launchToDetailReading` starts before the link opens. It ends when the station title, the tide or current timeline, and a spoken reading with the expected unit and date are visible.
- `revealScheduleEvent` starts at that boundary and includes swipe and accessibility-idle time as the page scrolls until a high tide or slack event row is visible in the schedule. This measures a scripted navigation journey. Gesture and idle time dominate the duration, so it is unsuitable for a schedule-rendering regression budget. Each iteration opens a fresh activity task. The warm run retains the target process.

Use the same optimized APKs and device setup as the physical run above. Run `io.openwaters.slackwater.benchmark.DetailBenchmark` in the instrumentation command. The links record station visits. Run the list benchmarks first or restore their Friday Harbor setup before repeating them.

### API 37 emulator run

On November 7, 2026 (Pacific), all four journeys completed five iterations on the API 37 arm64 phone emulator (build CE2A.260420.051.A2). The target APK was built from base `c2db9426fa988e180bd4bca23c78dc0277bba494` and had SHA-256 `751d6ed7d457030a2debe8537bc693b511776e7531edc2ae2141944738893fd4`. The raw results include frame timings and time to initial display. [Device measurements](evidence/detail-benchmarks-api37.json)

| Journey | Launch to dated reading, median (ms) | Reveal schedule event, median (ms) |
| --- | ---: | ---: |
| coldCurrentDetail | 2,362.8 | 3,070.7 |
| warmCurrentDetail | 1,974.7 | 3,207.2 |
| coldTideDetail | 2,297.3 | 3,173.6 |
| warmTideDetail | 1,946.0 | 3,201.4 |

## Multi-day chart scrubbing

Run `io.openwaters.slackwater.benchmark.ScrubBenchmark` with the instrumentation command above on a compact portrait phone in US English with tide heights in feet. Each iteration starts a new target process and opens the bundled Friday Harbor tide chart at 08:00 Pacific on November 6, 2025. After the reading appears, six horizontal swipes move forward and six move back. The benchmark checks that the selected day advances past November 6 and then moves to an earlier day.

The interval includes gesture injection and two settlement checks. After each group of swipes, the full spoken reading must match across two observations 750 ms apart. This run records a Friday Harbor visit; restore the list benchmark’s starting state before running it again. The built-in frame and memory statistics summarize the iteration trace, which extends beyond the named gesture interval. The raw Macrobenchmark memory maxima cover the iteration trace. For memory within the gesture itself, restrict the target process’s Perfetto counters to the named scrub interval. Use the per-iteration Perfetto traces to relate frame stalls to memory changes during scrubbing.

On November 7, 2026 (Pacific), all five iterations passed on the API 37 arm64 phone emulator. The benchmark target was built from `bb1aa20f8db9d4e36f1b5cab246cbf3fa4fdb42f` with APK SHA-256 `0cf7de9d3ee267c81bbf849d13d998b4e3cfef4bb75448290a03c4bc6766a4d2`. The instrumentation APK SHA-256 was `6a2e02c72e8913cde35e0babb0d54db09c6d693c837b5c399dffc9327b1863db`. The median scripted interval took 47.1 seconds, including input injection, observation and settling. The median of the whole-iteration anonymous RSS maxima was 147.25 MiB. The iteration-wide frame-overrun P95 was 111.8 ms. [Raw benchmark results](evidence/scrub-benchmark-api37.json)

Across the five named scrub intervals the anonymous RSS maxima were 105.5, 105.5, 114.0, 147.3 and 103.7 MiB (median 105.5 MiB). To reproduce this comparison, match the target layer’s actual and expected frames by process and surface-frame token within the `scrubAcrossDays` slice. Rank them by actual end time minus expected end time and read the target process’s most recent `mem.rss.anon` sample at the start of that frame. The elapsed gesture interval is a workload description and includes UI Automator overhead. Use the frame and memory data for performance comparisons.

In iteration 3, the target layer’s largest deadline miss was 128.47 ms, 18.925 seconds into the scrub. The most recent anonymous RSS sample at the start of that frame was 102 MiB. Anonymous RSS peaked at 114 MiB during the scrub and ended at 92 MiB. These values describe one emulator trace. Physical-device traces are still needed before setting a budget.

## Open measurements for issue #27

| Required journey | Evidence needed |
| --- | --- |
| Cold and warm first usable station list | The launch harness is implemented. Collect physical-device cold and warm baselines. |
| Complete station detail by capability | The bundled tide and measured current have cold and warm harnesses. Other capabilities and physical-device baselines remain open. |
| Interactive map with and without offline packs | Measure first responsive station selection with recorded network and pack state. |
| Focused station search, first and repeat queries | The focus, first-query and repeat-query harnesses are implemented. Collect physical-device baselines. |
| Memory and frames during long chart scrubs | The bundled tide benchmark produces traces; the scrub-only frame and memory comparison uses Perfetto post-processing. Physical-device traces and regression budgets remain open. |

The list and search benchmarks supply repeatable boundaries for physical measurements. The map computation benchmark has separate [camera-specific evidence](map-performance.md). Physical-device timings and regression budgets remain pending.
