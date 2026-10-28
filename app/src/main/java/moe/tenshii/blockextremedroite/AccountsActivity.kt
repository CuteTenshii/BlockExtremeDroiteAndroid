package moe.tenshii.blockextremedroite

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import moe.tenshii.blockextremedroite.databinding.ActivityAccountsBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** The selected accounts and their status, on a screen of their own so thousands of rows can scroll and recycle. */
class AccountsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAccountsBinding
    private lateinit var progress: ProgressStore
    private lateinit var settings: SettingsStore
    private lateinit var counter: BlockCounter
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val adapter = AccountAdapter(::openProfile)

    private var accounts: List<Account>? = null
    private var loadFailed = false
    private val runListener: (RunState) -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityAccountsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val listBottomPadding = resources.getDimensionPixelSize(R.dimen.gutter)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            binding.accounts.updatePadding(bottom = bars.bottom + listBottomPadding)
            insets
        }

        progress = ProgressStore(this)
        settings = SettingsStore(this)
        counter = BlockCounter.get(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.accounts.adapter = adapter
        binding.accounts.itemAnimator = null
        load()
    }

    override fun onStart() {
        super.onStart()
        RunMonitor.addListener(runListener)
        render()
    }

    override fun onStop() {
        RunMonitor.removeListener(runListener)
        super.onStop()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun load() {
        executor.execute {
            val result = runCatching { AccountRepository(this).load() }
            if (counter.latest == null) runCatching { counter.fetch() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { accounts = it }
                loadFailed = result.isFailure
                render()
            }
        }
    }

    private fun render() {
        val all = accounts
        if (all == null) {
            binding.summary.isVisible = false
            binding.empty.isVisible = true
            binding.empty.setText(if (loadFailed) R.string.list_load_failed else R.string.list_loading)
            return
        }
        val perAccount = counter.latest?.perAccount
        val rows = settings.select(all).map { AccountRow(it, progress.status(it), perAccount?.get(it.siteId)) }
        adapter.submitList(rows)

        binding.empty.isVisible = rows.isEmpty()
        binding.empty.setText(R.string.accounts_empty)
        binding.summary.isVisible = rows.isNotEmpty()
        binding.summary.text = getString(
            R.string.summary,
            rows.count { !it.status.isDone },
            rows.count { it.status == BlockStatus.BLOCKED || it.status == BlockStatus.ALREADY_BLOCKED },
            rows.count { it.status == BlockStatus.UNAVAILABLE },
        )
    }

    private fun openProfile(account: Account) {
        val uri = "https://x.com/${account.handle}".toUri()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(BlockService.X_PACKAGE))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }
}
