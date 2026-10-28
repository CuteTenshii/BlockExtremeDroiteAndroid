package moe.tenshii.blockextremedroite

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import moe.tenshii.blockextremedroite.databinding.OverlayRunBinding

/** Floating pill over the platform's app showing progress, with a stop button that works without leaving it. */
class RunOverlay(private val service: AccessibilityService, onStop: () -> Unit) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val binding = OverlayRunBinding.inflate(
        LayoutInflater.from(ContextThemeWrapper(service, R.style.Theme_BlockExtremeDroite)),
    )
    private var attached = false

    init {
        binding.overlayStop.setOnClickListener { onStop() }
    }

    fun show(platform: Platform) {
        if (attached) return
        binding.overlayStatus.text = service.getString(R.string.overlay_starting, platform.label)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Top centre stays clear of the apps' back, search and menu buttons and of their bottom-sheet menus.
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = service.resources.getDimensionPixelOffset(R.dimen.overlay_top_offset)
        }
        windowManager.addView(binding.root, params)
        attached = true
    }

    fun showProgress(label: String, blocked: Int, cap: Int?) {
        binding.overlayStatus.text = if (cap == null) {
            service.getString(R.string.overlay_blocking_unlimited, label, blocked)
        } else {
            service.getString(R.string.overlay_blocking, label, blocked, cap)
        }
    }

    fun showPause(blocked: Int, cap: Int?) {
        binding.overlayStatus.text = if (cap == null) {
            service.getString(R.string.overlay_pause_unlimited, blocked)
        } else {
            service.getString(R.string.overlay_pause, blocked, cap)
        }
    }

    fun hide() {
        if (!attached) return
        windowManager.removeView(binding.root)
        attached = false
    }
}
