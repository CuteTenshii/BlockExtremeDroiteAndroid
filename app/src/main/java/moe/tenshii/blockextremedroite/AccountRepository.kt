package moe.tenshii.blockextremedroite

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** Keeps the merged account list in app storage; it is downloaded on first launch and on each update. */
class AccountRepository(context: Context) {
    private val appContext = context.applicationContext
    private val listFile = File(appContext.filesDir, LIST_FILE)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Epoch millis of the last successful download. */
    val lastUpdated: Long get() = prefs.getLong(KEY_UPDATED_AT, 0L)

    /** Returns the saved list, downloading it first when nothing usable is saved yet. */
    @WorkerThread
    @Throws(IOException::class, IllegalArgumentException::class)
    fun load(): List<Account> {
        if (listFile.isFile) {
            runCatching { return json.decodeFromString<List<Account>>(listFile.readText()) }
        }
        return refresh()
    }

    /** Downloads the live sources; the saved list is only replaced once they combine into a valid one. */
    @WorkerThread
    @Throws(IOException::class, IllegalArgumentException::class)
    fun refresh(): List<Account> {
        val accounts = AccountDirectory.build(
            DirectorySources(
                accountsScript = Http.get("$SITE/accounts.js"),
                directoryJson = Http.get("$SITE/api/accounts"),
            ),
        )
        val temp = File(appContext.filesDir, "$LIST_FILE.tmp")
        temp.writeText(json.encodeToString(accounts))
        if (!temp.renameTo(listFile)) {
            temp.delete()
            throw IOException("Could not save the account list")
        }
        prefs.edit { putLong(KEY_UPDATED_AT, System.currentTimeMillis()) }
        return accounts
    }

    companion object {
        const val SITE = "https://onbloquelextremedroite.fun"
        private const val LIST_FILE = "accounts.json"
        private const val PREFS = "accounts"
        private const val KEY_UPDATED_AT = "updated_at"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
