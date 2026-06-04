package com.megamaced.nccollectives.util

import com.megamaced.nccollectives.BuildConfig
import com.megamaced.nccollectives.data.api.GitHubReleaseService
import com.megamaced.nccollectives.data.prefs.UserPreferences
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides the manual "Check for updates" affordance in Settings →
 * About. The app deliberately does NOT auto-check at launch: F-Droid
 * users get updates through F-Droid, and unsolicited network calls to
 * a third-party host (api.github.com) on every launch is the kind of
 * thing F-Droid's inclusion policy rules out. Sideload users who want
 * to know about new versions can hit the Settings button on demand.
 */
@Singleton
class UpdateChecker
    @Inject
    constructor(
        private val service: GitHubReleaseService,
        private val userPreferences: UserPreferences,
    ) {
        /**
         * Manual check triggered from Settings → About → "Check for updates".
         * Returns a structured result the caller uses to drive the UI.
         * Updates `lastCheckedAt` and `lastNotifiedVersion` so the
         * surfacing logic stays consistent across calls.
         */
        suspend fun checkNow(): ManualCheckResult {
            val release =
                runCatching { service.latestRelease() }
                    .onFailure { Timber.tag(TAG).w(it, "Manual update check failed") }
                    .getOrNull()
                    ?: return ManualCheckResult.Failed("Couldn't reach GitHub. Check your connection and try again.")

            userPreferences.setUpdateLastCheckedAt(System.currentTimeMillis())

            if (release.draft || release.preRelease) {
                return ManualCheckResult.UpToDate
            }

            val latest = parseSemVer(release.tagName)
                ?: return ManualCheckResult.Failed("Release tag \"${release.tagName}\" doesn't look like a version.")
            val current = parseSemVer(BuildConfig.VERSION_NAME)
                ?: return ManualCheckResult.Failed("This build's version (${BuildConfig.VERSION_NAME}) doesn't look like a version.")

            return if (latest > current) {
                userPreferences.setUpdateLastNotifiedVersion(release.tagName)
                ManualCheckResult.UpdateAvailable(tag = release.tagName, htmlUrl = release.htmlUrl)
            } else {
                ManualCheckResult.UpToDate
            }
        }

        companion object {
            private const val TAG = "UpdateChecker"
        }
    }

/**
 * Outcome of [UpdateChecker.checkNow]. Drives the manual "Check for
 * updates" affordance in Settings. [UpdateAvailable] carries the
 * release page URL so the UI can open the browser directly.
 */
sealed interface ManualCheckResult {
    data object UpToDate : ManualCheckResult

    data class UpdateAvailable(
        val tag: String,
        val htmlUrl: String,
    ) : ManualCheckResult

    data class Failed(
        val message: String,
    ) : ManualCheckResult
}

/**
 * Loosely-parsed semver triple. Strips an optional leading `v` and any
 * `-something` / `+something` suffix, then compares numerically. Returns
 * `null` if the tag doesn't have at least one numeric component.
 */
internal data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int = compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)
}

internal fun parseSemVer(raw: String): SemVer? {
    val trimmed = raw.trim().removePrefix("v").removePrefix("V")
    val core = trimmed.substringBefore('-').substringBefore('+')
    val parts = core.split('.').mapNotNull { it.toIntOrNull() }
    if (parts.isEmpty()) return null
    return SemVer(
        major = parts.getOrElse(0) { 0 },
        minor = parts.getOrElse(1) { 0 },
        patch = parts.getOrElse(2) { 0 },
    )
}
