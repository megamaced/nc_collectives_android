package com.megamaced.nccollectives.data.repository

import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.AppFailure
import com.megamaced.nccollectives.data.api.NotificationsApiService
import com.megamaced.nccollectives.data.api.apiCall
import com.megamaced.nccollectives.data.api.appFailure
import com.megamaced.nccollectives.data.api.dto.NotificationDto
import com.megamaced.nccollectives.data.auth.AccountGeneration
import com.megamaced.nccollectives.data.auth.bound
import com.megamaced.nccollectives.data.db.NcCollectivesDatabase
import com.megamaced.nccollectives.data.db.dao.AlertDao
import com.megamaced.nccollectives.data.db.entity.AlertEntity
import com.megamaced.nccollectives.data.mapper.toAlertEntity
import com.megamaced.nccollectives.data.mapper.toDomain
import com.megamaced.nccollectives.data.prefs.UserPreferences
import com.megamaced.nccollectives.domain.model.Alert
import com.megamaced.nccollectives.domain.model.DismissAllResult
import com.megamaced.nccollectives.domain.repository.AlertRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlertRepositoryImpl
    @Inject
    constructor(
        private val api: NotificationsApiService,
        private val alertDao: AlertDao,
        private val database: NcCollectivesDatabase,
        private val accountGeneration: AccountGeneration,
        private val userPreferences: UserPreferences,
        private val json: Json,
    ) : AlertRepository {
        /**
         * One server conversation at a time: a list and its commit, or a
         * dismissal and its commit. Refreshes are started from several places
         * at once (the tab, the foreground, a pull, every sync), and run side
         * by side an older list could land after a newer one, or after a
         * dismissal, and put back an alert that was just dismissed.
         */
        private val serverLock = Mutex()

        /**
         * The refresh under way and the account it is for. A caller on the
         * same account arriving meanwhile shares it rather than repeats it;
         * one on another account starts its own.
         */
        private var refreshInFlight: Pair<Long, CompletableDeferred<ApiResult<Unit>>>? = null

        override fun observeAlerts(): Flow<List<Alert>> =
            alertDao.observeVisible().distinctUntilChanged().map { rows -> rows.mapNotNull { it.toDomain() } }

        override fun observeUnseenCount(): Flow<Int> = alertDao.observeUnseenCount().distinctUntilChanged()

        override fun observeSupported(): Flow<Boolean?> = userPreferences.notificationsSupported

        // Issue #20, as everywhere the cache takes a server's answer: the
        // generation is captured before the request and checked inside the
        // write, and every request is bound to it.
        override suspend fun refresh(): ApiResult<Unit> = accountGeneration.bound { generation -> refreshBound(generation) }

        // Whatever it found, a refresh whose account has gone by the time its
        // caller resumes is no answer for the account now shown. Checked for
        // every caller, one that joined a refresh as well as the one that
        // started it: each resumes in its own time, and a switch can land
        // between the two.
        private suspend fun refreshBound(generation: Long): ApiResult<Unit> =
            refreshShared(generation).unlessAccountChanged(accountGeneration, generation)

        private suspend fun refreshShared(generation: Long): ApiResult<Unit> {
            val (shared, owner) = synchronized(this) {
                refreshInFlight
                    ?.takeIf { (forGeneration, _) -> forGeneration == generation }
                    ?.let { (_, inFlight) -> inFlight to false }
                    ?: (CompletableDeferred<ApiResult<Unit>>().also { refreshInFlight = generation to it } to true)
            }
            if (!owner) return shared.await()
            try {
                val result = serverLock.withLock { refreshLocked(generation) }
                shared.complete(result)
                return result
            } catch (e: Throwable) {
                shared.completeExceptionally(e)
                throw e
            } finally {
                synchronized(this) { if (refreshInFlight?.second === shared) refreshInFlight = null }
            }
        }

        private suspend fun refreshLocked(generation: Long): ApiResult<Unit> {
            val response = when (val sent = apiCall { api.list() }) {
                is ApiResult.Success -> sent.data
                is ApiResult.HttpError -> return sent
                is ApiResult.NetworkError -> return sent
                ApiResult.Unauthorised -> return ApiResult.Unauthorised
                ApiResult.Conflict -> return ApiResult.Conflict
                is ApiResult.Unexpected -> return sent
            }
            val entries: List<JsonElement> = when (response.code()) {
                // No app on the server notifies anyone, so there is nothing
                // to list — an answer, not a failure.
                HTTP_NO_CONTENT -> {
                    emptyList()
                }

                in 200..299 -> {
                    response.body()?.ocs?.data ?: return appFailure(AppFailure.EmptyResponse)
                }

                HTTP_UNAUTHORISED -> {
                    return ApiResult.Unauthorised
                }

                // A missing Notifications app, or a proxy or broken route:
                // only the capabilities can say which. Until they say the app
                // is missing, the cache stays and this is a failure.
                HTTP_NOT_FOUND -> {
                    if (!notificationsMissing()) return ApiResult.HttpError(response.code(), response.message())
                    if (!writeUnder(generation) { alertDao.clear() }) return accountChanged()
                    recordSupported(false, generation)
                    return ApiResult.Success(Unit)
                }

                else -> {
                    return ApiResult.HttpError(response.code(), response.message())
                }
            }
            val alerts = entries.mapNotNull(::decode)
            val recorded = writeUnder(generation) {
                // Nextcloud has no read flag, so whether one has been shown
                // is this device's, and survives the refresh by id.
                val seen = alertDao.seenIds().toHashSet()
                alertDao.upsertAll(alerts.map { it.copy(seen = it.id in seen) })
                if (alerts.isEmpty()) alertDao.clear() else alertDao.deleteMissing(alerts.map { it.id })
            }
            if (!recorded) return accountChanged()
            recordSupported(true, generation)
            return ApiResult.Success(Unit)
        }

        private fun decode(entry: JsonElement): AlertEntity? {
            val notification = entry as? JsonObject ?: return null
            return try {
                json.decodeFromJsonElement(NotificationDto.serializer(), notification).toAlertEntity()
            } catch (e: SerializationException) {
                Timber.w(e, "Skipping a notification this app can't read")
                null
            } catch (e: IllegalArgumentException) {
                Timber.w(e, "Skipping a notification this app can't read")
                null
            }
        }

        /**
         * Whether the server's capabilities say it has no Notifications app,
         * or one that doesn't list. False when they can't be read: a failure
         * to find out is not an answer.
         */
        private suspend fun notificationsMissing(): Boolean = notificationEndpoints()?.contains(ENDPOINT_LIST) == false

        /** The Notifications app's endpoints per the capabilities; empty without the app, null if unknown. */
        private suspend fun notificationEndpoints(): Set<String>? {
            val capabilities = (apiCall { api.capabilities() } as? ApiResult.Success)?.data?.ocs?.data ?: return null
            val all = (capabilities as? JsonObject)?.get("capabilities") as? JsonObject ?: return null
            val notifications = all["notifications"] as? JsonObject ?: return emptySet()
            val endpoints = notifications["ocs-endpoints"] as? JsonArray ?: return emptySet()
            return endpoints.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
        }

        override suspend fun markSeen(
            alertIds: Collection<Long>,
            generation: Long,
        ) {
            if (alertIds.isEmpty()) return
            writeUnder(generation) { alertDao.markSeen(alertIds.toList()) }
        }

        override suspend fun dismiss(alertId: Long): ApiResult<Unit> =
            accountGeneration.bound { generation ->
                serverLock.withLock { dismissLocked(alertId, generation) }
            }

        override suspend fun dismissAll(): DismissAllResult = accountGeneration.bound { generation -> dismissAllBound(generation) }

        private suspend fun dismissAllBound(generation: Long): DismissAllResult {
            var dismissed = 0
            var failed = 0
            // Only what this app shows. One at a time, so a refusal costs
            // that alert and not the rest; each holds the server lock on its
            // own, so a refresh can slot in between rather than wait for all.
            for (id in alertDao.visibleIds()) {
                val result = serverLock.withLock { dismissLocked(id, generation) }
                if (result is ApiResult.Success) dismissed++ else failed++
            }
            return DismissAllResult(dismissed = dismissed, failed = failed)
        }

        /**
         * Dismiss [alertId] while [generation] is current. The check comes
         * before the request as well as before the write: notification ids
         * are per server, and after a switch the same number names someone
         * else's notification on the next account's.
         */
        private suspend fun dismissLocked(
            alertId: Long,
            generation: Long,
        ): ApiResult<Unit> {
            if (!accountGeneration.isCurrent(generation)) return accountChanged()
            val result = apiCall { api.delete(alertId) }
            // Only a 2xx says it's gone. The Notifications app answers 200
            // for one already dismissed on another device, so a 404 is from
            // something else, such as a proxy or a broken route, and the
            // alert stays.
            if (result !is ApiResult.Success) return result.mapToUnit()
            if (!writeUnder(generation) { alertDao.delete(listOf(alertId)) }) return accountChanged()
            return ApiResult.Success(Unit)
        }

        private suspend fun recordSupported(
            supported: Boolean,
            generation: Long,
        ) {
            userPreferences.setNotificationsSupported(supported) { accountGeneration.isCurrent(generation) }
        }

        /** [NcCollectivesDatabase.writeUnder] on this repository's cache. */
        private suspend fun writeUnder(
            generation: Long,
            writes: suspend () -> Unit,
        ): Boolean = database.writeUnder(accountGeneration, generation, writes)

        private fun ApiResult<*>.mapToUnit(): ApiResult<Unit> =
            when (this) {
                is ApiResult.Success -> ApiResult.Success(Unit)
                is ApiResult.HttpError -> this
                is ApiResult.NetworkError -> this
                ApiResult.Unauthorised -> ApiResult.Unauthorised
                ApiResult.Conflict -> ApiResult.Conflict
                is ApiResult.Unexpected -> this
            }

        private companion object {
            const val HTTP_NO_CONTENT = 204
            const val HTTP_UNAUTHORISED = 401
            const val HTTP_NOT_FOUND = 404
            const val ENDPOINT_LIST = "list"
        }
    }
