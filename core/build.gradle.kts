plugins { alias(libs.plugins.kotlin.jvm); `java-test-fixtures`; `java-library` }
kotlin { jvmToolchain(17) }
dependencies {
    api("io.openwaters:slackwater-engine:0.1.0")
    implementation("io.openwaters:almanac:0.12.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testFixturesImplementation("io.openwaters:slackwater-engine:0.1.0")
    testFixturesImplementation("io.openwaters:almanac:0.12.2")
    testImplementation(libs.junit)
    testImplementation("com.google.code.gson:gson:2.11.0")
}

tasks.test {
    val fixtures = rootProject.file("vendor/slackwater/fixtures")
    inputs.files(fileTree(fixtures) { include("*.json") })
    systemProperty("predictionFixtures", fixtures.absolutePath)
}
