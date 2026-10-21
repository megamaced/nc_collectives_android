package com.megamaced.nccollectives.util

import com.megamaced.nccollectives.BuildConfig
import com.megamaced.nccollectives.R
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
                    ?: return ManualCheckResult.Failed(uiText(R.string.update_check_offline))

            if (release.draft || release.preRelease) {
                return ManualCheckResult.UpToDate
            }

            val latest = parseSemVer(release.tagName)
                ?: return ManualCheckResult.Failed(uiText(R.string.update_check_bad_release_tag, release.tagName))
            val current = parseSemVer(BuildConfig.VERSION_NAME)
                ?: return ManualCheckResult.Failed(uiText(R.string.update_check_bad_build_version, BuildConfig.VERSION_NAME))

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
        val message: UiText,
    ) : ManualCheckResult
}

/**
 * A parsed semantic version, ordered by SemVer 2.0.0 precedence.
 *
 * [preRelease] holds the dot-separated identifiers after the first `-`
 * (`3.0.0-beta.2` → `["beta", "2"]`). A pre-release sorts below the release
 * it leads up to, so someone running `3.0.0-beta.2` is offered `3.0.0`. Build
 * metadata (`+…`) is not kept, because it takes no part in precedence.
 */
internal data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList(),
) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        val core = compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)
        if (core != 0) return core
        // A release outranks any of its pre-releases.
        if (preRelease.isEmpty() != other.preRelease.isEmpty()) {
            return if (preRelease.isEmpty()) 1 else -1
        }
        preRelease.zip(other.preRelease).forEach { (mine, theirs) ->
            val order = comparePreReleaseIdentifiers(mine, theirs)
            if (order != 0) return order
        }
        // Every shared identifier is equal: the longer list is the later one.
        return preRelease.size.compareTo(other.preRelease.size)
    }
}

/**
 * Numeric identifiers compare by value and sort below alphanumeric ones;
 * alphanumeric identifiers compare in ASCII order. [parseSemVer] rejects
 * leading zeros, so comparing digit count and then the digits themselves
 * orders numeric identifiers by value however long they are.
 */
private fun comparePreReleaseIdentifiers(
    a: String,
    b: String,
): Int {
    val aNumeric = a.isNumericIdentifier()
    val bNumeric = b.isNumericIdentifier()
    return when {
        aNumeric && bNumeric -> {
            compareValuesBy(a, b, String::length, { it })
        }

        aNumeric -> {
            -1
        }

        bNumeric -> {
            1
        }

        else -> {
            a.compareTo(b)
        }
    }
}

private fun String.isNumericIdentifier(): Boolean = all { it in '0'..'9' }

private val IDENTIFIER = Regex("[0-9A-Za-z-]+")

/** SemVer 2.0.0 §9: a numeric pre-release identifier has no leading zero. */
private fun String.isValidPreReleaseIdentifier(): Boolean =
    IDENTIFIER.matches(this) && !(isNumericIdentifier() && length > 1 && startsWith('0'))

/**
 * A version made of one to three wholly numeric dot-separated components,
 * optionally followed by `-` and a pre-release, and by `+` and build
 * metadata. Returns null for anything else.
 *
 * Missing trailing components default to zero (`2.11` is `2.11.0`, which is
 * how the tags are written), but a component that is present has to be a
 * number, so `1.foo.3` is rejected rather than read as `1.3.0`. An
 * overflowing component is rejected rather than truncated. Pre-release and
 * build-metadata identifiers must be non-empty and use only ASCII letters,
 * digits and `-`, and a numeric pre-release identifier has no leading zero.
 */
internal fun parseSemVer(raw: String): SemVer? {
    val trimmed = raw.trim().removePrefix("v").removePrefix("V")
    val withoutBuild = trimmed.substringBefore('+')
    if ('+' in trimmed && trimmed.substringAfter('+').split('.').any { !IDENTIFIER.matches(it) }) {
        return null
    }
    val core = withoutBuild.substringBefore('-')
    val preRelease =
        if ('-' in withoutBuild) {
            withoutBuild.substringAfter('-').split('.')
        } else {
            emptyList()
        }
    if (preRelease.any { !it.isValidPreReleaseIdentifier() }) return null
    val parts = core.split('.')
    if (parts.isEmpty() || parts.size > 3) return null
    val numbers = parts.map { it.toIntOrNull() ?: return null }
    if (numbers.any { it < 0 }) return null
    return SemVer(
        major = numbers[0],
        minor = numbers.getOrElse(1) { 0 },
        patch = numbers.getOrElse(2) { 0 },
        preRelease = preRelease,
    )
}
