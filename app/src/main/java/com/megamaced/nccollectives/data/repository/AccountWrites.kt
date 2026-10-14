package com.megamaced.nccollectives.data.repository

import androidx.room.withTransaction
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.auth.AccountChangedException
import com.megamaced.nccollectives.data.auth.AccountGeneration
import com.megamaced.nccollectives.data.db.NcCollectivesDatabase

/**
 * Run [writes] in one transaction if [generation] is still current, and say
 * whether they ran. Checked inside the transaction, which is what makes it a
 * barrier: see [AccountGeneration].
 */
internal suspend fun NcCollectivesDatabase.writeUnder(
    accountGeneration: AccountGeneration,
    generation: Long,
    writes: suspend () -> Unit,
): Boolean =
    withTransaction {
        if (!accountGeneration.isCurrent(generation)) return@withTransaction false
        writes()
        true
    }

/** Why a write was dropped: the account it was made under has been switched away. */
internal const val ACCOUNT_CHANGED = "The account changed before this could be saved"

/**
 * [this], unless [generation] is no longer current: then the operation's
 * account has gone, and so has anything its success would name — a page,
 * a file, a collective — so it is reported abandoned instead.
 */
internal fun <T> ApiResult<T>.unlessAccountChanged(
    accountGeneration: AccountGeneration,
    generation: Long,
): ApiResult<T> = if (accountGeneration.isCurrent(generation)) this else accountChanged()

/** The answer for an operation whose account was switched away part way through. */
internal fun accountChanged(): ApiResult<Nothing> = ApiResult.Unexpected(AccountChangedException(ACCOUNT_CHANGED))
