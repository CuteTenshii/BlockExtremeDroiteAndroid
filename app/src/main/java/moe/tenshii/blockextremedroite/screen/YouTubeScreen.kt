package moe.tenshii.blockextremedroite.screen

import moe.tenshii.blockextremedroite.Platform

/**
 * Labels shown by the YouTube app. YouTube has no block: "hide user from my channel" is its closest action,
 * removing their comments from the viewer's channel.
 */
object YouTubeLabels {
    val OVERFLOW = listOf("plus", "show more")
    val HIDE_ACTION = listOf("masquer l'utilisateur sur ma chaîne", "hide user from my channel")
    val UNHIDE_ACTION = listOf("afficher l'utilisateur sur ma chaîne", "unhide user from my channel")
    val CONFIRM = listOf("masquer l'utilisateur", "hide user")
    val HIDDEN_NOTICES = listOf("utilisateur masqué", "user hidden")
    val UNAVAILABLE = listOf("cette chaîne n'existe pas", "this channel doesn't exist")
}

/**
 * The channel page doesn't expose its `@handle`, so any loaded channel counts as the target's. YouTube hands links
 * to channels it can't find to the browser, which shows up as [foregroundPackage] leaving YouTube.
 */
class YouTubeScreen(nodes: List<ScreenNode>, screenHeight: Int, private val foregroundPackage: String?) :
    BlockScreen(nodes, screenHeight) {

    override fun showsProfile(): Boolean = overflowButton() != null

    /** Only the menu tells whether a channel is hidden. */
    override fun isBlockedProfile(): Boolean = false

    override fun isUnavailable(): Boolean =
        hasLabelContaining(YouTubeLabels.UNAVAILABLE) ||
            (foregroundPackage != null && foregroundPackage != Platform.YOUTUBE.packageName && foregroundPackage !in SYSTEM_PACKAGES)

    override fun overflowButton(): ScreenNode? = topBarButton(YouTubeLabels.OVERFLOW)

    override fun blockMenuItem(): ScreenNode? = firstByPriority(YouTubeLabels.HIDE_ACTION)

    override fun hasUnblockMenuItem(): Boolean = hasLabel(YouTubeLabels.UNHIDE_ACTION)

    /** The dialog's title and confirm button share the same words; the button is below. */
    override fun confirmButton(): ScreenNode? = lowest(YouTubeLabels.CONFIRM)

    override fun blockSucceeded(): Boolean = hasLabelContaining(YouTubeLabels.HIDDEN_NOTICES)

    private companion object {
        /** Windows that cover YouTube without it having handed anything off. */
        val SYSTEM_PACKAGES = setOf("com.android.systemui", "com.google.android.permissioncontroller", "com.android.permissioncontroller")
    }
}
