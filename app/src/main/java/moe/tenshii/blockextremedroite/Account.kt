package moe.tenshii.blockextremedroite

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder
import java.util.Locale

/** Where the site lists an account: its team-verified `accounts.js`, or a community submission. */
enum class AccountSource { CURATED, COMMUNITY }

@Serializable
data class Account(
    /** The site's record key (`id`, else the handle, lowercased), used by its block counter. */
    val siteId: String,
    val name: String,
    val category: String,
    val reason: String,
    val source: AccountSource,
    /** The site's hand-picked photo (`portraits.js`), null for accounts it has none for. */
    val portraitUrl: String?,
    /** Only platforms with a usable profile link are present, so an account always has at least one. */
    val profiles: Map<Platform, Profile>,
) {
    /** The site's portrait when it picked one, else the platform's profile picture through the site's `/avatar` proxy. */
    fun avatarUrl(platform: Platform): String? = portraitUrl ?: profiles[platform]?.let { profile ->
        "${AccountRepository.SITE}/avatar?net=${platform.id}&u=" + URLEncoder.encode(profile.url, Charsets.UTF_8.name())
    }

    /** How the account is named on [platform]: its `@handle` there, else its name. */
    fun label(platform: Platform): String = profiles[platform]?.handle?.let { "@$it" } ?: name
}

/**
 * The site's curated `accounts.js` and `socials.js` (absent from the API), the `/api/accounts` community
 * directory, and the optional `portraits.js` and `followers.js`, null when they couldn't be fetched.
 */
class DirectorySources(
    val accountsScript: String,
    val socialsScript: String,
    val directoryJson: String,
    val portraitsScript: String? = null,
    val followersScript: String? = null,
)

@InternalSerializationApi @Serializable
private data class CuratedAccount(
    val id: String = "",
    val name: String = "",
    val handle: String = "",
    val category: String = "",
    val reason: String = "",
)

@InternalSerializationApi @Serializable
private data class Portrait(val url: String = "")

@InternalSerializationApi @Serializable
private data class FollowerCount(val count: Long? = null)

@InternalSerializationApi @Serializable
private data class Directory(
    val entries: List<DirectoryEntry>,
    val stale: Boolean = false,
)

@InternalSerializationApi @Serializable
private data class DirectoryEntry(
    val type: String,
    val id: String = "",
    @SerialName("target_id") val targetId: String? = null,
    val name: String = "",
    val category: String = "",
    val platform: String? = null,
    @SerialName("profile_url") val profileUrl: String? = null,
    val reason: String = "",
)

object AccountDirectory {
    private val json = Json { ignoreUnknownKeys = true }
    private val KNOWN_CATEGORIES = setOf("RN", "Reconquête", "Politiques", "Partis & mouvements", "Médias", "Influenceurs", "Groupes", "Autres")
    private const val FALLBACK_CATEGORY = "Autres"

    private class Record(
        val key: String,
        val name: String,
        val category: String,
        val reason: String,
        val source: AccountSource,
        val links: MutableMap<Platform, String>,
    )

