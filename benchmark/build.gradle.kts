plugins { alias(libs.plugins.macrobenchmark.target) }

android {
    namespace = "io.openwaters.slackwater.benchmark"
    compileSdk = 37
    targetProjectPath = ":app"
    defaultConfig {
        minSdk = 29
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes.create("benchmark") {
        isDebuggable = true
        signingConfig = signingConfigs.getByName("debug")
        matchingFallbacks += "release"
    }
    experimentalProperties["android.experimental.self-instrumenting"] = true
}
androidComponents.beforeVariants { it.enable = it.buildType == "benchmark" }
dependencies {
    implementation(libs.macrobenchmark)
    implementation(libs.test.junit)
    implementation(libs.test.runner)
    implementation(libs.uiautomator)
}
