package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.AppFailure
import com.megamaced.nccollectives.data.api.Failure
import com.megamaced.nccollectives.data.api.failure
import com.megamaced.nccollectives.data.api.userMessage
import com.megamaced.nccollectives.util.english
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A title no file can be named after is refused, before anything is sent,
 * with the app's own reason rather than an exception's English.
 */
@RunWith(AndroidJUnit4::class)
class PageTitleValidationIntegrationTest {
    private lateinit var env: IntegrationEnvironment

    @Before
    fun setUp() {
        env = IntegrationEnvironment.create()
    }

    @After
    fun tearDown() {
        env.close()
    }

    private fun assertRefused(
        reason: AppFailure,
        result: ApiResult<*>,
    ) {
        assertEquals(Failure.App(reason), result.failure())
        assertEquals(0, env.server.requestCount)
    }

    @Test
    fun renamingToDotDot_isAReservedTitle() =
        runTest {
            env.seedPage(id = 12)
            val result = env.pageRepository.renamePage(pageId = 12, newTitle = "..")
            assertRefused(AppFailure.ReservedTitle, result)
            assertEquals("A page can't be called \".\" or \"..\".", result.userMessage()?.english())
        }

    @Test
    fun renamingToOnlyCharactersAFileNameCantHold_isAnEmptyTitle() =
        runTest {
            env.seedPage(id = 12)
            val result = env.pageRepository.renamePage(pageId = 12, newTitle = "?/ |")
            assertRefused(AppFailure.EmptyTitle, result)
            assertEquals("Enter a title. It can't be only / \\ : * ? \" < > or |.", result.userMessage()?.english())
        }

    @Test
    fun creatingADot_isAReservedTitle() =
        runTest {
            env.seedPage(id = 1)
            val result = env.pageRepository.createPage(IntegrationEnvironment.COLLECTIVE_ID, parentPageId = 1, title = ".", body = "")
            assertRefused(AppFailure.ReservedTitle, result)
        }

    @Test
    fun creatingABlankTitle_isAnEmptyTitle() =
        runTest {
            env.seedPage(id = 1)
            val result = env.pageRepository.createPage(IntegrationEnvironment.COLLECTIVE_ID, parentPageId = 1, title = "   ", body = "")
            assertRefused(AppFailure.EmptyTitle, result)
        }
}
