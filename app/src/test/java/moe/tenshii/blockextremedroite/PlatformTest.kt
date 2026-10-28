package moe.tenshii.blockextremedroite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlatformTest {
    private fun identity(platform: Platform, link: String) = platform.profile(link)?.identity

    @Test
    fun readsXHandlesOnlyFromProfileLinks() {
        assertEquals("J_Bardella", Platform.X.profile("https://x.com/J_Bardella")?.handle)
        assertEquals("x:/j_bardella", identity(Platform.X, "https://x.com/J_Bardella"))
        assertEquals("abc", Platform.X.profile("https://mobile.twitter.com/@abc/")?.handle)
        assertNull(Platform.X.profile("http://x.com/abc"))
        assertNull(Platform.X.profile("https://x.com/abc/status/1"))
        assertNull(Platform.X.profile("https://x.com/home"))
        assertNull(Platform.X.profile("https://evilx.com/abc"))
        assertNull(Platform.X.profile("https://x.com/this_is_way_too_long"))
        assertNull(Platform.X.profile("https://www.instagram.com/abc"))
    }

    @Test
    fun readsInstagramUsernames() {
        val profile = Platform.INSTAGRAM.profile("https://www.instagram.com/Marine_LePen/")
        assertEquals("instagram:/marine_lepen", profile?.identity)
        assertEquals("marine_lepen", profile?.handle)
        assertEquals("https://www.instagram.com/marine_lepen/", profile?.url)
        assertNull(Platform.INSTAGRAM.profile("https://www.instagram.com/p/C0de/"))
        assertNull(Platform.INSTAGRAM.profile("https://www.instagram.com/reels"))
    }

    @Test
    fun readsTikTokUsernamesFromAtPaths() {
        val profile = Platform.TIKTOK.profile("https://m.tiktok.com/@MLP.officiel")
        assertEquals("tiktok:/@mlp.officiel", profile?.identity)
        assertEquals("mlp.officiel", profile?.handle)
        assertNull(Platform.TIKTOK.profile("https://www.tiktok.com/mlp.officiel"))
        assertNull(Platform.TIKTOK.profile("https://www.tiktok.com/@a"))
    }

    @Test
    fun readsYouTubeHandlesAndChannelIds() {
        val handle = Platform.YOUTUBE.profile("https://www.youtube.com/@MarineLePenOfficiel/")
        assertEquals("youtube:/@marinelepenofficiel", handle?.identity)
        assertEquals("MarineLePenOfficiel", handle?.handle)
        val channel = Platform.YOUTUBE.profile("https://youtube.com/channel/UCsDXZB1DYLUT9j-UhaJf1vA")
        // Channel ids are case-sensitive, so the site keeps their case.
        assertEquals("youtube:/channel/UCsDXZB1DYLUT9j-UhaJf1vA", channel?.identity)
        assertNull(channel?.handle)
        assertNull(Platform.YOUTUBE.profile("https://www.youtube.com/watch?v=FYWnuQc5mYA"))
    }

    @Test
    fun readsFacebookNamesAndNumericIds() {
        assertEquals("facebook:/ballardphilippe", identity(Platform.FACEBOOK, "https://fr-fr.facebook.com/BallardPhilippe/"))
        assertEquals("facebook:id:1234", identity(Platform.FACEBOOK, "https://www.facebook.com/profile.php?id=1234&ref=x"))
        assertEquals("facebook:id:987", identity(Platform.FACEBOOK, "https://www.facebook.com/pages/Some-Page/987"))
        assertEquals(
            "https://www.facebook.com/profile.php?id=987",
            Platform.FACEBOOK.profile("https://www.facebook.com/people/Someone/987/")?.url,
        )
        assertNull(Platform.FACEBOOK.profile("https://www.facebook.com/profile.php?id=abc"))
        assertNull(Platform.FACEBOOK.profile("https://www.facebook.com/"))
    }

    @Test
    fun wrapsFacebookLinksForItsApp() {
        val profile = Platform.FACEBOOK.profile("https://www.facebook.com/BallardPhilippe")!!
        assertEquals("fb://facewebmodal/f?href=https%3A%2F%2Fwww.facebook.com%2Fballardphilippe", Platform.FACEBOOK.appLink(profile))
        val instagram = Platform.INSTAGRAM.profile("https://www.instagram.com/abc")!!
        assertEquals(instagram.url, Platform.INSTAGRAM.appLink(instagram))
    }
}
