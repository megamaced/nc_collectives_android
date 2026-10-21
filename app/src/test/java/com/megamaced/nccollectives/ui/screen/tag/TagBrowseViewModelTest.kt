package com.megamaced.nccollectives.ui.screen.tag

import androidx.lifecycle.SavedStateHandle
import com.megamaced.nccollectives.R
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.ui.navigation.Destination
import com.megamaced.nccollectives.ui.screen.home.collective
import com.megamaced.nccollectives.ui.screen.home.listItem
import com.megamaced.nccollectives.util.uiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TagBrowseViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private val collectiveRepository = mockk<CollectiveRepository> {
        every { observeCollectives() } returns MutableStateFlow(
            listOf(
                collective(1, name = "Wiki", emoji = "📚", favoritePageIds = setOf(11)),
                collective(2, name = "Recipes", favoritePageIds = setOf(22)),
            ),
        )
        coEvery { toggleFavorite(any(), any(), any()) } returns ApiResult.Success(Unit)
    }
    private val pageRepository = mockk<PageRepository> {
        every { observePagesWithTag("soup") } returns
            flowOf(listOf(listItem(11, collectiveId = 1), listItem(22, collectiveId = 2), listItem(23, collectiveId = 2)))
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun acrossCollectives() =
        TagBrowseViewModel(
            SavedStateHandle(
                mapOf(
                    Destination.TagBrowse.ARG_COLLECTIVE_ID to Destination.TagBrowse.ALL_COLLECTIVES,
                    Destination.TagBrowse.ARG_TAG_NAME to "soup",
                ),
            ),
            collectiveRepository,
            pageRepository,
        )

    @Test
    fun acrossCollectivesEveryRowSaysWhereItIs_andWhetherItIsStarredThere() =
        runTest(dispatcher) {
            val vm = acrossCollectives()
            backgroundScope.launch { vm.rows.collect {} }
            advanceUntilIdle()

            assertEquals(listOf(true, true, false), vm.rows.value.map { it.isFavorite })
            assertEquals(listOf("📚 Wiki", "Recipes", "Recipes"), vm.rows.value.map { it.collectiveLabel })
            assertEquals(uiText(R.string.tag_all_collectives), vm.uiState.value.collectiveName)
            verify(exactly = 0) { pageRepository.observePagesWithTagInCollective(any(), any()) }
        }

    @Test
    fun starringAcrossCollectivesUsesTheRowsOwnCollective() =
        runTest(dispatcher) {
            val vm = acrossCollectives()
            backgroundScope.launch { vm.rows.collect {} }
            advanceUntilIdle()

            vm.toggleFavorite(vm.rows.value.single { it.page.id == 23L })
            vm.toggleFavorite(vm.rows.value.single { it.page.id == 11L })
            advanceUntilIdle()

            coVerify { collectiveRepository.toggleFavorite(collectiveId = 2, pageId = 23, favorite = true) }
            coVerify { collectiveRepository.toggleFavorite(collectiveId = 1, pageId = 11, favorite = false) }
            coVerify(exactly = 0) { collectiveRepository.toggleFavorite(collectiveId = 0, pageId = any(), favorite = any()) }
        }
}
