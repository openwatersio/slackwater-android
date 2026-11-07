# Android station detail

Station cards, reminder and calendar events, and public tide/current links open the same station page. A related-station link pushes a page and Back restores the previous station. The page clock is the instant under the timeline centreline. The chart, alert offer, week highlight, summary tiles and public link use that clock in the station’s zone.

## Detail conformance

These rows refer to the numbered titles in [detail §18](https://github.com/openwatersio/slackwater-ios/blob/02188f2a2f5ef4166cd74c373a5885736d7ef159/docs/detail.md#18-conformance-scenarios). The [summary and schedule evidence](detail-panels.md) covers the tile, sheet, week, print and row scenarios. The [chart evidence](detail-chart.md) and [input checks](detail-input.md) cover the 39 scrubber scenarios, gesture arbitration, interruption, focus, and posture restoration.

| Detail scenario | Android evidence and boundary |
|---|---|
| 1 Tide page, 2 Derived gate | `DetailConformanceTest.tidePage` opens Friday Harbor. `derivedGatePage` uses the bundled Malibu Rapids identity and lag rules with a test reference-port fit. Its lead gives phase without a speed. |
| 3 Online gate, never downloaded | `neverDownloadedOnlineGateHasAnAlternativeAndNoWeekBar` expands Station details and checks that an empty cache says “Not downloaded.” It opens the alternative and verifies continuous predictions from a compatible fitted Canadian gate. Without a fitted gate, the page can offer another current station with continuous predictions. |
| 4 Unavailable station | `unavailableStationOffersHelpAndNearbyWithoutAStarOrSchedule` opens Aigio, verifies the catalog-only explanation, and checks available actions. |
| 5–13 Opening through Date in the strip | See the schedule, timeline and input fixtures. Day and week boundaries use the station’s civil calendar. |
| 14–24 Provisional current through Moon jump | See the panel and chart fixtures. A dated summary row requests its event instant through the same controller as the chart. |
| 25 Pushed link | Related links share the station-page back stack. Namesake selection records the chosen ID in the list store. `StationListTest` exercises navigation and favorites from the list. |
| 26 Waiting page fills in, 27 Failed download | The page observes saved-model revisions and download intents. `waitingPageFillsInWithoutReopeningTheStation` installs a preview and then a fit while the page stays open. A usable preview can hand over to a fitted model after the chart settles. Fetch execution, network status, backoff and retry progress depend on [#9](https://github.com/openwatersio/slackwater-android/issues/9) and [#10](https://github.com/openwatersio/slackwater-android/issues/10). |
| 28 Share at now, 29 Share a moment | `DetailLinkTest` checks bare and timed URLs, current/tide namespaces and a dated link opening the correct station at local time. Android domain verification and broader link routing are tracked in [#13](https://github.com/openwatersio/slackwater-android/issues/13). |
| 30 Reduce Motion | The core controller uses direct landings with reduced motion. The detail renderer and input follow the device animation setting. Tour entry is tracked with the [station-list onboarding flow (#4)](https://github.com/openwatersio/slackwater-android/issues/4). |
| 31 A Norwegian tide | `norwegianFooterNamesItsHarmonicPublisher` checks that the Andenes page names Kartverket. Other source records retain their published data credit. |
| 31 Free, 32 Week and month, 33 Stored only | See the print tests and [panel evidence](detail-panels.md). |

## Captured scenes

The API 37 arm64 emulator rendered each production page in a 411 dp phone viewport and a 1280 dp expanded viewport on November 6, 2026. The vector scene opens at a predicted reversal, where cross-flow still determines the actual speed and set. The derived scene uses an explicitly seeded reference-port fit. Captures wait for the sky layer and resting commentary.

| Station and condition | Phone | Expanded |
|---|---|---|
| Friday Harbor tide | [Phone](evidence/detail/phone-tide.webp) | [Expanded](evidence/detail/expanded-tide.webp) |
| Flat Point measured current | [Phone](evidence/detail/phone-current.webp) | [Expanded](evidence/detail/expanded-current.webp) |
| Hampton Roads vector slack | [Phone](evidence/detail/phone-vector-slack.webp) | [Expanded](evidence/detail/expanded-vector-slack.webp) |
| Malibu Rapids derived gate | [Phone](evidence/detail/phone-derived.webp) | [Expanded](evidence/detail/expanded-derived.webp) |
| Sunnyvale East Channel Below Caribbean Drive | [Phone](evidence/detail/phone-long-label.webp) | [Expanded](evidence/detail/expanded-long-label.webp) |

## Nearby and current-chart decisions

The local station inset plots the nearby stations relative to the selected station using bundled coordinates. Pin taps open those stations. The title and inset open the station in Slackwater’s discovery map. Offline pack status is tracked in [#14](https://github.com/openwatersio/slackwater-android/issues/14).

The current plot uses scrubber §13.2 for measured speed and §13.3 for a derived phase. An observed reversal, a clipped current window and a timing-only gate have different meanings. Follow the [parity ledger](../ios-parity.md#specified-targets-and-open-designs) and [#28](https://github.com/openwatersio/slackwater-android/issues/28) for the open threshold-axis, narrative-summary, rotary, confidence and slack-core decisions.
