# Offline chart packs

On November 6, 2026, an API 37 arm64 emulator downloaded the rendered Fiord style with MapLibre Android 13.7.1. The production chart-pack manager used three regions bounded to 49.23–49.32° N, 123.16–123.07° W around Vancouver. The regions used the production tier zoom ranges 0–4, 5–8 and 9–12. Bounding the validation area keeps the live check repeatable with a modest download. The production-footprint run below checks the full geometry.

The manager reported three ready regions, zero failures and 10,146,701 bytes summed across the regions. The native HTTP client requested 23 distinct tile paths, 97 glyph-range paths, and the 1× and 2× JSON and PNG files for the hosted ofm_f384 sprite. Shared resources can contribute to multiple region byte counts.

After clearing MapLibre's ambient cache, the test force-stopped the app and started a fresh instrumentation process with airplane mode enabled and both Wi-Fi and mobile data disabled. The download process had PID 5473 and the offline renderer had PID 5659. The production NativeStationMap loaded Fiord and returned rendered vector features at five successively shifted camera positions, from 49.278° N, 123.120° W to 49.286° N, 123.128° W. The camera visited z4, z5, z8, z9 and z12. This check launches the production map in a test activity.

A separate offline invocation seeded a favorite, a recent station and a Canadian tide model in the app's normal stores. It enumerated the three existing SDK region IDs, invoked the production removal action, and waited until all three disappeared from MapLibre. The saved places still matched their original snapshot and the model's stored offset remained 2.5.

## Production footprint

A clean download used desiredChartPacks with a Vancouver fix and the bundled chs-vancouver station. All 11 regions completed in 38.360 seconds with zero failures. They covered the entire world at z0–4, nine surrounding grid cells at z5–8 and the station detail region at z9–12. MapLibre reported 202,707,599 summed region bytes. Its HTTP client requested 2,632 distinct tile paths, 97 glyph-range paths, and both sprite resolutions.

The initial cold reopen exposed a station region that changed native ID from 11 to 12. The SDK reopened the eastern longitude at −122.82377019524696° while the requested value was −122.82377019524695°. The corrected manager compares bounds with a tolerance of 0.00000001° while keeping the tier and style checks exact.

After the fix, download PID 10056 recorded all 11 native region IDs and cleared the ambient cache. With airplane mode on, Wi-Fi and mobile data off, and the app force-stopped, PID 10480 reopened all 11 regions in 2.273 seconds. The entire key-to-ID mapping matched, including world ID 1, grid IDs 2–10 and station ID 11. The production map returned rendered Fiord features at z4, z5, z8, z9 and z12.

## Reproducing the checks

The default ChartPackDeviceTest uses a local HTTP interceptor, a unique style URL, and one raster color per zoom. It checks that MapLibre downloaded the sprite index, sprite image, tiles and glyph ranges, clears the ambient cache, disables SDK network access, and compares the center pixel at every camera position to that zoom's expected tile color. The run requested 17 unique tile URLs and 97 glyph-range URLs.

To reproduce the live check, use a fresh dedicated emulator with the debug and test APKs installed. Start with network connectivity and fresh app data so the download phase can observe every resource request. For full geometry, include `-e chartFull true` in every phase as shown below. Omit that flag in all three commands for the bounded check.

```sh
runner=io.openwaters.slackwater.android.test/io.openwaters.slackwater.android.SlackwaterTestRunner
test=io.openwaters.slackwater.android.map.ChartPackDeviceTest
adb shell am instrument -w -e class "$test" -e chartLive true -e chartFull true -e chartPhase download "$runner"
adb shell cmd connectivity airplane-mode enable
adb shell svc wifi disable
adb shell svc data disable
adb shell am force-stop io.openwaters.slackwater.android
adb shell am instrument -w -e class "$test" -e chartLive true -e chartFull true -e chartPhase offline "$runner"
adb shell am instrument -w -e class "$test" -e chartLive true -e chartFull true -e chartPhase remove "$runner"
adb shell cmd connectivity airplane-mode disable
adb shell svc wifi enable
adb shell svc data enable
```
