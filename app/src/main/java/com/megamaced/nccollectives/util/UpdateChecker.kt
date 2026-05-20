package com.megamaced.nccollectives.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.megamaced.nccollectives.BuildConfig
import com.megamaced.nccollectives.R
import com.megamaced.nccollectives.data.api.GitHubReleaseService
import com.megamaced.nccollectives.data.prefs.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Polls the GitHub Releases API at most once every 24 hours on startup,
 * compares the latest non-prerelease tag against `BuildConfig.VERSION_NAME`,
 * and posts a notification on the "App updates" channel when a newer
 * version is available. Tapping the notification opens the release's
 * html_url in the user's browser. The last notified tag is persisted in
 * DataStore so the same release isn't surfaced more than once.
 *
 * Distribution is sideload + GitHub Releases (see README); without an
 * app-store update channel the in-app check is the only way users hear
 * about new versions.
 */
@Singleton
class UpdateChecker
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val service: GitHubReleaseService,
        private val userPreferences: UserPreferences,
    ) {
        suspend fun checkOnStartup() {
            val now = System.currentTimeMillis()
            val state = userPreferences.getUpdateState()
            if (now - state.lastCheckedAt < CHECK_INTERVAL_MS) return

            val release =
                runCatching { service.latestRelease() }
                    .onFailure { Timber.tag(TAG).d(it, "GitHub release check failed") }
                    .getOrNull() ?: return

            // Always advance the last-checked stamp on a successful response
            // even if we decide not to notify — keeps the next launch out of
            // the network for 24h regardless of whether the user is on the
            // latest version or a pre-release.
            userPreferences.setUpdateLastCheckedAt(now)

            if (release.draft || release.preRelease) return

            val latest = parseSemVer(release.tagName) ?: return
            val current = parseSemVer(BuildConfig.VERSION_NAME) ?: return
            if (latest <= current) return

            val tag = release.tagName
            if (tag == state.lastNotifiedVersion) return

            postNotification(tag, release.htmlUrl)
            userPreferences.setUpdateLastNotifiedVersion(tag)
        }

        /**
         * Manual check triggered from Settings → About → "Check for updates".
         * Unlike [checkOnStartup] this:
         *  - ignores the 24h throttle (the user has explicitly asked),
         *  - returns a structured result for the caller to drive the UI,
         *  - does **not** post a notification (the UI surfaces the outcome
         *    directly, so a notification would just duplicate the message).
         *
         * Still updates the `lastCheckedAt` stamp on a successful response —
         * a manual check is a perfectly good signal that we've talked to
         * GitHub recently, so the startup check skips for the next 24h.
         * Still updates `lastNotifiedVersion` when surfacing a new tag so a
         * notification doesn't fire on the next launch for the same release
         * the user already saw in-app.
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

        private fun postNotification(
            tag: String,
            htmlUrl: String,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ requires runtime POST_NOTIFICATIONS. The app
                // never prompts for it (we never need user attention for
                // anything else), so bail quietly if it isn't granted —
                // the next launch after the user enables notifications
                // for the app will surface the update.
                val granted =
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return
            }

            val manager = NotificationManagerCompat.from(context)
            ensureChannel()

            val intent =
                Intent(Intent.ACTION_VIEW, Uri.parse(htmlUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            val pendingIntent =
                PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle(context.getString(R.string.update_available_title))
                    .setContentText(context.getString(R.string.update_available_text, tag))
                    .setStyle(
                        NotificationCompat
                            .BigTextStyle()
                            .bigText(context.getString(R.string.update_available_big_text, tag)),
                    ).setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()

            manager.notify(NOTIFICATION_ID, notification)
        }

        private fun ensureChannel() {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing != null) return
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.update_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.update_channel_description)
                }
            manager.createNotificationChannel(channel)
        }

        companion object {
            private const val TAG = "UpdateChecker"
            private const val CHANNEL_ID = "updates"
            private const val NOTIFICATION_ID = 4711
            private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
        }
    }

/**
 * Outcome of [UpdateChecker.checkNow]. Drives the manual "Check for
 * updates" affordance in Settings. [UpdateAvailable] carries the
 * release page URL so the UI can open the browser directly — same end
 * destination as tapping the auto-check notification.
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
