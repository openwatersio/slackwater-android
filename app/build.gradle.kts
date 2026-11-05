plugins { alias(libs.plugins.android); alias(libs.plugins.compose) }
android {
    namespace = "io.openwaters.slackwater.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.openwaters.slackwater.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    androidResources { noCompress += "tcdb" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.material3)
    androidTestImplementation(testFixtures(project(":core")))
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    // Compose still brings Espresso 3.5; API 37 needs the public InputManager lookup.
    androidTestImplementation(libs.espresso)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.junit)
    debugImplementation(libs.compose.test.manifest)
}
