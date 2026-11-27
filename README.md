# Slackwater for Android

Native Android app for offline tide and current predictions. It opens on the station list: your location, favorites, nearby stations and recents, each with a live reading, plus search across the bundled catalog, using local prediction data. The map uses Internet access to load the OpenFreeMap Fiord basemap and tiles. Tile requests tell OpenFreeMap which areas you view or download, including areas around your location and saved stations. MapLibre caches viewed tiles on the device. If the basemap fails to load or takes more than two seconds on first open, local station pins remain available. Tap Retry map to allow up to 30 seconds for a slow connection, or return to local pins while it loads. The app asks for coarse and fine location once for the list, with location hardware optional. Tap a map pin for its station preview, then tap the preview to open the tide or current chart. Tap the station name on the chart to return to its position on the map.

On the Salish Sea map, shaded triangles show certified current speeds. Channel patches draw above the regional fill when their station has predictions for the displayed time. At detail zooms, Dodd Narrows shows a direction arrow when a nonzero CHS reading covers the displayed time.

Slackwater automatically keeps a world overview, regional coverage around your location, and detail around favorite, recent, and downloaded stations. Detail extends about 20 km in each direction except near the polar limits of the map. Packs can download over mobile data. New coverage is limited to 200,000 distinct tile locations across zoom levels. On metered networks, Android Data Saver pauses automatic transfers. Once saved stations have loaded, revoking location access or letting a one-time grant expire drops regions held only for that fix. Open **Downloads** from the station list to check progress, pause or resume transfers, retry a failure, or remove chart packs. Removing packs disables automatic chart downloads until you enable them again.

Open a Canadian tide station and tap **Download for offline use** to request its official tide preview from the Canadian Hydrographic Service. The requested window starts 48 hours before the station’s local midnight and ends five days after it. The chart shows the dates IWLS supplied. A saved preview must cover now through at least 48 hours after local midnight, with no gaps longer than 30 minutes. **Downloads** shows pending transfers and lets you cancel or retry. The saved chart reopens offline. An unfinished request resumes when the app is open, or while a background download run lasts, and network access permits it. With Android Data Saver enabled, preview transfers wait for an unrestricted connection. While the preview is available, the app downloads the 60-day tide fit in the foreground. Detail adopts the fitted model when chart motion settles.

Canadian current gates with a validated fit recipe download official speed and direction predictions on request. The app fits them along the published flood axis over the gate’s 60- or 210-day window. Where the catalog authorizes a provisional fit, detail can use the first 60 days while the rest downloads. Online-only gates continue to use official prediction weeks.

On the phone, Canadian stations in the list and favorites download while the app is open. Opening a station moves it ahead of other queued stations. In Downloads, select **Within 25 km**, **Within 150 km**, or **All current stations** to extend coverage. Radius choices use the device location. Clearing a choice stops pending automatic work outside the remaining selection and keeps downloaded predictions for offline use. On Android 14 and later, with a radius selected, the queue continues after you leave the app once any station in it starts downloading or waits for a connection; with only All current stations, once a current does. A progress notification shows the run, and its **Cancel** stops it until the app next comes back on screen. While the run lasts, the rest of the queue continues with it, including stations from the list and direct requests. The radius is measured from the last location the app had. With notifications turned off, the run still appears in Android's Task Manager, which can stop it. Android can stop the run at any time, and so does losing the network, Data Saver, or 30 minutes in which nothing is saved, such as when one station keeps failing. A run that stops, or whose app Android closed while it waited for a connection, does not start again in the background: what finished stays saved, and the rest resumes when the app is next on screen, which also starts a new run. With only the list's stations selected, downloads stop when you leave the app, as they do on earlier Android versions.

## Build

Install JDK 17 so Gradle can discover it for both Android Studio sync and command-line builds. If Gradle cannot find your installation, set `org.gradle.java.installations.paths=/absolute/path/to/jdk-17` in `~/.gradle/gradle.properties`, using the JDK home that contains `bin/java`. CI runs Gradle on JDK 25; core and the included Kotlin builds use a JDK 17 toolchain, while app and benchmark use the Gradle JDK. A separate CI job runs the build checks with Gradle on JDK 17, and device tests run on both JDKs.

Use Node 24.2 or later, Python 3, Git, and Android SDK platform 37. Gradle invokes `python3` to index the pinned current catalog, and accepts `-PpythonExecutable=/absolute/path/to/python3` when Python is outside the build process’s PATH. Set `ANDROID_HOME` or add `sdk.dir` to an ignored `local.properties` file. Gradle needs Node on its PATH; for Android Studio launched outside a shell, set `nodeExecutable=/absolute/path/to/node` in your user Gradle properties (`~/.gradle/gradle.properties`), or pass `-PnodeExecutable=/absolute/path/to/node` on the command line.

```sh
node scripts/sync-upstream.mjs
node scripts/sync-upstream.mjs --check
node scripts/export-strings.mjs --check
node --test scripts/*.test.mjs
./gradlew :core:test :app:assembleDebug :app:lintDebug
python3 scripts/check-apk.py app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:connectedDebugAndroidTest
```

