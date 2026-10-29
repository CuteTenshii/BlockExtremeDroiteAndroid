package moe.tenshii.blockextremedroite

import androidx.annotation.StringRes
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.net.URLEncoder
import java.util.Locale

/** The site's four filter categories; [key] is what its deck sends as a card's `cat` and takes as a request's `cat`. */
enum class SiteCategory(val key: String, @param:StringRes val label: Int) {
    POLITICS("pol", R.string.category_politics),
    PARTIES("partis", R.string.category_parties),
    MEDIA("medias", R.string.category_media),
    INFLUENCERS("influ", R.string.category_influencers);

    companion object {
        fun fromKey(key: String?): SiteCategory? = entries.firstOrNull { it.key == key }

        /** Lists saved before the deck only have the site's old labels; those without a match stay uncategorised, like on the site. */
        fun fromLabel(label: String): SiteCategory? = when (label) {
            "RN", "Reconquête", "Politiques" -> POLITICS
            "Partis & mouvements" -> PARTIES
            "Médias" -> MEDIA
            "Influenceurs" -> INFLUENCERS
            else -> null
        }
    }
}

@Serializable
data class Account(
    /** The site's record key, used by its block counter. */
    val siteId: String,
    /** The other record keys the site groups under this person; lists saved before the deck have none. */
    val aliases: List<String> = emptyList(),
    val name: String,
    /** The site's descriptive label (`RN`, `Médias`…), shown with the account. */
    val category: String,
    val reason: String,
    /** The site's filter category key; null in lists saved before the deck. */
    val cat: String? = null,
    /** Only platforms with a usable profile link are present, so an account always has at least one. */
    val profiles: Map<Platform, Profile>,
) {
    /** Every record key the site knows this person by, as its deck's `have` and its counter use them. */
    val ids: List<String> get() = (listOf(siteId) + aliases).distinct()

    val siteCategory: SiteCategory? get() = SiteCategory.fromKey(cat) ?: SiteCategory.fromLabel(category)

    /** The platform's profile picture through the site's `/avatar` proxy. */
    fun avatarUrl(platform: Platform): String? = profiles[platform]?.let { profile ->
        "${AccountRepository.SITE}/avatar?net=${platform.id}&u=" + URLEncoder.encode(profile.url, Charsets.UTF_8.name())
    }

    /** How the account is named on [platform]: its `@handle` there, else its name. */
    fun label(platform: Platform): String = profiles[platform]?.handle?.let { "@$it" } ?: name
}

/**
 * One `/api/deck` answer: the cards for the asked platform, the site's totals per platform keyed by category
 * ([SiteCategory.key], or `all`), and the daily quota flag.
 */
class DeckPage(val cards: List<JsonElement>, val totals: Map<Platform, Map<String, Int>>, val quotaReached: Boolean)

@InternalSerializationApi @Serializable
private data class DeckResponse(
    val cards: List<JsonElement>,
    val total: Map<String, Map<String, Int>>,
    val quota: Boolean = false,
)

@InternalSerializationApi @Serializable
private data class DeckCard(
    val id: String,
    val name: String,
    val aliases: List<String>,
    val links: Map<String, String?> = emptyMap(),
    val category: String = "",
    val reason: String = "",
    val cat: String? = null,
    val follower: FollowerCount? = null,
)

@InternalSerializationApi @Serializable
private data class FollowerCount(val count: Long? = null)

/** Reads the site's `/api/deck` answers and folds their cards into the saved list. */
object Deck {
    private val json = Json { ignoreUnknownKeys = true }
    private const val FALLBACK_CATEGORY = "Autres"

    @OptIn(InternalSerializationApi::class)
    fun parse(body: String): DeckPage {
        val response = json.decodeFromString<DeckResponse>(body)
        val totals = response.total.mapNotNull { (platformId, byCategory) ->
            Platform.fromId(platformId)?.let { it to byCategory }
        }.toMap()
        return DeckPage(response.cards, totals, response.quota)
    }

    /**
     * Adds [cards] sent for [platform] to [accounts]. A card whose ids match a saved account is that person:
     * it fills in their profile on [platform] and leaves their details as first received. Cards that
     * are malformed or lack a usable profile link on [platform] are skipped, like the site's own page does.
     */
    @OptIn(InternalSerializationApi::class)
    fun merge(accounts: List<Account>, platform: Platform, cards: List<JsonElement>): List<Account> {
        val merged = accounts.toMutableList()
        val byAlias = HashMap<String, Int>()
        merged.forEachIndexed { index, account -> account.ids.forEach { byAlias[it] = index } }
        for (element in cards) {
            val card = runCatching { json.decodeFromJsonElement(DeckCard.serializer(), element) }.getOrNull() ?: continue
            val siteId = card.id.lowercase(Locale.ROOT)
            if (siteId.isEmpty()) continue
            val link = card.links[platform.id] ?: continue
            val profile = platform.profile(link)?.copy(followers = card.follower?.count?.takeIf { it >= 0 }) ?: continue
            val aliases = (listOf(siteId) + card.aliases.map { it.lowercase(Locale.ROOT) }).distinct()
            val index = aliases.firstNotNullOfOrNull(byAlias::get)
            if (index != null) {
                val existing = merged[index]
                merged[index] = existing.copy(
                    aliases = (existing.aliases + aliases).distinct() - existing.siteId,
                    cat = existing.cat ?: card.cat,
                    profiles = existing.profiles + (platform to profile),
                )
                aliases.forEach { byAlias[it] = index }
            } else {
                merged += Account(
                    siteId = siteId,
                    aliases = aliases - siteId,
                    name = card.name.trim().ifEmpty { profile.handle ?: siteId },
                    category = card.category.trim().ifEmpty { FALLBACK_CATEGORY },
                    reason = card.reason.trim(),
                    cat = card.cat,
                    profiles = mapOf(platform to profile),
                )
                aliases.forEach { byAlias[it] = merged.lastIndex }
            }
        }
        return merged
    }
}
