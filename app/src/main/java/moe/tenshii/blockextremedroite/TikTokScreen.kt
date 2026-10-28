package moe.tenshii.blockextremedroite

/** Labels shown by the TikTok app, read from its profile, share sheet and block dialog. */
object TikTokLabels {
    /** Blocking lives in the share sheet opened from the profile's top bar. */
    val OVERFLOW = listOf("partager", "share")
    val BLOCK_ACTION = listOf("bloquer", "block")
    val UNBLOCK_ACTION = listOf("débloquer", "unblock")
    /** Followed by the username, without an `@`. */
    val BLOCKED_NOTICES = listOf("vous avez bloqué ", "you blocked ")
    val UNAVAILABLE = listOf("impossible de trouver ce compte", "couldn't find this account")
    /** A link to a missing or banned account lands on the feed instead of a profile. */
    val FEED = listOf("pour toi", "for you")
}

/** The profile shows `@username`; usernames may hold dots, unlike X handles. */
class TikTokScreen(nodes: List<ScreenNode>, screenHeight: Int, username: String) : BlockScreen(nodes, screenHeight) {
    private val username = Labels.normalize(username)
    private val handlePattern = Regex("(^|[^a-z0-9_.])" + Regex.escape("@${this.username}") + "($|[^a-z0-9_.])")

    override fun showsProfile(): Boolean = nodes.any { node -> node.labels.any(handlePattern::containsMatchIn) }

    override fun isBlockedProfile(): Boolean =
        hasLabel(TikTokLabels.UNBLOCK_ACTION) || hasLabel(TikTokLabels.BLOCKED_NOTICES.map { it + username })

    override fun isUnavailable(): Boolean = hasLabelContaining(TikTokLabels.UNAVAILABLE) || hasLabel(TikTokLabels.FEED)

    override fun overflowButton(): ScreenNode? = topBarButton(TikTokLabels.OVERFLOW)

    override fun blockMenuItem(): ScreenNode? = firstByPriority(TikTokLabels.BLOCK_ACTION)

    override fun hasUnblockMenuItem(): Boolean = hasLabel(TikTokLabels.UNBLOCK_ACTION)

    override fun confirmButton(): ScreenNode? = lowest(TikTokLabels.BLOCK_ACTION)

    override fun blockSucceeded(): Boolean = isBlockedProfile()
}
