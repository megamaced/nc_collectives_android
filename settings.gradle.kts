pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NcCollectives"
include(":app")
// Generator for the baseline profile shipped in :app (R-64). Ships nothing
// itself — it exists so `generateBaselineProfile` has a com.android.test APK
// to drive the app from. `pluginManagement` above needs no change: both the
// `androidx.baselineprofile` marker and the plugin it resolves to sit under
// groups the `androidx.*` regex already lets through.
include(":baselineprofile")