The device check requires an attached emulator/device. CI runs it on API 26 and API 37.0, after `adb emu geo fix -140.0 30.0`: on API 26 the emulator's own GPS position reaches the location tests past their test providers, so it has to match theirs, and that open-ocean position keeps later tests' lists free of nearby stations. Test fixtures exercise NOAA tide events and Almanac solar positions on both JVM and Android; the launch test verifies that the list’s cards read predictions from the bundled database. `SlackwaterTestRunner` records the list's one location ask and the first-run tour as done before any test, so neither the system prompt nor the tour's station covers a test's activity; the tour's own tests clear the flag.

`upstream.lock.json` pins source repositories, product documentation, data and recipe provenance separately. `vendor/` contains ignored exact-SHA checkouts. Sync moves a clean checkout that is not at its pinned commit: it fetches the commit, switches to it and says which checkout moved. It never replaces local work. It fails, and leaves the checkout where it is, on changed or untracked dependency files, on a commit made in the checkout that no branch keeps, and when the move would write over an ignored file. `--check` moves nothing and fails on any checkout that is not at its pin, including the iOS one. `--check` performs no network requests and rejects changed/missing/unexpected packaged assets. Generated catalogs are committed; `stations.tcdb` stays uncompressed. Canadian files contain identity/evidence only, with no user predictions or fitted models.

Cards map the uncompressed tide database directly from the APK and look up each station by ID with the pinned Kotlin database reader. Runtime clock injection lives in the pure core. Test data never enters the release APK.

Sync moves a checkout by detaching it at the pinned commit, so a branch you had checked out there stays. To keep commits you made on a detached checkout, put them on a branch first; sync names the commit and the command when it refuses. A stash does not count. Because Gradle runs `--check` on every configuration, a stray untracked file in any checkout, the iOS one included, stops the build until it is removed or ignored. A full clone keeps its history; only the shallow checkouts sync creates are fetched shallow.

Gradle checks the source pins and packaged inputs during configuration, including Android Studio sync. Kotlin compiler scratch files under `kotlin/.kotlin/` are excluded from the source cleanliness check. CI audits the debug, release and optimized benchmark APKs and the release bundle.

Dependency checksums are in `gradle/verification-metadata.xml`; the wrapper has a distribution checksum. CI resolves published source variants for the debug app and tests. After updating Gradle or a dependency, sync the project in Android Studio to check the additional archives requested by the IDE. Verify any new sources, samples and Javadoc archives against the publisher before adding their SHA-256 pins. A wrapper update also needs the matching Gradle source ZIP checksum. Keep Studio’s generated .idea directory local. Upstream fixture provenance is recorded in the shared test fixture; data credits and third-party notices are available from the launch screen.

## Releasing

