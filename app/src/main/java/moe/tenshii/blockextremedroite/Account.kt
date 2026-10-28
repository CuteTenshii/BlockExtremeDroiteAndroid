package moe.tenshii.blockextremedroite

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.Locale

@Serializable
data class Account(
    /** The site's record key (`id`, else the handle, lowercased), used by its block counter. */
    val siteId: String,
    val handle: String,
    val name: String,
    val category: String,
    val reason: String,
) {
    /** X handles are case-insensitive, so progress is keyed on the lowercase form. */
    val key: String
        get() = handle.lowercase(Locale.ROOT)
}

/** The site's curated `accounts.js` (absent from the API) and the `/api/accounts` community directory. */
class DirectorySources(val accountsScript: String, val directoryJson: String)

@InternalSerializationApi @Serializable
private data class CuratedAccount(
    val id: String = "",
    val name: String = "",
    val handle: String = "",
    val category: String = "",
    val reason: String = "",
)

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
    val platform: String = "",
    @SerialName("profile_url") val profileUrl: String? = null,
    val reason: String = "",
)

object AccountDirectory {
    private val json = Json { ignoreUnknownKeys = true }
    private val HANDLE = Regex("[A-Za-z0-9_]{1,15}")
    private val X_HOSTS = setOf("x.com", "twitter.com")
    private val RESERVED_PATHS = setOf("home", "explore", "search", "i", "intent", "hashtag", "settings", "messages", "notifications")
    private val KNOWN_CATEGORIES = setOf("RN", "Reconquête", "Politiques", "Partis & mouvements", "Médias", "Influenceurs", "Groupes", "Autres")
    private const val FALLBACK_CATEGORY = "Autres"

    private class Record(val name: String, val category: String, val reason: String, var xLink: String?)

    /**
     * Rebuilds the site's X list: curated accounts, then directory additions and link updates.
     * A removal drops every record that resolves to the same X handle, like the site's identity groups.
     */
    @OptIn(InternalSerializationApi::class)
    fun build(sources: DirectorySources): List<Account> {
        val curated = json.decodeFromString<List<CuratedAccount>>(arrayLiteral(sources.accountsScript))
        val directory = json.decodeFromString<Directory>(sources.directoryJson)
        require(!directory.stale) { "Directory snapshot is stale" }

        val records = LinkedHashMap<String, Record>()
        for (account in curated) {
            val key = account.id.ifEmpty { account.handle }.lowercase(Locale.ROOT)
            if (key.isEmpty() || key in records) continue
            val xLink = account.handle.takeIf { it.isNotBlank() }?.let { "https://x.com/${it.trim()}" }
            records[key] = Record(account.name, account.category, account.reason, xLink)
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
                    name = entry.name,
                    category = entry.category.takeIf { it in KNOWN_CATEGORIES } ?: FALLBACK_CATEGORY,
                    reason = entry.reason,
                    xLink = null,
                ).also { records[key] = it }
            } else {
                continue
            }
            if (entry.platform == "x" && !entry.profileUrl.isNullOrBlank()) record.xLink = entry.profileUrl
        }

        val byHandle = LinkedHashMap<String, Account>()
        val removedHandles = HashSet<String>()
        for ((key, record) in records) {
            val handle = record.xLink?.let(::handleFromLink) ?: continue
            val account = Account(key, handle, record.name.trim().ifEmpty { handle }, record.category.trim(), record.reason.trim())
            if (key in removed) removedHandles += account.key
            byHandle.putIfAbsent(account.key, account)
        }
        val accounts = byHandle.values.filter { it.key !in removedHandles }
        require(accounts.isNotEmpty()) { "Account list is empty" }
        return accounts
    }

    /** Returns the handle of an `https://x.com/<handle>` profile link, or null for anything else. */
    fun handleFromLink(link: String): String? {
        val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")?.removePrefix("mobile.")?.removePrefix("m.")
        if (host !in X_HOSTS) return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        val handle = segments.singleOrNull()?.removePrefix("@") ?: return null
        if (!HANDLE.matches(handle) || handle.lowercase(Locale.ROOT) in RESERVED_PATHS) return null
        return handle
    }

    /** Extracts the JSON array from a `const accounts = [...];` script, skipping any leading comments. */
    private fun arrayLiteral(script: String): String {
        val start = script.indexOf('=').let { if (it < 0) -1 else script.indexOf('[', it) }
        val end = script.lastIndexOf(']')
        require(start in 0 until end) { "No JSON literal found" }
        return script.substring(start, end + 1)
    }
}
