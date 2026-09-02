package com.megamaced.nccollectives.util

import com.megamaced.nccollectives.BuildConfig
import com.megamaced.nccollectives.data.api.GitHubReleaseService
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
    ) {
        /**
         * Manual check triggered from Settings → About → "Check for updates".
         * Returns a structured result the caller uses to drive the UI.
         *
         * Issue #37: it used to also write `lastCheckedAt` and
         * `lastNotifiedVersion` to DataStore "so the surfacing logic stays
         * consistent across calls". Nothing ever read them. The only reader,
         * `UserPreferences.getUpdateState`, had no call sites of its own, and
         * Settings answers from its in-memory `UpdateCheckUiState` — so this
         * was a write-only feature costing two DataStore edits per check plus
         * a model, an accessor, two keys and two special cases in
         * `clearAccountScoped`. Removed rather than wired up, because the
         * check is manual: the user taps it and reads the answer on the same
         * screen, and there is nothing to resurface later.
         */
        suspend fun checkNow(): ManualCheckResult {
            val release =
                runCatching { service.latestRelease() }
                    .onFailure { Timber.tag(TAG).w(it, "Manual update check failed") }
                    .getOrNull()
                    ?: return ManualCheckResult.Failed("Couldn't reach GitHub. Check your connection and try again.")

            if (release.draft || release.preRelease) {
                return ManualCheckResult.UpToDate
            }

            val latest = parseSemVer(release.tagName)
                ?: return ManualCheckResult.Failed("Release tag \"${release.tagName}\" doesn't look like a version.")
            val current = parseSemVer(BuildConfig.VERSION_NAME)
                ?: return ManualCheckResult.Failed("This build's version (${BuildConfig.VERSION_NAME}) doesn't look like a version.")

            return if (latest > current) {
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
 * A parsed semver triple. Strips an optional leading `v` and any
 * `-prerelease` / `+build` suffix, then compares numerically.
 */
internal data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int = compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)
}

/**
 * One to three wholly numeric dot-separated components, or null.
 *
 * Issue #26: the previous `split('.').mapNotNull { it.toIntOrNull() }`
 * *dropped* the components it couldn't parse rather than rejecting the
 * input, so `1.foo.3` parsed as `1.3.0` and `foo.2` as `2.0.0` — both
 * contradicting the numeric contract and both comparing wrongly against the
 * installed version. Low severity because the input is the GitHub Releases
 * API reporting this project's own tags, which are well-formed; the point is
 * that a malformed one should now fail the check rather than misreport it.
 *
 * Missing trailing components still default to zero — `2.11` is `2.11.0`,
 * which is how the tags are written — but a component that is *present* has
 * to be a number. An overflowing component is a non-number as far as
 * `toIntOrNull` is concerned, so it is rejected rather than silently
 * truncated.
 */
internal fun parseSemVer(raw: String): SemVer? {
    val trimmed = raw.trim().removePrefix("v").removePrefix("V")
    val core = trimmed.substringBefore('-').substringBefore('+')
    val parts = core.split('.')
    if (parts.isEmpty() || parts.size > 3) return null
    val numbers = parts.map { it.toIntOrNull() ?: return null }
    if (numbers.any { it < 0 }) return null
    return SemVer(
        major = numbers[0],
        minor = numbers.getOrElse(1) { 0 },
        patch = numbers.getOrElse(2) { 0 },
    )
}
