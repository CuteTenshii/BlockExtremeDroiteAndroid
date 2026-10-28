package moe.tenshii.blockextremedroite.screen

import java.util.Locale

/** Labels shown by the X app (Jetpack Compose UI, so no stable view IDs). */
object XLabels {
    /** Profile top bar overflow, most specific first. */
    val OVERFLOW = listOf("plus d'options", "autres options", "more options", "options", "plus", "more")
    val BLOCK_ACTION = listOf("bloquer", "block")
    val UNBLOCK_ACTION = listOf("débloquer", "unblock")
    val BLOCKED_STATE = listOf("bloqué", "bloquée", "blocked")
    /** Sentences that name the handle after the block, `%s` being `@handle`. */
    val BLOCKED_NOTICES = listOf(
        "vous avez bloqué %s", "tu as bloqué %s", "%s a été bloqué", "%s est bloqué",
        "you blocked %s", "you have blocked %s", "%s has been blocked", "%s is blocked",
    )
    val UNAVAILABLE = listOf(
        "ce compte n'existe pas", "compte suspendu", "a été suspendu", "est suspendu",
        "this account doesn't exist", "account suspended", "has been suspended", "is suspended",
    )
    val SENSITIVE_GATE = listOf("oui, voir le profil", "voir le profil", "yes, view profile", "view profile")
}

/** X shows `@handle` on the profile, in its menu items and in its notices, so every step checks the target. */
class XScreen(nodes: List<ScreenNode>, screenHeight: Int, handle: String) : BlockScreen(nodes, screenHeight) {
    private val mention = "@" + handle.lowercase(Locale.ROOT)
    private val handlePattern = Regex("(^|[^a-z0-9_])" + Regex.escape(mention) + "($|[^a-z0-9_])")

    override fun showsProfile(): Boolean = nodes.any { node -> node.labels.any(handlePattern::containsMatchIn) }

    override fun isBlockedProfile(): Boolean = nodes.any { node ->
        node.labels.any { label ->
            label in XLabels.UNBLOCK_ACTION ||
                label in XLabels.BLOCKED_STATE ||
                XLabels.UNBLOCK_ACTION.any { label.startsWith("$it $mention") } ||
                (handlePattern.containsMatchIn(label) &&
                    XLabels.BLOCKED_NOTICES.any { notice -> label.contains(notice.format(mention)) })
        }
    }

    override fun isUnavailable(): Boolean = hasLabelContaining(XLabels.UNAVAILABLE)

    override fun sensitiveGate(): ScreenNode? = firstByPriority(XLabels.SENSITIVE_GATE)

    override fun overflowButton(): ScreenNode? = topBarButton(XLabels.OVERFLOW)

    /** "Bloquer @handle" in the overflow menu; a bare "Bloquer" is accepted only if no other handle is named. */
    override fun blockMenuItem(): ScreenNode? =
        nodes.firstOrNull { node ->
            node.labels.any { label -> XLabels.BLOCK_ACTION.any { label.startsWith("$it @") } && handlePattern.containsMatchIn(label) }
        } ?: nodes.firstOrNull { node ->
            node.labels.any { it in XLabels.BLOCK_ACTION } &&
                node.labels.none { label -> label.contains('@') && !label.contains(mention) }
        }

    override fun hasUnblockMenuItem(): Boolean = nodes.any { node ->
        node.labels.any { label -> XLabels.UNBLOCK_ACTION.any { label.startsWith("$it $mention") } }
    }

    /** The dialog's confirm button is a bare "Bloquer"; the lowest one wins over any title text. */
    override fun confirmButton(): ScreenNode? = lowest(XLabels.BLOCK_ACTION)

    override fun blockSucceeded(): Boolean = isBlockedProfile()
}
