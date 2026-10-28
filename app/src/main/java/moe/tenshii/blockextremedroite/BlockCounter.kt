package moe.tenshii.blockextremedroite

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

data class CounterSnapshot(val total: Long, val perAccount: Map<String, Long>)

/** Plural rules only need the magnitude, so large counts are clamped into an Int. */
fun pluralQuantity(count: Long): Int = count.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

/**
 * The site's collective block counter (`/api/counts`). Reports are queued on disk first so a block
 * made offline is still counted later; the server dedupes on profile + device id.
 */
class BlockCounter private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()

    @Serializable
    private data class CountsResponse(val counts: Map<String, Long>, val total: Long)

    @Serializable
    private data class ReportResponse(val ok: Boolean, val count: Long, val changed: Boolean)

    /** Random v4 UUID, the only identifier the site receives, matching what its own page sends. */
    private val deviceId: String
        get() = prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also { id ->
            prefs.edit { putString(KEY_DEVICE, id) }
        }

    @WorkerThread
    @Throws(IOException::class, IllegalArgumentException::class)
    fun fetch(): CounterSnapshot {
        val response = json.decodeFromString<CountsResponse>(Http.get(COUNTS_URL))
        val perAccount = response.counts.mapNotNull { (key, count) ->
            key.removePrefix(X_PREFIX).takeIf { it.length < key.length }?.let { it to count }
        }.toMap()
        return CounterSnapshot(response.total, perAccount)
    }

    fun report(account: Account) {
        synchronized(prefs) {
            prefs.edit { putStringSet(KEY_QUEUE, pending() + account.siteId) }
        }
        flush()
    }

    /** Sends queued reports in the background; stops at the first failure and keeps the rest for later. */
    fun flush() {
        executor.execute {
            for (siteId in synchronized(prefs) { pending() }) {
                val sent = runCatching { send(siteId) }.getOrDefault(false)
                if (!sent) break
                synchronized(prefs) { prefs.edit { putStringSet(KEY_QUEUE, pending() - siteId) } }
            }
        }
    }

    private fun pending(): Set<String> = prefs.getStringSet(KEY_QUEUE, emptySet()).orEmpty().toSet()

    private fun send(siteId: String): Boolean {
        val body = buildJsonObject {
            put("account_id", X_PREFIX + siteId)
            put("device_id", deviceId)
            put("blocked", true)
        }
        return json.decodeFromString<ReportResponse>(Http.postJson(COUNTS_URL, body.toString())).ok
    }

    companion object {
        private const val COUNTS_URL = "${AccountRepository.SITE}/api/counts"
        private const val X_PREFIX = "x:"
        private const val PREFS = "counter"
        private const val KEY_DEVICE = "device_id"
        private const val KEY_QUEUE = "queue"
        private val json = Json { ignoreUnknownKeys = true }

        @Volatile
        private var instance: BlockCounter? = null

        fun get(context: Context): BlockCounter =
            instance ?: synchronized(this) { instance ?: BlockCounter(context).also { instance = it } }
    }
}