    /**
     * Rebuilds the site's list: curated accounts and their `socials.js` links, then directory additions and link
     * updates. Records sharing a profile on any platform are one person, like the site's identity groups: the
     * first record keeps its key and details, each platform takes the first usable link, and a removal of any
     * record drops the whole group.
     */
    @OptIn(InternalSerializationApi::class)
    fun build(sources: DirectorySources): List<Account> {
        val curated = json.decodeFromString<List<CuratedAccount>>(literal(sources.accountsScript, '[', ']'))
        val socials = json.decodeFromString<Map<String, JsonObject>>(literal(sources.socialsScript, '{', '}'))
        val portraits = sources.portraitsScript
            ?.let { script -> runCatching { json.decodeFromString<Map<String, Portrait>>(literal(script, '{', '}')) }.getOrNull() }
            .orEmpty()
        val followers = sources.followersScript
            ?.let { script -> runCatching { json.decodeFromString<Map<String, FollowerCount>>(literal(script, '{', '}')) }.getOrNull() }
            .orEmpty()
        val directory = json.decodeFromString<Directory>(sources.directoryJson)
        require(!directory.stale) { "Directory snapshot is stale" }

        val records = LinkedHashMap<String, Record>()
        for (account in curated) {
            val key = account.id.ifEmpty { account.handle }.lowercase(Locale.ROOT)
            if (key.isEmpty() || key in records) continue
            val links = LinkedHashMap<Platform, String>()
            account.handle.takeIf { it.isNotBlank() }?.let { links[Platform.X] = "https://x.com/${it.trim()}" }
            socials[key]?.forEach { (platformId, value) ->
                val platform = Platform.fromId(platformId) ?: return@forEach
                (value as? JsonPrimitive)?.takeIf { it.isString }?.let { links[platform] = it.content }
            }
            records[key] = Record(key, account.name, account.category, account.reason, AccountSource.CURATED, links)
        }

        val removed = HashSet<String>()
        for (entry in directory.entries) {
            if (entry.type == "removal") {
                entry.targetId?.let { removed += it.lowercase(Locale.ROOT) }
                continue
            }
            val key = (if (entry.type == "new") entry.id else entry.targetId.orEmpty()).lowercase(Locale.ROOT)
            if (key.isEmpty()) continue
            val record = records[key] ?: if (entry.type == "new") {
                Record(
                    key = key,
                    name = entry.name,
                    category = entry.category.takeIf { it in KNOWN_CATEGORIES } ?: FALLBACK_CATEGORY,
                    reason = entry.reason,
                    source = AccountSource.COMMUNITY,
                    links = LinkedHashMap(),
                ).also { records[key] = it }
            } else {
                continue
            }
            val platform = entry.platform?.let(Platform::fromId)
            if (platform != null && !entry.profileUrl.isNullOrBlank()) record.links[platform] = entry.profileUrl
        }

        val accounts = ArrayList<Account>()
        for (group in identityGroups(records.values.toList())) {
            if (group.any { it.key in removed }) continue
            val profiles = LinkedHashMap<Platform, Profile>()
            for (platform in Platform.entries) {
                val profile = group.firstNotNullOfOrNull { record -> record.links[platform]?.let(platform::profile) } ?: continue
                profiles[platform] = profile.copy(followers = followers[profile.identity]?.count?.takeIf { it >= 0 })
            }
            if (profiles.isEmpty()) continue
            val first = group.first()
            val fallbackName = profiles.values.firstNotNullOfOrNull { it.handle } ?: first.key
            accounts += Account(
                siteId = first.key,
                name = first.name.trim().ifEmpty { fallbackName },
                category = first.category.trim(),
                reason = first.reason.trim(),
                source = first.source,
                portraitUrl = portraits[first.key]?.url?.trim()?.takeIf { it.startsWith("https://") },
                profiles = profiles,
            )
        }
        require(accounts.isNotEmpty()) { "Account list is empty" }
        return accounts
    }

    /** Unites records that link the same profile; groups and their members keep the records' order. */
    private fun identityGroups(records: List<Record>): Collection<List<Record>> {
        val parents = IntArray(records.size) { it }
        fun find(index: Int): Int {
            var i = index
            while (parents[i] != i) {
                parents[i] = parents[parents[i]]
                i = parents[i]
            }
            return i
        }
        val seen = HashMap<String, Int>()
        records.forEachIndexed { index, record ->
            for ((platform, link) in record.links) {
                val identity = platform.profile(link)?.identity ?: continue
                val other = seen.putIfAbsent(identity, index) ?: continue
                val (a, b) = find(index) to find(other)
                if (a != b) parents[maxOf(a, b)] = minOf(a, b)
            }
        }
        return records.indices.groupBy(::find).values.map { members -> members.map(records::get) }
    }

    /** Extracts the JSON literal from a `const name = [...];` or `{...}` script, skipping any leading comments. */
    private fun literal(script: String, open: Char, close: Char): String {
        val start = script.indexOf('=').let { if (it < 0) -1 else script.indexOf(open, it) }
        val end = script.lastIndexOf(close)
        require(start in 0 until end) { "No JSON literal found" }
        return script.substring(start, end + 1)
    }
}
