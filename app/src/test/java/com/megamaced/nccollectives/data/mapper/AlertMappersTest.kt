package com.megamaced.nccollectives.data.mapper

import com.megamaced.nccollectives.data.api.dto.NotificationDto
import com.megamaced.nccollectives.data.db.entity.AlertEntity
import com.megamaced.nccollectives.di.NetworkModule
import com.megamaced.nccollectives.domain.model.AlertKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which notifications become alerts, and as what. The JSON is in the
 * Notifications API's own shape, as Collectives 4.7 and Text (Nextcloud 34)
 * fill it in, so a field read under the wrong name fails here.
 */
class AlertMappersTest {
    private val json = NetworkModule.provideJson()

    private fun parse(raw: String): NotificationDto = json.decodeFromString(NotificationDto.serializer(), raw)

    /**
     * [pageLinkJson] is the page parameter's `link` as raw JSON, left out
     * when null; [collective] is the collective parameter, left out when null.
     */
    private fun collectivesNotification(
        id: Long = 31,
        pageLinkJson: String? = "\"https://cloud.example/apps/collectives/Wiki/Page-11\"",
        collective: String? = """{"type":"highlight","id":"7","name":"📚 Wiki","link":"https://cloud.example/apps/collectives/Wiki"}""",
    ): String {
        val page = buildString {
            append("""{"type":"highlight","id":"11","name":"📄 Soup"""")
            if (pageLinkJson != null) append(""","link":$pageLinkJson""")
            append("}")
        }
        val collectiveParameter = if (collective != null) """"collective":$collective,""" else ""
        return """
            {"notification_id":$id,"app":"collectives","user":"me","datetime":"2026-10-14T19:00:00+00:00",
             "object_type":"page","object_id":"11","subject":"Bob updated 📄 Soup in Wiki","message":"",
             "subjectRich":"{user} updated {page} in {collective}",
             "subjectRichParameters":{
               "user":{"type":"user","id":"bob","name":"Bob"},
               $collectiveParameter
               "page":$page},
             "icon":"","shouldNotify":true,"actions":[]}
            """.trimIndent()
    }

    @Test
    fun aChangedPageIsAnUpdate_withWhoWhatAndWhere() {
        val alert = parse(collectivesNotification()).toAlertEntity()!!

        assertEquals(31L, alert.id)
        assertEquals(AlertKind.PageUpdated.name, alert.kind)
        assertEquals(11L, alert.pageId)
        assertEquals(7L, alert.collectiveId)
        assertEquals("Bob", alert.actorName)
        assertEquals("📄 Soup", alert.pageTitle)
        assertEquals("📚 Wiki", alert.collectiveName)
        assertEquals("Bob updated 📄 Soup in Wiki", alert.subject)
        assertEquals(1_792_004_400L, alert.timestamp)
        assertTrue(alert.verified)
        assertFalse(alert.seen)
    }

    @Test
    fun aPageWithNothingToLinkToWasDeleted() {
        assertEquals(AlertKind.PageDeleted.name, parse(collectivesNotification(pageLinkJson = null)).toAlertEntity()!!.kind)
    }

    @Test
    fun aLinkThatIsThereButBrokenIsNoDeletion() {
        // Collectives leaves the link out for a deleted page; one that's
        // there but not a usable string is malformed, and says nothing.
        for (broken in listOf("\"\"", "{}", "[]", "5", "null", "true")) {
            assertNull("link $broken", parse(collectivesNotification(pageLinkJson = broken)).toAlertEntity())
        }
    }

    @Test
    fun aCollectivesNotificationWithoutAUsableCollectiveIsNoAlert() {
        assertNull("no collective", parse(collectivesNotification(collective = null)).toAlertEntity())
        assertNull(
            "a collective id that's no number",
            parse(collectivesNotification(collective = """{"type":"highlight","id":"wiki","name":"Wiki"}""")).toAlertEntity(),
        )
        assertNull(
            "a malformed collective",
            parse(collectivesNotification(collective = """{"type":"highlight","id":"7","name":"Wiki","link":{}}""")).toAlertEntity(),
        )
        assertNull("a collective that's no object", parse(collectivesNotification(collective = "\"7\"")).toAlertEntity())
    }

    @Test
    fun aMentionInACollectivePageIsVerifiedByItsCollectiveParameter() {
        val alert = parse(
            """
            {"notification_id":40,"app":"text","datetime":"2026-10-14T19:00:00+00:00","object_type":"file","object_id":"11",
             "subject":"Bob has mentioned you in Wiki - Soup",
             "subjectRichParameters":{
               "user":{"type":"user","id":"bob","name":"Bob"},
               "node":{"type":"file","id":"11","name":"Soup.md","path":"Wiki/Soup.md","link":"https://cloud.example/f/11"},
               "collective":{"id":"7","type":"highlight","name":"Wiki","link":"https://cloud.example/apps/collectives/Wiki"},
               "page":{"id":"11","type":"highlight","name":"Soup","link":"https://cloud.example/apps/collectives/Wiki/Soup"}}}
            """.trimIndent(),
        ).toAlertEntity()!!

        assertEquals(AlertKind.Mention.name, alert.kind)
        assertEquals(11L, alert.pageId)
        assertEquals(7L, alert.collectiveId)
        assertTrue(alert.verified)
    }

    @Test
    fun aMentionWithoutTheCollectiveParametersIsKeptUnverified() {
        val alert = parse(
            """
            {"notification_id":41,"app":"text","datetime":"2026-10-14T19:00:00+00:00","object_type":"file","object_id":"12",
             "subject":"Bob has mentioned you in the text document Notes.md",
             "subjectRichParameters":{
               "user":{"type":"user","id":"bob","name":"Bob"},
               "node":{"type":"file","id":"12","name":"Notes.md","path":"Notes.md","link":"https://cloud.example/f/12"}}}
            """.trimIndent(),
        ).toAlertEntity()!!

        assertEquals(AlertKind.Mention.name, alert.kind)
        assertEquals(12L, alert.pageId)
        assertNull(alert.collectiveId)
        assertFalse(alert.verified)
    }

    @Test
    fun anotherAppsNotificationIsNotAnAlert() {
        assertNull(
            parse(
                """{"notification_id":50,"app":"files_sharing","object_type":"share","object_id":"3","subject":"x","subjectRichParameters":{}}""",
            ).toAlertEntity(),
        )
        // Right app, wrong object.
        assertNull(
            parse("""{"notification_id":51,"app":"collectives","object_type":"collective","object_id":"7"}""").toAlertEntity(),
        )
    }

    @Test
    fun parametersSentAsAnEmptyListAreNoParameters() {
        // PHP serialises an empty associative array as `[]`. Without a
        // `page` parameter a Collectives notification can't be told apart.
        assertNull(
            parse("""{"notification_id":52,"app":"collectives","object_type":"page","object_id":"11","subjectRichParameters":[]}""")
                .toAlertEntity(),
        )
        val mention = parse(
            """{"notification_id":53,"app":"text","object_type":"file","object_id":"11","subjectRichParameters":[]}""",
        ).toAlertEntity()!!
        assertEquals(AlertKind.Mention.name, mention.kind)
        assertFalse(mention.verified)
    }

    @Test
    fun aPageParameterNamingAnotherPageCountsForNothing() {
        val other = """{"type":"highlight","id":"99","name":"Elsewhere","link":"https://x/p"}"""
        assertNull(
            parse(
                """{"notification_id":54,"app":"collectives","object_type":"page","object_id":"11",
                |"subjectRichParameters":{"page":$other}}
                """.trimMargin(),
            ).toAlertEntity(),
        )
        val mention = parse(
            """{"notification_id":55,"app":"text","object_type":"file","object_id":"11",
            |"subjectRichParameters":{"collective":{"type":"highlight","id":"7","name":"Wiki"},"page":$other}}
            """.trimMargin(),
        ).toAlertEntity()!!
        assertFalse("the page parameter disagrees with the file", mention.verified)
        assertNull(mention.pageTitle)
    }

    @Test
    fun aCollectiveParameterWithoutAUsableIdDoesNotVerify() {
        val mention = parse(
            """{"notification_id":56,"app":"text","object_type":"file","object_id":"11",
            |"subjectRichParameters":{"collective":{"type":"highlight","id":"wiki","name":"Wiki"},
            |"page":{"type":"highlight","id":"11","name":"Soup","link":"https://x/p"}}}
            """.trimMargin(),
        ).toAlertEntity()!!

        assertFalse(mention.verified)
        assertNull(mention.collectiveId)
    }

    @Test
    fun aMentionWhosePageParameterIsMalformedIsKeptUnverified() {
        val mention = parse(
            """{"notification_id":58,"app":"text","object_type":"file","object_id":"11",
            |"subjectRichParameters":{"collective":{"type":"highlight","id":"7","name":"Wiki"},
            |"page":{"type":"highlight","id":"11","name":"Soup","link":{}}}}
            """.trimMargin(),
        ).toAlertEntity()!!

        assertEquals(AlertKind.Mention.name, mention.kind)
        assertFalse(mention.verified)
        assertNull(mention.pageTitle)
    }

    @Test
    fun anObjectIdThatIsNoPageIdIsNotAnAlert() {
        assertNull(parse("""{"notification_id":53,"app":"collectives","object_type":"page","object_id":"abc"}""").toAlertEntity())
    }

    @Test
    fun aDateThisCannotReadLeavesTheAlertUndated() {
        assertEquals(
            0L,
            parse(
                """{"notification_id":57,"app":"collectives","object_type":"page","object_id":"11","datetime":"yesterday",
                |"subjectRichParameters":{"collective":{"type":"highlight","id":"7","name":"Wiki"},
                |"page":{"type":"highlight","id":"11","name":"Soup"}}}
                """.trimMargin(),
            ).toAlertEntity()!!
                .timestamp,
        )
    }

    @Test
    fun aStoredKindThisVersionDoesNotKnowIsSkipped() {
        val row = AlertEntity(
            id = 1,
            kind = "Reaction",
            pageId = 11,
            collectiveId = null,
            actorName = null,
            pageTitle = null,
            collectiveName = null,
            subject = "",
            timestamp = 0,
            verified = true,
            seen = false,
        )

        assertNull(row.toDomain())
        assertEquals(AlertKind.Mention, row.copy(kind = "Mention").toDomain()!!.kind)
    }
}
