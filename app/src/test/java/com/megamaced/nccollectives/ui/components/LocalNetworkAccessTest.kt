package com.megamaced.nccollectives.ui.components

import android.Manifest
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class LocalNetworkAccessTest {
    @get:Rule
    val rule = createComposeRule()

    private var requestedPermission: String? = null
    private var permissionRequestCode: Int? = null
    private val owner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                requestedPermission = input as String
                permissionRequestCode = requestCode
            }
        }
    }

    @Test
    @Config(sdk = [35, 36])
    fun olderAndroidDoesNotOfferThePermission() {
        showAccessAction()

        rule.onNodeWithText("Allow local network access").assertDoesNotExist()
        assertNull(requestedPermission)
    }

    @Test
    @Config(sdk = [37])
    fun android17RequestsPermissionOnlyAfterTheUserChoosesIt() {
        showAccessAction()

        rule.onNodeWithText("Allow local network access").assertExists()
        assertNull(requestedPermission)

        rule.onNodeWithText("Allow local network access").performClick()
        rule.runOnIdle { assertEquals(Manifest.permission.ACCESS_LOCAL_NETWORK, requestedPermission) }
    }

    @Test
    @Config(sdk = [37])
    fun unsuccessfulPermissionRequestKeepsRetryAndOffersSettings() {
        showAccessAction()

        rule.onNodeWithText("Allow local network access").performClick()
        rule.runOnIdle {
            owner.activityResultRegistry.dispatchResult(requireNotNull(permissionRequestCode), false)
        }

        rule.onNodeWithText("Allow local network access").assertExists()
        rule.onNodeWithText("Open app settings").assertExists()
    }

    @Test
    @Config(sdk = [37])
    fun accessActionIsDisabledDuringSignIn() {
        showAccessAction(enabled = false)

        rule.onNodeWithText("Allow local network access").assertIsNotEnabled()
        assertNull(requestedPermission)
    }

    private fun showAccessAction(enabled: Boolean = true) {
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                LocalNetworkAccess(enabled = enabled)
            }
        }
    }
}
