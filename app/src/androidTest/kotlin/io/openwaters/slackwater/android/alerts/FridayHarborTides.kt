package io.openwaters.slackwater.android.alerts

import java.time.Duration

/**
 * How far ahead the alert tests look for Friday Harbor's next high tide. Its tides are mixed: on some days the lower
 * high flattens out, and consecutive highs fall about 24 h 45 min apart (17:22Z on November 13, 2026, then 18:07Z on
 * November 14), so a one-day search finds nothing for 45 minutes or more each such day, depending on where it starts.
 * A reminder built from the high this finds must take its lead from that high, since it can be more than a day off.
 * `FridayHarborTidesTest` checks that the widest gap in the year ahead still fits.
 */
internal val NEXT_HIGH_SEARCH: Duration = Duration.ofDays(3)
