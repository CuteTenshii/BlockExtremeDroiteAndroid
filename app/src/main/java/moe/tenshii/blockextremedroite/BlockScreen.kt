package moe.tenshii.blockextremedroite

import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/** A visible node of the platform's app, reduced to what the matchers need. */
class ScreenNode(
    text: CharSequence?,
    description: CharSequence?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val source: AccessibilityNodeInfo? = null,
) {
    val labels: List<String> = listOf(Labels.normalize(text), Labels.normalize(description))
        .filter { it.isNotEmpty() }
        .distinct()

    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/** Labels shared by every app. Everything is compared after [normalize]. */
object Labels {
    val LOGGED_OUT = listOf("se connecter", "créer un compte", "log in", "sign in", "create account")
    private val WHITESPACE = Regex("[\\s\\u00A0\\u202F]+")

    fun normalize(value: CharSequence?): String {
        if (value.isNullOrEmpty()) return ""
        return value.toString()
            .replace('\u2019', '\'')
            .replace(WHITESPACE, " ")
            .trim()
            .lowercase(Locale.ROOT)
    }
}

/**
 * One reading of a platform app's screen, made for the profile being blocked. Each app gets a subclass holding
 * its own labels, in French and English; when an app changes its wording, its subclass is the place to update.
 */
abstract class BlockScreen(protected val nodes: List<ScreenNode>, protected val screenHeight: Int) {
    /** The target's profile is loaded. Apps that never show the handle only tell that some profile is. */
    abstract fun showsProfile(): Boolean

    abstract fun isBlockedProfile(): Boolean

    /** The profile is gone, or the app shows a blocked profile the same way as a missing one. */
    abstract fun isUnavailable(): Boolean

    open fun isLoggedOut(): Boolean = nodes.any { node -> node.labels.any { it in Labels.LOGGED_OUT } }

    /** A warning to tap through before the profile shows, like X's sensitive-content gate. */
    open fun sensitiveGate(): ScreenNode? = null

    abstract fun overflowButton(): ScreenNode?

    abstract fun blockMenuItem(): ScreenNode?

    abstract fun hasUnblockMenuItem(): Boolean

    /** The open menu names another profile than the target, so nothing may be blocked from it. */
    open fun menuShowsOtherProfile(): Boolean = false

    abstract fun confirmButton(): ScreenNode?

    abstract fun blockSucceeded(): Boolean

    protected fun hasLabel(candidates: List<String>): Boolean =
        nodes.any { node -> node.labels.any { it in candidates } }

    protected fun hasLabelContaining(candidates: List<String>): Boolean =
        nodes.any { node -> node.labels.any { label -> candidates.any(label::contains) } }

    protected fun firstByPriority(candidates: List<String>): ScreenNode? =
        nodes.mapNotNull { node -> priority(node, candidates)?.let { node to it } }
            .minByOrNull { it.second }
            ?.first

    /** Top-bar buttons only: the same labels lower on screen belong to posts. Ties go to the rightmost. */
    protected fun topBarButton(candidates: List<String>): ScreenNode? {
        val topArea = screenHeight * TOP_AREA_RATIO
        return nodes.filter { it.centerY < topArea }
            .mapNotNull { node -> priority(node, candidates)?.let { node to it } }
            .minWithOrNull(compareBy<Pair<ScreenNode, Int>> { it.second }.thenByDescending { it.first.right })
            ?.first
    }

    /** Dialog buttons sit below the dialog's title, which often repeats the same words. */
    protected fun lowest(candidates: List<String>): ScreenNode? =
        nodes.filter { node -> node.labels.any { it in candidates } }.maxByOrNull { it.bottom }

    private fun priority(node: ScreenNode, candidates: List<String>): Int? =
        node.labels.mapNotNull { label -> candidates.indexOf(label).takeIf { it >= 0 } }.minOrNull()

    companion object {
        private const val TOP_AREA_RATIO = 0.35

        /** [foregroundPackage] is the app in front, which differs from the platform's when it handed the link off. */
        fun of(platform: Platform, profile: Profile, nodes: List<ScreenNode>, screenHeight: Int, foregroundPackage: String?): BlockScreen =
            when (platform) {
                Platform.X -> XScreen(nodes, screenHeight, requireNotNull(profile.handle))
                Platform.INSTAGRAM -> InstagramScreen(nodes, screenHeight, requireNotNull(profile.handle))
                Platform.TIKTOK -> TikTokScreen(nodes, screenHeight, requireNotNull(profile.handle))
                Platform.FACEBOOK -> FacebookScreen(nodes, screenHeight, profile.url)
                Platform.YOUTUBE -> YouTubeScreen(nodes, screenHeight, foregroundPackage)
            }
    }
}
