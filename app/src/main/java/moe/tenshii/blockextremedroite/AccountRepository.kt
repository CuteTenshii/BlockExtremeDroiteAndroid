package moe.tenshii.blockextremedroite

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One deck batch: the whole saved list, the accounts it brought or completed on the asked platform, and why it may be the last for now. */
class DeckBatch(val accounts: List<Account>, val added: List<Account>, val quotaReached: Boolean, val exhausted: Boolean)

/**
 * Keeps the account list in app storage. The site hands it out card by card (`/api/deck`): a batch is asked for
 * when the profiles left to block run low, never ahead of time, and each one is saved as soon as it arrives.
 * Lists saved before the deck keep the site's record keys, so the deck only adds what they lack.
 */
class AccountRepository(context: Context) {
    private val appContext = context.applicationContext
    private val listFile = File(appContext.filesDir, LIST_FILE)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val session = SiteSession(appContext)
    private val progress = ProgressStore(appContext)

    /** How many accounts the site lists on [platform] in [category], or all of them when null, as of the last batch. */
    fun total(platform: Platform, category: SiteCategory?): Int? =
        prefs.getInt(totalKey(platform, category?.key ?: ALL_CATEGORIES), -1).takeIf { it >= 0 }

    /** Whether the site said today it has nothing more on [platform] in [category]. */
    fun isExhausted(platform: Platform, category: SiteCategory?): Boolean =
        prefs.getString(exhaustedKey(platform, category), null) == today()

    /** The site's quota is daily, so once it is reached nothing more comes until tomorrow. */
    val quotaReachedToday: Boolean get() = prefs.getString(KEY_QUOTA_DAY, null) == today()

    /** Whether asking the site for a batch on [platform] in [category] can bring anything today. */
    fun canFetch(platform: Platform, category: SiteCategory?): Boolean =
        session.current != null && !quotaReachedToday && !isExhausted(platform, category)

    /** Returns the saved list, empty until a first batch has arrived. */
    @WorkerThread
    fun load(): List<Account> = read()

    /**
     * Asks the site for the next [DECK_BATCH] cards on [platform], in [category] or all of them when null.
     * Throws [SessionRequiredException] when the anti-bot check has to be passed again.
     */
    @WorkerThread
    @Throws(IOException::class, IllegalArgumentException::class, SessionRequiredException::class)
    fun fetchBatch(platform: Platform, category: SiteCategory?): DeckBatch = synchronized(FETCH_LOCK) {
        val headers = session.headers()
        val before = read()
        val page = try {
            Deck.parse(Http.postJson(DECK_URL, deckRequest(platform, category, before), headers))
        } catch (e: HttpException) {
            if (e.code == HTTP_UNAUTHORIZED) {
                session.clear()
                throw SessionRequiredException()
            }
            throw e
        }
        val accounts = Deck.merge(before, platform, page.cards)
        write(accounts)
        val heldBefore = before.filter { platform in it.profiles }.mapTo(HashSet()) { it.siteId }
        val added = accounts.filter { platform in it.profiles && it.siteId !in heldBefore }
        // The site sends nothing more once the asked category is done, until it lists someone new.
        val exhausted = added.isEmpty() && !page.quotaReached
        prefs.edit {
            for ((platform, byCategory) in page.totals) {
                byCategory.forEach { (key, total) -> putInt(totalKey(platform, key), total) }
            }
            if (page.quotaReached) putString(KEY_QUOTA_DAY, today()) else remove(KEY_QUOTA_DAY)
            if (exhausted) putString(exhaustedKey(platform, category), today()) else remove(exhaustedKey(platform, category))
        }
        DeckBatch(accounts, added, page.quotaReached, exhausted)
    }

    /**
     * Mirrors the site's page: `have` lists what the app already holds on [platform] so the server skips it,
     * and `marks` the profiles already blocked.
     */
    private fun deckRequest(platform: Platform, category: SiteCategory?, accounts: List<Account>): String = buildJsonObject {
        put("net", platform.id)
        put("cat", category?.key ?: ALL_CATEGORIES)
        put("n", DECK_BATCH)
        putJsonArray("marks") { marks(accounts).forEach(::add) }
        putJsonArray("have") {
            accounts.filter { platform in it.profiles }.flatMap { it.ids }.distinct().forEach(::add)
        }
    }.toString()

    private fun marks(accounts: List<Account>): List<String> = accounts.flatMap { account ->
        account.profiles.filter { (_, profile) -> progress.status(profile).isBlocked }
            .map { (platform, _) -> "${platform.id}:${account.siteId}" }
    }

    private fun read(): List<Account> {
        if (!listFile.isFile) return emptyList()
        return runCatching { json.decodeFromString<List<Account>>(listFile.readText()) }.getOrDefault(emptyList())
    }

    private fun write(accounts: List<Account>) {
        val temp = File(appContext.filesDir, "$LIST_FILE.tmp")
        temp.writeText(json.encodeToString(accounts))
        if (!temp.renameTo(listFile)) {
            temp.delete()
            throw IOException("Could not save the account list")
        }
    }

    companion object {
        const val SITE = "https://onbloquelextremedroite.fun"
        private const val DECK_URL = "$SITE/api/deck"
        /** The batch size the site's page asks for. */
        const val DECK_BATCH = 8
        private const val ALL_CATEGORIES = "all"
        private const val HTTP_UNAUTHORIZED = 401
        private const val LIST_FILE = "accounts.json"
        private const val PREFS = "accounts"
        private const val KEY_QUOTA_DAY = "quota_day"
        private val FETCH_LOCK = Any()
        private val json = Json { ignoreUnknownKeys = true }

        private fun totalKey(platform: Platform, categoryKey: String) = "total_${platform.id}_$categoryKey"

        private fun exhaustedKey(platform: Platform, category: SiteCategory?) =
            "exhausted_${platform.id}_${category?.key ?: ALL_CATEGORIES}"

        private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
    }
}
