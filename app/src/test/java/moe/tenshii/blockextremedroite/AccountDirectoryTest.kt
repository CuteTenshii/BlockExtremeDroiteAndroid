package moe.tenshii.blockextremedroite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AccountDirectoryTest {

    private fun sources(
        accounts: String,
        directory: String = """{"entries":[]}""",
        portraits: String? = null,
        followers: String? = null,
        socials: String = "const socials = {};",
    ) = DirectorySources(accounts, socials, directory, portraits, followers)

    private val List<Account>.xHandles get() = map { it.profiles[Platform.X]?.handle }

    @Test
    fun mergesCuratedAndDirectory() {
        val accounts = AccountDirectory.build(
            sources(
                accounts = """
                    // comment [with brackets]
                    const accounts = [
                      {"name": "A", "handle": "Alpha", "category": "RN", "reason": "r"},
                      {"id": "pa1", "name": "B", "handle": "", "category": "RN", "reason": ""},
                      {"name": "C", "handle": "charlie", "category": "Médias", "reason": ""}
                    ];
                """.trimIndent(),
                directory = """
                    {"entries": [
                      {"id": "u1", "type": "new", "name": "D", "category": "Inconnue", "platform": "x", "profile_url": "https://twitter.com/delta/"},
                      {"id": "u2", "type": "new", "name": "E", "category": "RN", "platform": "instagram", "profile_url": "https://instagram.com/echo"},
                      {"id": "u3", "type": "update", "target_id": "pa1", "platform": "x", "profile_url": "https://x.com/bravo"}
                    ], "frozen": true}
                """.trimIndent(),
                portraits = """const portraits = {"alpha": {"url": "https://example.org/a.jpg"}, "u1": {"url": "http://example.org/d.jpg"}};""",
                followers = """
                    // Relevés partiels ; sources : exemple.
                    const followerCounts = {"x:/bravo": {"count": 1200, "date": "2026-10-28"}, "instagram:/alpha": {"count": 5}};
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("Alpha", "bravo", "charlie", "delta", null), accounts.xHandles)
        assertEquals(listOf("alpha", "pa1", "charlie", "u1", "u2"), accounts.map { it.siteId })
        // Portraits are keyed like the site's records, and only HTTPS photos are kept.
        assertEquals(listOf("https://example.org/a.jpg", null, null, null, null), accounts.map { it.portraitUrl })
        // Follower counts are keyed by the site's profile identity.
        assertEquals(listOf(null, 1200L, null, null, null), accounts.map { it.profiles[Platform.X]?.followers })
        assertEquals("instagram:/echo", accounts.last().profiles.getValue(Platform.INSTAGRAM).identity)
        // A community link update keeps a curated account curated.
        assertEquals(
            listOf(AccountSource.CURATED, AccountSource.CURATED, AccountSource.CURATED, AccountSource.COMMUNITY, AccountSource.COMMUNITY),
            accounts.map { it.source },
        )
        assertEquals("Autres", accounts[3].category)
    }

    @Test
    fun curatedAccountsTakeTheirOtherProfilesFromSocials() {
        val accounts = AccountDirectory.build(
            sources(
                accounts = """const accounts = [{"name": "A", "handle": "alpha"}, {"id": "b1", "name": "B", "handle": ""}];""",
                socials = """
                    const socials = {
                      "alpha": {
                        "sources": {"facebook": "https://example.org/source"},
                        "facebook": "https://fr-fr.facebook.com/AlphaPage/",
                        "youtube": "https://www.youtube.com/watch?v=abc"
                      },
                      "b1": {"tiktok": "https://www.tiktok.com/@bravo.b"}
                    };
                """.trimIndent(),
            ),
        )
        assertEquals(listOf(Platform.X, Platform.FACEBOOK), accounts[0].profiles.keys.toList())
        assertEquals("https://www.facebook.com/alphapage", accounts[0].profiles.getValue(Platform.FACEBOOK).url)
        assertEquals("@bravo.b", accounts[1].label(Platform.TIKTOK))
        assertEquals("B", accounts[1].label(Platform.X))
    }

    @Test
    fun recordsSharingAnyProfileAreOnePerson() {
        val accounts = AccountDirectory.build(
            sources(
                accounts = """const accounts = [{"name": "A", "handle": "alpha"}, {"name": "B", "handle": "bravo"}];""",
                socials = """const socials = {"alpha": {"instagram": "https://www.instagram.com/shared/"}};""",
                directory = """
                    {"entries": [
                      {"id": "u1", "type": "new", "name": "A on TikTok", "platform": "tiktok", "profile_url": "https://www.tiktok.com/@alpha"},
                      {"type": "update", "target_id": "u1", "platform": "instagram", "profile_url": "https://instagram.com/Shared"}
                    ]}
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("alpha", "bravo"), accounts.map { it.siteId })
        // The first record keeps its details; the later one adds the platform it was missing.
        assertEquals(listOf(Platform.X, Platform.INSTAGRAM, Platform.TIKTOK), accounts[0].profiles.keys.toList())
        assertEquals("A", accounts[0].name)
    }

    @Test
    fun accountsWithoutAnyUsableProfileAreLeftOut() {
        val accounts = AccountDirectory.build(
            sources(
                accounts = """const accounts = [{"name": "A", "handle": "alpha"}, {"id": "b1", "name": "B"}];""",
                socials = """const socials = {"b1": {"youtube": "https://t.me/s/somewhere"}};""",
            ),
        )
        assertEquals(listOf("alpha"), accounts.map { it.siteId })
    }

    @Test
    fun removalDropsEveryRecordSharingTheHandle() {
        val accounts = AccountDirectory.build(
            sources(
                accounts = """const accounts = [{"name": "A", "handle": "alpha"}, {"name": "B", "handle": "bravo"}];""",
                directory = """
                    {"entries": [
                      {"id": "u1", "type": "new", "name": "A again", "platform": "x", "profile_url": "https://x.com/Alpha"},
                      {"type": "removal", "target_id": "U1"}
                    ]}
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("bravo"), accounts.xHandles)
    }

    @Test
    fun removalDropsRecordsLinkedOnAnotherPlatform() {
        val accounts = AccountDirectory.build(
            sources(
                accounts = """const accounts = [{"name": "A", "handle": "alpha"}, {"name": "B", "handle": "bravo"}];""",
                socials = """const socials = {"alpha": {"youtube": "https://www.youtube.com/@AlphaTube"}};""",
                directory = """
                    {"entries": [
                      {"id": "u1", "type": "new", "name": "A tube", "platform": "youtube", "profile_url": "https://m.youtube.com/@alphatube/"},
                      {"type": "removal", "target_id": "u1"}
                    ]}
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("bravo"), accounts.map { it.siteId })
    }

    @Test
    fun rejectsStaleDirectory() {
        assertThrows(IllegalArgumentException::class.java) {
            AccountDirectory.build(
                sources(
                    accounts = """const accounts = [{"name": "A", "handle": "alpha"}];""",
                    directory = """{"entries": [], "stale": true}""",
                ),
            )
        }
    }
}
