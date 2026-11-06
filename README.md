# Slackwater for Android

Native Android app for offline tide and current predictions. The first Compose target loads a pinned Friday Harbor record and evaluates the Kotlin prediction and astronomy libraries without network permission. The app declares coarse and fine location for the station list, with location hardware optional. Station browsing and forecast screens are tracked separately.

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

The device check requires an attached emulator/device. CI runs it on API 26 and API 37.0. Test fixtures exercise NOAA tide events and Almanac solar positions on both JVM and Android; the launch test verifies the application has no Internet permission and loads the bundled station.

`upstream.lock.json` pins source repositories, product documentation, data and recipe provenance separately. `vendor/` contains ignored exact-SHA checkouts. Sync fails on changed or untracked dependency files or a different revision; inspect and preserve local work before replacing a checkout. `--check` performs no network requests and rejects changed/missing/unexpected packaged assets. Generated catalogs are committed; `stations.tcdb` stays uncompressed. Canadian files contain identity/evidence only, with no user predictions or fitted models.

The launch screen maps the uncompressed tide database directly from the APK and looks up Friday Harbor by station ID with the pinned Kotlin database reader. Runtime clock injection lives in the pure core; the shell does not yet ask for location or load Canadian models, so permission/location/model seed behavior belongs with those feature consumers. Test data never enters the release APK.

Gradle checks the source pins and packaged inputs during configuration, including Android Studio sync. Kotlin compiler scratch files under `kotlin/.kotlin/` are excluded from the source cleanliness check. CI audits both debug and release APKs.

Dependency checksums are in `gradle/verification-metadata.xml`; the wrapper has a distribution checksum. CI resolves published source variants for the debug app and tests. After updating Gradle or a dependency, sync the project in Android Studio to check the additional archives requested by the IDE. Verify any new sources, samples and Javadoc archives against the publisher before adding their SHA-256 pins. A wrapper update also needs the matching Gradle source ZIP checksum. Keep Studio’s generated .idea directory local. Upstream fixture provenance is recorded in the shared test fixture; data credits and third-party notices are available from the launch screen.

## Development plan

The [implementation plan](docs/superpowers/plans/2026-11-05-android-development.md) and [parity ledger](docs/ios-parity.md) define the remaining work against iOS PR #900. Work is tracked in [repository issues](https://github.com/openwatersio/slackwater-android/issues).

## Android reminders

The alert editor previews station events offline. Saving a reminder requires a current Premium entitlement or active free day. The first save on Android 13 or later asks for notification permission. A denial keeps the rule available in My alerts. Enable notifications for Slackwater and its Station reminders channel in Android settings to resume delivery.

Android may defer reminders in Doze or when exact alarms are unavailable. The app uses one-shot alarms and rechecks the station prediction, notification access and entitlement before posting. Tapping a notification opens the station at the event time. Android can restart a closed app to deliver an alarm. After a user force-stop, open Slackwater to rebuild the schedule. Reboot, package replacement and clock changes restore eligible entries within the saved horizon.

The release manifest requests notification and boot access. Declaring special exact-alarm access requires a separate release policy review. [Android alarm guidance](https://developer.android.com/develop/background-work/services/alarms) and [notification permissions](https://developer.android.com/develop/ui/compose/notifications/notification-permission).
