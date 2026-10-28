package moe.tenshii.blockextremedroite

import android.content.Context
import androidx.core.content.edit

enum class BlockStatus {
    PENDING,
    BLOCKED,
    ALREADY_BLOCKED,
    UNAVAILABLE,
    FAILED;

    /** Failed accounts stay in the queue: retrying is safe because already-blocked profiles are detected. */
    val isDone: Boolean get() = this == BLOCKED || this == ALREADY_BLOCKED || this == UNAVAILABLE
}

class ProgressStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("progress", Context.MODE_PRIVATE)

    fun status(account: Account): BlockStatus =
        prefs.getString(account.key, null)
            ?.let { name -> BlockStatus.entries.firstOrNull { it.name == name } }
            ?: BlockStatus.PENDING

    fun set(account: Account, status: BlockStatus) {
        prefs.edit { putString(account.key, status.name) }
    }

    fun clear() {
        prefs.edit { clear() }
    }
}

/** [sessionCap] is null when a session runs through the whole queue. */
data class Pacing(val minDelaySeconds: Int, val maxDelaySeconds: Int, val sessionCap: Int?)

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var pacing: Pacing
        get() = Pacing(
            prefs.getInt(KEY_DELAY_MIN, DEFAULT_PACING.minDelaySeconds),
            prefs.getInt(KEY_DELAY_MAX, DEFAULT_PACING.maxDelaySeconds),
            prefs.getInt(KEY_CAP, DEFAULT_CAP).takeIf { it != NO_LIMIT },
        )
        set(value) = prefs.edit {
            putInt(KEY_DELAY_MIN, value.minDelaySeconds)
            putInt(KEY_DELAY_MAX, value.maxDelaySeconds)
            putInt(KEY_CAP, value.sessionCap ?: NO_LIMIT)
        }

    /** Stored as exclusions so categories added to the list later start out selected. */
    var excludedCategories: Set<String>
        get() = prefs.getStringSet(KEY_EXCLUDED, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit { putStringSet(KEY_EXCLUDED, value) }

    /** The accounts left once the excluded sources and categories are filtered out. */
    fun select(accounts: List<Account>): List<Account> {
        val sources = excludedSources
        val categories = excludedCategories
        return accounts.filter { it.source !in sources && it.category !in categories }
    }

    var excludedSources: Set<AccountSource>
        get() = prefs.getStringSet(KEY_EXCLUDED_SOURCES, emptySet()).orEmpty()
            .mapNotNull { name -> AccountSource.entries.firstOrNull { it.name == name } }.toSet()
        set(value) = prefs.edit { putStringSet(KEY_EXCLUDED_SOURCES, value.map { it.name }.toSet()) }

    var listExpanded: Boolean
        get() = prefs.getBoolean(KEY_LIST_EXPANDED, true)
        set(value) = prefs.edit { putBoolean(KEY_LIST_EXPANDED, value) }

    var pacingExpanded: Boolean
        get() = prefs.getBoolean(KEY_PACING_EXPANDED, true)
        set(value) = prefs.edit { putBoolean(KEY_PACING_EXPANDED, value) }

    companion object {
        private const val DEFAULT_CAP = 30
        val DEFAULT_PACING = Pacing(minDelaySeconds = 6, maxDelaySeconds = 12, sessionCap = DEFAULT_CAP)
        const val DELAY_RANGE_MIN = 0
        const val DELAY_RANGE_MAX = 60
        const val CAP_MIN = 5
        const val CAP_MAX = 200
        const val CAP_STEP = 5
        /** The slider's last stop, one step past [CAP_MAX], stands for "no limit". */
        const val CAP_NO_LIMIT_POSITION = CAP_MAX + CAP_STEP
        private const val NO_LIMIT = -1
        private const val KEY_DELAY_MIN = "delay_min"
        private const val KEY_DELAY_MAX = "delay_max"
        private const val KEY_CAP = "session_cap"
        private const val KEY_EXCLUDED = "excluded_categories"
        private const val KEY_EXCLUDED_SOURCES = "excluded_sources"
        private const val KEY_LIST_EXPANDED = "list_expanded"
        private const val KEY_PACING_EXPANDED = "pacing_expanded"
    }
}
