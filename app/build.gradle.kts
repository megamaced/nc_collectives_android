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
        versionCode = 18
        versionName = "2.3.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
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

    // Unit tests
    testImplementation(libs.junit)
}

ktlint {
    version.set("1.5.0")
    android.set(true)
    ignoreFailures.set(false)
    filter {
        exclude("**/generated/**")
        exclude("**/build/**")
    }
}
