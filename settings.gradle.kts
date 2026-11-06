pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "Slackwater"
val nodeExecutable = providers.gradleProperty("nodeExecutable").orElse("node").get()
val setupHelp = "Install Node 24.2+, run node scripts/sync-upstream.mjs, then retry. " +
    "Set -PnodeExecutable=/absolute/path/to/node if Gradle cannot find Node."
try {
    val upstreamCheck = providers.exec {
        commandLine(nodeExecutable, file("scripts/sync-upstream.mjs").absolutePath, "--check")
        isIgnoreExitValue = true
    }
    if (upstreamCheck.result.get().exitValue != 0) {
        throw GradleException(upstreamCheck.standardError.asText.get().trim() + "\n" + setupHelp)
    }
} catch (error: Exception) {
    throw GradleException("Upstream input verification failed. $setupHelp\n${error.message}", error)
}
include(":core", ":app", ":benchmark")
includeBuild("vendor/slackwater/kotlin") { name = "slackwater-engine" }
includeBuild("vendor/almanac/kotlin") { name = "almanac" }
includeBuild("vendor/slackwater-database/packages/kotlin")
