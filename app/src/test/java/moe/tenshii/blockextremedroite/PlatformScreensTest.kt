package moe.tenshii.blockextremedroite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Labels and positions come from each app's screens on a 1200×2664 phone, in English and French. */
class PlatformScreensTest {
    private val height = 2664

    private fun node(text: String? = null, description: String? = null, top: Int = 1200, right: Int = 600) =
        ScreenNode(text, description, right - 100, top, right, top + 100)

    @Test
    fun instagramFindsTheUsernameOptionsAndSheetButton() {
        fun screen(vararg nodes: ScreenNode) = InstagramScreen(nodes.toList(), height, "marine_lepen")
        assertTrue(screen(node("marine_lepen", top = 126)).showsProfile())
        assertFalse(screen(node("marine_lepen2", top = 126)).showsProfile())
        val options = node(description = "Options", top = 126, right = 1178)
        assertEquals(options, screen(node("Marine Le Pen", top = 318), options).overflowButton())

        val title = node("Bloquer jordanbardella ?", top = 1624)
        val button = node(description = "Bloquer", top = 2303)
        val report = node(description = "Bloquer et signaler", top = 2447)
        assertEquals(button, screen(title, button, report).confirmButton())
    }

    @Test
    fun instagramReadsBlockedAndMissingProfiles() {
        fun screen(vararg nodes: ScreenNode) = InstagramScreen(nodes.toList(), height, "jordanbardella")
        assertTrue(screen(node("Débloquer", "Débloquer Jordan Bardella")).isBlockedProfile())
        assertTrue(screen(node("You've blocked this account")).isBlockedProfile())
        assertFalse(screen(node("Suivre", "Suivre Jordan Bardella")).isBlockedProfile())
        assertTrue(screen(node("Utilisateur(ice) introuvable")).isUnavailable())
        assertTrue(screen(node("User not found")).isUnavailable())
    }

    @Test
    fun tiktokMatchesDottedHandlesAndItsShareSheet() {
        fun screen(vararg nodes: ScreenNode) = TikTokScreen(nodes.toList(), height, "mlp.officiel")
        assertTrue(screen(node("@mlp.officiel", top = 413)).showsProfile())
        assertFalse(screen(node("@mlp.officiel2", top = 413)).showsProfile())
        assertFalse(screen(node("@mlp", top = 413)).showsProfile())
        val share = node(description = "Share", top = 142, right = 1178)
        assertEquals(share, screen(node(description = "Notifications", top = 142, right = 1067), share).overflowButton())

        val dialogButton = node("Block", top = 1653)
        assertEquals(dialogButton, screen(node("Block Marine Le Pen (mlp.officiel)?", top = 1010), dialogButton, node("Cancel", top = 1653)).confirmButton())
        assertTrue(screen(node("Unblock", top = 715)).isBlockedProfile())
        assertTrue(screen(node("You blocked mlp.officiel")).isBlockedProfile())
        assertFalse(screen(node("You blocked someone.else")).isBlockedProfile())
    }

    @Test
    fun tiktokTakesTheFeedForAMissingProfile() {
        val feed = TikTokScreen(listOf(node(description = "For You", top = 126), node(description = "Home", top = 2467)), height, "ghost")
        assertTrue(feed.isUnavailable())
        assertFalse(feed.showsProfile())
    }

    @Test
    fun facebookChecksTheMenuLinkAgainstTheTarget() {
        fun screen(vararg nodes: ScreenNode) = FacebookScreen(nodes.toList(), height, "https://www.facebook.com/ballardphilippe")
        val settings = node(description = "More profile settings", top = 126, right = 1078)
        assertTrue(screen(settings, node(description = "More options for a post", top = 2134)).showsProfile())
        assertFalse(screen(node(description = "More options for a post", top = 2134)).showsProfile())

        assertFalse(screen(node("https://www.facebook.com/BallardPhilippe")).menuShowsOtherProfile())
        assertTrue(screen(node("https://www.facebook.com/someone.else")).menuShowsOtherProfile())
        val numeric = FacebookScreen(listOf(node("https://www.facebook.com/chosen.name")), height, "https://www.facebook.com/profile.php?id=42")
        assertFalse(numeric.menuShowsOtherProfile())
    }

