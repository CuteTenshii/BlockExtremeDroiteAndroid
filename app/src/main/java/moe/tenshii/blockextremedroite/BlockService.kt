package moe.tenshii.blockextremedroite

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.net.toUri
import kotlin.random.Random

/**
 * Drives the X app: opens each profile through a deep link, then taps ⋯, Bloquer and the confirmation.
 * X's accessibility events trigger each check (debounced, as Compose sends them in bursts); a short poll
 * covers screens that change without emitting any.
 */
class BlockService : AccessibilityService() {

    private enum class Step { OPEN, PROFILE, MENU, CONFIRM, VERIFY, PAUSE }

    private class Session(val queue: ArrayDeque<Account>, val pacing: Pacing) {
        var step = Step.OPEN
        var stepStartedAt = 0L
        var deadline = 0L
        var resumeAt = 0L
        var current: Account? = null
        var blocked = 0
        var consecutiveFailures = 0
        var confirmRetried = false

        val capReached: Boolean get() = pacing.sessionCap?.let { blocked >= it } ?: false
    }

    private val handler = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var overlay: RunOverlay? = null
    private lateinit var progress: ProgressStore
    private lateinit var counter: BlockCounter

    private val tick = object : Runnable {
        override fun run() {
            advance()
            if (session != null) handler.postDelayed(this, TICK_MS)
        }
    }

