package moe.tenshii.blockextremedroite.screen

/** Labels shown by the Instagram app, read from its profile, ⋯ menu and block sheet. */
object InstagramLabels {
    val OVERFLOW = listOf("options")
    val BLOCK_ACTION = listOf("bloquer", "block")
    val UNBLOCK_ACTION = listOf("débloquer", "unblock")
    val BLOCKED_NOTICES = listOf("vous avez bloqué ce compte", "you've blocked this account")
    /** Instagram also says this when a profile you blocked earlier is opened from a link. */
    val UNAVAILABLE = listOf("utilisateur(ice) introuvable", "utilisateur introuvable", "user not found")
}

/** The profile's top bar shows the username without an `@`. */
class InstagramScreen(nodes: List<ScreenNode>, screenHeight: Int, private val username: String) : BlockScreen(nodes, screenHeight) {
    override fun showsProfile(): Boolean = hasLabel(listOf(username))

    override fun isBlockedProfile(): Boolean =
        hasLabel(InstagramLabels.UNBLOCK_ACTION) || hasLabelContaining(InstagramLabels.BLOCKED_NOTICES)

    override fun isUnavailable(): Boolean = hasLabel(InstagramLabels.UNAVAILABLE)

    override fun overflowButton(): ScreenNode? = topBarButton(InstagramLabels.OVERFLOW)

    override fun blockMenuItem(): ScreenNode? = firstByPriority(InstagramLabels.BLOCK_ACTION)

    override fun hasUnblockMenuItem(): Boolean = hasLabel(InstagramLabels.UNBLOCK_ACTION)

    /** The sheet's "Bloquer" sits under its "Bloquer username ?" title and above "Bloquer et signaler". */
    override fun confirmButton(): ScreenNode? = lowest(InstagramLabels.BLOCK_ACTION)

    override fun blockSucceeded(): Boolean = isBlockedProfile()
}
