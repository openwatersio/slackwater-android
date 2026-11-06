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
    val index = rootProject.file("app/src/main/assets/slackwater/station-index.json")
    inputs.file(index)
    systemProperty("mapStationIndex", index.absolutePath)
    val fixtures = rootProject.file("vendor/slackwater/fixtures")
    inputs.files(fileTree(fixtures) { include("*.json") })
    systemProperty("predictionFixtures", fixtures.absolutePath)
}

tasks.register<Test>("nationalTideDirectionSweep") {
    description = "Compare every bundled tide station against the pinned iOS lunar-month direction probes."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("*NationalTideDirectionSweep") }
    val records = rootProject.file("vendor/slackwater-ios/Slackwater/Resources/stations.json")
    inputs.file(records)
    systemProperty("mapTideRecords", records.absolutePath)
}

tasks.test { exclude("**/NationalTideDirectionSweep.class") }