    private val eventCheck = Runnable { advance() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        progress = ProgressStore(this)
        counter = BlockCounter.get(this)
        overlay = RunOverlay(this) { stop(StopReason.USER) }
        RunMonitor.update { it.copy(serviceConnected = true) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (session == null || event?.packageName != X_PACKAGE) return
        handler.removeCallbacks(eventCheck)
        handler.postDelayed(eventCheck, EVENT_DEBOUNCE_MS)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    fun start(accounts: List<Account>, pacing: Pacing) {
        if (session != null || accounts.isEmpty()) return
        session = Session(ArrayDeque(accounts), pacing)
        overlay?.show()
        RunMonitor.update {
            it.copy(running = true, currentHandle = null, blocked = 0, cap = pacing.sessionCap, lastStop = null, lastBlocked = 0)
        }
        handler.post(tick)
    }

    fun stop(reason: StopReason) {
        val ended = session ?: return
        session = null
        handler.removeCallbacks(tick)
        handler.removeCallbacks(eventCheck)
        overlay?.hide()
        RunMonitor.update {
            it.copy(running = false, currentHandle = null, lastStop = reason, lastBlocked = ended.blocked)
        }
        if (reason != StopReason.SERVICE_DISCONNECTED) returnToApp()
    }

    private fun disconnect() {
        if (instance !== this) return
        stop(StopReason.SERVICE_DISCONNECTED)
        overlay?.hide()
        instance = null
        RunMonitor.update { it.copy(serviceConnected = false) }
    }

    private fun advance() {
        val session = session ?: return
        val now = SystemClock.uptimeMillis()
        when (session.step) {
            Step.OPEN -> openNext(session, now)
            Step.PAUSE -> if (now >= session.resumeAt) openNext(session, now)
            else -> {
                // Gives X a moment to react to the previous tap before the tree is read again.
                if (session.step != Step.PROFILE && now - session.stepStartedAt < TAP_DWELL_MS) return
                val account = session.current ?: return openNext(session, now)
                inspect(session, account, captureScreen(), now)
            }
        }
    }

    private fun openNext(session: Session, now: Long) {
        if (session.capReached) return stop(StopReason.CAP_REACHED)
        val account = session.queue.removeFirstOrNull() ?: return stop(StopReason.FINISHED)
        // A fresh task guarantees the previous profile is gone before the next one is inspected.
        val intent = Intent(Intent.ACTION_VIEW, "https://x.com/${account.handle}".toUri())
            .setPackage(X_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            return stop(StopReason.X_NOT_INSTALLED)
        }
        session.current = account
        session.confirmRetried = false
        enter(session, Step.PROFILE, PROFILE_TIMEOUT_MS, now)
        overlay?.showProgress(account.handle, session.blocked, session.pacing.sessionCap)
        RunMonitor.update { it.copy(currentHandle = account.handle, blocked = session.blocked) }
    }

    private fun inspect(session: Session, account: Account, screen: XScreen, now: Long) {
        val handle = account.handle
        val elapsed = now - session.stepStartedAt
        when (session.step) {
            Step.PROFILE -> {
                val handleShown = screen.showsHandle(handle)
                val overflow = if (handleShown) screen.overflowButton() else null
                val gate = screen.sensitiveGate()
                when {
                    handleShown && screen.isBlockedProfile(handle) -> finish(session, account, BlockStatus.ALREADY_BLOCKED, now)
                    overflow != null -> {
                        click(overflow)
                        enter(session, Step.MENU, MENU_TIMEOUT_MS, now)
                    }
                    screen.isUnavailable() && (handleShown || elapsed > UNAVAILABLE_GRACE_MS) ->
                        finish(session, account, BlockStatus.UNAVAILABLE, now)
                    gate != null -> click(gate)
                    !handleShown && elapsed > LOGIN_GRACE_MS && screen.isLoggedOut() -> stop(StopReason.LOGGED_OUT)
                    now > session.deadline -> finish(session, account, BlockStatus.FAILED, now)
                }
            }
            Step.MENU -> {
                val item = screen.blockMenuItem(handle)
                when {
                    item != null -> {
                        click(item)
                        enter(session, Step.CONFIRM, CONFIRM_TIMEOUT_MS, now)
                    }
                    screen.hasUnblockMenuItem(handle) -> finish(session, account, BlockStatus.ALREADY_BLOCKED, now)
                    now > session.deadline -> finish(session, account, BlockStatus.FAILED, now)
                }
            }
            Step.CONFIRM -> {
                val button = screen.confirmButton()
                when {
                    button != null -> {
                        click(button)
                        enter(session, Step.VERIFY, VERIFY_TIMEOUT_MS, now)
                    }
                    // Some X builds block straight from the menu without a dialog.
                    screen.blockSucceeded(handle) -> finish(session, account, BlockStatus.BLOCKED, now)
                    now > session.deadline -> finish(session, account, BlockStatus.FAILED, now)
                }
            }
            Step.VERIFY -> {
                val button = if (session.confirmRetried) null else screen.confirmButton()
                when {
                    screen.blockSucceeded(handle) -> finish(session, account, BlockStatus.BLOCKED, now)
                    button != null && elapsed > CONFIRM_RETRY_MS -> {
                        session.confirmRetried = true
                        click(button)
                    }
                    now > session.deadline -> finish(session, account, BlockStatus.FAILED, now)
                }
            }
            Step.OPEN, Step.PAUSE -> Unit
        }
    }

    private fun enter(session: Session, step: Step, timeoutMs: Long, now: Long) {
        session.step = step
        session.stepStartedAt = now
        session.deadline = now + timeoutMs
    }

    private fun finish(session: Session, account: Account, status: BlockStatus, now: Long) {
        progress.set(account, status)
        if (status == BlockStatus.BLOCKED || status == BlockStatus.ALREADY_BLOCKED) counter.report(account)
        if (status == BlockStatus.BLOCKED) session.blocked++
        session.consecutiveFailures = if (status == BlockStatus.FAILED) session.consecutiveFailures + 1 else 0
        RunMonitor.update { it.copy(blocked = session.blocked) }

        when {
            session.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES -> stop(StopReason.TOO_MANY_FAILURES)
            session.capReached -> stop(StopReason.CAP_REACHED)
            session.queue.isEmpty() -> stop(StopReason.FINISHED)
            else -> {
                val delayMs = if (status == BlockStatus.BLOCKED) {
                    1000L * Random.nextInt(session.pacing.minDelaySeconds, session.pacing.maxDelaySeconds + 1)
                } else {
                    minOf(SKIP_DELAY_MS, 1000L * session.pacing.maxDelaySeconds)
                }
                session.step = Step.PAUSE
                session.resumeAt = now + delayMs
                overlay?.showPause(session.blocked, session.pacing.sessionCap)
            }
        }
    }

    private fun captureScreen(): XScreen {
        val roots = windows.mapNotNull { it.root }.filter { it.packageName == X_PACKAGE }
            .ifEmpty { listOfNotNull(rootInActiveWindow?.takeIf { it.packageName == X_PACKAGE }) }
        val nodes = ArrayList<ScreenNode>()
        val bounds = Rect()
        val pending = ArrayDeque(roots)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (!node.isVisibleToUser) continue
            if (!node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty()) {
                node.getBoundsInScreen(bounds)
                nodes += ScreenNode(node.text, node.contentDescription, bounds.left, bounds.top, bounds.right, bounds.bottom, node)
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(pending::addLast)
        }
        return XScreen(nodes, resources.displayMetrics.heightPixels)
    }

    /** Compose often puts the label on a child of the clickable node, so the click goes to the nearest clickable ancestor. */
    private fun click(target: ScreenNode) {
        var node: AccessibilityNodeInfo? = target.source
        repeat(MAX_CLICK_ANCESTORS) {
            val current = node ?: return@repeat
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node = current.parent
        }
        tap(target.centerX.toFloat(), target.centerY.toFloat())
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun returnToApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
    }

    companion object {
        const val X_PACKAGE = "com.twitter.android"

        /** False when X is missing or disabled; either way its profile links cannot be opened. */
        fun isXInstalled(context: Context): Boolean = try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(X_PACKAGE, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                context.packageManager.getApplicationInfo(X_PACKAGE, 0)
            }
            info.enabled
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        var instance: BlockService? = null
            private set

        private const val TICK_MS = 250L
        private const val EVENT_DEBOUNCE_MS = 60L
        private const val TAP_DWELL_MS = 150L
        private const val PROFILE_TIMEOUT_MS = 15_000L
        private const val MENU_TIMEOUT_MS = 6_000L
        private const val CONFIRM_TIMEOUT_MS = 6_000L
        private const val VERIFY_TIMEOUT_MS = 8_000L
        private const val CONFIRM_RETRY_MS = 2_000L
        private const val UNAVAILABLE_GRACE_MS = 5_000L
        private const val LOGIN_GRACE_MS = 5_000L
        private const val SKIP_DELAY_MS = 300L
        private const val TAP_DURATION_MS = 60L
        private const val MAX_CLICK_ANCESTORS = 6
        private const val MAX_CONSECUTIVE_FAILURES = 3
    }
}
