plugins { alias(libs.plugins.kotlin.jvm); `java-test-fixtures`; `java-library` }
kotlin { jvmToolchain(17) }
dependencies {
    api("io.openwaters:slackwater-engine:0.1.0")
    implementation("io.openwaters:almanac:0.12.2")
    testFixturesImplementation("io.openwaters:slackwater-engine:0.1.0")
    testFixturesImplementation("io.openwaters:almanac:0.12.2")
    testImplementation(libs.junit)
}