    @Test
    fun facebookConfirmsWithTheDialogButtonAndReadsTheNotice() {
        fun screen(vararg nodes: ScreenNode) = FacebookScreen(nodes.toList(), height, "https://www.facebook.com/marinelepen")
        val menuItem = node(description = "Bloquer", top = 537)
        val dialogButton = node("BLOQUER", top = 1667)
        assertEquals(menuItem, screen(menuItem).blockMenuItem())
        assertEquals(dialogButton, screen(node("Voulez-vous vraiment bloquer Marine Le Pen ?", top = 929), dialogButton).confirmButton())
        assertTrue(screen(node("Vous avez bloqué Marine Le Pen")).blockSucceeded())
        assertTrue(screen(node("You blocked Philippe Ballard")).blockSucceeded())
        assertFalse(screen(node("Blocking this user")).blockSucceeded())
        assertTrue(screen(node("What’s on your mind?", top = 413)).isUnavailable())
        assertTrue(screen(node("Quoi de neuf\u00A0?", top = 413)).isUnavailable())
    }

    @Test
    fun youtubeHidesTheUserFromTheChannel() {
        fun screen(vararg nodes: ScreenNode, foreground: String? = Platform.YOUTUBE.packageName) =
            YouTubeScreen(nodes.toList(), height, foreground)
        val more = node(description = "Show more", top = 126, right = 1200)
        assertTrue(screen(node(description = "Search", top = 126, right = 1067), more).showsProfile())
        assertEquals(more, screen(more).overflowButton())

        assertEquals(node("Masquer l'utilisateur sur ma chaîne").labels, screen(node("Masquer l'utilisateur sur ma chaîne")).blockMenuItem()?.labels)
        assertNull(screen(node("Afficher l'utilisateur sur ma chaîne")).blockMenuItem())
        assertTrue(screen(node("Unhide user from my channel")).hasUnblockMenuItem())

        val confirm = node("Hide user", top = 1432)
        assertEquals(confirm, screen(node("Hide user", top = 1185), confirm, node("Cancel", top = 1432)).confirmButton())
        assertTrue(screen(node("Utilisateur masqué et commentaires supprimés")).blockSucceeded())
        assertTrue(screen(node("User hidden and comments removed")).blockSucceeded())
    }

    @Test
    fun youtubeTakesAHandOffToTheBrowserForAMissingChannel() {
        assertTrue(YouTubeScreen(emptyList(), height, "io.github.forkmaintainers.iceraven").isUnavailable())
        assertFalse(YouTubeScreen(emptyList(), height, "com.android.systemui").isUnavailable())
        assertFalse(YouTubeScreen(emptyList(), height, Platform.YOUTUBE.packageName).isUnavailable())
        assertFalse(YouTubeScreen(emptyList(), height, null).isUnavailable())
    }

    @Test
    fun picksTheScreenForEachPlatform() {
        val account = AccountDirectory.build(
            DirectorySources(
                accountsScript = """const accounts = [{"name": "A", "handle": "alpha"}];""",
                socialsScript = """const socials = {"alpha": {"facebook": "https://www.facebook.com/alpha.page", "youtube": "https://www.youtube.com/channel/UCsDXZB1DYLUT9j-UhaJf1vA"}};""",
                directoryJson = """{"entries": []}""",
            ),
        ).single()
        for ((platform, profile) in account.profiles) {
            val screen = BlockScreen.of(platform, profile, emptyList(), height, null)
            val expected = when (platform) {
                Platform.X -> XScreen::class
                Platform.FACEBOOK -> FacebookScreen::class
                Platform.YOUTUBE -> YouTubeScreen::class
                else -> error("Unexpected $platform")
            }
            assertEquals(expected, screen::class)
        }
    }
}
