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
        // compose-markdown publishes through JitPack; we restrict the
        // resolver to that single group so we can't accidentally pull
        // anything else from JitPack.
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.jeziellago") }
        }
    }
}

rootProject.name = "NcCollectives"
include(":app")
