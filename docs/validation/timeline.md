# Timeline controller and prediction windows

The detail screen owns a timeline controller per station. Its schedule anchor is a civil date in the station’s zone. Its session now and selected time are instants. Send foreground and Now events with the refreshed clock. A display tick advances motion and rest timers using a monotonic clock.

For a constituent-backed or derived station, construct `TimelineChunks` with the initial schedule anchor and a loader calling `repository.timelineChunk(station, zone, window)`. Await `open(openingInstant)` before presenting the strip. Give its window to `TimelineController`, then pass that controller and the publication to `ContinuousTimeline`. Dispatch input on the same serial executor that receives completed loads. The bridge keeps the old content visible during a drag and exposes the new content when the controller accepts it. A failed load completes exceptionally and keeps the old window usable. Close the continuous timeline when leaving the station or replacing its model to cancel remaining worker jobs.

Call `refresh()` when the selected chunk changes and when the strip becomes quiet. At rest the store trims distant weeks on either side. It can append on the right during motion. Calculate the magnetic targets from the displayed series and settings when a window is built and install them with `TimelineAction.Targets`. During native momentum or a host scroll animation, tag frames with the generation that started that motion.

For an online current, `onlineTimelineBlock` selects a single downloaded block covering the 228-hour window. Supply that window when the requested week is available. For a tide preview, use `completeTimelinePreview` to expand a sliced repository result to the full saved series, then initialize the preview controller with `previewTimelineWindow`. The detail UI owns download notices and retry controls.

## Interruption trace

The executable trace in `TimelineInterruptionTraceTest` opens at March 1, 2026, 12:00 UTC with a March 1 anchor. The host requests December, then July, while the reader is dragging.

| Input | Selected (UTC) | Anchor | Motion | Pending request |
|---|---|---|---|---|
| Begin drag | March 1, 12:00 | March 1 | Drag | None |
| Request December 22 | March 1, 12:00 | March 1 | Drag | First |
| Request July 22 | March 1, 12:00 | March 1 | Drag | Second |
| July coverage arrives | March 1, 12:00 | March 1 | Drag | Second |
| Lift finger | July 22, 12:00 | March 1 | Resting | None |

The July jump lands directly because its replacement window excludes the starting offset. A late momentum frame from the earlier generation leaves the July instant in place. After 600 ms at rest the continuous schedule can re-anchor to July 22.

Run the core fixtures with `./gradlew :core:test --tests '*Timeline*' --tests '*Magnetic*'`. [Pinned scrubber specification](https://github.com/openwatersio/slackwater-ios/blob/5b26bcd5ce0220302de3ba32be5ebf67552fb06e/docs/scrubber.md)
