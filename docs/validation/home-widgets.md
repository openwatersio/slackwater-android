# Home-screen station widgets

## Pixel Launcher

On the Android API 37 emulator, add Slackwater from the Pixel Launcher widget picker. Search for Friday Harbor and select the **San Juan Island · Tides · noaa/9449880** result. The two-column card shows the station, tide height and arrow, next turn, curve and snapshot time. Drag its right handle to four columns. The name and reading now share a row, the reading includes the tide direction, and the curve uses the wider time span.

With both emulator radios off and the app process absent, the launcher retained the wide card with its snapshot time. Tapping it delivered `slackwater://station/noaa%2F9449880` to the main activity and opened the Friday Harbor tide timeline. Restore Wi-Fi and cellular data after the check.

## Coverage and refresh

An online current station needs a saved uninterrupted prediction covering the requested widget span. A current fit must pass the same recipe and fit-window checks used in the app. The rendering tests use an expired Arran Rapids window and compatible and incompatible Johnstone Strait fits. Settings tests exercise station successors and selection by tide or current.

“Nearest” uses the last station IDs found under the current location permission from the app’s foreground fix or a usable last-known system fix read during configuration. The widget never requests background location. WorkManager schedules periodic refresh and can defer it. Each card includes its snapshot time. A measured current window can show its observed closing as a station-local time.

## Saved stations and model replacement

On the API 37 emulator, Friday Harbor showed the filled favorite star before and after a force-stop. Opening its station link cold started a new app process (PID 25270 to 25495), and the favorite action still read “Remove from favorites.”

To repeat the favorite check, open Friday Harbor and add it to Favorites. Record the PID with `adb shell pidof io.openwaters.slackwater.android`, then run:

```sh
adb shell am force-stop io.openwaters.slackwater.android
adb shell am start -W -a android.intent.action.VIEW -d https://slackwater.xyz/tides/friday-harbor io.openwaters.slackwater.android
adb shell pidof io.openwaters.slackwater.android
```

Confirm the PID changed and the station still offers “Remove from favorites.” Restore its original favorite state afterward.

The device test `WidgetRenderingTest.widgetReadsCompleteFitOnBothSidesOfAtomicReplacement` renders Johnstone Strait Central through the widget’s AndroidPredictionRepository and RemoteViews using an isolated model directory. It pauses the writer after syncing a replacement current fit to a temporary file. The displayed speed matches the original fit while the new fit is staged and matches the replacement fit after publication. The curve is visible at both reads. All four widget rendering tests passed on API 37.
