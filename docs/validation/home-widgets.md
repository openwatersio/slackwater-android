# Home-screen station widgets

## Pixel Launcher

On the Android API 37 emulator, add Slackwater from the Pixel Launcher widget picker. Search for Friday Harbor and select the **San Juan Island · Tides · noaa/9449880** result. The two-column card shows the station, tide height and arrow, next turn, curve and snapshot time. Drag its right handle to four columns. The name and reading now share a row, the reading includes the tide direction, and the curve uses the wider time span.

With both emulator radios off and the app process absent, the launcher retained the wide card with its snapshot time. Tapping it delivered `slackwater://station/noaa%2F9449880` to the main activity and opened the Friday Harbor tide timeline. Restore Wi-Fi and cellular data after the check.

## Coverage and refresh

An online current station needs a saved uninterrupted prediction covering the requested widget span. A current fit must pass the same recipe and fit-window checks used in the app. The rendering tests use an expired Arran Rapids window and compatible and incompatible Johnstone Strait fits. Settings tests exercise station successors and selection by tide or current.

“Nearest” uses the last station IDs found under the current location permission from the app’s foreground fix or a usable last-known system fix read during configuration. The widget never requests background location. WorkManager schedules periodic refresh and can defer it. Each card includes its snapshot time. A measured current window can show its observed closing as a station-local time.
