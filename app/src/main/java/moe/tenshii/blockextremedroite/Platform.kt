package moe.tenshii.blockextremedroite

import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/** A network the site lists accounts on, blocked through its official app. [id] is the site's own key. */
enum class Platform(val id: String, val label: String, val packageName: String, private val hosts: Set<String>) {
    X("x", "X", "com.twitter.android", setOf("x.com", "twitter.com")),
    INSTAGRAM("instagram", "Instagram", "com.instagram.android", setOf("instagram.com")),
    FACEBOOK("facebook", "Facebook", "com.facebook.katana", setOf("facebook.com")),
    TIKTOK("tiktok", "TikTok", "com.zhiliaoapp.musically", setOf("tiktok.com")),
    YOUTUBE("youtube", "YouTube", "com.google.android.youtube", setOf("youtube.com"));

    /**
     * Reads a direct profile link the way the site's `identityForLink` does, so two records linking the same
     * profile get the same [Profile.identity]. Search pages, posts and other non-profile links give null.
     */
    fun profile(link: String): Profile? {
        val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host?.lowercase(Locale.ROOT)?.replace(HOST_PREFIX, "")
        if (host !in hosts) return null
        val originalPath = uri.path.orEmpty().trimEnd('/')
        val path = originalPath.lowercase(Locale.ROOT)
        return when (this) {
            X -> {
                val handle = originalPath.removePrefix("/").removePrefix("@")
                if (!X_HANDLE.matches(handle) || handle.lowercase(Locale.ROOT) in X_RESERVED) return null
                Profile(identity(handle.lowercase(Locale.ROOT)), "https://x.com/$handle", handle)
            }
            INSTAGRAM -> {
                val username = path.removePrefix("/")
                if (!INSTAGRAM_USERNAME.matches(username) || username in INSTAGRAM_RESERVED) return null
                Profile(identity(username), "https://www.instagram.com/$username/", username)
            }
            TIKTOK -> {
                val username = path.removePrefix("/@").takeIf { path.startsWith("/@") } ?: return null
                if (!TIKTOK_USERNAME.matches(username)) return null
                Profile(identity("@$username"), "https://www.tiktok.com/@$username", username)
            }
            YOUTUBE -> when {
                YOUTUBE_HANDLE.matches(originalPath) ->
                    Profile(identity(path.removePrefix("/")), "https://www.youtube.com$originalPath", originalPath.substring(2))
                YOUTUBE_CHANNEL.matches(originalPath) ->
                    Profile(identity(originalPath.removePrefix("/")), "https://www.youtube.com$originalPath", null)
                else -> null
            }
            FACEBOOK -> {
                val numericId = if (path == "/profile.php") {
                    queryParameter(uri, "id")?.takeIf(DIGITS::matches) ?: return null
                } else {
                    FACEBOOK_NUMERIC.matchEntire(path)?.groupValues?.get(1)
                }
                when {
                    numericId != null -> Profile("$id:id:$numericId", "https://www.facebook.com/profile.php?id=$numericId", null)
                    FACEBOOK_NAME.matches(path) -> Profile(identity(path.removePrefix("/")), "https://www.facebook.com$path", null)
                    else -> null
                }
            }
        }
    }

    /** The link handed to the app. Facebook only takes web links wrapped in its own scheme. */
    fun appLink(profile: Profile): String = when (this) {
        FACEBOOK -> "fb://facewebmodal/f?href=" + URLEncoder.encode(profile.url, Charsets.UTF_8.name())
        else -> profile.url
    }

    private fun identity(path: String) = "$id:/$path"

    companion object {
        fun fromId(id: String): Platform? = entries.firstOrNull { it.id == id }

        private val HOST_PREFIX = Regex("^(www|m|mobile|fr-fr)\\.")
        private val DIGITS = Regex("\\d+")
        private val X_HANDLE = Regex("[A-Za-z0-9_]{1,15}")
        private val X_RESERVED = setOf("home", "explore", "search", "i", "intent", "hashtag", "settings", "messages", "notifications")
        private val INSTAGRAM_USERNAME = Regex("[a-z0-9._]{1,30}")
        private val INSTAGRAM_RESERVED = setOf("p", "reel", "reels", "stories", "explore", "accounts", "direct")
        private val TIKTOK_USERNAME = Regex("[a-z0-9._]{2,24}")
        private val YOUTUBE_HANDLE = Regex("/@[A-Za-z0-9._-]{3,30}")
        private val YOUTUBE_CHANNEL = Regex("/channel/UC[A-Za-z0-9_-]{22}")
        private val FACEBOOK_NUMERIC = Regex("/(?:pages|people)/[a-z0-9._%-]+/(\\d+)")
        private val FACEBOOK_NAME = Regex("/(?:[a-z0-9._-]{3,100}|p/[a-z0-9._-]{3,100})")

        private fun queryParameter(uri: URI, name: String): String? =
            uri.rawQuery?.split('&')?.firstNotNullOfOrNull { pair ->
                pair.split('=', limit = 2).takeIf { it.size == 2 && it[0] == name }?.get(1)
            }
    }
}

@Serializable
data class Profile(
    /** The site's key for this profile (`instagram:/marine_lepen`), shared by every record that links it. */
    val identity: String,
    /** Canonical link, opened in the platform's app. */
    val url: String,
    /** The name the app shows with an `@`; null when the link only gives a page name or a numeric id. */
    val handle: String?,
    /** From the site's dated, partial `followers.js` survey; null when it has no figure. */
    val followers: Long? = null,
)
