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
        testInstrumentationRunner = "io.openwaters.slackwater.android.SlackwaterTestRunner"
    }
    buildTypes.create("benchmark") {
        initWith(buildTypes.getByName("release"))
        applicationIdSuffix = ".benchmark"
        signingConfig = signingConfigs.getByName("debug")
        isMinifyEnabled = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        matchingFallbacks += "release"
    }
    buildFeatures { compose = true }
    androidResources { noCompress += listOf("tcdb", "currents.json") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    testImplementation(libs.junit)
    implementation(project(":core"))
    implementation("io.openwaters:almanac:0.12.2")
    implementation("io.openwaters:slackwater-database:0.1.0")
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.material3)
    implementation(libs.datastore)
    implementation(libs.maplibre)
    androidTestImplementation(testFixtures(project(":core")))
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    // Compose still brings Espresso 3.5; API 37 needs the public InputManager lookup.
    androidTestImplementation(libs.espresso)
    // MapLibre supplies OkHttp at runtime; the test hooks its client to delay one style response.
    androidTestCompileOnly("com.squareup.okhttp3:okhttp:4.12.0") { isTransitive = false }
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.junit)
    debugImplementation(libs.compose.test.manifest)
}

tasks.register("checkStudioSources") {
    group = "verification"
    description = "Resolve published source variants for the app and its debug tests."
    val classpaths = listOf(
        "debugCompileClasspath", "debugRuntimeClasspath",
        "debugUnitTestCompileClasspath", "debugUnitTestRuntimeClasspath",
        "debugAndroidTestCompileClasspath", "debugAndroidTestRuntimeClasspath",
    )
    for (classpath in classpaths) {
        val sources = configurations.getByName(classpath).incoming.artifactView {
            withVariantReselection()
            componentFilter { it is ModuleComponentIdentifier }
            attributes {
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.DOCUMENTATION))
                attribute(DocsType.DOCS_TYPE_ATTRIBUTE, objects.named(DocsType.SOURCES))
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            }
        }
        inputs.files(sources.files)
    }
    doLast { logger.lifecycle("Verified ${inputs.files.files.size} dependency source archives.") }
}

val currentIndexDir = layout.buildDirectory.dir("generated/currentIndex/assets")
val generateCurrentIndex = tasks.register<Exec>("generateCurrentIndex") {
    val catalog = file("src/main/assets/slackwater/currents.json")
    val generator = rootProject.file("scripts/index-currents.py")
    val index = currentIndexDir.get().file("slackwater/current-index.tsv").asFile
    inputs.files(catalog, generator)
    outputs.file(index)
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python3"), generator, catalog, index)
}
android.sourceSets.getByName("main").assets.srcDir(currentIndexDir.get().asFile)
tasks.named("preBuild").configure { dependsOn(generateCurrentIndex) }
