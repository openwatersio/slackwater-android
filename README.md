# Slackwater for Android

Native Android app for offline tide and current predictions. The first Compose target loads a pinned Friday Harbor record and evaluates the Kotlin prediction and astronomy libraries without network permission. Station browsing and forecast screens are tracked separately.

## Build

Use JDK 17, Node 24.2 or later, Python 3, Git, and Android SDK platform 37. Set `ANDROID_HOME` or add `sdk.dir` to an ignored `local.properties` file. Gradle needs Node on its PATH; for Android Studio launched outside a shell, set `nodeExecutable=/absolute/path/to/node` in your user Gradle properties (`~/.gradle/gradle.properties`), or pass `-PnodeExecutable=/absolute/path/to/node` on the command line.

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

The launch screen maps the uncompressed tide database directly from the APK and looks up Friday Harbor by station ID with the pinned Kotlin database reader. Runtime clock injection lives in the pure core; the shell requests no location and loads no Canadian models, so permission/location/model seed behavior belongs with those feature consumers. Test data never enters the release APK.

Gradle checks the source pins and packaged inputs during configuration, including Android Studio sync. Kotlin compiler scratch files under `kotlin/.kotlin/` are excluded from the source cleanliness check. CI audits both debug and release APKs.

Dependency checksums are in `gradle/verification-metadata.xml`; the wrapper has a distribution checksum. Review dependency changes before intentionally regenerating verification metadata. Upstream fixture provenance is recorded in the shared test fixture; data credits and third-party notices are available from the launch screen.

## Development plan

The [implementation plan](docs/superpowers/plans/2026-11-05-android-development.md) and [parity ledger](docs/ios-parity.md) define the remaining work against iOS PR #900. Work is tracked in [repository issues](https://github.com/openwatersio/slackwater-android/issues).
