package moe.tenshii.blockextremedroite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AccountDirectoryTest {

    private fun sources(accounts: String, directory: String = """{"entries":[]}""") =
        DirectorySources(accounts, directory)

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
            ),
        )
        assertEquals(listOf("Alpha", "bravo", "charlie", "delta"), accounts.map { it.handle })
        assertEquals(listOf("alpha", "pa1", "charlie", "u1"), accounts.map { it.siteId })
        // A community link update keeps a curated account curated.
        assertEquals(
            listOf(AccountSource.CURATED, AccountSource.CURATED, AccountSource.CURATED, AccountSource.COMMUNITY),
            accounts.map { it.source },
        )
        assertEquals("Autres", accounts.last().category)
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
        assertEquals(listOf("bravo"), accounts.map { it.handle })
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

    @Test
    fun extractsHandlesOnlyFromProfileLinks() {
        assertEquals("J_Bardella", AccountDirectory.handleFromLink("https://x.com/J_Bardella"))
        assertEquals("abc", AccountDirectory.handleFromLink("https://mobile.twitter.com/@abc/"))
        assertNull(AccountDirectory.handleFromLink("http://x.com/abc"))
        assertNull(AccountDirectory.handleFromLink("https://x.com/abc/status/1"))
        assertNull(AccountDirectory.handleFromLink("https://x.com/home"))
        assertNull(AccountDirectory.handleFromLink("https://evilx.com/abc"))
        assertNull(AccountDirectory.handleFromLink("https://x.com/this_is_way_too_long"))
    }
}
