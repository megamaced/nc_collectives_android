import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    // kapt is here for one consumer only: Prism4j's grammar bundler
    // (Batch 24). The rest of the project uses KSP. Applied without
    // version because the Kotlin Gradle plugin already provides it.
    kotlin("kapt")
    alias(libs.plugins.hilt)
    alias(libs.plugins.baselineprofile)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

// Release-signing config is sourced from environment variables so the keystore
// never lands on disk in the repo. CI decodes ANDROID_RELEASE_KEYSTORE_BASE64
// into a file and exports ANDROID_RELEASE_KEYSTORE_FILE for this script; local
// signed builds export the same four vars from a shell-rc file. If any are
// absent the release build still works but produces an unsigned APK.
val releaseKeystoreFile: String? = System.getenv("ANDROID_RELEASE_KEYSTORE_FILE")
val releaseStorePassword: String? = System.getenv("ANDROID_RELEASE_STORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("ANDROID_RELEASE_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("ANDROID_RELEASE_KEY_PASSWORD")
val hasReleaseSigningConfig =
    releaseKeystoreFile != null &&
        releaseStorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null &&
        file(releaseKeystoreFile).exists()

android {
    namespace = "com.megamaced.nccollectives"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.megamaced.nccollectives"
        minSdk = 29
        targetSdk = 36
        versionCode = 34
        versionName = "2.10.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // AGP 8.x embeds an extra APK signing block named "Dependency metadata"
    // intended for Play Console reporting. F-Droid's binary scanner flags
    // any non-standard signing block, so disable both APK + bundle embedding.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = file(releaseKeystoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Room's MigrationTestHelper loads the exported schema JSONs from the
    // instrumentation APK's assets. Schemas are exported via the KSP arg
    // below rather than the Room Gradle plugin, so nothing copies them in
    // automatically and every migration test would fail with
    // "Cannot find the schema file in the assets folder".
    sourceSets {
        getByName("androidTest").assets.srcDirs(files("$projectDir/schemas"))
    }

    testOptions {
        unitTests {
            // Robolectric reads the merged manifest and resource table from
            // the unit-test task's own output; without this every test that
            // inflates a theme or resolves a string dies at startup.
            isIncludeAndroidResources = true
        }
    }
}

// Prism4j (Batch 24) drags in the legacy `annotations-java5` artifact;
// the modern `annotations` (transitive from Kotlin stdlib) ships the
// same FQCNs, so the two collide at the dex step. Exclude globally so
// every configuration (compile, runtime, kapt classpath) drops it.
configurations.all {
    exclude(group = "org.jetbrains", module = "annotations-java5")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Core / Lifecycle / Activity
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // LifecycleResumeEffect — screens re-check the server when the user
    // navigates back to one that's still on the nav backstack (B-58).
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    // Compose (BOM aligns transitive versions)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    // Browser (Custom Tabs for Nextcloud login)
    implementation(libs.androidx.browser)

    // WebKit compat (algorithmic darkening for the WebView-backed Text
    // editor — see PageEditWebScreen.kt)
    implementation(libs.androidx.webkit)

    // Secure storage (Tink-backed)
    implementation(libs.androidx.security.crypto)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // WorkManager (with Hilt assisted-injection support)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Networking
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)

    // Image loading (reuses the authenticated OkHttp client)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Markdown rendering
    implementation(libs.markwon.core)
    implementation(libs.markwon.linkify)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.ext.tasklist)
    implementation(libs.markwon.ext.strikethrough)
    implementation(libs.markwon.image)
    implementation(libs.markwon.html)
    implementation(libs.markwon.syntax.highlight)
    implementation(libs.prism4j) {
        // Prism4j's pom declares a dep on its own bundler annotation
        // processor. Pull it in via kapt below instead — otherwise the
        // annotation-processor classes ship in the runtime classpath.
        exclude(group = "io.noties", module = "prism4j-bundler")
    }
    kapt(libs.prism4j.bundler)

    // Drag-to-reorder (Batch 23)
    implementation(libs.reorderable)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Logging
    implementation(libs.timber)

    // Baseline profile, consumer side (R-64). profileinstaller is what applies
    // a shipped profile at runtime — without it the profile rides along in
    // assets/dexopt/ and ART never sees it.
    //
    // Declared explicitly because it was previously only arriving
    // transitively, pulled in by whichever AndroidX libraries ship their own
    // profiles. That resolved to the same 1.4.1, so this changes nothing
    // today; it stops a future dependency bump from dropping it, or from
    // settling below 1.4.0 — which is the floor for reading back a profile
    // recorded on an API 34+ device, i.e. exactly what :baselineprofile
    // generates on.
    implementation(libs.androidx.profileinstaller)
    // The generator. Supplies the profile to the merge task; ships nothing.
    baselineProfile(project(":baselineprofile"))

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    // Integration suite (app/src/test/.../integration). Robolectric supplies
    // the Android runtime, so these get real Room, real WorkManager and a
    // real Compose tree while staying in the fast JVM task.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Required, not optional: `createComposeRule` launches a bare
    // `ComponentActivity`, and this is the artifact that declares one. Verified
    // by removing it — every Compose test then dies with "Unable to resolve
    // activity for Intent". `debugImplementation` per AndroidX's own guidance,
    // so the extra activity lands in the debug manifest only and the release
    // APK — and therefore F-Droid's reproducible build — is untouched.
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Instrumented tests. Room migrations are hand-written against seven
    // committed schemas, and MigrationTestHelper is the only thing that
    // actually exercises them — a wrong migration silently corrupts user data.
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// Consumer half of the baseline profile setup (R-64). The generator lives in
// :baselineprofile; this decides what happens to what it produces.
baselineProfile {
    // Write one profile under src/main rather than a per-variant one.
    //
    // Two reasons. The app has no flavours, so a per-variant split would only
    // ever hold one file. And the per-variant path is `app/src/<variant>/…` —
    // for the release variant that is `app/src/release/`, which .gitignore's
    // `release/` entry silently swallows, so the generated profile would look
    // committed, never actually be committed, and quietly stop shipping.
    mergeIntoMain = true

    // Generation must never be a side effect of building. CI runs
    // `assembleRelease` on every push; with this true that would try to boot
    // an emulator on a runner that has none, and F-Droid's builder — which
    // takes the same path — would fail outright. Generating is an explicit,
    // occasional `:app:generateBaselineProfile` by a human, and the result is
    // committed.
    automaticGenerationDuringBuild = false
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_reports")
    metricsDestination = layout.buildDirectory.dir("compose_metrics")
}

ktlint {
    version.set("1.8.0")
    android.set(true)
    ignoreFailures.set(false)
    filter {
        exclude("**/generated/**")
        exclude("**/build/**")
    }
}
