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

    private fun screen(vararg nodes: ScreenNode) = XScreen(nodes.toList(), height)

    @Test
    fun matchesHandleAsWholeMention() {
        assertTrue(screen(node("@J_Bardella")).showsHandle("j_bardella"))
        assertTrue(screen(node(description = "Profil de @j_bardella, 3 k abonnés")).showsHandle("J_Bardella"))
        assertFalse(screen(node("@J_Bardella2")).showsHandle("J_Bardella"))
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
        assertEquals(item, screen(node("Bloquer @other"), item).blockMenuItem("Alpha"))
        assertNull(screen(node("Bloquer @other")).blockMenuItem("alpha"))
        assertNull(screen(node("Débloquer @alpha")).blockMenuItem("alpha"))
        assertNotNull(screen(node("Block")).blockMenuItem("alpha"))
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
        assertTrue(screen(node("Débloquer")).isBlockedProfile("alpha"))
        assertTrue(screen(node("Unblock")).isBlockedProfile("alpha"))
        assertTrue(screen(node("Vous avez bloqué @alpha")).isBlockedProfile("alpha"))
        assertTrue(screen(node("@alpha a été bloqué")).isBlockedProfile("alpha"))
        assertFalse(screen(node("Unblocked @alpha")).isBlockedProfile("alpha"))
        assertFalse(screen(node("Suivre"), node("@alpha")).isBlockedProfile("alpha"))
        assertTrue(screen(node("Unblock @alpha")).hasUnblockMenuItem("Alpha"))
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
