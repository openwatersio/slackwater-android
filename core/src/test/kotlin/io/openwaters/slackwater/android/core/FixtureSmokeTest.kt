package io.openwaters.slackwater.android.core

import org.junit.Test

class FixtureSmokeTest {
    @Test fun matchesUpstreamFixtures() {
        FixtureSmoke.verify()
    }
}
