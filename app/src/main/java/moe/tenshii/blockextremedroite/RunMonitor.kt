package moe.tenshii.blockextremedroite

import androidx.annotation.MainThread

enum class StopReason {
    FINISHED,
    CAP_REACHED,
    USER,
    X_NOT_INSTALLED,
    LOGGED_OUT,
    TOO_MANY_FAILURES,
    SERVICE_DISCONNECTED,
}

data class RunState(
    val serviceConnected: Boolean = false,
    val running: Boolean = false,
    val currentHandle: String? = null,
    val blocked: Int = 0,
    /** Null when the session has no limit. */
    val cap: Int? = null,
    val lastStop: StopReason? = null,
    /** Accounts blocked by the session that ended with [lastStop]. */
    val lastBlocked: Int = 0,
)

/** Shares the service's run state with the UI; both live on the main thread of the same process. */
@MainThread
object RunMonitor {
    var state = RunState()
        private set

    private val listeners = LinkedHashSet<(RunState) -> Unit>()

    fun addListener(listener: (RunState) -> Unit) {
        listeners += listener
        listener(state)
    }

    fun removeListener(listener: (RunState) -> Unit) {
        listeners -= listener
    }

    fun update(transform: (RunState) -> RunState) {
        state = transform(state)
        listeners.toList().forEach { it(state) }
    }
}
