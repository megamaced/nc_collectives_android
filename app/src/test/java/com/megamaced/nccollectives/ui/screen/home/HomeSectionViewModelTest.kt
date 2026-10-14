package com.megamaced.nccollectives.ui.screen.home

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.auth.CurrentUser
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.ui.navigation.Destination
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class HomeSectionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val collectives = MutableStateFlow(listOf(collective(1), collective(2)))
    private val recent = MutableStateFlow((1L..250L).map { listItem(it) })

    private val collectiveRepository = mockk<CollectiveRepository> {
        every { observeCollectives() } returns collectives
    }
    private val pageRepository = mockk<PageRepository> {
        every { observeRecentPagesAcrossCollectives(any()) } answers {
            val limit = firstArg<Int>()
            recent.map { it.take(limit) }
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun recentlyUpdated(collective: Long? = null): Pair<HomeSectionViewModel, SavedStateHandle> {
        val saved = SavedStateHandle(
            buildMap {
                put(Destination.HomeSeeAll.ARG_SECTION, HomeSection.RecentlyUpdated.key)
                if (collective != null) put(Destination.HomeSeeAll.ARG_COLLECTIVE, collective)
            },
        )
        return HomeSectionViewModel(saved, collectiveRepository, pageRepository, mockk<CurrentUser>(relaxed = true)) to saved
    }

    @Test
    fun loadMoreGoesOnUntilEveryPageIsShown() =
        runTest(dispatcher) {
            val (vm, _) = recentlyUpdated()
            backgroundScope.launch { vm.content.collect {} }
            advanceUntilIdle()

            fun shown() = vm.content.value as HomeSectionContent.Pages
            assertEquals(HOME_SECTION_LIMIT, shown().pages.size)
            assertTrue(shown().hasMore)

            vm.loadMore()
            advanceUntilIdle()
            assertEquals(2 * HOME_SECTION_LIMIT, shown().pages.size)
            assertTrue(shown().hasMore)

            vm.loadMore()
            advanceUntilIdle()
            assertEquals(250, shown().pages.size)
            assertFalse(shown().hasMore)
        }

    @Test
    fun aFilterOnAGoneCollectiveIsForgotten_soRestoringItDoesNotBringItBack() =
        runTest(dispatcher) {
            val (vm, saved) = recentlyUpdated(collective = 99)
            backgroundScope.launch { vm.content.collect {} }
            backgroundScope.launch { vm.recentFilter.collect {} }
            advanceUntilIdle()

            assertNull(vm.recentFilter.value)
            assertEquals(ALL_COLLECTIVES, saved.get<Long>(Destination.HomeSeeAll.ARG_COLLECTIVE))

            collectives.value = collectives.value + collective(99)
            advanceUntilIdle()
            assertNull(vm.recentFilter.value)
        }
}
