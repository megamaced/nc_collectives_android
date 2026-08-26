import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.baselineprofile)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

android {
    namespace = "com.megamaced.nccollectives.baselineprofile"
    compileSdk = 36

    defaultConfig {
        // 29 to match :app. It also clears the floor `BaselineProfileRule` sets
        // — `collect` is `@RequiresApi(28)` — so lint has nothing to flag.
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // The app this module drives. It is *not* a dependency: AGP builds an
    // independent test APK, installs both, and the journey talks to the app
    // through UiAutomator. Nothing here can see :app's classes, which is why
    // the journey below asserts on rendered text rather than on symbols.
    targetProjectPath = ":app"

    testOptions.managedDevices.localDevices {
        // A Gradle Managed Device, not a connected one: AGP downloads the
        // emulator + system image, boots it, runs, and tears it down, so
        // generation needs no pre-provisioned AVD on the machine doing it.
        //
        // aosp-atd, API 35, and the reasoning for each:
        //
        //   aosp-atd — an Automated Test Device image. ATD strips the
        //   preinstalled apps and Play services a profile run never touches,
        //   so it boots in a fraction of the time and fits the RAM budget of
        //   a CI container. AOSP rather than google-atd because an AOSP build
        //   is userdebug and therefore rootable, which is the fallback the
        //   profile extraction takes if the unrooted path is unavailable.
        //
        //   API 35, not 29 (the app's floor) — profile extraction on an
        //   unrooted device needs `pm dump-profiles
        //   --dump-classes-and-methods`, which lands in API 34. Below that,
        //   collection depends on root and fails on any image that refuses
        //   `adb root`. 35 rather than 36 keeps this off the newest image,
        //   where 16 KB page-size defaults are still settling. The API level
        //   only decides which API-gated branches the journey walks; the
        //   profile itself is emitted as portable human-readable rules and
        //   recompiled per-device by ART, so generating above minSdk does
        //   not narrow who benefits.
        create("aospAtd35") {
            device = "Pixel 6"
            apiLevel = 35
            systemImageSource = "aosp-atd"
            // Pinned rather than left to the default. AGP 8.13 defaults
            // this to x86_64 but warns that AGP 9 will flip the default to
            // arm64-v8a, which on an x86_64 host means either NDK
            // translation or a device that cannot run at all. Stating the
            // host architecture keeps the AGP 9 upgrade from silently
            // changing what this generates on.
            //
            // Note: AGP 8.13.2 prints its "unspecified testedAbi" warning
            // anyway — the setup task has the property but its
            // CreationAction never wires the DSL value through. Setting it
            // is still what AGP's own message asks for; the warning is
            // cosmetic and expected until that is fixed upstream.
            testedAbi = "x86_64"
        }
    }
}

baselineProfile {
    // Generation runs on the managed device above and nothing else.
    // `useConnectedDevices = false` is the half that matters on a developer
    // machine with a phone plugged in: left true, the plugin would happily
    // generate from whatever is attached, and a profile is only as good as
    // the device state that produced it.
    managedDevices += "aospAtd35"
    useConnectedDevices = false
}

dependencies {
    // Brings UiAutomator, the JUnit4 runner and benchmark-macro transitively.
    implementation(libs.androidx.benchmark.macro.junit4)
    // AndroidJUnit4, the runner class the test is annotated with. Not
    // transitive from the line above.
    implementation(libs.androidx.test.junit)
}

// Mirrors the block in app/build.gradle.kts. The root script applies the
// ktlint *plugin* to every subproject but carries no shared configuration, so
// without this the new module would be checked at the plugin's default ktlint
// version and without Android style — i.e. CI's single `ktlintCheck` would
// hold two modules to two different rule sets.
ktlint {
    version.set("1.8.0")
    android.set(true)
    ignoreFailures.set(false)
    filter {
        exclude("**/generated/**")
        exclude("**/build/**")
    }
}
