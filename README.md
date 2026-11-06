# Slackwater for Android

Native Android app for offline tide and current predictions. It opens on the station list: your location, favorites, nearby stations and recents, each with a live reading, plus search across the bundled catalog, using local prediction data. The map uses Internet access to load the OpenFreeMap Fiord basemap and tiles. Tile requests tell OpenFreeMap which area is on screen, including the area around your location when the map is centered there. If the style is unavailable offline, local station pins still open their station details. The app asks for coarse and fine location once for the list, with location hardware optional. Tap a map pin for its station preview, then tap the preview to open the tide or current chart. Tap the station name on the chart to return to its position on the map.

## Build

Install JDK 17 so Gradle can discover it for both Android Studio sync and command-line builds. If Gradle cannot find your installation, set `org.gradle.java.installations.paths=/absolute/path/to/jdk-17` in `~/.gradle/gradle.properties`, using the JDK home that contains `bin/java`.

Use Node 24.2 or later, Python 3, Git, and Android SDK platform 37. Set `ANDROID_HOME` or add `sdk.dir` to an ignored `local.properties` file. Gradle needs Node on its PATH; for Android Studio launched outside a shell, set `nodeExecutable=/absolute/path/to/node` in your user Gradle properties (`~/.gradle/gradle.properties`), or pass `-PnodeExecutable=/absolute/path/to/node` on the command line.

```sh
node scripts/sync-upstream.mjs
node scripts/sync-upstream.mjs --check
node --test scripts/*.test.mjs
./gradlew :core:test :app:assembleDebug :app:lintDebug
python3 scripts/check-apk.py app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:connectedDebugAndroidTest
```

The device check requires an attached emulator/device. CI runs it on API 26 and API 37.0. Test fixtures exercise NOAA tide events and Almanac solar positions on both JVM and Android; the launch test verifies that the list’s cards read predictions from the bundled database. `SlackwaterTestRunner` records the list's one location ask before any test, so the system prompt never covers a test's activity.

`upstream.lock.json` pins source repositories, product documentation, data and recipe provenance separately. `vendor/` contains ignored exact-SHA checkouts. Sync fails on changed or untracked dependency files or a different revision; inspect and preserve local work before replacing a checkout. `--check` performs no network requests and rejects changed/missing/unexpected packaged assets. Generated catalogs are committed; `stations.tcdb` stays uncompressed. Canadian files contain identity/evidence only, with no user predictions or fitted models.

Cards map the uncompressed tide database directly from the APK and look up each station by ID with the pinned Kotlin database reader. Runtime clock injection lives in the pure core. Test data never enters the release APK.

Gradle checks the source pins and packaged inputs during configuration, including Android Studio sync. Kotlin compiler scratch files under `kotlin/.kotlin/` are excluded from the source cleanliness check. CI audits the debug, release and optimized benchmark APKs.

Dependency checksums are in `gradle/verification-metadata.xml`; the wrapper has a distribution checksum. CI resolves published source variants for the debug app and tests. After updating Gradle or a dependency, sync the project in Android Studio to check the additional archives requested by the IDE. Verify any new sources, samples and Javadoc archives against the publisher before adding their SHA-256 pins. A wrapper update also needs the matching Gradle source ZIP checksum. Keep Studio’s generated .idea directory local. Upstream fixture provenance is recorded in the shared test fixture; data credits and third-party notices are available from the launch screen.

## Development plan

The [implementation plan](docs/superpowers/plans/2026-11-05-android-development.md) and [parity ledger](docs/ios-parity.md) define the remaining work against iOS PR #900. Work is tracked in [repository issues](https://github.com/openwatersio/slackwater-android/issues).

## Android reminders

The alert editor previews station events offline. Saving a reminder requires a current Premium entitlement or active free day. The first save on Android 13 or later asks for notification permission. A denial keeps the rule available in My alerts. Enable notifications for Slackwater and its Station reminders channel in Android settings to resume delivery.

Android may defer reminders in Doze or when exact alarms are unavailable. The app uses one-shot alarms and rechecks the station prediction, notification access and entitlement before posting. Tapping a notification opens the station at the event time. Android can restart a closed app to deliver an alarm. After a user force-stop, open Slackwater to rebuild the schedule. Reboot, package replacement and clock changes restore eligible entries within the saved horizon.

Reminders use Android notification and boot permissions. Declaring special exact-alarm access requires a separate release policy review. [Android alarm guidance](https://developer.android.com/develop/background-work/services/alarms) and [notification permissions](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

## Station calendars

Open **Station calendars** from a station to publish the next 90 days of tide turns, slack windows or derived slacks, plus visible lunar eclipses. Each station and series has a local Android calendar. Events open the station at the event time and have no reminders. Publishing requires read and write calendar access.

A subscription started with purchased Premium keeps publishing after lapse. A free-day subscription stops when the free day ends, and purchasing converts it to a purchased subscription. Android schedules refresh and expiry work. If execution or calendar access is blocked, already-published events can remain until the next successful reconciliation. Opening Slackwater retries pending work.

Removing a favorite keeps its calendar subscription. Removing the subscription asks for confirmation with the station name and number of generated events that will be removed. Personal entries and events already underway stay in Calendar.

## Printable tide and current tables

Read a station’s seven-day schedule for free. With Premium or an active free day, open **Printable tables** to preview a week starting on the schedule’s selected day or the calendar month containing that day. Share exports the preview as a PDF, and Print opens Android’s print dialog. The document uses the station’s time zone, the selected reading units and local Letter or A4 paper sizing.

Tables use predictions already on the device. For online current gates and Canadian tide previews, the PDF identifies the covered dates within the requested period. Sharing grants the receiving app read access to that PDF.

## Prediction shortcuts

Open Slackwater once, then touch and hold its launcher icon for **Next low tide**, **Next high tide**, or **Next slack window**. The launcher controls how many actions it shows. All three are also available under **Prediction shortcuts** in the app. Choose a place or date in the result screen; on a supported launcher you can pin the configured query to your home screen.

A query without a place needs authorized device location. A named query stays at the selected station and uses predictions available offline. A date means the whole calendar day at that station, even when your device uses another time zone. An undated slack query looks ahead 48 hours using your comfort-current setting.
