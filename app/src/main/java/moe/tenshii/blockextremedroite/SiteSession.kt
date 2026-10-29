package moe.tenshii.blockextremedroite

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/** Thrown when the site's API needs a session the app doesn't have; the user must pass the anti-bot check again. */
class SessionRequiredException : Exception("A site session is required")

/**
 * The device id and anti-bot session the site's API asks for. The session comes from a Turnstile check
 * ([moe.tenshii.blockextremedroite.activity.VerifyDialog]) and starts with its expiry in epoch millis.
 */
class SiteSession(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Random v4 UUID, the only identifier the site receives, matching what its own page sends. */
    val deviceId: String
        get() = synchronized(LOCK) {
            prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also { id ->
                prefs.edit { putString(KEY_DEVICE, id) }
            }
        }

    /** The stored session while it has more than a minute left, else null. */
    val current: String?
        get() = prefs.getString(KEY_SESSION, null)?.takeIf { expiry(it) > System.currentTimeMillis() + MIN_REMAINING_MS }

    fun save(session: String) {
        prefs.edit { putString(KEY_SESSION, session) }
    }

    /** Drops a session the server refused, so the next request asks for a new check instead of failing again. */
    fun clear() {
        prefs.edit { remove(KEY_SESSION) }
    }

    /** The headers the API needs, or throws when there is no usable session. */
    @Throws(SessionRequiredException::class)
    fun headers(): Map<String, String> {
        val session = current ?: throw SessionRequiredException()
        return mapOf(HEADER_SESSION to session, HEADER_DEVICE to deviceId)
    }

    companion object {
        /** Shared with [BlockCounter], which created the device id before sessions existed. */
        private const val PREFS = "counter"
        private const val KEY_DEVICE = "device_id"
        private const val KEY_SESSION = "session"
        private const val MIN_REMAINING_MS = 60_000L
        private const val HEADER_SESSION = "X-BF-Session"
        private const val HEADER_DEVICE = "X-BF-Device"
        private val LOCK = Any()

        fun isValid(session: String): Boolean = expiry(session) > 0

        private fun expiry(session: String): Long = session.substringBefore('.', "").toLongOrNull() ?: 0L
    }
}
