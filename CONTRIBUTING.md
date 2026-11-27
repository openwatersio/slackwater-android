# Contributing

Slackwater for Android follows the product behavior of [slackwater-ios](https://github.com/openwatersio/slackwater-ios). [docs/ios-parity.md](docs/ios-parity.md) lists each iOS contract, the Android issue that owns it and the deliberate Android differences. Read the row for the area you are changing before you start.

## Getting started

The [README's Build section](README.md#build) has the toolchain (JDK 17, Node 24, Python 3, Android SDK platform 37) and what each check covers. The short version:

```sh
node scripts/sync-upstream.mjs
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

`sync-upstream.mjs` fills the ignored `vendor/` folder with the pinned sources, and a new worktree needs it before its first Gradle build. Run it again after pulling a change to `upstream.lock.json`: it moves each clean checkout to its new pin. The last command needs an emulator or device.

## Pull requests

Work on a branch and open a pull request. Say in the description what changed and why, and what you ran. Small pull requests get reviewed faster.

CI builds the debug, release and benchmark variants and the release bundle, and runs the device suite on API 26 and API 37. A pull request needs all of it green.

- **Strings** come from the pinned iOS catalog. Add the key to `scripts/strings-map.json` and run `node scripts/export-strings.mjs`; do not edit `ios_strings.xml` by hand.
- **A new or updated dependency** needs its checksums in `gradle/verification-metadata.xml`. Add only the new components. `./gradlew --write-verification-metadata sha256` rewrites the whole file and relabels the source archives that were checked by hand, so take the new entries from its output and restore the rest. Check new source archives against the publisher's checksum, and add the library to `app/src/main/res/raw/third_party_notices.txt`.
- **A workflow change** that adds or updates an action needs `gh actions-lock --relock`, so `.github/workflows/actions.lock` matches.

## Releasing

Merging to `main` is releasing to testers. The [Play upload workflow](.github/workflows/release.yml) runs every night at 09:23 UTC: it takes the head of `main` once Android CI has passed on it, builds and signs the release bundle, and uploads it to Google Play's internal testing track. A night with no new commit uploads nothing. [docs/releasing.md](docs/releasing.md) describes each job, the version codes and the one-time setup.

What that means for a change:

- **A red `main` ships nothing.** The nightly waits for Android CI on the commit and stops if it failed.
- **The bundle is checked, not only the APK.** `scripts/check-apk.py` runs on both in CI: the reviewed assets and no others, the tide database and current catalog marked uncompressed, and no test fixture code. A change to packaging has to keep both passing.
- **Premium stays off in release builds** until its purchases have been verified on a Play-distributed build ([#12](https://github.com/openwatersio/slackwater-android/issues/12)). CI checks that.
- **Closed testing and production are deliberate.** A maintainer promotes a nightly in Play Console, or runs the workflow by hand with a closed track. The workflow refuses open testing and production.

Maintainers can start an upload without waiting for the night:

```sh
gh workflow run release.yml --ref main -f track=internal
```

A failed run is not re-run. Start a new one: a re-run would give an old commit a newer version code, and the workflow refuses it once `main` has moved.

The upload key and the Google settings live in the `play` environment, which only `main` can use. Never add a signing key, a keystore or a Google credential to the repository, to a repository-level secret, or to a job that runs Gradle.

## Review checks

Checks a reviewer of this repository applies beyond the general ones.

- **Secrets and build code stay apart.** A job that runs Gradle, or any other code from the repository's dependencies, holds no secret. Secrets belong to an environment with a branch rule, not to the repository.
- **Packaging changes cover the bundle.** Play receives the bundle, so a check that only reads an APK does not cover what ships.
- **A dependency change shows its whole footprint.** Read the merged release manifest for new permissions and components, list every transitive library in the notices, and say in the pull request what the library can send off the device.
- **`verification-metadata.xml` only gains entries.** A diff that changes an existing entry's `origin` or drops one is a rewrite to undo, not a change to accept.
- **Workflow shell logic has no tests.** Read each `gh api` call for what happens when it fails or returns nothing, and each condition for what a re-run does.

## License and CLA

Slackwater for Android is licensed under [GPL-3.0](LICENSE). Contributors sign the [Contributor License Agreement](CLA.md) before a pull request can be merged, and are prompted on their first one. [docs/licensing.md](docs/licensing.md) explains why.
