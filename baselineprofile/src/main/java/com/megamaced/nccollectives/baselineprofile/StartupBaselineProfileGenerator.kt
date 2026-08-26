package com.megamaced.nccollectives.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the baseline profile shipped in `:app` (R-64).
 *
 * A baseline profile is a list of classes and methods that ART pre-compiles at
 * install time instead of interpreting on first run. Everything the journey
 * below touches lands in that list; everything it does not is still
 * interpreted on the user's first launch.
 *
 * ## What this journey covers
 *
 * Cold start, end to end, up to the first screen an unauthenticated user sees:
 * process creation, `NcCollectivesApplication` and the Hilt component,
 * `MainActivity` with the splash-screen handoff and edge-to-edge setup,
 * Compose's runtime and first composition, `NcCollectivesTheme`,
 * `NcCollectivesScaffold`'s auth gate, and `LoginScreen` with its
 * `hiltViewModel()`, Material 3 `Scaffold`, `OutlinedTextField` and `Button`.
 * That is the bulk of the cold-start cost — Compose's first composition and
 * Hilt's graph construction dominate it, and both are fully exercised here.
 *
 * ## What it deliberately does NOT cover
 *
 * Every authenticated screen: the collective list, the page tree, page
 * view/edit, Markwon rendering, Room queries against a populated cache, Coil
 * image loading, sync workers. The app gates all of it behind a Nextcloud
 * login, and no server is reachable from a generation run — a journey that
 * claimed to scroll the page tree would in fact sit on the login screen and
 * silently produce the same profile as this one, while reading as if it
 * covered more. If a fake or recorded server is ever wired into the test
 * fixtures, extending the journey past this point is the obvious next win.
 *
 * `AuthState` resolves without any network: `SessionManager.init` reads the
 * token store synchronously, so a fresh install lands on `Unauthenticated`
 * — and therefore on `LoginScreen` — immediately, with no wait on I/O.
 */
@RunWith(AndroidJUnit4::class)
class StartupBaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() {
        baselineProfileRule.collect(
            packageName = targetPackageName(),
            // Also emits `startup-prof.txt`. The startup profile is what ART
            // uses to order the dex layout, so the classes this journey needs
            // first end up physically adjacent — a separate win from the
            // pre-compilation, and only available to a journey that really is
            // a cold start.
            includeInStartupProfile = true,
        ) {
            // Home first: `startActivityAndWait` measures a launch, and a
            // launch from an already-foregrounded task is a warm start that
            // would miss process creation entirely.
            pressHome()
            startActivityAndWait()

            // `startActivityAndWait` returns on the first frame, which the
            // splash screen can satisfy on its own. Waiting for text only
            // `LoginScreen` draws is what proves the journey reached real
            // composition rather than stopping at the window background.
            //
            // Asserted, not best-effort: a silent timeout here still produces
            // a profile, just a much thinner one, and a profile that quietly
            // got worse is the failure mode most likely to go unnoticed.
            val loginRendered =
                device.wait(Until.hasObject(By.textContains(LOGIN_SUBTITLE)), LOGIN_TIMEOUT_MS)
            check(loginRendered) {
                "Timed out after ${LOGIN_TIMEOUT_MS}ms waiting for LoginScreen (\"$LOGIN_SUBTITLE\"). " +
                    "The profile would only cover process start, so the run is failed instead."
            }
        }
    }

    /**
     * The application id to profile.
     *
     * Read from the instrumentation arguments rather than hard-coded: the
     * baselineprofile plugin passes the target's real application id, which
     * is the one for the synthetic `nonMinifiedRelease` variant it builds for
     * generation — not something this module could restate without going
     * stale the first time a suffix changes.
     */
    private fun targetPackageName(): String =
        requireNotNull(
            InstrumentationRegistry.getArguments().getString(ARG_TARGET_PACKAGE_NAME),
        ) {
            "$ARG_TARGET_PACKAGE_NAME was not passed to the instrumentation. Generation must be " +
                "driven through a Gradle task (`:app:generateBaselineProfile`), not by running " +
                "this test directly."
        }

    private companion object {
        /** Set by the baselineprofile plugin's producer half. */
        const val ARG_TARGET_PACKAGE_NAME = "androidx.benchmark.targetPackageName"

        /** Substring of `LoginScreen`'s subtitle — the proof of first composition. */
        const val LOGIN_SUBTITLE = "Connect to your Nextcloud server"

        /**
         * Generous on purpose. The generation device is a headless emulator
         * with software rendering, and the first of the iterations runs
         * against a freshly installed, wholly uncompiled app.
         */
        const val LOGIN_TIMEOUT_MS = 30_000L
    }
}
