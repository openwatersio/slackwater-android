# Slackwater Android implementation plan

> **For agentic workers:** Use `superpowers:subagent-driven-development` or `superpowers:executing-plans` to implement this plan task by task. Checkboxes track implementation work; writing this plan does not mark any feature complete.

**Goal:** Ship a native Android Slackwater app whose predictions, station selection, chart interactions, and planning features conform to the iOS product contracts at PR #900, with documented adaptations for Android services.

**Architecture:** A pure Kotlin core owns station capabilities, prediction adapters, time and window math, chart state, and planning rules. Compose, MapLibre, Glance, and Android services consume that core through repositories with explicit coverage and entitlement state. Predictions and Canadian fits run on the device; generated catalogs and certified map fields come from pinned upstream artifacts.

**Tech stack:** Kotlin, Gradle composite builds, JDK 17, Android minSdk 26, Compose/Canvas, coroutines and Flow, DataStore, MapLibre Native, Glance, WorkManager, AlarmManager, Calendar Provider, and Play Billing. Select compatible Android Gradle Plugin, Compose, and library versions in Task 1, lock them in the version catalog, and set compile/target SDK to the release requirements verified at that time.

**Spec:** [iOS PR #900](https://github.com/openwatersio/slackwater-ios/pull/900), pinned to `5b26bcd5ce0220302de3ba32be5ebf67552fb06e`. The [parity ledger](../../ios-parity.md) links every source contract, defines precedence, and records Android adaptations and unresolved upstream designs. Read both documents before implementing a task.

## Global constraints

- Use the pinned PR tree, including documents unchanged by the PR. A moving `main` branch or an older Android issue must not silently change this baseline.
- Keep every prediction, station, date, default chart, units/formats, comfort-current setting, and home-screen widget free. Gate alerts, station calendars, printable tables, and changes away from the default sky setting through the shared Premium entitlement.
- Bundle Canadian station identities, never CHS predictions or fitted harmonics. Fetch directly from IWLS and fit privately on the user's device. Preserve source attribution and the detail-footer text "Predictions — not for navigation".
- Use station-local calendars for civil days, midnight, noon, and displayed dates; use elapsed time for geometry and durations. A civil day can have 23 or 25 hours.
- Missing data is an explicit unavailable state. Never render it as zero current, calm water, a flat forecast, a supported direction, or a usable slack window.
- Read the same recipe, model-compatibility rule, effective threshold, units, and station identity in the app, widgets, alerts, calendars, and shortcuts.
- Require no Slackwater account or server for the core app. Cross-device favorites sync, Wear OS, Apple-style lock-screen widgets, and Apple purchase portability are outside this release scope.
- All paths below are proposed additions unless described as existing. The repository baseline is `8746c0a91f9f364ef798e229115e0770e8108b33`, containing `README.md` and `LICENSE`; there is no app target or test runner yet.

## Review focus

- A device changes time zone or crosses DST while the user plans a remote station: preserve the selected instant and station-local day. Tasks 4, 6, 12, and 16 own these tests.
- A Canadian model or catalog changes while another consumer is reading it: publish atomically, reject incompatible models everywhere, and remove invalid future planning output. Tasks 2, 7, 8, 11, and 14 own these tests.
- A sample window ends before a slack window closes: show the observed boundary without inventing a duration or extrapolating. Tasks 3, 6, 8, 12, and 16 own these tests.
- Android kills the process, revokes permission, or delays background work: retain user intent, expose blocked/stale output, and do not deliver an expired trial alert. Tasks 7 and 11–15 own these tests.
- Fast gestures, a late fetch, and an explicit jump compete: the newest user request wins without a visible time jump or duplicate event. Tasks 4, 6, and 8 own these tests.

## Scope and source policy

The target is the full phone product described by PR #900. Intermediate builds are useful test milestones, not a redefinition of v1. The ledger separates behavior Android can implement directly, platform adaptations, specified targets that iOS has not shipped, and designs the source leaves unresolved.

`scrubber.md` owns detail marks and motion; `current-charts.md` owns current semantics and low-resolution charts; `list.md` owns search and list grouping; `detail.md` owns the surrounding page. In a conflict, use the owning document and record the choice in the ledger. In particular, search keeps every namesake even though a sentence in the detail document also mentions search replacement. The later, specific Sun-card composition in detail §8.3 governs its layout.

Do not copy Apple frameworks, widget families, phrase budgets, notification ceilings, store product identifiers, or iOS timing measurements as Android requirements. Preserve their user-visible purpose where Android supports it. The plan's Android choices are proposals for this repository, not claims that PR #900 prescribes Android APIs.

## Dependencies and existing work

The following issue states were checked on November 5, 2026. Verify their delivered APIs and pin reviewed commits when each task starts; a closed issue alone is not an integration test.

| Dependency | Evidence | Consequence |
|---|---|---|
| Kotlin engine | [slackwater#350](https://github.com/openwatersio/slackwater/issues/350), closed; [Kotlin README at `e66586a`](https://github.com/openwatersio/slackwater/blob/e66586a0e2a12c1565e2aefb866c14317f257961/kotlin/README.md) | Consume the engine from source. Tide/current/subordinate predictions, vector readings, CHS recipe types, and an IWLS module exist. Android device validation remains required. |
| Kotlin astronomy | [almanac#46](https://github.com/openwatersio/almanac/issues/46), closed; [README at `75ba6f7`](https://github.com/openwatersio/almanac/blob/75ba6f7b6db7904968db380ce82ad92e8c042b0c/kotlin/README.md) | Consume from source and validate the actual sun, moon, eclipse, and season calls on Android. |
| Kotlin database reader | [slackwater-database#393](https://github.com/openwatersio/slackwater-database/pull/393), merged | Android pins the reader separately from the bundled TCDB (1.0.1-beta.20261103). Device tests check harmonic data, datum shift, and publisher attribution before either pin advances. |
| Kotlin harmonic fitter | [slackwater#405](https://github.com/openwatersio/slackwater/issues/405), open | Blocks completed Canadian fits, not bundled predictions, timeline work, preview rendering, or online windows. Recipe classes do not mean the fitter exists. |
| Play organization setup | [Android#3](https://github.com/openwatersio/slackwater-android/issues/3) | Start alongside Task 1. Package registration, signing certificate, products, and testing track need an account owner. |
| Verified app links | [slackwater.xyz#222](https://github.com/openwatersio/slackwater.xyz/issues/222), open | Website must publish the actual package name and Play signing certificate. Internal links can be tested earlier. |

Keep the existing [Android issues](https://github.com/openwatersio/slackwater-android/issues) as delivery units where they fit. The issue mappings below are a reconciliation plan, not a claim that those issue bodies already reflect PR #900. In particular, #11/#12's widget paywall assumption, #10's abbreviated gate taxonomy, and #4's grouping shorthand must follow the ledger. Add focused issues for alerts, calendars, printable tables, query shortcuts, and documented current-chart targets when implementation begins; do not reopen the closed umbrella issue #1 as one large coding task.

## File and ownership map

Use `:core` and `:app` initially. Keep feature packages cohesive; split Gradle modules only when a measured build or ownership need warrants it. This avoids requiring separate modules for every screen before a working app exists.

| Path | Responsibility |
|---|---|
| `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/wrapper/`, `gradlew`, `gradlew.bat` | Reproducible toolchain and pinned source dependencies |
| `upstream.lock.json`, `scripts/sync-upstream.mjs`, `scripts/export-strings.mjs` | Full SHAs, resource manifest/hashes, fixture provenance, and deterministic string export |
| `vendor/` | Ignored exact-SHA source checkouts restored by the setup script; preserve upstream license/notice files |
| `core/src/main/kotlin/io/openwaters/slackwater/android/core/` | Pure station, reading, timeline, download-policy, alert, and query contracts |
| `app/src/main/kotlin/io/openwaters/slackwater/android/` | Android repositories and feature packages: `stations`, `detail`, `map`, `downloads`, `settings`, `widgets`, `premium`, `alerts`, `calendars`, `sharing`, `shortcuts` |
| `app/src/main/assets/slackwater/` | Generated station data, routes, CHS identities/evidence, and certified map artifacts; explicit allowlist |
| `app/src/main/res/values*/` | Exported product copy plus separate Android-only strings |
| `core/src/test/`, `app/src/test/`, `app/src/androidTest/`, `benchmark/` | Pure conformance, repository, device/UI, and optimized performance checks |
| `docs/ios-parity.md`, `docs/validation/`, `docs/releasing.md` | Source coverage, device evidence, and Android release procedure |

Proposed namespace/application ID: `io.openwaters.slackwater.android`. Confirm its availability with #3 before publishing or configuring website associations; keep it stable afterward. This is an Android identifier, independent of the engine package `io.openwaters.slackwater`.

### Shared interfaces

These are app-owned contracts to implement, not assumed upstream APIs. Define their types in the named files and keep subsequent tasks on these interfaces.

| Owner | Interface and data contract |
|---|---|
| `core/station/StationCatalog.kt` | `suspend fun identities(): List<StationIdentity>`; `suspend fun resolve(id: StationId): StationResolution`. Resolution distinguishes live identity, successor, tombstone, restricted identity, and unknown ID. `StationId` wraps the complete upstream string. |
| `core/prediction/PredictionRepository.kt` | `suspend fun load(id: StationId, span: TimeSpan, purpose: PredictionPurpose): PredictionResult`. `TimeSpan` has `start`/`end: Instant`. Results distinguish ready data with coverage/provenance/capabilities, missing download, unsupported purpose, and invalid model. Never return an empty successful curve for a missing station. |
| `core/prediction/StationCapabilities.kt` | Distinguish tide, measured current, derived timing-only, and online current; record continuous versus bounded coverage, vector/axis availability, final/bounded/provisional fit state, and permitted planning consumers. Define eligibility per consumer: provisional current alerts are supported, while the Siri-equivalent query loader requires a complete current model. |
| `core/settings/ReadingSettings.kt` | `fun effectiveThresholdKn(id: StationId): Double`; global default 0.5 kn, Off 0, supported nonzero range 0.1–10 kn. Store tide values in metres and current values in knots; convert only at presentation boundaries. |
| `core/timeline/TimelineController.kt` | `fun dispatch(action: TimelineAction): TimelineState`. Actions include drag/momentum/rest, explicit target, Now, foreground, viewport change, and data publication. State keeps `anchor`, session `now`, selected instant, offset, motion, request generation, and coverage separate. |
| `core/planning/OccurrenceResolver.kt` | `suspend fun occurrences(rule: AlertRule, span: TimeSpan, settings: ReadingSettings): List<AlertOccurrence>`. Occurrences retain event/fire instants, optional observed close, `noWindow`, and stable minute-based identity. |
| `core/premium/Entitlement.kt` | `fun isPremium(at: Instant): Boolean`; purchased entitlement and optional free-day end remain distinct. Preserve the entitlement origin of each calendar subscription. |
| `core/shortcuts/PredictionQuery.kt` | `suspend fun answer(request: PredictionQuery): PredictionAnswer`. Request has kind, optional concrete place, optional civil date; answer names its station, dates, observed boundaries, and unavailable/ambiguous state. |

All paths beginning `core/` in the interface table are below the Kotlin core source directory above. Use injected `Clock`, station `ZoneId`, storage, and dispatcher dependencies so fixtures never depend on a developer's clock, locale, or saved emulator state. Repositories publish revisions after atomic writes; consumers do not poll directories or maintain independent compatibility rules. Model rejection or fitted-to-online migration also persists `PredictionInvalidation(stationId, reason, revision)` before deleting data; notification/calendar reconciliation consumes and acknowledges that record only after obsolete app-owned output is removed. Temporary missing coverage is not an invalidation.

## Delivery order

| Stage | Tasks | Exit condition |
|---|---|---|
| Foundation | 1–3, start account work from 18 | A device reads a pinned catalog and produces fixture-matching bundled readings offline. |
| Phone core | 4–6, 9, 16–17 as their dependencies land | Browsing, detail, scrubber, map pins, links, settings, localization, and accessibility work with bundled data. |
| Canadian and offline coverage | 7–8, 10 | Preview, fitted, bounded, provisional, derived, and online states are honest through interruption and offline use. |
| Glance and planning | 11–15 | Free widgets and purchased/free-day planning behavior pass device lifecycle tests. |
| Release | 18 | Source-linked conformance evidence, Play testing, and unresolved-design dispositions are recorded. |

The critical path is Task 1 → database reader → Tasks 2–6. Canadian fitting has a separate upstream dependency; use recorded fixtures to develop its UI while #405 is open, but do not mark its production task complete. Map fields, Play setup, translations, and alert-rule logic can proceed in parallel once their interfaces are stable. Avoid estimates based on issue count; estimate each stage after its first working slice and dependency review.

## Implementation tasks

Each task should fit a focused PR, or a short sequence of PRs if the listed file ownership crosses a reviewable boundary. For each task: add the named failing tests first, run the stated check to prove the missing behavior, implement, rerun to pass, and commit only that task's deliverable. Checks listed here become available through Task 1; they are planned commands, not checks already run on this repository.

### Task 1: Launchable app and reproducible inputs

**Issue:** [#2](https://github.com/openwatersio/slackwater-android/issues/2). **Depends on:** no app work.

**Files:** Create the root Gradle files, `core/build.gradle.kts`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/io/openwaters/slackwater/android/MainActivity.kt`, `upstream.lock.json`, `scripts/sync-upstream.mjs`, `.github/workflows/android.yml`, and `app/src/androidTest/kotlin/io/openwaters/slackwater/android/LaunchTest.kt`.

**Interfaces:** Establish `:core`, `:app`, injected test clocks/seeds, and exact-SHA source dependencies. The lock records the iOS product-doc SHA separately from engine, Almanac, database reader, resource, and recipe-evidence revisions. Start engine/Almanac evaluation with the reviewed candidates above; never substitute a moving branch.

- [ ] Add `LaunchTest.launchesWithoutNetwork` and one engine/Almanac fixture smoke test that runs on API 26 and the current target API. A shell that launches while loading no station does not satisfy the fixture check.
- [ ] Add deterministic resource sync with explicit file allowlist, hashes, provenance, and `--check`; exclude CHS user predictions/models, test recordings, and development tools from the APK. Copy `stations.tcdb` as an uncompressed asset suitable for the upstream reader; keep the identity index separate from full records.
- [ ] Build the shell, source composite builds, Gradle dependency verification, and CI. Implement an offline test mode and seeded permission/location/model states before feature tests need them.
- [ ] Verify `rtk ./gradlew :core:test :app:assembleDebug :app:lintDebug` and `rtk node scripts/sync-upstream.mjs --check`; run `rtk ./gradlew :app:connectedDebugAndroidTest` with the emulator/device attached. Check the packaged asset manifest for prohibited CHS payloads.
- [ ] Commit the launchable target and pin manifest. Do not include feature scaffolding whose behavior belongs to a later task.

### Task 2: Catalog, identity migrations, and shared storage

**Issues:** [#4](https://github.com/openwatersio/slackwater-android/issues/4), [#18](https://github.com/openwatersio/slackwater-android/issues/18). **Depends on:** 1 and database reader #296.

**Files:** Create `core/.../station/StationCatalog.kt`, `StationIdentity.kt`, `StationResolution.kt`; `app/.../data/BundledStationCatalog.kt`, `UserStateStore.kt`, `ModelStore.kt`; `core/src/test/.../StationResolutionTest.kt` and `app/src/test/.../UserStateStoreTest.kt`. Here and below `...` expands to `io/openwaters/slackwater/android/core` for core files and `io/openwaters/slackwater/android` for app files, under the source/test directories in the file map.

**Interfaces:** Implement the catalog/resolution contracts; expose ordered favorites, recents, namesake choices, requested/cancelled downloads, and reading preferences as repository state. Store user settings in DataStore and models/windows as versioned atomic files accessible to the widget execution path. Exclude fetched CHS data and caches from cloud backup; prevent device-local alerts/free-day records from accidental cross-device restore.

- [ ] Add tests for full IDs containing `:` and `/`, successive retired-ID remaps, tombstoned favorites, restricted identities, corrupt storage, interrupted atomic replacement, and preservation of unknown record fields.
- [ ] Implement lazy identity loading and one-record database lookup without decoding every constituent record at startup. Keep ownership of datum conversion in the prediction adapter.
- [ ] Define migration/version handling before storing the first model; on unsupported data preserve user intent and report an unavailable state. Never lose a favorite because its model is absent.
- [ ] Verify `rtk ./gradlew :core:test :app:testDebugUnitTest`; inspect process restart and widget reads during a model replacement on a device.
- [ ] Commit storage and catalog readers with migration fixtures.

### Task 3: Prediction adapters and current semantics

**Issues:** shared foundation for #4–#10, #15, #18. **Depends on:** 2.

**Files:** Create `core/.../prediction/PredictionRepository.kt`, `StationCapabilities.kt`, `EngineAdapter.kt`, `CurrentWindows.kt`, `CurrentPresentation.kt`; `core/.../settings/ReadingSettings.kt`; `core/src/test/.../PredictionParityTest.kt`, `CurrentWindowsTest.kt`.

**Interfaces:** Produce covered tide/current series, engine events, full-vector readings, derived timing capabilities, and the effective-threshold lookup. Use upstream `Station`, `CurrentStation`, subordinate types, readings, and `ChsCurrentFit` contracts through adapters; do not duplicate the harmonic sum or fitter in the app.

- [ ] Run the upstream golden fixture corpus through the Android adapter: timeline differences at most `1e-6` m/kn, exact event count/kind, and the upstream event timing/value tolerances (currently below 60 s and 0.02 m/kn). Preserve tighter fixture-specific tolerances. Test datum offsets and subordinate minute-to-second conversion separately.
- [ ] Add `CurrentWindowsTest.weakWaterIsNotSlack`, `reversalOnEntryStepCounts`, `aboveThresholdExcursionSplitsRun`, `clippedRunHasNoDuration`, and `offLeavesOnlySlackInstant`. Test published nonreciprocal bearings and a major-axis slack with nonzero vector speed.
- [ ] Implement interpolated crossings, touching-run merge, exact-slack semantics, and one shared threshold policy. Curve geometry uses signed major-axis speed; lead/card readings use the vector; map-pin semantics remain major-axis. Derived gates expose no magnitude, measured window, or bearing.
- [ ] Verify `rtk ./gradlew :core:test`; add an instrumented engine smoke case for each station capability before claiming Android integration.
- [ ] Commit the adapters and fixtures; record any unavailable upstream API as a specific dependency issue rather than an app-side numerical approximation.

### Task 4: Time windows and interruptible scrubber state

**Issue:** [#5](https://github.com/openwatersio/slackwater-android/issues/5). **Depends on:** 3.

**Files:** Create `core/.../timeline/TimelineWindow.kt`, `TimelineChunks.kt`, `TimelineController.kt`, `MagneticTargets.kt`; `core/src/test/.../TimelineWindowTest.kt`, `TimelineChunksTest.kt`, `TimelineControllerTest.kt`.

**Interfaces:** Implement the controller contract, fixed online span, continuous chunk publication, and exact target requests. Keep semantic state independent of Compose scrolling primitives.

- [ ] Assert online `start = anchor - 48 h`, `end = anchor + 180 h`, duration 228 elapsed hours; continuous chunks are seven station-local calendar days. Test spring/fall DST, adjacent samples/events/day chrome, inverse x/time mapping at 18 dp/hour, and stationary selection after an origin shift.
- [ ] Port the documented rest, magnetic, and interruption rules. Test 200-second home detection, 450 ms pill/rest timing, 600 ms schedule re-anchor, 650 ms opening from two hours before now, latest request wins, pending request during drag, and cancellation by new user input.
- [ ] Implement contiguous bounded chunk caching, off-main sampling, and quiet-only prepend/left eviction. Explicit jumps publish target coverage first. Refresh session `now` on Now/foreground, relabel days across local midnight, and preserve a scrubbed-away selection.
- [ ] Verify `rtk ./gradlew :core:test --tests '*Timeline*' --tests '*Magnetic*'`; use the exact snap priorities, distances, and animation rules from scrubber §§7–8 as fixture data rather than deriving new thresholds.
- [ ] Commit the state machine with traces that show selected instant, anchor, motion, and pending generation through interruptions.

### Task 5: Station list, search, location, and settings

**Issues:** [#4](https://github.com/openwatersio/slackwater-android/issues/4), [#15](https://github.com/openwatersio/slackwater-android/issues/15). **Depends on:** 2–3.

**Files:** Create `core/.../station/StationGroups.kt`, `StationSearch.kt`; `app/.../stations/StationListScreen.kt`, `StationCard.kt`, `LocationRepository.kt`; `app/.../settings/SettingsScreen.kt`; `core/src/test/.../StationGroupsTest.kt`, `StationSearchTest.kt`; `app/src/androidTest/.../StationListTest.kt`.

**Interfaces:** Consume catalog identities, user state, capabilities, and readings. Produce concrete station navigation and preference changes; no screen resolves a station by display name alone.

- [ ] Turn every list conformance scenario into fixtures, including unknown/denied/waiting/located permission states, no-fix ranking without fake distance labels, hero selection, group dedupe without mutating saved lists, namesake preference, every-name search results, recent cap, and deterministic ranking ties.
- [ ] Implement the documented section order, filters, favorites, recents, download/footer rows, first-run handoff, and map handoff. Match list §§4–7 exactly; near/favorite/recent dedupe and namesake collapse are separate operations. Keep search usable when location is denied.
- [ ] Render cards from shared chart/readout primitives. Refresh visible cards on activation and every 60 seconds while active, preserving scroll and filter. Add units, formats, comfort current, About, licenses, and source links; keep download management and Premium controls in their owning tasks.
- [ ] Verify `rtk ./gradlew :core:test :app:testDebugUnitTest :app:connectedDebugAndroidTest`; test location denial and approximate location on a device, text scaling, and persistent favorites after restart.
- [ ] Commit browsing and settings with screenshots for the source's reference states.

### Task 6: Detail page, chart rendering, and sky

**Issue:** [#6](https://github.com/openwatersio/slackwater-android/issues/6). **Depends on:** 3–5.

**Files:** Create `app/.../detail/StationDetailScreen.kt`, `TimelineCanvas.kt`, `ChartGeometry.kt`, `SkyScene.kt`, `Schedule.kt`, `SummarySheets.kt`; `core/.../timeline/ChartScene.kt`; `app/src/androidTest/.../DetailConformanceTest.kt`, `ScrubberConformanceTest.kt`.

**Interfaces:** Render one `TimelineState` and one selected instant through every visual/accessibility layer. Reuse a chart scene for detail/cards/widgets at their prescribed scales; distinguish measured data from schematic derived curves.

- [ ] Implement the complete scenario sets in detail §18 and scrubber §17, identifying cases by title as well as number. Cover horizontal versus vertical gestures, edge-back, hard data edges, flings, interrupted opening, tap/snap, long press handoff, Reduce Motion, text scaling, and TalkBack adjustment/event navigation.
- [ ] Draw the fixed centerline/riding dot, moving curve and marks, selected-time lead, commentary/Now controls, sky, sun/moon/eclipse chrome, and event/day lanes to the source tokens. Use real text measurement and collision exclusion; preserve 18 dp/hour on wider screens. Keep unsupported current window/peak claims out of derived and preview surfaces.
- [ ] Build the header/favorite/share actions, related station chooser, range/next-max/Moon/Sun cards and sheets, seven-local-day schedule, week picker, provenance, and unavailable pages. Summary cards follow the scrubber's selected instant, as do the schedule highlight, share link, and alert offer. Picker selects station-local noon only when the current selection is outside its target window; row/link jumps use exact instants.
- [ ] Verify `rtk ./gradlew :core:test :app:connectedDebugAndroidTest`; capture tide, measured current, vector slack, derived, and long-label scenes at phone and expanded widths. Validate screenshots and TalkBack manually in addition to semantic assertions.
- [ ] Commit the complete bundled-data detail slice. Add the current-chart target dispositions from the ledger; do not claim resolved upstream gaps are implemented because the basic curve renders.

### Task 7: Canadian downloads, previews, and fit compatibility

**Issues:** [#9](https://github.com/openwatersio/slackwater-android/issues/9), [#18](https://github.com/openwatersio/slackwater-android/issues/18). **Depends on:** 2–3; full fit completion also requires fitter #405.

**Files:** Create `core/.../downloads/DownloadPolicy.kt`, `ModelCompatibility.kt`; `app/.../downloads/ChsDownloadCoordinator.kt`, `IwlsCacheStore.kt`, `ChsFitRunner.kt`, `DownloadWorker.kt`, `DownloadsScreen.kt`; `core/src/test/.../ModelCompatibilityTest.kt`; `app/src/test/.../ChsDownloadTest.kt`.

**Interfaces:** Consume the existing Kotlin IWLS module and shared fit recipes; publish persisted preview/model revisions and queue status. Keep one coordinated request budget across foreground and workers. Online fetches remain a separate serial queue owned in Task 8.

- [ ] Add fixture tests for nearest required-series resolution within 3 km, epoch-week/sample-grid cache identity, exact endpoints, retained duplicate sample weights, 24-hour empty cache, positive-over-empty precedence, truncated/corrupt responses, and cancellation before model replacement.
- [ ] Implement the phone download policy and tiers from the pinned `ChsFitService`, `ChsQueue`, and `DownloadTier` references: automatic nearby/favorite selection, user requests/cancellations, promotion on open, pause/resume/retry, bulk gate selection, progress and management. Port the policy tests; do not apply watch-specific limits to the phone by inference. The 150 km choice includes 25 km; deselecting it returns to 25 km, and deselecting 25 clears both. All-current selection is independent. Automatic Nearby alone does not request background continuation; explicit optional selections can.
- [ ] Fetch the seven-elapsed-day tide preview starting 48 hours before local midnight. Reject any nonfinite sample or gap over 30 minutes; use it only while it covers now. Persist it across interruption, clamp the strip/selections to coverage, disable date picking and schedule re-anchoring, and hand off to the full model only when motion is quiet.
- [ ] Fit with upstream `ChsTideFit` and `ChsCurrentFit`: 60-day tide recipe, evidence-driven 60/210-day current windows, authorized provisional models only. One capability-specific compatibility entry point serves every consumer. Currents require station identity and `catalog recipe = running engine stamp = stored model recipe`, plus an authorized recorded fit window; tide models legitimately omit the current recipe/window fields. A bound-only update keeps the model. Atomic writes preserve the previous usable model on failure.
- [ ] Bound each background chunk attempt with cancellation and persisted resume state; a whole IWLS retry sequence can exceed an ordinary worker's lifetime. Foreground user downloads expose progress/cancel. Verify current Android transfer/foreground-service restrictions and job quotas before selecting any long-running fallback; never promise continuous background completion. See [long-running worker guidance](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running).
- [ ] Verify `rtk ./gradlew :core:test :app:testDebugUnitTest :app:connectedDebugAndroidTest` using recorded/synthetic IWLS fixtures, followed by a limited real-device download smoke test. Exercise process death, offline resume, Data Saver, and provisional-to-full replacement; commit only with the upstream fitter parity evidence attached.

### Task 8: Derived and online gates

**Issue:** [#10](https://github.com/openwatersio/slackwater-android/issues/10). **Depends on:** 4, 6, and the storage/fetch portion of 7.

**Files:** Create `core/.../prediction/DerivedGateAdapter.kt`, `OnlineSampleEvents.kt`; `app/.../downloads/OnlineGateStore.kt`, `OnlineGateFetcher.kt`; `app/.../detail/GateDetailState.kt`; `core/src/test/.../GatePredictionTest.kt`; `app/src/test/.../OnlineGateStoreTest.kt`.

**Interfaces:** Derived gates consume a fitted reference tide, fixed high/low lags, and timing-only capabilities. Online gates consume official covered speed/direction samples and never enter the fit queue.

- [ ] Test derived missing-reference state, no numeric speed/bearing/window, online interpolated zero crossings and one maximum per sign run, separate flood/ebb bearings, coverage gaps, retention, and tier transitions in both directions.
- [ ] Store disjoint online blocks honestly; require coverage of the whole displayed 228-hour window. Refresh covered-today data quietly at most once per local day while running, skip that refresh in Data Saver, fetch uncovered today, and prune blocks older than 60 days on save. Show validity through the final covered local date.
- [ ] Implement request holds, serial prefetch/backoff, and the online edge re-anchor: after 600 ms near an edge, fetch the needed block under the existing strip; publish only if that request is still current. Explicit date requests may show the documented loading/honesty page. Never replace the current week with an error for a week merely approached by scrolling.
- [ ] Verify `rtk ./gradlew :core:test :app:testDebugUnitTest :app:connectedDebugAndroidTest`, including an old fetch completing after Now, offline holes, a bounded completed model without endless progress, and the tide-preview handoff.
- [ ] Commit gate readers, stores, and detail-state coverage.

### Task 9: Map and station pins

**Issue:** [#7](https://github.com/openwatersio/slackwater-android/issues/7). **Depends on:** 3, 5; CHS states integrate after 7–8.

**Files:** Create `core/.../map/MapPinState.kt`, `VisibleStationGrid.kt`; `app/.../map/MapScreen.kt`, `StationLayers.kt`; `core/src/test/.../MapPinStateTest.kt`; `app/src/androidTest/.../MapConformanceTest.kt`.

**Interfaces:** Consume catalog positions and shared prediction capability/revision state; emit a concrete station selection. Use the pinned `ChartPacks.swift` style URL and add app layers at runtime without editing the basemap style.

- [ ] Port the pinned `MapPinState` tests and measured thresholds, including cheap two-sample tide direction with exact-search fallback, visible-grid decimation, subordinate reference-event reuse, and neutral missing models.
- [ ] Add MapLibre Native, station/legend layers, attribution, preview card, filter and location handoff. Restricted identities remain explanatory rings. Keep Dodd's vector arrow separate from major-axis pin coloring.
- [ ] Test style reload, camera changes, rapid filter updates, offline style failure, no-axis CHS identity, and tap-through to the same concrete detail as search.
- [ ] Verify `rtk ./gradlew :core:test :app:connectedDebugAndroidTest`; profile visible-set calculations on an optimized device build.
- [ ] Commit the map without uncertified field interpolation.

### Task 10: Certified current fields and offline chart packs

**Issues:** [#8](https://github.com/openwatersio/slackwater-android/issues/8), [#14](https://github.com/openwatersio/slackwater-android/issues/14). **Depends on:** 9 and input manifest from 1.

**Files:** Create `core/.../map/FillField.kt`, `PatchField.kt`, `ChartGrid.kt`; `app/.../map/CurrentFieldLayer.kt`, `ChartPackStore.kt`; `app/.../downloads/ChartDownloadsScreen.kt`; `core/src/test/.../FieldFormatTest.kt`, `ChartGridTest.kt`.

**Interfaces:** Binary readers use the pinned `pack.py` specifications and JSON sidecar hashes. Chart packs use the same style as the rendered map. Fill evaluates its own bundled u/v harmonics and mean offsets without waiting for CHS downloads. Patch availability depends on its station anchor's model or online coverage.

- [ ] Add binary golden tests for header/version/bounds/checksums, mean-flow offsets, one anchor evaluation per patch per frame, missing anchors, and retained/rejected geometry. Use committed certification, never a hardcoded expectation that every named pass has patch cells.
- [ ] Render speed context with the source ramp, no green field color, no claim of slack timing, no flow beyond online samples, and no interpolation across certification boundaries. Keep unsupported water absent.
- [ ] Port `ChartGrid` and tier ownership: world z0–4, surrounding grid z5–8, station discs z9–12, with no overlapping tier downloads. Validate MapLibre's actual offline style/resource behavior, including glyphs/sprites; implement progress, cancellation, quota/storage failure, and removal in Downloads.
- [ ] Verify `rtk ./gradlew :core:test :app:connectedDebugAndroidTest`; after download, relaunch in airplane mode and pan/zoom across tier boundaries. Delete packs and prove app/model data remains intact.
- [ ] Commit field rendering and offline packs as separate PRs if their review boundaries remain independent.

### Task 11: Free home-screen widgets

**Issue:** [#11](https://github.com/openwatersio/slackwater-android/issues/11). **Depends on:** 2–3, 6, and internal links from 16; CHS integration follows 7–8.

**Files:** Create `app/.../widgets/StationWidget.kt`, `WidgetConfigurationActivity.kt`, `WidgetRepository.kt`, `WidgetRefreshWorker.kt`; `app/src/test/.../WidgetSnapshotTest.kt`; `app/src/androidTest/.../WidgetConfigurationTest.kt`.

**Interfaces:** Widgets load the same catalog, settings, model compatibility, and shared low-resolution chart scene locally. Configuration stores a concrete station or the source-supported location choice without bypassing location authorization.

- [ ] Test free access, missing configuration, incompatible/provisional models, expired online coverage, units/threshold changes, and tap links containing the whole station ID.
- [ ] Implement small and wide card surfaces from current-charts §15, including their different spans, reading/set, event times, derived fallback, and window-close countdown only where supported honestly.
- [ ] Refresh after relevant data/favorite/settings changes and through WorkManager. Android periodic refresh is not an exact iOS widget timeline: record snapshot time and use absolute event times whenever a live timer cannot remain truthful. Validate widget resize and refresh behavior on real launchers. See [Glance lifecycle](https://developer.android.com/develop/ui/compose/glance/glance-app-widget).
- [ ] Verify `rtk ./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest`; manually verify widgets with the app process absent and network off. A stale countdown must not imply the window remains open.
- [ ] Commit widgets with no entitlement gate on the home-screen surface.

### Task 12: Alert rules and occurrences

**Issue:** create a focused alert-engine issue. **Depends on:** 3, 7–8 capability guards; can develop against fixtures in parallel.

**Files:** Create `core/.../planning/AlertRule.kt`, `OccurrenceResolver.kt`, `SunWindows.kt`, `DeliveryPlan.kt`; `core/src/test/.../AlertOccurrencesTest.kt`, `DeliveryPlanTest.kt`.

**Interfaces:** Implement the shared occurrence contract. Separate rules from station-calendar subscriptions and pure calculation from OS scheduling. Rules preserve unknown encodings byte for byte.

- [ ] Test every trigger: slack-window opening, derived slack, current peak by phase, high/low and daily higher-high/lower-low, directional height crossing in metres, and eclipse first contact. Include whole-local-day comparison, midnight/DST, observed versus clipped boundaries, and sampled-over reversals.
- [ ] Assert minute-floor occurrence identity, nearest single occurrence within six hours for `once`, 30-minute new-editor lead versus zero internal default, daylight/golden/blue filters, and zero threshold's slack-instant fallback.
- [ ] Build a 14-day notification plan with deduplication, preserved different leads, immediate fire only when a newly saved upcoming event's lead has passed, and actual scheduled-through coverage. Non-Premium notification plans are empty; both scheduled and overflow entries exclude `fire >= freeDayEnd`. Remove expired once-rules. Use an initial app budget of 64 pending reminders for parity/reliability testing, explicitly an app policy rather than an Android OS limit. Persist the plan's horizon and refill its overflow after delivery without extending that horizon or firing overdue entries; foreground reconciliation builds a fresh horizon. Test the 64th/65th boundary, exact expiry, and purchase conversion during trial.
- [ ] Reconcile serially with one queued follow-up on rule/settings/model/entitlement changes and foreground. On successors, adjust height thresholds only with a known datum shift; otherwise disable the rule and require the height to be set again.
- [ ] Verify `rtk ./gradlew :core:test --tests '*Alert*' --tests '*DeliveryPlan*'`; commit fixture-complete rule calculation before platform notification UI.

### Task 13: Play Billing and monthly free day

**Issue:** [#12](https://github.com/openwatersio/slackwater-android/issues/12). **Depends on:** 1, 5, and #3 for live products.

**Files:** Create `core/.../premium/Entitlement.kt`, `FreeDayPolicy.kt`; `app/.../premium/PremiumRepository.kt`, `PremiumScreen.kt`; `core/src/test/.../FreeDayPolicyTest.kt`; `app/src/test/.../PremiumRepositoryTest.kt`.

**Interfaces:** All gates call `isPremium(at)`; preserve purchased status separately from a 24-hour free-day end. Android free-day state is device-local under the no-account scope; do not advertise iCloud-like sharing or Play/Apple purchase interchange.

- [ ] Test monthly eligibility in the device calendar/time zone, 24 elapsed hours across DST/month rollover, purchase during free day, pending/cancelled transactions, expiry without foreground UI, and configuration retained after lapse.
- [ ] Implement yearly subscription and lifetime non-consumable products, localized Play prices, purchase/restore state, pending-state handling, acknowledgement within three days of purchase, and foreground purchase reconciliation using current [Billing guidance](https://developer.android.com/google/play/billing/integrate). Confirm price and product configuration with the account owner before release.
- [ ] Build the Premium sheet, supporter status, free-day labels, and a `PREMIUM_ENABLED` build option. A disabled build removes sales entry points and Premium routes. Free users may restore the default sky setting; a gated change leaves the previous value intact.
- [ ] Verify `rtk ./gradlew :core:test :app:testDebugUnitTest` and Play license-tester purchase/renewal/restore/lapse flows on a Play-distributed build. Document offline purchased-entitlement reconciliation separately from the deterministic free-day clock.
- [ ] Commit entitlement support; advertised paid planning features must remain disabled for release until the products are actually purchasable.

### Task 14: Alert editor and Android delivery

**Issue:** create focused alert-UI/delivery issue. **Depends on:** 6, 12, 13, 16.

**Files:** Create `app/.../alerts/AlertLine.kt`, `AlertEditor.kt`, `AlertsScreen.kt`, `AlarmScheduler.kt`, `AlertReceiver.kt`, `RescheduleReceiver.kt`; `app/src/test/.../AlarmSchedulerTest.kt`; `app/src/androidTest/.../AlertDeliveryTest.kt`.

**Interfaces:** Consume a typed rule draft from chart selection and pure delivery plan; persist intent independently of notification and exact-alarm permission. Every delivery opens the station at its event instant.

- [ ] Implement Rest/Pressed/Set chart-line states, the half-second press behavior, once/every choices, leads 0/15/30/60/180/1440 minutes, sun filter, enable/delete, past-time states, and free preview with Premium required at Save. Unsupported previews/online gates expose no alert promise. Add scrubber §12.5's alert hairline/bell to Task 6's chart scene at the event instant, not the reminder fire time; resolve markers off-main on rule/window/threshold changes, deduplicate the same minute, clip across tiles, and omit them on cards and online gates. Test a 30-minute lead and multiple rules for one event.
- [ ] Schedule one-shot AlarmManager intents and notification channels. Request notification permission at first save; check exact-alarm authorization where required and explain blocked precision without silently promising exact delivery. Retain the rule when access is denied. Use `SCHEDULE_EXACT_ALARM` only after the release policy check; do not assume eligibility for `USE_EXACT_ALARM`. See [alarms](https://developer.android.com/develop/background-work/services/alarms) and [notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission).
- [ ] Recheck current rule/model/entitlement at delivery, reject delayed alarms whose event is already past, prohibit free-day notifications at or after expiry, cancel obsolete intents, refill the persisted plan's unexpired overflow, and restore that plan after reboot/package replacement/time changes as supported. Ordinary process death, Doze, and user force-stop are different test cases; document the force-stop limitation.
- [ ] Verify `rtk ./gradlew :core:test :app:testDebugUnitTest :app:connectedDebugAndroidTest`; run device tests with app closed, permissions revoked, duplicate reschedules, changed model/threshold, and late/expired fires. No blanket ActivityKit/Live Update equivalence is promised.
- [ ] Commit editor and delivery with device evidence. Add optional on-device sentence parsing only after the manual editor works: capability gate it, show a typed preview, keep sentences off storage/network, clamp relative dates to 0–14, reject unsupported requests, and preserve the previous draft on failure. Model output selects a rule; the engine calculates the answer. See the ledger's parser decision gate.

### Task 15: Station calendars and printable tables

**Issue:** create separate calendar and printable-table issues. **Depends on:** 6, 12–14, 16.

**Files:** Create `core/.../planning/StationSubscription.kt`, `CalendarPlan.kt`; `app/.../calendars/CalendarRepository.kt`, `CalendarSettingsScreen.kt`; `app/.../sharing/PrintableTableDocument.kt`; `core/src/test/.../CalendarPlanTest.kt`; `app/src/androidTest/.../CalendarReconciliationTest.kt`, `PrintTableTest.kt`.

**Interfaces:** Calendar subscriptions retain station ID, owned calendar identity, and purchased/free-day origin. Print consumes the detail schedule and source/units/time-zone metadata, never a screenshot of a chart.

- [ ] Generate 90 days of station events from the source's per-kind trigger list, no reminders, station-and-series calendar names, station-local notes, and event links. Online gates are excluded. Start with an app-owned local Calendar Provider calendar; disclose local-only behavior because synced Google/Exchange calendar creation needs provider-specific integration. Use [Calendar Provider](https://developer.android.com/identity/providers/calendar-provider) with read/write permissions and [local-calendar semantics](https://developer.android.com/reference/android/provider/CalendarContract.Calendars).
- [ ] Test ownership before adoption/deletion, identical names across series/stations, idempotence, exact title/alarm-offset agreement with start/end each within 90 seconds, preservation of underway/personal events, revoked permissions, deletion confirmation with event count, and subscriptions remaining visible after unfavorite.
- [ ] Preserve the different lapse rules: purchased-origin calendars keep publishing after lapse; free-day-origin calendars stop and remove generated events on expiry reconciliation, retaining subscriptions; purchase during trial converts their origin. Persist cleanup work when permission or OS scheduling prevents immediate removal, and explain that already-written external calendar events can remain until cleanup runs.
- [ ] Consume durable `PredictionInvalidation` records from Tasks 7–8: remove only that station's app-owned future calendar events, preserve underway/personal events and subscriptions, and retry after revoked permission/provider failure. Test invalidation → permission denial → process restart → permission restoration. A missing online block alone must not clear unrelated calendar data.
- [ ] Implement Premium print/PDF sharing through Android's print/share APIs, copying detail §8.6's content, date-span, units, time-zone, attribution, and footnotes. Preserve the free on-screen schedule. Online gates print stored blocks and tide previews print downloaded coverage without initiating downloads; both preview and PDF identify gaps/empty days clipped to the requested Week/Month. Test a partial-month online gate, a tide preview, pagination, translated text, and DST dates.
- [ ] Verify `rtk ./gradlew :core:test :app:connectedDebugAndroidTest`, inspect an exported PDF, and repeat calendar reconciliation with a real provider. Commit calendar and print work independently.

### Task 16: Links, sharing, and prediction shortcuts

**Issue:** [#13](https://github.com/openwatersio/slackwater-android/issues/13), plus a focused query-shortcuts issue. **Depends on:** 2–3; full detail uses 6.

**Files:** Create `core/.../sharing/StationLinks.kt`, `core/.../shortcuts/PredictionQuery.kt`; `app/.../sharing/LinkRouter.kt`, `app/.../shortcuts/ShortcutPublisher.kt`, `QueryResultScreen.kt`; `core/src/test/.../StationLinksTest.kt`, `PredictionQueryTest.kt`.

**Interfaces:** Resolve `https://slackwater.xyz/<kind>/<slug>[/<instant>]` and `slackwater://station/<id>` to a concrete station/instant. Publish next-low, next-high, and next-slack-window query actions using the shared query core.

- [ ] Round-trip every bundled ID with RFC 3986 unreserved encoding of the whole ID, including slashes/colons; test malformed/untrusted routes, missing/retired stations, timestamp parsing, cold start, and exact selected-instant landing.
- [ ] Implement website shares from `slugs.json` and verified Android App Links once #3 and website #222 supply package/certificate data. Restrict app routes to the intended station paths; test actual signed-build verification, not just manifest matching.
- [ ] Implement launcher/pinned shortcuts and an in-app result with optional explicit place/date. Preserve Siri's query semantics: explicit place never falls back; placeless requires real authorized location, valid fix at most ten minutes old, refresh bounded at five seconds. An undated slack-window query with no window at the selected current station in the next 48 hours returns a named no-window answer, never a farther station. Dated queries cover their whole requested station-local day, including dates beyond 48 hours. Exclude unsupported/provisional predictions according to the query loader rules, independently of alert eligibility.
- [ ] Test named-place priority and ambiguity, complete station-local dates across DST, overlapping windows, unknown closes, comfort Off, and matching text/chart results. Query launcher shortcut capacity instead of copying Apple's phrase limits. Voice assistant integration is a separate capability gate, not a release promise.
- [ ] Verify `rtk ./gradlew :core:test :app:connectedDebugAndroidTest`; commit route and query work separately if needed.

### Task 17: Localization, accessibility, and performance evidence

**Issue:** [#16](https://github.com/openwatersio/slackwater-android/issues/16), cross-cutting acceptance. **Depends on:** 1; continue through each feature stage.

**Files:** Create `scripts/export-strings.mjs`, `app/src/main/res/values*/strings.xml`, `strings_android.xml`, `benchmark/build.gradle.kts`, `benchmark/src/main/.../UserJourneyBenchmark.kt`, `docs/validation/android-performance.md`, `docs/validation/accessibility.md`.

**Interfaces:** Export the pinned `Localizable.xcstrings`, relevant permission text, and reusable shortcut/result strings; keep Android-only platform copy separate. Treat pluralization, format arguments, escaping, and Apple-to-Android locale tags as typed conversion work.

- [ ] Add exporter fixtures for plurals, placeholders, apostrophes, Unicode, regional locales, missing translations, and freshness against the pinned SHA. Never overwrite handwritten Android strings.
- [ ] Check TalkBack reading order, chart adjustment/event navigation, named dated events, touch targets, color-independent state, font scaling, reduced motion, contrast, and landscape/tablet layout in each feature PR. A Canvas must expose semantic actions and the event table.
- [ ] Measure optimized builds on physical devices for cold first usable list, cold/warm complete detail by capability, interactive map with/without packs, focused search, first query and repeat queries. Record hardware/OS/build/boundary, frame behavior, memory during long scrubs, and offline state. Use [Macrobenchmark metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics); set regression budgets from Android evidence, not iOS simulator numbers.
- [ ] Verify `rtk node scripts/export-strings.mjs --check`, normal unit/device checks, and `rtk ./gradlew :benchmark:connectedCheck` after the benchmark module exists. Review screenshots in every shipped locale, especially long and RTL text where supported.
- [ ] Commit exporter and evidence; keep the ledger's acceptance rows linked to tests or device reports.

### Task 18: Play closed testing and release gate

**Issues:** [#3](https://github.com/openwatersio/slackwater-android/issues/3), [#17](https://github.com/openwatersio/slackwater-android/issues/17). **Depends on:** account work starts immediately; release depends on completed scope above.

**Files:** Create `.github/workflows/release.yml`, `docs/releasing.md`, `docs/validation/android-conformance.md`, and `store/` listing/screenshot sources; update `README.md` with real build/test instructions.

**Interfaces:** Produce a signed Android App Bundle, exact source/dependency manifest, release evidence, and Play testing opt-in route. Account-owner actions include business verification, signing enrollment, product creation, price approval, and tester access; they are external gates, not coding subtasks that an app test can prove.

- [ ] Complete the organization account and Play App Signing setup, register package/certificate, configure the closed track and tester group from #3, and keep signing/upload credentials in approved CI secrets.
- [ ] Reconcile all ledger rows: tests for implemented behavior, device evidence for platform services, explicit disposition for unresolved chart designs, and accurate platform-difference copy. Do not mark v1 parity complete with unexplained skipped source scenarios.
- [ ] Verify and inspect the release candidate with `rtk ./gradlew :core:test :app:testDebugUnitTest :app:lintRelease :app:bundleRelease`; run instrumented conformance on API 26 and the current target API plus physical-device notification/widget/billing/calendar checks. Check generated-input freshness and APK/AAB license/assets inventory.
- [ ] Derive Play Data safety answers from the actual Android build and SDK behavior; retain privacy/support/source links and attribution. Adapt iOS listing claims to shipped Android coverage, local calendars, widget timing, and free-day scope. Use Android screenshots and exported locale copy; verify current Play policy/target API and billing requirements at submission.
- [ ] Publish the approved release candidate to closed testing, record opt-in/install/upgrade results, and triage evidence before production. Billing, exact-alarm eligibility, and verified app links must be tested with the signed distribution build. Production rollout remains a separate release action.

## Definition of done

The plan is implemented when the checked-in source, automated tests, and device evidence support every applicable ledger row; every Android deviation has an explicit product explanation; generated inputs and recipe evidence are reproducible; and the closed-testing build supports the promised behavior offline and through ordinary lifecycle interruptions. An unavailable upstream dependency, experimental assistant API, or untested device service remains an open gate with an owner and evidence request.

During implementation, update the ledger with test/report links and keep the task checkboxes current. Refreshing the iOS baseline is a deliberate dependency change: review the contract diff, regenerate fixtures/resources, and rerun the affected conformance checks together.
