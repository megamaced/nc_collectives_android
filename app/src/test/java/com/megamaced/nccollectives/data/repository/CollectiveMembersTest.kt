package com.megamaced.nccollectives.data.repository

import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.CirclesApiService
import com.megamaced.nccollectives.data.api.CollectivesApiService
import com.megamaced.nccollectives.data.api.Envelope
import com.megamaced.nccollectives.data.api.EnvelopeBody
import com.megamaced.nccollectives.data.api.OcsMeta
import com.megamaced.nccollectives.data.api.dto.CircleMemberDto
import com.megamaced.nccollectives.data.auth.AccountGeneration
import com.megamaced.nccollectives.data.db.NcCollectivesDatabase
import com.megamaced.nccollectives.data.db.dao.AttachmentDao
import com.megamaced.nccollectives.data.db.dao.CollectiveDao
import com.megamaced.nccollectives.data.db.dao.EditQueueDao
import com.megamaced.nccollectives.data.db.dao.PageDao
import com.megamaced.nccollectives.domain.model.CollectiveMemberLevel
import com.megamaced.nccollectives.domain.model.DEFAULT_MEMBER_LIMIT
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * `CollectiveRepositoryImpl.listMembers`. Three things are worth pinning
 * here, and none of them are about the happy path being pretty.
 *
 * The DAO mocks are deliberately **strict** rather than relaxed: an
 * unstubbed call throws, which is how these tests assert that the members
 * path never touches Room. That is a design commitment, not an
 * implementation detail — member lists are privacy-adjacent and are not
 * cached, so a later "just cache it for offline" edit should fail a test
 * rather than pass review.
 */
class CollectiveMembersTest {
    private val circlesApi = mockk<CirclesApiService>()

    private fun repository() =
        CollectiveRepositoryImpl(
            api = mockk<CollectivesApiService>(),
            circlesApi = circlesApi,
            dao = mockk<CollectiveDao>(),
            pageDao = mockk<PageDao>(),
            attachmentDao = mockk<AttachmentDao>(),
            editQueueDao = mockk<EditQueueDao>(),
            database = mockk<NcCollectivesDatabase>(),
            // Real, not a mock: it has no dependencies and the member path
            // does no Room writes for it to guard.
            accountGeneration = AccountGeneration(),
        )

    private fun envelopeOf(vararg members: CircleMemberDto) =
        Envelope(
            EnvelopeBody(
                meta = OcsMeta(status = "ok", statuscode = 200, message = "OK"),
                data = members.toList(),
            ),
        )

    private fun member(
        id: String,
        level: Int,
        displayName: String,
    ) = CircleMemberDto(
        id = id,
        singleId = "single-$id",
        userId = "$id@macemail.co.uk",
        userType = 1,
        level = level,
        displayName = displayName,
    )

    @Test
    fun `members are mapped and returned without touching the cache`() =
        runTest {
            coEvery { circlesApi.listMembers("circle-1", 50) } returns
                envelopeOf(
                    member("david", level = 9, displayName = "David Mace"),
                    member("rowan", level = 1, displayName = "Rowan Ash"),
                )

            val result = repository().listMembers("circle-1", limit = 50)

            assertTrue(result is ApiResult.Success)
            val members = (result as ApiResult.Success).data
            assertEquals(listOf("David Mace", "Rowan Ash"), members.map { it.displayName })
            assertEquals(
                listOf(CollectiveMemberLevel.Owner, CollectiveMemberLevel.Member),
                members.map { it.level },
            )
        }

    /**
     * A non-positive limit means *unbounded* to the server — 200 members at
     * ~2.2 KB each is ~440 KB — so it is replaced rather than forwarded.
     */
    @Test
    fun `a non-positive limit is replaced by the documented default`() =
        runTest {
            coEvery { circlesApi.listMembers("circle-1", DEFAULT_MEMBER_LIMIT) } returns envelopeOf()

            repository().listMembers("circle-1", limit = 0)
            repository().listMembers("circle-1", limit = -1)

            coVerify(exactly = 2) { circlesApi.listMembers("circle-1", DEFAULT_MEMBER_LIMIT) }
        }

    /**
     * 403 is the *expected* answer for a non-member, and Circles gives the
     * same answer for a circle that doesn't exist. It has to arrive intact
     * so the caller can render it and stop: every Circles controller calls
     * `throttle()` on a failed permission check, so a retry loop throttles
     * the user's own IP.
     */
    @Test
    fun `403 comes back as a terminal HttpError`() =
        runTest {
            coEvery { circlesApi.listMembers("circle-1", DEFAULT_MEMBER_LIMIT) } throws
                HttpException(
                    Response.error<Unit>(
                        403,
                        """{"ocs":{"meta":{"status":"failure","statuscode":403,"message":""},"data":{"message":"This member is not a moderator"}}}"""
                            .toResponseBody("application/json".toMediaType()),
                    ),
                )

            val result = repository().listMembers("circle-1", limit = DEFAULT_MEMBER_LIMIT)

            assertTrue(result is ApiResult.HttpError)
            assertEquals(403, (result as ApiResult.HttpError).code)
        }

    /**
     * `Collective.circleId` is nullable, so a caller can reach here with
     * nothing to address. Sending it would request `/circles//members`.
     */
    @Test
    fun `a blank circleId never reaches the network`() =
        runTest {
            val result = repository().listMembers("   ", limit = DEFAULT_MEMBER_LIMIT)

            assertTrue(result is ApiResult.Unexpected)
            assertTrue((result as ApiResult.Unexpected).cause is IllegalArgumentException)
            coVerify(exactly = 0) { circlesApi.listMembers(any(), any()) }
        }
}
