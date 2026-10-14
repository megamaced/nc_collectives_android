package com.megamaced.nccollectives.data.auth

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** `AccountGeneration.bound`: one account for an operation, and for everything nested in it. */
class AccountGenerationBoundTest {
    private val generation = AccountGeneration()

    @Test
    fun outsideABindingItBindsTheAccountCurrentNow() =
        runTest {
            val now = generation.current()

            val (handed, bound) = generation.bound { it to currentCoroutineContext()[AccountBinding]?.generation }

            assertEquals(now, handed)
            assertEquals(now, bound)
        }

    @Test
    fun insideABindingItKeepsTheOuterAccount_evenOnceAnotherIsCurrent() =
        runTest {
            val outer = generation.current()

            val handed = generation.bound {
                generation.invalidate()
                generation.settle()
                generation.bound { inner -> inner }
            }

            assertEquals(outer, handed)
        }
}
