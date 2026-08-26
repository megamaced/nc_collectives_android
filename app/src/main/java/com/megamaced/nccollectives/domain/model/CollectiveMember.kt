package com.megamaced.nccollectives.domain.model

/**
 * A member of the Nextcloud Team (Circle) that backs a collective.
 *
 * Collectives itself has no members endpoint — membership *is* Teams, so
 * this comes from the Circles API keyed by [Collective.circleId], and it is
 * never cached in Room. It is a server snapshot for as long as a screen is
 * open, the same shape `RemoteListViewModel` already documents for the two
 * trash listings: the lists are small, rarely looked at, and
 * privacy-adjacent, and the web app doesn't cache them either.
 */
data class CollectiveMember(
    /** Membership id, unique within the circle. Stable list key. */
    val id: String,
    /** Cross-circle identity of the principal; compare on this, not [id]. */
    val singleId: String,
    /** Login name — `user@host` when federated, bare uid locally. */
    val userId: String,
    /** May be blank: mail and contact memberships often have no name. */
    val displayName: String,
    val level: CollectiveMemberLevel,
    val type: CollectiveMemberType,
) {
    /**
     * What to actually put on screen. Kept here rather than in each caller
     * because a blank [displayName] is normal for mail/contact members and
     * a row rendering as empty space reads as a bug.
     */
    val label: String
        get() = displayName.ifBlank { userId }.ifBlank { id }
}

/**
 * Circles membership levels, using the numbers from the live capability
 * payload (Circles 34.0.0).
 *
 * Declaration order is load-bearing: entries ascend by [raw], so the enum's
 * natural ordering is privilege ordering and `level >= Admin` means what it
 * reads as. [Unknown] deliberately sorts *below* every real level — a level
 * the server didn't send, or one a future Circles invents, must never be
 * treated as more privileged than a plain member.
 */
enum class CollectiveMemberLevel(
    val raw: Int,
) {
    Unknown(0),
    Member(1),
    Moderator(4),
    Admin(8),
    Owner(9),
    ;

    companion object {
        fun fromRaw(raw: Int): CollectiveMemberLevel = entries.firstOrNull { it.raw == raw } ?: Unknown
    }
}

/**
 * What kind of principal a membership points at. A Team is not a list of
 * user accounts: it can also contain groups, bare email addresses, contacts,
 * other Teams, and app-owned singles, and those rows behave differently
 * (no avatar to fetch, no profile to open).
 */
enum class CollectiveMemberType(
    val raw: Int,
) {
    /** No such wire value; stands for a `userType` this app doesn't know. */
    Unknown(-1),
    Single(0),
    User(1),
    Group(2),
    Mail(4),
    Contact(8),
    Circle(16),
    App(10000),
    ;

    companion object {
        fun fromRaw(raw: Int): CollectiveMemberType = entries.firstOrNull { it.raw == raw } ?: Unknown
    }
}

/**
 * Default cap for `CollectiveRepository.listMembers`.
 *
 * A limit is not optional. Even without `fullDetails` each member costs
 * ~2.2 KB — `invitedBy` and `basedOn` are deep nested duplicates — so an
 * unbounded read of a 200-member team is ~440 KB of JSON to render a list
 * of names. 100 caps that at roughly 220 KB while covering every team a
 * phone-sized UI can usefully show at once.
 *
 * The endpoint returns no total count, so a caller that gets exactly
 * [DEFAULT_MEMBER_LIMIT] rows cannot tell a full team from a truncated one.
 * Treat `size == limit` as "there may be more" rather than as a total.
 */
const val DEFAULT_MEMBER_LIMIT = 100
