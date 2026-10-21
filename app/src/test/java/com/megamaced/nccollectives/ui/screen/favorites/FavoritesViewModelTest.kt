package com.megamaced.nccollectives.ui.screen.favorites

import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.ui.screen.home.collective
import com.megamaced.nccollectives.ui.screen.home.listItem
import com.megamaced.nccollectives.util.UiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private val collectiveRepository = mockk<CollectiveRepository>(relaxed = true) {
        every { observeCollectives() } returns MutableStateFlow(
            listOf(
                collective(1, name = "wiki", emoji = "📚", favoritePageIds = setOf(11, 12)),
                collective(2, name = "Empty", favoritePageIds = emptySet()),
                collective(3, name = "Recipes", favoritePageIds = setOf(31)),
            ),
        )
    }
    private val pageRepository = mockk<PageRepository>(relaxed = true) {
        every { observePageList(1) } returns
            flowOf(listOf(listItem(11, collectiveId = 1, title = "soup"), listItem(12, collectiveId = 1, title = "Bread"), listItem(13)))
        every { observePageList(2) } returns flowOf(listOf(listItem(21, collectiveId = 2)))
        every { observePageList(3) } returns flowOf(listOf(listItem(31, collectiveId = 3, title = "Stew")))
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun favoritesAreGroupedUnderTheirCollectives_bothAToZ_andCollectivesWithoutAnyAreLeftOut() =
        runTest(dispatcher) {
            val vm = FavoritesViewModel(collectiveRepository, pageRepository)
            backgroundScope.launch { vm.groups.collect {} }
            advanceUntilIdle()

            assertEquals(listOf("Recipes", "📚 wiki"), vm.groups.value.map { it.collectiveLabel })
            assertEquals(listOf(listOf(31L), listOf(12L, 11L)), vm.groups.value.map { group -> group.pages.map { it.id } })
        }

    @Test
    fun unstarringUsesThePagesOwnCollective_andOffersItBack() =
        runTest(dispatcher) {
            coEvery { collectiveRepository.toggleFavorite(any(), any(), any()) } returns ApiResult.Success(Unit)
            val vm = FavoritesViewModel(collectiveRepository, pageRepository)
            val removed = mutableListOf<Long>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.removed.collect { removed += it.id } }
            backgroundScope.launch { vm.groups.collect {} }
            advanceUntilIdle()

            val stew = vm.groups.value
                .first()
                .pages
                .single()
            vm.unfavorite(stew)
            advanceUntilIdle()
            coVerify { collectiveRepository.toggleFavorite(collectiveId = 3, pageId = 31, favorite = false) }
            assertEquals(listOf(31L), removed)

            vm.restore(stew)
            advanceUntilIdle()
            coVerify { collectiveRepository.toggleFavorite(collectiveId = 3, pageId = 31, favorite = true) }
        }

    @Test
    fun aRefusedUnstarIsReported_andNotOfferedBack() =
        runTest(dispatcher) {
            coEvery { collectiveRepository.toggleFavorite(any(), any(), any()) } returns ApiResult.NetworkError(java.io.IOException())
            val vm = FavoritesViewModel(collectiveRepository, pageRepository)
            val removed = mutableListOf<Long>()
            val messages = mutableListOf<UiText>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.removed.collect { removed += it.id } }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { messages += it } }
            backgroundScope.launch { vm.groups.collect {} }
            advanceUntilIdle()

            vm.unfavorite(
                vm.groups.value
                    .first()
                    .pages
                    .single(),
            )
            advanceUntilIdle()

            assertTrue(removed.isEmpty())
            assertEquals(1, messages.size)
        }
}
