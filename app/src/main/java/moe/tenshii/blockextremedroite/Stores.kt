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
    val isDone: Boolean get() = isBlocked || this == UNAVAILABLE

    val isBlocked: Boolean get() = this == BLOCKED || this == ALREADY_BLOCKED
}

/** Keyed on [Profile.identity], so a profile listed under two records is only processed once. */
class ProgressStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("progress", Context.MODE_PRIVATE)

    init {
        migrateXHandles()
    }

    fun status(profile: Profile): BlockStatus =
        prefs.getString(profile.identity, null)
            ?.let { name -> BlockStatus.entries.firstOrNull { it.name == name } }
            ?: BlockStatus.PENDING

    fun set(profile: Profile, status: BlockStatus) {
        prefs.edit { putString(profile.identity, status.name) }
    }

    fun clear(platform: Platform) {
        val prefix = platform.id + ":"
        prefs.edit { prefs.all.keys.filter { it.startsWith(prefix) }.forEach(::remove) }
    }

    /** Versions that only knew X keyed progress on the bare lowercase handle. */
    private fun migrateXHandles() {
        val legacy = prefs.all.filter { (key, value) -> ':' !in key && value is String }
        if (legacy.isEmpty()) return
        prefs.edit {
            for ((handle, status) in legacy) {
                remove(handle)
                putString("${Platform.X.id}:/$handle", status as String)
            }
        }
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

    /** Stored as exclusions so every category starts out selected. */
    var excludedCategories: Set<SiteCategory>
        get() = prefs.getStringSet(KEY_EXCLUDED, emptySet()).orEmpty().mapNotNull(SiteCategory::fromKey).toSet()
        set(value) = prefs.edit { putStringSet(KEY_EXCLUDED, value.map { it.key }.toSet()) }

    /** The single category to ask the site's deck for, or null when several are selected. */
    val deckCategory: SiteCategory?
        get() = (SiteCategory.entries - excludedCategories).singleOrNull()

    var platform: Platform
        get() = prefs.getString(KEY_PLATFORM, null)?.let(Platform::fromId) ?: Platform.X
        set(value) = prefs.edit { putString(KEY_PLATFORM, value.id) }

    /** The accounts listed on [platform], less the excluded categories; uncategorised ones only count while none is excluded, like on the site. */
    fun select(accounts: List<Account>, platform: Platform = this.platform): List<Account> {
        val excluded = excludedCategories
        return accounts.filter { account ->
            platform in account.profiles && account.siteCategory.let { if (it == null) excluded.isEmpty() else it !in excluded }
        }
    }

    var listExpanded: Boolean
        get() = prefs.getBoolean(KEY_LIST_EXPANDED, true)
        set(value) = prefs.edit { putBoolean(KEY_LIST_EXPANDED, value) }

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
        /** Not the old `excluded_categories`, which held the site's former labels. */
        private const val KEY_EXCLUDED = "excluded_site_categories"
        private const val KEY_LIST_EXPANDED = "list_expanded"
        private const val KEY_PLATFORM = "platform"
    }
}