A nightly workflow uploads a signed bundle of `main` to Google Play internal testing. [Releasing to Google Play](docs/releasing.md) covers its setup, its version codes and how to run it by hand, and the [contributing guide](CONTRIBUTING.md#releasing) says what the nightly means for a change.

## Remaining work

The [parity ledger](docs/ios-parity.md) defines the remaining work against iOS main at the merge commit of PR #921 (`6457d71b46af0e1c3310e1f8688dffc0db102d33`). Work is tracked in [repository issues](https://github.com/openwatersio/slackwater-android/issues) and the [Slackwater Android project](https://github.com/orgs/openwatersio/projects/5).

## Translations

The app follows the device language, or its per-app language setting on Android 13 and later. It supports English and the 15 locales shipped by the pinned iOS app: Danish, German, Spanish (Spain), Finnish, French (Canada), Irish, Scottish Gaelic, Italian, Japanese, Korean, Norwegian Bokmål, Dutch, Portuguese (Brazil and Portugal), and Swedish.

`node scripts/export-strings.mjs` exports shared text from the exact iOS commit in `upstream.lock.json`. `scripts/strings-map.json` maps existing Android resource IDs to iOS keys and preserves Android's English wording and the existing Canadian French copy through explicit migration overrides. Edit Android-specific copy in `scripts/translations/android/*.json`; the exporter writes it separately to `strings_android.xml`. Generated files record their source, and `--check` rejects stale output, incomplete locale coverage and incompatible format arguments. See [translation validation](docs/validation/localization.md) for coverage and device evidence.

## Monthly free day

In a debug build, open **Settings → Premium → Try everything free today** to start 24 hours of planning access. The start is saved on this device. Eligibility compares the saved start and the current time in the device’s current calendar and time zone, including after a clock or calendar change. A free day that crosses into the next month runs until its original end. The next month’s offer becomes available after that.

Debug builds enable the Premium sheet and `slackwater://premium` link by default. Build with `-PpremiumEnabled=false` to test the disabled debug variant. Release and benchmark builds keep these entry points disabled unless built with `-PpremiumEnabled=true`, which is how a Play-distributed test build sells Premium before the release default changes. A build with Premium disabled asks Play about purchases only for an account already saved as a buyer, or to finish an acknowledgement, both of which a test build can leave behind; that is how a refund or lapse still reaches it. Every build includes the Google Play Billing Library. It adds the `com.android.vending.BILLING` permission and brings Google Play services and Google's DataTransport reporting library with it, all listed in the in-app notices.

## Premium purchases

The Premium sheet sells two Play products: a yearly subscription (`io.openwaters.slackwater.premium.yearly`) and a lifetime purchase (`io.openwaters.slackwater.premium.lifetime`), at the prices Play returns for the signed-in account. A plan Play does not return cannot be bought, and the sheet offers a retry. A restore Play cannot answer says that purchases are temporarily unavailable. Purchases belong to the Play account; they are not shared with the App Store.

The app keeps one saved answer to "does this account own Premium", which every gate reads, including reminders that fire with the app closed. It asks Play again each time the app comes to the front, after a purchase, and on **Restore purchase**:

- A paid yearly or lifetime purchase sets the answer and is acknowledged, which Play requires within three days. An acknowledgement that Play refuses, or that could not be attempted because Play did not answer right after the purchase, is retried in the background whenever the device is online, for about four days, and on each return to the app.
- A pending payment grants nothing until Play reports it paid. While one is pending the app asks Play again twice a day in the background, so a payment that completes with the app closed is still acknowledged in time.
- When Play answers that the account owns neither product, as after a refund or a lapsed subscription, the answer is cleared.
- When Play cannot be reached, or only one of its two purchase lists answers and shows nothing, the saved answer stands. An offline launch never ends Premium.

The monthly free day is separate: it runs on the device's clock and needs no connection. Purchase checks run only on the device against Play's own records; there is no Slackwater server. Verifying these flows with Play license testers on a Play-distributed build is tracked in [#12](https://github.com/openwatersio/slackwater-android/issues/12).

## Android reminders

The alert editor previews station events offline. Saving a reminder requires a current Premium entitlement or active free day. The first save on Android 13 or later asks for notification permission. A denial keeps the rule available in My alerts. Enable notifications for Slackwater and its Station reminders channel in Android settings to resume delivery.

Android may defer reminders in Doze or when exact alarms are unavailable. The app uses one-shot alarms and rechecks the station prediction, notification access and entitlement before posting. Tapping a notification opens the station at the event time. Android can restart a closed app to deliver an alarm. After a user force-stop, open Slackwater to rebuild the schedule. Reboot, package replacement and clock changes restore eligible entries within the saved horizon.

Reminders use Android notification and boot permissions. Declaring special exact-alarm access requires a separate release policy review. [Android alarm guidance](https://developer.android.com/develop/background-work/services/alarms) and [notification permissions](https://developer.android.com/develop/ui/compose/notifications/notification-permission).

## Station calendars

Open **Station calendars** from a station to publish the next 90 days of tide turns, slack windows or derived slacks, plus visible lunar eclipses. Each station and series has a local Android calendar. Events open the station at the event time and have no reminders. Publishing requires read and write calendar access.

A subscription started with purchased Premium keeps publishing after lapse. A free-day subscription stops when the free day ends, resumes during a later free day, and becomes a purchased subscription if the user buys Premium. Android schedules refresh and expiry work. If execution or calendar access is blocked, already-published events can remain until the next successful reconciliation. Opening Slackwater retries pending work.

Removing a favorite keeps its calendar subscription. Removing the subscription asks for confirmation with the station name and number of generated events that will be removed. Personal entries and events already underway stay in Calendar.

## Printable tide and current tables

Read a station’s seven-day schedule for free. With Premium or an active free day, open **Printable tables** to preview a week starting on the schedule’s selected day or the calendar month containing that day. Share exports the preview as a PDF, and Print opens Android’s print dialog. The document uses the station’s time zone, the selected reading units and local Letter or A4 paper sizing.

Tables use predictions already on the device. For online current gates and Canadian tide previews, the PDF identifies the covered dates within the requested period. Sharing grants the receiving app read access to that PDF.

## Prediction shortcuts

Open Slackwater once, then touch and hold its launcher icon for **Next low tide**, **Next high tide**, or **Next slack window**. The launcher controls how many actions it shows. All three are also available under **Prediction shortcuts** in the app's Settings. Choose a place or date in the result screen; on a supported launcher you can pin the configured query to your home screen.

A query without a place needs authorized device location. A named query stays at the selected station and uses predictions available offline. A date means the whole calendar day at that station, even when your device uses another time zone. An undated slack query looks ahead 48 hours using your comfort-current setting.

## License

Slackwater for Android is © Open Water Software, LLC and contributors, and licensed under [GPL-3.0](LICENSE).

Contributors sign the [Contributor License Agreement](CLA.md) before their pull request can be merged; you are prompted automatically on your first pull request. [docs/licensing.md](docs/licensing.md) explains why.
