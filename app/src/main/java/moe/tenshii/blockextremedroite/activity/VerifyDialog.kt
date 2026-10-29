package moe.tenshii.blockextremedroite.activity

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.webkit.JavascriptInterface
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.tenshii.blockextremedroite.AccountRepository
import moe.tenshii.blockextremedroite.R
import moe.tenshii.blockextremedroite.SiteSession
import moe.tenshii.blockextremedroite.databinding.DialogVerifyBinding

/**
 * Passes the site's Turnstile anti-bot check, the same one its page runs, and stores the session it unlocks.
 * The check runs on a page served under the site's address, as Turnstile only accepts the site's own hostname.
 * It can't be dismissed: it closes once a session is saved, posting [REQUEST_KEY] to the fragment manager.
 */
class VerifyDialog : DialogFragment() {
    private var binding: DialogVerifyBinding? = null
    private lateinit var session: SiteSession
    private val timeout = Runnable { fail() }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        isCancelable = false
        session = SiteSession(requireContext())
        val binding = DialogVerifyBinding.inflate(layoutInflater).also { binding = it }
        binding.retry.setOnClickListener { start() }
        setUpChallenge(binding)
        start()
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.verify_title)
            .setView(binding.root)
            .create()
    }

    override fun onDestroyView() {
        binding?.let { binding ->
            binding.root.removeCallbacks(timeout)
            binding.challenge.destroy()
        }
        binding = null
        super.onDestroyView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpChallenge(binding: DialogVerifyBinding) {
        with(binding.challenge) {
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(Bridge(), BRIDGE)
        }
    }

    private fun start() {
        val binding = binding ?: return
        binding.status.setText(R.string.verify_running)
        binding.retry.isVisible = false
        binding.root.removeCallbacks(timeout)
        binding.root.postDelayed(timeout, SILENT_TIMEOUT_MS)
        binding.challenge.loadDataWithBaseURL("${AccountRepository.SITE}/", page(), "text/html", "utf-8", null)
    }

    private fun fail() {
        val binding = binding ?: return
        binding.root.removeCallbacks(timeout)
        binding.status.setText(R.string.verify_failed)
        binding.retry.isVisible = true
    }

    private fun page(): String {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return """
            <!doctype html>
            <html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>html, body { margin: 0; background: transparent; }</style>
            <script>
            function start() {
              turnstile.render('#challenge', {
                sitekey: '$SITE_KEY',
                action: 'counter',
                appearance: 'interaction-only',
                language: 'auto',
                theme: '${if (night) "dark" else "light"}',
                size: 'flexible',
                'before-interactive-callback': () => $BRIDGE.interactive(),
                callback: async (token) => {
                  try {
                    const response = await fetch('/api/session', {
                      method: 'POST',
                      headers: { 'Content-Type': 'application/json', 'X-BF': '1' },
                      body: JSON.stringify({ token, device_id: '${session.deviceId}' }),
                    });
                    const data = await response.json();
                    if (!response.ok || !data.session) throw Error();
                    $BRIDGE.done(String(data.session));
                  } catch (e) {
                    $BRIDGE.failed();
                  }
                },
                'error-callback': () => { $BRIDGE.failed(); return true; },
                'expired-callback': () => $BRIDGE.failed(),
                'timeout-callback': () => $BRIDGE.failed(),
              });
            }
            </script>
            <script src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit&onload=start" async defer></script>
            </head><body><div id="challenge"></div></body></html>
        """.trimIndent()
    }

    /** Called by the page on a WebView thread, so every call hops back to the main thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun interactive() {
            onMain { binding ->
                binding.root.removeCallbacks(timeout)
                binding.status.setText(R.string.verify_interactive)
            }
        }

        @JavascriptInterface
        fun done(value: String) {
            onMain {
                if (!SiteSession.isValid(value)) {
                    fail()
                    return@onMain
                }
                session.save(value)
                parentFragmentManager.setFragmentResult(REQUEST_KEY, Bundle.EMPTY)
                dismissAllowingStateLoss()
            }
        }

        @JavascriptInterface
        fun failed() {
            onMain { fail() }
        }

        private fun onMain(action: (DialogVerifyBinding) -> Unit) {
            binding?.root?.post { binding?.let(action) }
        }
    }

    companion object {
        const val REQUEST_KEY = "verify"
        private const val TAG = "verify"
        /** The site's public Turnstile key, the one its page renders. */
        private const val SITE_KEY = "0x4AAAAAAFoxc5B7xqsMNxCh"
        private const val BRIDGE = "BlockExtremeDroite"
        /** A check that never asks for interaction nor finishes is stuck; the site's page gives up after 20 s too. */
        private const val SILENT_TIMEOUT_MS = 20_000L

        /** Shows the check unless it is already up; skipped once the activity has saved its state. */
        fun show(fragments: FragmentManager) {
            if (fragments.isStateSaved || fragments.findFragmentByTag(TAG) != null) return
            VerifyDialog().show(fragments, TAG)
        }
    }
}
