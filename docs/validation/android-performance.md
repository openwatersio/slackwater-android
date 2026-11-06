# Android performance evidence

The benchmark installs a separate target app, `io.openwaters.slackwater.android.benchmark`, signed with the debug key. Its build enables R8 optimization and shell profiling, disables debugging, and uses full ART compilation. The current release build ships without R8 minification, so its timing can differ. It exercises two surfaces available in the Android app today. The launch screen loads one station. The prediction shortcut resolves a named place and computes a tide answer from the bundled database.

| Benchmark | Start | End |
| --- | --- | --- |
| coldStationShell | Launch after Macrobenchmark stops the process | Friday Harbor station name appears |
| warmStationShell | Relaunch the activity with the process warm | Friday Harbor station name appears |
| firstNamedPrediction | Tap **Show prediction** after entering Friday Harbor in a freshly launched **Next low tide** shortcut | The answer displays the time remaining until low tide |
| repeatNamedPrediction | Tap **Show prediction** for Friday Harbor after a successful Port Townsend query in the same activity | The new answer displays the time remaining until low tide |

Each run collects five iterations. The custom trace sections include UI Automator observation latency. Startup timing reports the platform’s first-frame metric separately. Frame timing spans the measured interaction. Navigation to the shortcut, typing, and the prior warm-up query occur outside the query measurement. A fresh process still performs the shortcut’s default nearby lookup before the first named submission.

## Physical-device run

Use a dedicated, unlocked API 29+ phone with English UI, fresh app data, location access denied and airplane mode enabled. Record the device model, OS build, app Git SHA, locale, display refresh rate, battery and thermal state. Keep device settings and station data identical when comparing revisions.

```sh
adb devices -l
# Replace SERIAL with the phone’s adb serial.
export ANDROID_SERIAL=SERIAL
./gradlew :app:assembleBenchmark
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/benchmark/app-benchmark.apk
adb -s "$ANDROID_SERIAL" shell pm clear io.openwaters.slackwater.android.benchmark
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.output.enable=true
```

Archive the benchmark JSON and per-iteration Perfetto traces in `benchmark/build/outputs/connected_android_test_additional_output/benchmark/connected/`. Report median and range of each duration, frame CPU time percentiles and frame overrun where supported. Retain the traces and record the run conditions alongside each result. Repeat independent runs on the same handset to estimate normal variation before setting a regression budget. [Android Macrobenchmark metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)

## Emulator smoke run, November 6, 2026

All four tests passed on the API 37 arm64 Android 17 Medium Phone AVD (Google Play image `CE2A.260420.051.A2/16418371`, 1080 × 2400, 420 dpi). The optimized target APK had SHA-256 `95e3f2dd5d42b60bf418bf50f0556eba1af9582e47e2e0ea5247b53e981b7b38`. Airplane mode was off. Each test produced five duration samples, frame CPU and overrun samples, and five Perfetto traces. The shell tests also reported time to initial display. This run verifies the harness. Physical-device measurements will supply the performance baselines.

## Open measurements for issue #27

| Required journey | Evidence needed |
| --- | --- |
| Cold and warm first usable station list | Wait for an actionable station row on the production browsing screen. |
| Complete station detail by capability | Open each supported capability through the production detail screen and wait for its reading, schedule and chart. Capture cold and warm separately. |
| Interactive map with and without offline packs | Measure first responsive station selection with recorded network and pack state. |
| Focused station search, first and repeat queries | Record search-focus latency and wait for correctly filtered, actionable station results on the list screen. |
| Memory and frames during long chart scrubs | Exercise sustained gesture input on the production scrubber, capture memory over time and correlate frame stalls in the trace. |

These journeys depend on the production list, detail, scrubber and map screens. The shell and shortcut runs provide separate boundaries. Physical measurements and regression budgets remain pending.
