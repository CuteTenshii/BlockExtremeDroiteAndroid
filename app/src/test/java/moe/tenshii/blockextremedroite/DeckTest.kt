package moe.tenshii.blockextremedroite

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckTest {

    private fun cards(json: String): List<JsonElement> = Json.decodeFromString(json)

    @Test
    fun parsesTotalsForEveryCategoryAndTheQuota() {
        val page = Deck.parse(
            """
                {"cards": [{"id": "a"}], "total": {"x": {"all": 120, "pol": 40}, "instagram": {"all": 30}, "mastodon": {"all": 9}},
                 "quota": true, "extra": 1}
            """.trimIndent(),
        )
        assertEquals(1, page.cards.size)
        assertEquals(
            mapOf(Platform.X to mapOf("all" to 120, "pol" to 40), Platform.INSTAGRAM to mapOf("all" to 30)),
            page.totals,
        )
        assertTrue(page.quotaReached)
        assertFalse(Deck.parse("""{"cards": [], "total": {}}""").quotaReached)
    }

    @Test
    fun rejectsAnAnswerWithoutCards() {
        assertThrows(IllegalArgumentException::class.java) { Deck.parse("""{"error": "Origine refusée."}""") }
    }

    @Test
    fun listsSavedBeforeTheDeckStillLoadAndTakeNewCards() {
        val saved = Json { ignoreUnknownKeys = true }.decodeFromString<List<Account>>(
            """
                [{"siteId": "mlp_officiel", "name": "Marine", "category": "RN", "reason": "", "source": "CURATED", "portraitUrl": null,
                  "profiles": {"X": {"identity": "x:/mlp_officiel", "url": "https://x.com/MLP_officiel", "handle": "MLP_officiel"}}}]
            """.trimIndent(),
        )
        assertEquals(listOf("mlp_officiel"), saved.single().ids)
        assertEquals(SiteCategory.POLITICS, saved.single().siteCategory)
        val merged = Deck.merge(
            saved,
            Platform.INSTAGRAM,
            cards("""[{"id": "mlp_officiel", "name": "M", "aliases": ["mlp_officiel"], "links": {"instagram": "https://www.instagram.com/marine_lepen/"}}]"""),
        )
        assertEquals("Marine", merged.single().name)
        assertEquals(listOf(Platform.X, Platform.INSTAGRAM), merged.single().profiles.keys.toList())
    }

    @Test
    fun turnsCardsIntoAccounts() {
        val accounts = Deck.merge(
            emptyList(),
            Platform.X,
            cards(
                """
                    [
                      {"id": "Alpha", "name": " A ", "aliases": ["alpha", "pa1"], "links": {"x": "https://twitter.com/Alpha/", "instagram": "https://instagram.com/a"},
                       "category": "RN", "reason": "r", "verified": true, "follower": {"count": 1200, "date": "2026-10-28"}, "cat": "pol"},
                      {"id": "u1", "name": "", "aliases": ["u1"], "links": {"x": "https://x.com/delta"}, "category": ""}
                    ]
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("alpha", "u1"), accounts.map { it.siteId })
        assertEquals(listOf("alpha", "pa1"), accounts[0].ids)
        assertEquals(listOf("A", "delta"), accounts.map { it.name })
        assertEquals(listOf("RN", "Autres"), accounts.map { it.category })
        // The deck's own category wins; a card without one falls back on its label, and "Autres" has none.
        assertEquals(listOf(SiteCategory.POLITICS, null), accounts.map { it.siteCategory })
        // Only the asked platform's link is taken, like the site's page does.
        assertEquals(listOf(Platform.X), accounts[0].profiles.keys.toList())
        assertEquals("x:/alpha", accounts[0].profiles.getValue(Platform.X).identity)
        assertEquals(1200L, accounts[0].profiles.getValue(Platform.X).followers)
    }

    @Test
    fun aCardSharingAnAliasFillsInTheSamePerson() {
        val fromX = Deck.merge(
            emptyList(),
            Platform.X,
            cards("""[{"id": "alpha", "name": "A", "aliases": ["alpha", "pa1"], "links": {"x": "https://x.com/alpha"}}]"""),
        )
        val merged = Deck.merge(
            fromX,
            Platform.TIKTOK,
            cards("""[{"id": "pa1", "name": "A on TikTok", "aliases": ["pa1", "tt9"], "links": {"tiktok": "https://www.tiktok.com/@alpha"}}]"""),
        )
        val account = merged.single()
        assertEquals("alpha", account.siteId)
        assertEquals("A", account.name)
        assertEquals(listOf("alpha", "pa1", "tt9"), account.ids)
        assertEquals(listOf(Platform.X, Platform.TIKTOK), account.profiles.keys.toList())
    }

    @Test
    fun skipsMalformedCardsAndUnusableLinks() {
        val accounts = Deck.merge(
            emptyList(),
            Platform.YOUTUBE,
            cards(
                """
                    [
                      {"id": "a", "name": "A", "aliases": ["a"], "links": {"youtube": "https://www.youtube.com/watch?v=abc"}},
                      {"id": "b", "name": "B", "links": {"youtube": "https://www.youtube.com/@bravo"}},
                      {"id": "c", "name": "C", "aliases": ["c"], "links": {"x": "https://x.com/charlie"}},
                      {"id": "", "name": "E", "aliases": [], "links": {"youtube": "https://www.youtube.com/@echo"}},
                      "not a card",
                      {"id": "d", "name": "D", "aliases": ["d"], "links": {"youtube": "https://m.youtube.com/@Delta/"}}
                    ]
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("d"), accounts.map { it.siteId })
        assertEquals("@Delta", accounts.single().label(Platform.YOUTUBE))
    }
}
