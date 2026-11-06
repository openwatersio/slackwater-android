# Detail timeline input

On November 6, 2026, TalkBack’s Actions menu selected a high tide at Friday Harbor and opened an alert draft for the same event. The debug build used the #6 worktree based on `ae4892e`. The Google Play arm64 emulator ran Android API 37, build CE2A.260420.051.A2, with TalkBack 17.0.0.889642762, US English, a 1080 × 2400 display at 420 dpi, font scale 1.0 and default animation settings.

## TalkBack on the tide chart

The check used TalkBack’s **Display speech output** setting. Its captions show what the service sent to speech. Pronunciation and audio quality still need a listener check. The starting link selected November 6 at 12:10 PM in Friday Harbor.

| Action | Observed result |
|---|---|
| Touch-explore the plot | A green focus ring enclosed the scrubber. TalkBack displayed “Rising, 6.9, feet, Nov 6 · 12:10 PM, PST. Tide timeline.” The lead showed the same height and time. [Focus and speech](evidence/detail-input/focus.png) |
| Swipe down, then right, and open Actions | TalkBack offered Earlier by five minutes, Later by five minutes, Previous event, Next event and Set an alert. [TalkBack menu](evidence/detail-input/actions.png) |
| Select Later by five minutes | The lead, speech output and alert offer moved to 12:15 PM. The rounded reading remained 6.9 feet. [Five-minute step](evidence/detail-input/five-minutes.png) |
| Select Next event | The lead showed High, 7.7 ft, 2:10 PM. The dot rested on the high and the alert line named the same tide. Touch-exploring the parked plot produced “High, 7.7, feet, Nov 6 · 2:10 PM, PST. Tide timeline.” [High tide](evidence/detail-input/next-event.png) |
| Select Set an alert | The editor opened on Friday Harbor high tide at 14:10 PST. The check dismissed the draft. [Alert editor](evidence/detail-input/alert.png) |

## Gesture and resize tests

All seven tests in `ScrubberConformanceTest` and `DetailPostureTest` passed on the API 37 emulator. The scrubber fixtures exercise an interrupted opening, a press without a second tap, horizontal and vertical gestures, direct five-minute and event navigation, left-arrow input in RTL, preview edges, and transfer of a selected moment 40 days away to replacement data.

The posture fixtures widen and narrow the detail viewport while the schedule loads. A schedule in the upper half returns to the top after its rows appear. A glimpse near the bottom returns to the chart. Scrolling while rows load cancels the pending restoration. These tests use resized Compose layouts. On devices with a hinge angle sensor, widening the display during an opening movement can also drive the scrubber from up to six hours ahead back to the saved instant. Closing, ordinary resizes and Reduce Motion use direct placement. `DetailHingeTest` checks the one-second wait for initial movement, the 250 ms rest timeout and the easing. `TimelineControllerTest` verifies that a drag takes ownership of the selection before another hinge frame can move it. The test phone has no hinge sensor. Physical hinge motion, TalkBack traversal on a folding device and spoken traversal of the measured, vector, online and derived current charts still need device checks.

Both `DetailPreviewExpiryTest` cases opened a saved Canadian tide preview and refreshed its session clock past the last sample. Foreground return and Return to now each withdrew the chart and schedule and showed the download action without a model write.
