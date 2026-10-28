package moe.tenshii.blockextremedroite

import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/** A visible node of the X app, reduced to what the matchers need. */
class ScreenNode(
    text: CharSequence?,
    description: CharSequence?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val source: AccessibilityNodeInfo? = null,
) {
    val labels: List<String> = listOf(XLabels.normalize(text), XLabels.normalize(description))
        .filter { it.isNotEmpty() }
        .distinct()

    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * Labels shown by the X app (Jetpack Compose UI, so no stable view IDs), in French and English.
 * Everything is compared after [normalize]. When X changes its wording, this is the place to update.
 */
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
    private val WHITESPACE = Regex("[\\s\\u00A0\\u202F]+")

    val LOGGED_OUT = listOf("se connecter", "créer un compte", "log in", "sign in", "create account")

    fun normalize(value: CharSequence?): String {
        if (value.isNullOrEmpty()) return ""
        return value.toString()
            .replace('\u2019', '\'')
            .replace(WHITESPACE, " ")
            .trim()
            .lowercase(Locale.ROOT)
    }
}

class XScreen(private val nodes: List<ScreenNode>, private val screenHeight: Int) {

    fun showsHandle(handle: String): Boolean {
        val pattern = handlePattern(handle)
        return nodes.any { node -> node.labels.any(pattern::containsMatchIn) }
    }

    fun isBlockedProfile(handle: String): Boolean {
        val mention = mention(handle)
        val pattern = handlePattern(handle)
        return nodes.any { node ->
            node.labels.any { label ->
                label in XLabels.UNBLOCK_ACTION ||
                    label in XLabels.BLOCKED_STATE ||
                    XLabels.UNBLOCK_ACTION.any { label.startsWith("$it $mention") } ||
                    (pattern.containsMatchIn(label) &&
                        XLabels.BLOCKED_NOTICES.any { notice -> label.contains(notice.format(mention)) })
            }
        }
    }

    fun isUnavailable(): Boolean = nodes.any { node ->
        node.labels.any { label -> XLabels.UNAVAILABLE.any(label::contains) }
    }

    fun isLoggedOut(): Boolean = nodes.any { node -> node.labels.any { it in XLabels.LOGGED_OUT } }

    fun sensitiveGate(): ScreenNode? = firstByPriority(XLabels.SENSITIVE_GATE)

    /** The overflow button sits in the profile's top bar, so matches lower on screen are ignored. */
    fun overflowButton(): ScreenNode? {
        val topArea = screenHeight * TOP_AREA_RATIO
        return nodes.filter { it.centerY < topArea }
            .mapNotNull { node -> priority(node, XLabels.OVERFLOW)?.let { node to it } }
            .minWithOrNull(compareBy<Pair<ScreenNode, Int>> { it.second }.thenByDescending { it.first.right })
            ?.first
    }

    /** "Bloquer @handle" in the overflow menu; a bare "Bloquer" is accepted only if no other handle is named. */
    fun blockMenuItem(handle: String): ScreenNode? {
        val mention = mention(handle)
        val pattern = handlePattern(handle)
        return nodes.firstOrNull { node ->
            node.labels.any { label -> XLabels.BLOCK_ACTION.any { label.startsWith("$it @") } && pattern.containsMatchIn(label) }
        } ?: nodes.firstOrNull { node ->
            node.labels.any { it in XLabels.BLOCK_ACTION } &&
                node.labels.none { label -> label.contains('@') && !label.contains(mention) }
        }
    }

    fun hasUnblockMenuItem(handle: String): Boolean {
        val mention = mention(handle)
        return nodes.any { node ->
            node.labels.any { label -> XLabels.UNBLOCK_ACTION.any { label.startsWith("$it $mention") } }
        }
    }

    /** The dialog's confirm button is a bare "Bloquer"; the lowest one wins over any title text. */
    fun confirmButton(): ScreenNode? =
        nodes.filter { node -> node.labels.any { it in XLabels.BLOCK_ACTION } }.maxByOrNull { it.bottom }

    fun blockSucceeded(handle: String): Boolean = isBlockedProfile(handle)

    private fun firstByPriority(candidates: List<String>): ScreenNode? =
        nodes.mapNotNull { node -> priority(node, candidates)?.let { node to it } }
            .minByOrNull { it.second }
            ?.first

    private fun priority(node: ScreenNode, candidates: List<String>): Int? =
        node.labels.mapNotNull { label -> candidates.indexOf(label).takeIf { it >= 0 } }.minOrNull()

    private fun mention(handle: String) = "@" + handle.lowercase(Locale.ROOT)

    private fun handlePattern(handle: String) =
        Regex("(^|[^a-z0-9_])" + Regex.escape(mention(handle)) + "($|[^a-z0-9_])")

    private companion object {
        const val TOP_AREA_RATIO = 0.35
    }
}
