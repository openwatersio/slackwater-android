# Android accessibility and conformance checks

Use these checks with the [Android parity ledger](../ios-parity.md). The tables preserve the scenario titles and numbering from iOS revision `5b26bcd5ce0220302de3ba32be5ebf67552fb06e`, including both detail scenarios numbered 31. The middle column names related Android tests. A model or storage test covers only that layer. Each final column gives the outstanding end-to-end check for its feature. Source wording that names an iOS control maps to its Android equivalent, such as TalkBack heading navigation, system location permission, or the native print and document pickers.

## Verified on Android

On November 6, 2026, the API 37 arm64 Google Play emulator (build CE2A.260420.051.A2) ran the debug app based on `9601dd6` with the changes in this PR.

| Surface | Evidence |
|---|---|
| Friday Harbor alert chart, English phone | With TalkBack 17.0.0.889642762 active, successive Tab presses moved the visible accessibility focus through Data and licenses, the tide plot, Show chart events, Set an alert, My alerts and Station calendars. [Four consecutive focus rings](evidence/talkback-keyboard.png). Spoken gesture traversal still needs a listener check. |
| Alert chart actions | `AlertDetailPlotDeviceTest` selects a tide row, moves five minutes using an accessibility action and the right arrow in RTL, selects previous and next events, and returns to now. A component fixture selects an online-current slack with alerts unavailable. The tests exercise chart actions and collection rows independently of the speech engine. |
| Dated shortcut | `QueryActivityTest` verifies both high tides for July 16 at Friday Harbor. `QueryLayoutTest` scrolls the submit button and both dated event rows into view, and checks each line of text for truncation and clipping. |
| Light theme contrast | Screenshot samples on the phone gave 16.23:1 for heading text (#1D1B20) and 6.12:1 for the tide curve (#6750A4) against the surface (#FEF7FF). The event rows give text equivalents for the plotted tide times. Other backgrounds and current states still need separate checks. |

The layout test passed at the following activity sizes reported by Android. The expanded and landscape cases used display overrides on the same emulator.

| Activity (dp) | App locale | Font scale | Animator duration scale |
|---|---|---:|---:|
| 411 × 914 | en-US | 1.0 | 1 |
| 360 × 800 | fr-CA | 2.0 | 0 |
| 1200 × 540 | fr-CA | 2.0 | 0 |
| 1067 × 1707 | fr-CA | 2.0 | 0 |

The [station list (#4)](https://github.com/openwatersio/slackwater-android/issues/4), [timeline (#5)](https://github.com/openwatersio/slackwater-android/issues/5), [detail scrubber (#6)](https://github.com/openwatersio/slackwater-android/issues/6), [map (#7)](https://github.com/openwatersio/slackwater-android/issues/7) and [translations (#16)](https://github.com/openwatersio/slackwater-android/issues/16) supply the remaining screens and locales. Repeat the checks below on those surfaces, including a foldable that supports posture changes.

## Device checks

Record the commit, build variant, device or emulator, Android API, screen size/posture, locale, font scale, animation setting, TalkBack version and test date with each result. Attach a screenshot for layout, a traversal or action transcript for speech, and before/after selected instants for navigation. Record observed failures against their scenario titles. These manual checks await device evidence.

| Setup | Action and expected result |
|---|---|
| Phone, TalkBack | Navigate by headings through My Location, Favorites, Near Me and Recents. Traverse cards as single controls, then open detail and traverse the event table. Each event announces its type, station-local date, time, and known value. Activating it selects that instant. |
| TalkBack on tide, measured/vector current, derived and online detail | Adjust the timeline forward and back five minutes, then invoke next/previous event. Compare speech, lead, and table selection, including the last reachable event. Verify unknown speed and direction are spoken honestly. |
| Phone, keyboard and RTL | In English and an RTL locale, tab through search, chart controls and dated events. Operate controls from a hardware keyboard and confirm dates advance chronologically across midnight and DST. Confirm native Back returns focus to the originating control. |
| Phone at maximum font and display size, tablet at expanded width | Check translated long names, dates and values in portrait, landscape and split screen. Read each control and event without overlap or truncation of its meaning. Inspect effective touch bounds for at least 48 × 48 dp. |
| Foldable, folded and unfolded | Follow scrubber scenario 38 in both directions, including while the schedule loads. Record selected instant, week, and first visible content. |
| System animations disabled | Open detail, change day, select an event, return to Now and settle near a target. Confirm instant landing and stable focus. |
| Greyscale and normal colour | Read high/low, ebb/flood, slack, magnitude and unknown speed using labels or glyphs. Measure foreground/background contrast on each sky or status background, including provisional values, against 4.5:1 for normal text and 3:1 for large text and essential graphics. |

## Station list and search

[Reference scenarios, §14](https://github.com/openwatersio/slackwater-ios/blob/5b26bcd5ce0220302de3ba32be5ebf67552fb06e/docs/list.md#14-conformance-scenarios).

| Scenario | Related Android test | Pending device check |
|---|---|---|
| 1. Group order | `StationGroupsTest.groupOrderWithAFixAFavoriteAndARecent` | With a fix, a favorite, and a recent, the list shows My Location, Favorites, Near Me, Recents, then the footer. |
| 2. One home | `StationGroupsTest.eachStationHasOneHomeAndSavedListsStayIntact` | A station that is both a hero and a favorite appears only as a hero; a favorite that is also recent appears only in Favorites. Removing the hero station's favorite later restores nothing that was not stored. |
| 3. Namesakes | `StationGroupsTest.namesakesCollapseInNearMeAndTheChoiceWins` | Two nearby tide stations named the same appear once in Near Me; after choosing the farther one on its detail, Near Me shows that one. |
| 4. Filter | `StationGroupsTest.seriesFilterNarrowsNearMeOnly` · `StationSearchTest.theSeriesFilterFillsTheLimitWithThatSeries` | Choosing Currents narrows Near Me and search to currents and leaves heroes, favorites, and recents untouched. Tapping Currents again returns to All. |
| 5. Denied | `StationGroupsTest.withoutAFixTheLastOpenedPlaceAnchorsFiveCards` | My Location shows the amber card and names the last-opened place used for distances; "Go to Settings" opens the app's settings, and Near Me shows 5 cards ranked from that place. |
| 6. Not determined | `LocationSlotTest.notAllowedOffersThePromptUntilTheSystemRefusesIt` | With no ask in flight, the slot shows the green card; "Find tides near me" raises the system prompt. |
| 7. Authorized, no fix | `LocationSlotTest.allowedWithoutAFixIsLocatingUntilTheRequestFails` | The slot shows "Finding your location…" and Near Me shows 5 cards. |
| 8. First launch, no fix | `StationGroupsTest.firstLaunchRanksFromAnnapolis` | My Location says "Showing places near Chesapeake Bay"; Near Me keeps that proper-name heading in every language and ranks from Annapolis. |
| 9. Second hero | `StationGroupsTest.aSecondHeroOnlyWithinTheCrossSeriesRadius` | A fix within 20 km of both a tide and a current station shows both as heroes; tapping one opens only that one. |
| 10. Unfavorite | `UserStateStoreTest.favoritesAndRecentsSurviveReopenAndMigrateInOrder` | Swiping Unfavorite removes the station from Favorites and puts it at the front of Recents. |
| 11. Recents cap | `UserStateStoreTest.favoritesAndRecentsSurviveReopenAndMigrateInOrder` | Opening a seventh distinct station drops the oldest recent; re-opening a recent moves it to the front without duplicating it. |
| 12. Removed favorite | `StationGroupsTest.removedFavoritesKeepTheirSlot` | A favorite that leaves the catalog shows the removed card; "Choose another station" offers five nearby stations and puts the choice in the same slot; Remove deletes it without adding it to Recents. |
| 13. Empty query | `StationSearchTest.anEmptyQueryReturnsTheNearestSixtyAndSaysSo` | Opening search shows the 60 nearest stations immediately with the truncation notice. |
| 14. Name before region | `StationSearchTest.nameMatchesRankBeforeRegionThenAliasThenDistance` | A query matching one station's name and another's region ranks the name match first. |
| 15. Superseded query | Pending | Typing quickly shows only the results for the final query. |
| 16. Result tap | Pending | Tapping a result closes search and opens the station. |
| 17. Pending Canadian card | Pending | A queued Canadian station shows its place in line in place of the reading, a skeleton curve, and its full card height before data arrives. |
| 18. Current download | Pending | A Canadian gate with an early model keeps its reading and “~” before the speed alongside the ordinary download status and progress. |
| 19. Removed deep link | Pending | A link to a withdrawn station closes search and shows the Station unavailable section. |
| 20. Unknown deep link | Pending | A link to an id the app has never known leaves the list as it was. |
| 21. No search match | `StationSearchTest.noMatchesIsEmpty` | A completed search with no match shows "No matches" and keeps the series filter visible. |
| 22. Approximate fix | Pending | My Location says "Approximate location" instead of precise coordinates when iOS supplies reduced accuracy. |
| 23. Slack threshold | Pending | Changing comfort current changes the example window in Settings and refreshes measured-current cards already mounted in the list or map preview. |
| 24. Heading navigation | Pending | With a fix, a favorite, and a recent, the headings rotor steps through My Location, Favorites, Near Me, and Recents, and stops on nothing inside a card. |
| 25. Resume and live readings | Pending | Leave the app overnight, then reopen it with the list and a selected detail visible. Mounted cards refresh immediately, keep updating every 60 seconds while active, and preserve the selection and scroll position. A map preview keeps its eager first frame until the next tick. After midnight, online current cards select the current day’s cached window without a network request. |

## Station detail

[Reference scenarios, §18](https://github.com/openwatersio/slackwater-ios/blob/5b26bcd5ce0220302de3ba32be5ebf67552fb06e/docs/detail.md#18-conformance-scenarios).

| Scenario | Related Android test | Pending device check |
|---|---|---|
| 1. Tide page | Pending | shows header, scrubber, Range and Moon, the nearby-current link when a current lies within 20 km, the schedule, the footer, collapsed Station details, and Nearby, in that order. |
| 2. Derived gate | Pending | shows "Shape only / No speed", links its reference port, and lists only slacks with no values. |
| 3. Online gate, never downloaded | Pending | shows the honesty card, no scrubber, no week bar, and the "Try … instead" link; the star still works. |
| 4. Unavailable station | Pending | has no star, no schedule, no tiles, and gives no reason. |
| 5. Opening | Pending | the schedule starts at today, today's day is open, and the row nearest now is highlighted. |
| 6. One open day | Pending | opening Wednesday closes Today; tapping Wednesday again closes it. |
| 7. Row tap | Pending | tapping a row moves the scrubber to that event, and the highlight lands on that row. |
| 8. Re-anchor | Pending | scrubbing past the end of the week and resting 600 ms moves the schedule to the selected day without moving the graph. On the online gate, resting near either end of the downloaded window moves the schedule to that day once a block covers it, and shows the extending card (§12.3) while one downloads. |
| 9. DST week | Pending | the schedule still shows seven local days, each with its station-local date. |
| 10. Eclipses | Pending | lunar rows show 🌘 and "Eclipse"; solar rows show a sun and "Solar Eclipse". |
| 11. Pick a week | Pending | picking a date three weeks ahead moves the bar's range to start that day, centres local noon of that day, and shows the Now pill. |
| 12. Return | Pending | Now restores today's range and the current instant. |
| 13. Date in the strip | Pending | tapping a date in the scrubber's day row opens the picker; tapping a sunrise moves the scrubber. |
| 14. Provisional current | Pending | the Next max value has a "~" and attention amber ink while the model is provisional. A bounded final model retains the mark and attention ink. |
| 15. Range follows the scrub | Pending | moving across an extreme flips the caption between "low to high" and "high to low", at a station whose swing is not marked. |
| 16. Seasonal station | Pending | a seasonally dominated station's Range tile reads "mostly seasonal" or "partly seasonal" and carries a calendar glyph, whatever its swing ranks. |
| 17. A marked swing | Pending | a swing in the top tenth of its fortnight captions "beyond normal here", or "the fortnight's biggest" when nothing in the window goes further, and carries the span glyph. An ordinary swing carries neither. |
| 18. The sheet opens anywhere | Pending | an ordinary station's Range tile is a button and its sheet leads with the three sections. |
| 19. A station with no bounds | Pending | a CHS station's sheet keeps the fortnight and the year, and drops only the section measured against the station's own ends — absent rather than empty or zeroed. |
| 20. The next bigger swing | Pending | tapping the first section's caption moves the scrubber to that swing and closes the sheet. |
| 21. A marked maximum | Pending | a current maximum in the top tenth of its own direction's fortnight captions "Strong flood · &lt;time&gt;", or "Hardest flood · &lt;time&gt;" when nothing in the window runs harder, and keeps its time in both. |
| 22. The Next max sheet | Pending | a harmonic current station's marked tile opens a sheet with the fortnight and the year sections, and no section measured against the station's ends. |
| 23. An online gate stays inert | Pending | its Next max tile is not a button — nine and a half days is not a fortnight to rank against. |
| 24. Moon jump | Pending | choosing "Next full" in the Moon sheet closes it and moves the scrubber to that night. |
| 25. Pushed link | Pending | following "Tide at &lt;port&gt;" and pressing Back returns to the current page as it was. |
| 26. Waiting page fills in | Pending | a queued Canadian station shows its place in line and becomes the tide detail when its download finishes, without navigation. |
| 27. Failed download | Pending | shows "Retry"; retrying moves it to the front of the queue. |
| 28. Share at now | Pending | sharing an untouched page shares the bare station link. |
| 29. Share a moment | Pending | sharing after scrubbing a day ahead includes that instant in the station's offset, and opening the link lands on it. |
| 30. Reduce Motion | Pending | with Reduce Motion on, opening a day shows its rows at once, and the first-run tour lands on each mark directly. The tour's moves are ordinary scrubber requests, so with Reduce Motion off each one rides or lands directly by scrubber §8, like any other request. |
| 31. A Norwegian tide | Pending | Andenes's footer names Kartverket, not TICON-4. |
| 31. Free | `StationScheduleTest.freeScheduleShowsTheWeekAndChecksPremiumBeforeOpeningTheSheet` | tapping Printable tables without Premium opens Support Slackwater and no table. |
| 32. Week and month | `PrintableTableTest.weekStartsAtAnchorLocalMidnightAndMonthUsesCivilDays` · `PrintPreviewTest.nativeChooserOpensFromDisplayedMonth` · `PrintPreviewTest.nativeSpoolerOpensFromDisplayedMonth` | with Premium the sheet opens on the schedule's week; Month shows the anchor's whole month, and share offers Print and Save to Files. |
| 33. Stored only | `PrintableTableTest.partialMonthOnlineBlocksPreserveGapsLocalValuesSetsAndBareSlacks` | an online gate's month past its stored block says "Predictions available … only." with the download hint; a gate with nothing stored has no row. |

## Timeline scrubber

[Reference scenarios, §17](https://github.com/openwatersio/slackwater-ios/blob/5b26bcd5ce0220302de3ba32be5ebf67552fb06e/docs/scrubber.md#17-conformance-scenarios).

| Scenario | Related Android test | Pending device check |
|---|---|---|
| 1. Ordinary opening | Pending | Given a complete current window and Reduce Motion off, opening starts two hours behind now, reaches now in 650 ms, and ends with the lead time equal to the centre time. |
| 2. Interrupted opening | Pending | A drag, tap, plot press, or host request during the opening cancels it; the drag continues from the strip's position when it began, without jumping to now. |
| 3. First drag | Pending | The first drag after opening, including one that starts on the lead's text, changes the lead and riding dot during the gesture, before release or snap. |
| 4. Direction | Pending | Dragging content left advances selected time; dragging it right moves selected time backward. |
| 5. Momentum | Pending | A flick continues natively, updates the lead during motion, then evaluates the magnet once. |
| 6. Near target | Pending | Resting 3 units (10 minutes) from a tide extreme animates to its exact instant. |
| 7. Outside capture | Pending | Resting 4.5 or more units from every target remains where released, including 40 minutes before a low. |
| 8. Already parked | Pending | Resting within 0.5 unit of a target starts no redundant animation, and the lead shows the target's exact instant. |
| 9. Request during motion | Pending | A commentary request issued during a fling (the visible pill is inert while the strip moves, §6.4), or a schedule-row tap during a ride or the opening, stops that motion and goes to its own target; no later momentum or ride frame overwrites the request. A request during a drag waits for the finger to lift. |
| 10. Now during settle | Pending | A return to now that arrives during a magnetic animation or a ride, such as a scene reactivation (the visible pill is inert while the strip moves, §6.4), cancels the animation, refreshes now, lands home, and removes the Now pill. |
| 11. Reduce Motion | Pending | Opening, commentary, Now, taps, picker, shared-link, schedule-row, and sheet selections, and a drag-end magnetic settle land directly with no travel, and the pill row appears without an opacity animation. |
| 12. Resize | Pending | Rotate or resize after scrubbing. The lead time and centre time remain equal and unchanged. |
| 13. Cross midnight | Pending | Scrubbing continuously across midnight changes the day chrome without discontinuity or a page transition. Where sunset falls after local midnight (western Alaska in summer), the night band runs from that sunset to the sunrise that follows it, the afternoon carries the daylight tint, and each day name sits in daylight. |
| 14. DST | Pending | On a transition week, the civil day occupies 23 or 25 hours at the same 18-unit/hour scale; sunrise, sunset, and noon remain correct locally. |
| 15. Window edge | Pending | No event label is clipped at an edge: an event within 1.5 hours of a loaded edge draws no mark, reading, or time. No direct control or commentary advertises a target that cannot be centred over valid data, and every programmatic move lands on a reachable centre. A drag stops at the loaded edge without overscroll. |
| 16. Tide turn | Pending | At an exact high, the lead says High, the high's reading hangs below its dot, and the commentary advances to the following event. |
| 17. Fast tide | Pending | At 0.6 m/hr or faster, the lead's state carries the rate in the ramp's lifted colour, with the glyph. A run that reaches that rate has one commentary stop, `Fastest rise` or `Fastest fall` at its fastest point in the peak rate's lifted ramp colour, named until the selection reaches it; the run's high or low, or an earlier sunrise or sunset, is next after that. A passed peak is never named again. |
| 18. Measured slack window | `CurrentWindowsTest.weakWaterIsNotSlack` | The curve is yellow outside the slack windows, graph blue across interpolated window edges, and has a short green stroke at the zero instant. The lead says Slack only within a second of zero and Flooding or Ebbing elsewhere in the window. At a weak station whose window covers a maximum, the lead says Max flood or Max ebb on it. |
| 19. Short slack window | `CurrentWindowsTest.offLeavesOnlySlackInstant` | A fast reversal whose bracketing samples both exceed the comfort current still has a window, interpolated where the line crosses ±threshold. A window narrower than a turn dot is not drawn. The zero instant keeps its short green stroke, event time, magnetic target, and Slack commentary stop even with comfort current Off. A current that never reaches zero has no slack mark. |
| 20. Different current peaks | Pending | Equal speeds have equal thread colours across stations; a 6 kn and 12 kn peak do not both become local “maximum red.” |
| 21. Derived gate | Pending | The lead has no number or bearing, the shape has blue zero-fading fill beneath its positive half only and no speed thread, and only known slack instants get green dots, each with its time in the event-time row; there is no dashed zero line. |
| 22. Past/future | Pending | A graph with now in view has a 35%-opacity past stroke and full-strength future stroke split exactly at the absolute-now dot. |
| 23. Adjustable value | Pending | A non-touch increment changes selected time by five minutes and announces a dated, station-local lead value, with the set on a measured current wherever the lead shows one (§9.2) and no provisional `~`; the next- and previous-event actions park exactly on a magnetic target, or at the nearest reachable centre when the target lies within half a viewport of the content edge. |
| 24. Direct controls | Pending | Commentary and Now meet semantic target sizes and expose their visible meaning without requiring the canvas labels. |
| 25. No-colour reading | Pending | High/low, current direction, slack, magnitude, and schematic unknown remain distinguishable in greyscale. |
| 26. Larger text | Pending | At the largest accessibility size the lead value and its pad grow together, the plot and chrome rows move down without overprinting, and commentary and Now share one row. |
| 27. Chunk seam | Pending | Browse several weeks in both directions, including a DST transition. No sample gap, duplicated event, broken night band, or selected-time jump appears. Left-edge changes wait for motion to stop. |
| 28. Scale | Pending | A new visible peak can expand the plot with a 300 ms glide; ordinary chunk churn leaves it stable. Curve and riding dot share each frame's scale, and Reduce Motion adopts directly. |
| 29. Plot tap | Pending | A tap selects its moment or a magnetic target strictly within 4.5 units and rides to a reachable centre time, holding left-edge chunk publishes until it lands. |
| 30. Date or sun tap | Pending | A tap nearer the drawn date than any sun time stops motion and opens the picker where the host can change dates; a sun tap centres that event. With the sun, moon and stars switch off, every day-row tap is the date's. |
| 31. Sun commentary | Pending | Sunrise or sunset wins over a later water event, has no provisional tilde, and advances after landing. A fast run's peak is a water stop like any other. With the switch off, the commentary names only water stops. |
| 32. Dated lead | Pending | Crossing midnight changes the displayed local date. A one-digit hour occupies the same clock width as a two-digit hour. |
| 33. Foreground return | Pending | A home selection follows refreshed now; a selection away from the old now stays where the person left it. Either way, when the refreshed now falls on a new station-local day, the Today, Tomorrow, and Yesterday labels move with it. |
| 34. Far destination | Pending | A shared link opens at its requested instant, and a picked week at station-local noon of the picked day unless the selection already lies inside that week's window, with data on both sides. A picked day, link, or Now within seven days of travel rides there; over seven days it lands without travel. Now restores the current schedule. |
| 35. Model replacement | Pending | A refined CHS model or changed slack threshold refreshes the graph and readout while preserving the selected instant. |
| 36. Slack caps | Pending | Window and green runs replace the underlying stroke, including in the past, and only their ends against the yellow curve have separation gaps; a green run joins its window's blue with none. Fill and sky remain intact. |
| 37. Solar eclipse | Pending | At a locally visible partial eclipse, the sun has a bite and the moon remains visible, and greater obscuration darkens the backdrop. The strip marks greatest eclipse with the same small `🌘` a lunar eclipse gets, and greatest eclipse is the eclipse's only magnetic target; its other contacts are not. |
| 38. Fold | Pending | Unfolding after scrubbing lands on the folded instant, including one weeks from now; folding while home lands on refreshed now. Both directions keep the chosen schedule week. A schedule in the upper half of the viewport returns to view after its data loads; a glimpse at the bottom leaves the chart in view. Scrolling while data loads cancels the pending schedule restoration. |
| 39. Reach row | Pending | A harmonic or fitted current whose model peak for its year stays under the make-way anchor draws the maximum-time row; one whose peak reaches it, and every online or derived gate, omits the row. Picking another week or scrolling across springs changes neither. |
