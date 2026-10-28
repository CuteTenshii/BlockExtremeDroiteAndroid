package moe.tenshii.blockextremedroite

import java.util.Locale

/** Labels shown by the Facebook app, read from its profile and Page top bar, menu and block dialogs. */
object FacebookLabels {
    val OVERFLOW = listOf("autres paramètres du profil", "more profile settings")
    val BLOCK_ACTION = listOf("bloquer", "block")
    val UNBLOCK_ACTION = listOf("débloquer", "unblock")
    /** Followed by the profile's name. */
    val BLOCKED_NOTICES = listOf("vous avez bloqué ", "you blocked ")
    val UNAVAILABLE = listOf("ce contenu n'est pas disponible", "this content isn't available")
    /** A link to a missing profile, or to one already blocked, lands on the news feed instead. */
    val FEED = listOf("quoi de neuf ?", "what's on your mind?")
}

/**
 * Facebook shows the profile's name but not the name in its link, so the profile can't be checked before the menu.
 * The menu lists the profile's own link, which is checked against the target before anything is blocked.
 */
class FacebookScreen(nodes: List<ScreenNode>, screenHeight: Int, profileUrl: String) : BlockScreen(nodes, screenHeight) {
    private val target = comparable(profileUrl)

    override fun showsProfile(): Boolean = overflowButton() != null

    /** Facebook has no blocked state on profiles: blocked ones are not shown at all. */
    override fun isBlockedProfile(): Boolean = false

    override fun isUnavailable(): Boolean = hasLabelContaining(FacebookLabels.UNAVAILABLE) || hasLabel(FacebookLabels.FEED)

    override fun overflowButton(): ScreenNode? = topBarButton(FacebookLabels.OVERFLOW)

    override fun blockMenuItem(): ScreenNode? = firstByPriority(FacebookLabels.BLOCK_ACTION)

    override fun hasUnblockMenuItem(): Boolean = hasLabel(FacebookLabels.UNBLOCK_ACTION)

    /** Numeric links may be shown as the profile's chosen name, so only named links are compared. */
    override fun menuShowsOtherProfile(): Boolean {
        if (PROFILE_ID in target) return false
        return nodes.any { node ->
            node.labels.any { label -> label.startsWith(LINK_PREFIX) && comparable(label) != target }
        }
    }

    /** The dialog's "BLOQUER" sits under the menu's "Bloquer" item. */
    override fun confirmButton(): ScreenNode? = lowest(FacebookLabels.BLOCK_ACTION)

    override fun blockSucceeded(): Boolean = nodes.any { node ->
        node.labels.any { label -> FacebookLabels.BLOCKED_NOTICES.any(label::startsWith) }
    }

    private companion object {
        const val LINK_PREFIX = "https://www.facebook.com/"
        const val PROFILE_ID = "profile.php"

        fun comparable(link: String) = link.lowercase(Locale.ROOT).trimEnd('/')
    }
}
