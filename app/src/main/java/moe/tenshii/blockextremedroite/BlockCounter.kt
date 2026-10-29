package moe.tenshii.blockextremedroite

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.concurrent.Executors

/** [counts] is keyed like the site's counter, `platform:siteId`. */
data class CounterSnapshot(val total: Long, private val counts: Map<String, Long>) {
    /** Sums the person's records like the site does, null when the counter knows none of them. */
    fun count(account: Account, platform: Platform): Long? =
        account.ids.mapNotNull { counts[counterKey(it, platform)] }.takeIf { it.isNotEmpty() }?.sum()
}

private fun counterKey(siteId: String, platform: Platform) = "${platform.id}:$siteId"

/** Plural rules only need the magnitude, so large counts are clamped into an Int. */
fun pluralQuantity(count: Long): Int = count.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

/**
 * The site's collective block counter (`/api/counts`). Reports are queued on disk first so a block made
 * offline, or while the site session has expired, is still counted later; the server dedupes on profile + device id.
 */
class BlockCounter private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val session = SiteSession(context)
    private val executor = Executors.newSingleThreadExecutor()

    init {
        migrateXQueue()
    }

    @Serializable
    private data class CountsResponse(val counts: Map<String, Long>, val total: Long)

    @Serializable
    private data class ReportResponse(val ok: Boolean, val count: Long, val changed: Boolean)

    /** The last snapshot fetched by any screen, so another one can show counts without fetching again. */
    @Volatile
    var latest: CounterSnapshot? = null
        private set

    @WorkerThread
    @Throws(IOException::class, IllegalArgumentException::class)
    fun fetch(): CounterSnapshot {
        val response = json.decodeFromString<CountsResponse>(Http.get(COUNTS_URL))
        return CounterSnapshot(response.total, response.counts).also { latest = it }
    }

    fun report(account: Account, platform: Platform) {
        val key = counterKey(account.siteId, platform)
        synchronized(prefs) {
            prefs.edit {
                putStringSet(KEY_QUEUE, pending() + key)
                account.cardTokens[platform]?.let { putString(tokenKey(key), it) }
            }
        }
        flush()
    }

    /** Sends queued reports in the background; stops at the first failure and keeps the rest for later. */
    fun flush() {
        executor.execute {
            for (key in synchronized(prefs) { pending() }) {
                val sent = runCatching { send(key) }.getOrDefault(false)
                if (!sent) break
                synchronized(prefs) {
                    prefs.edit {
                        putStringSet(KEY_QUEUE, pending() - key)
                        remove(tokenKey(key))
                    }
                }
            }
        }
    }

    private fun tokenKey(key: String) = "$KEY_TOKEN_PREFIX$key"

    private fun pending(): Set<String> = prefs.getStringSet(KEY_QUEUE, emptySet()).orEmpty().toSet()

    /** Versions that only knew X queued bare site ids. */
    private fun migrateXQueue() {
        synchronized(prefs) {
            val queue = pending()
            val legacy = queue.filter { ':' !in it }
            if (legacy.isNotEmpty()) {
                prefs.edit { putStringSet(KEY_QUEUE, queue - legacy.toSet() + legacy.map { counterKey(it, Platform.X) }) }
            }
        }
    }

    private fun send(key: String): Boolean {
        val headers = session.headers()
        val token = prefs.getString(tokenKey(key), null)
        val body = buildJsonObject {
            put("account_id", key)
            put("device_id", session.deviceId)
            put("blocked", true)
            // Lists saved before the deck have no tokens; the site's page also leaves the field out when it has none.
            if (token != null) put("card_token", token)
        }
        return try {
            json.decodeFromString<ReportResponse>(Http.postJson(COUNTS_URL, body.toString(), headers)).ok
        } catch (e: HttpException) {
            if (e.code == HTTP_UNAUTHORIZED) session.clear()
            throw e
        }
    }

    companion object {
        private const val COUNTS_URL = "${AccountRepository.SITE}/api/counts"
        private const val PREFS = "counter"
        private const val KEY_QUEUE = "queue"
        private const val KEY_TOKEN_PREFIX = "token:"
        private const val HTTP_UNAUTHORIZED = 401
        private val json = Json { ignoreUnknownKeys = true }

        @Volatile
        private var instance: BlockCounter? = null

        fun get(context: Context): BlockCounter =
            instance ?: synchronized(this) { instance ?: BlockCounter(context).also { instance = it } }
    }
}
