package moe.tenshii.blockextremedroite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XScreenTest {
    private val height = 2000

    private fun node(text: String? = null, description: String? = null, top: Int = 100, right: Int = 100) =
        ScreenNode(text, description, right - 50, top, right, top + 40)

    private fun screen(vararg nodes: ScreenNode, handle: String = "alpha") = XScreen(nodes.toList(), height, handle)

    @Test
    fun matchesHandleAsWholeMention() {
        assertTrue(screen(node("@J_Bardella"), handle = "j_bardella").showsProfile())
        assertTrue(screen(node(description = "Profil de @j_bardella, 3 k abonnés"), handle = "J_Bardella").showsProfile())
        assertFalse(screen(node("@J_Bardella2"), handle = "J_Bardella").showsProfile())
    }

    @Test
    fun findsOverflowInTopBarOnlyPreferringSpecificLabels() {
        val generic = node(description = "Plus", top = 60, right = 1000)
        val specific = node(description = "Plus d’options", top = 60, right = 900)
        val low = node(description = "More options", top = 1500)
        assertEquals(specific, screen(generic, specific, low).overflowButton())
        assertNull(screen(low).overflowButton())
    }

    @Test
    fun blockMenuItemTargetsTheRightHandle() {
        val item = node("Bloquer @alpha")
        assertEquals(item, screen(node("Bloquer @other"), item, handle = "Alpha").blockMenuItem())
        assertNull(screen(node("Bloquer @other")).blockMenuItem())
        assertNull(screen(node("Débloquer @alpha")).blockMenuItem())
        assertNotNull(screen(node("Block")).blockMenuItem())
    }

    @Test
    fun confirmButtonIsTheLowestBareBlockLabel() {
        val title = node("Bloquer @alpha ?", top = 800)
        val button = node("Bloquer", top = 1100)
        assertEquals(button, screen(title, button).confirmButton())
        assertEquals(button, screen(node("Block", top = 300), button).confirmButton())
    }

    @Test
    fun detectsBlockedProfileInBothLanguages() {
        assertTrue(screen(node("Débloquer")).isBlockedProfile())
        assertTrue(screen(node("Unblock")).isBlockedProfile())
        assertTrue(screen(node("Vous avez bloqué @alpha")).isBlockedProfile())
        assertTrue(screen(node("@alpha a été bloqué")).isBlockedProfile())
        assertFalse(screen(node("Unblocked @alpha")).isBlockedProfile())
        assertFalse(screen(node("Suivre"), node("@alpha")).isBlockedProfile())
        assertTrue(screen(node("Unblock @alpha"), handle = "Alpha").hasUnblockMenuItem())
    }

    @Test
    fun detectsUnavailableGateAndLogin() {
        assertTrue(screen(node("Ce compte n’existe pas")).isUnavailable())
        assertTrue(screen(node("Account suspended")).isUnavailable())
        assertNotNull(screen(node("Oui, voir le profil")).sensitiveGate())
        assertTrue(screen(node("Se connecter")).isLoggedOut())
        assertFalse(screen(node("Se connecter avec Google maintenant")).isLoggedOut())
    }
}
