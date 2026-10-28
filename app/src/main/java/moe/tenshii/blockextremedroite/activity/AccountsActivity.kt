package moe.tenshii.blockextremedroite.activity

import android.content.ActivityNotFoundException
import android.content.Intent
import android.icu.text.CompactDecimalFormat
import android.os.Bundle
import android.view.Menu
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.chip.Chip
import moe.tenshii.blockextremedroite.Account
import moe.tenshii.blockextremedroite.AccountAdapter
import moe.tenshii.blockextremedroite.AccountRepository
import moe.tenshii.blockextremedroite.AccountRow
import moe.tenshii.blockextremedroite.BlockCounter
import moe.tenshii.blockextremedroite.BlockStatus
import moe.tenshii.blockextremedroite.Platform
import moe.tenshii.blockextremedroite.PortraitLoader
import moe.tenshii.blockextremedroite.Profile
import moe.tenshii.blockextremedroite.ProgressStore
import moe.tenshii.blockextremedroite.R
import moe.tenshii.blockextremedroite.RunMonitor
import moe.tenshii.blockextremedroite.RunState
import moe.tenshii.blockextremedroite.SettingsStore
import moe.tenshii.blockextremedroite.databinding.ActivityAccountsBinding
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The selected accounts and their status on the platform picked on the main screen, on a screen of their own so
 * thousands of rows can scroll and recycle. Its filters only narrow what is shown here; a blocking run still covers
 * the whole selection.
 */
class AccountsActivity : AppCompatActivity() {
    private enum class Sort(@param:StringRes val label: Int) {
        SITE(R.string.sort_site),
        BLOCKS(R.string.sort_blocks),
        FOLLOWERS(R.string.sort_followers),
    }

    private lateinit var binding: ActivityAccountsBinding
    private lateinit var progress: ProgressStore
    private lateinit var settings: SettingsStore
    private lateinit var counter: BlockCounter
    private lateinit var platform: Platform
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var adapter: AccountAdapter
    private val compactNumbers = CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT)
    private val numbers = NumberFormat.getIntegerInstance()

    private var accounts: List<Account>? = null
    private var loadFailed = false
    private var category: String? = null
    private var minFollowers = 0L
    private var minBlocks = 0L
    private var sort = Sort.SITE
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
        platform = settings.platform
        adapter = AccountAdapter(PortraitLoader.get(this), platform, ::openProfile)
        savedInstanceState?.let { state ->
            category = state.getString(KEY_CATEGORY)
            minFollowers = state.getLong(KEY_MIN_FOLLOWERS)
            minBlocks = state.getLong(KEY_MIN_BLOCKS)
            sort = Sort.entries.getOrElse(state.getInt(KEY_SORT)) { Sort.SITE }
        }

        binding.toolbar.subtitle = platform.label
        binding.hint.text = getString(R.string.accounts_hint, platform.label)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.filterCategory.setOnMenu {
            listOf(getString(R.string.filter_category_all) to { category = null }) +
                categories().map { name -> name to { category = name } }
        }
        binding.filterFollowers.setOnMenu {
            listOf(getString(R.string.filter_any) to { minFollowers = 0 }) + FOLLOWER_STEPS.map { step ->
                getString(R.string.filter_followers_min, compactNumbers.format(step)) to { minFollowers = step }
            }
        }
        binding.filterBlocks.setOnMenu {
            listOf(getString(R.string.filter_any) to { minBlocks = 0 }) + BLOCK_STEPS.map { step ->
                getString(R.string.filter_blocks_min, numbers.format(step)) to { minBlocks = step }
            }
        }
        binding.sort.setOnMenu {
            Sort.entries.map { option -> getString(option.label) to { sort = option } }
        }
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_CATEGORY, category)
        outState.putLong(KEY_MIN_FOLLOWERS, minFollowers)
        outState.putLong(KEY_MIN_BLOCKS, minBlocks)
        outState.putInt(KEY_SORT, sort.ordinal)
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

    /** Makes the chip open a menu of (label, apply) options; picking one re-filters from the top of the list. */
    private fun Chip.setOnMenu(options: () -> List<Pair<String, () -> Unit>>) {
        setOnClickListener {
            // A tap toggles the chip itself; its checked state must keep showing whether the filter is set.
            renderFilters()
            val choices = options()
            val popup = PopupMenu(this@AccountsActivity, this)
            choices.forEachIndexed { index, (label, _) -> popup.menu.add(Menu.NONE, index, index, label) }
            popup.setOnMenuItemClickListener { item ->
                choices[item.itemId].second()
                render(scrollToTop = true)
                true
            }
            popup.show()
        }
        setOnCloseIconClickListener { performClick() }
    }

    private fun categories(): List<String> =
        settings.select(accounts.orEmpty(), platform).groupingBy { it.category }.eachCount().entries
            .sortedByDescending { it.value }.map { it.key }

    private fun renderFilters() {
        binding.filterCategory.text = category ?: getString(R.string.filter_category)
        binding.filterCategory.isChecked = category != null
        binding.filterFollowers.text =
            if (minFollowers > 0) getString(R.string.filter_followers_min, compactNumbers.format(minFollowers)) else getString(R.string.filter_followers)
        binding.filterFollowers.isChecked = minFollowers > 0
        binding.filterBlocks.text =
            if (minBlocks > 0) getString(R.string.filter_blocks_min, numbers.format(minBlocks)) else getString(R.string.filter_blocks)
        binding.filterBlocks.isChecked = minBlocks > 0
        binding.sort.setText(sort.label)
        binding.sort.isChecked = sort != Sort.SITE
    }

    private fun render(scrollToTop: Boolean = false) {
        renderFilters()
        val all = accounts
        if (all == null) {
            binding.summary.isVisible = false
            binding.empty.isVisible = true
            binding.empty.setText(if (loadFailed) R.string.list_load_failed else R.string.list_loading)
            return
        }
        val snapshot = counter.latest
        val selected = settings.select(all, platform).map { account ->
            val profile = account.profiles.getValue(platform)
            AccountRow(account, profile, progress.status(profile), snapshot?.count(account, platform))
        }
        val shown = selected.filter { row ->
            (category == null || row.account.category == category) &&
                (row.profile.followers ?: 0) >= minFollowers &&
                (row.blockCount ?: 0) >= minBlocks
        }
        val rows = when (sort) {
            Sort.SITE -> shown
            Sort.BLOCKS -> shown.sortedByDescending { it.blockCount ?: -1 }
            Sort.FOLLOWERS -> shown.sortedByDescending { it.profile.followers ?: -1 }
        }
        adapter.submitList(rows) { if (scrollToTop) binding.accounts.scrollToPosition(0) }

        binding.empty.isVisible = rows.isEmpty()
        binding.empty.setText(if (selected.isEmpty()) R.string.accounts_empty else R.string.accounts_filtered_empty)
        binding.summary.isVisible = selected.isNotEmpty()
        binding.summary.text = getString(
            R.string.summary,
            selected.count { !it.status.isDone },
            selected.count { it.status == BlockStatus.BLOCKED || it.status == BlockStatus.ALREADY_BLOCKED },
            selected.count { it.status == BlockStatus.UNAVAILABLE },
        )
    }

    private fun openProfile(profile: Profile) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, platform.appLink(profile).toUri()).setPackage(platform.packageName))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, profile.url.toUri()))
        }
    }

    private companion object {
        val FOLLOWER_STEPS = listOf(10_000L, 100_000L, 1_000_000L, 10_000_000L)
        val BLOCK_STEPS = listOf(10L, 100L, 1_000L, 10_000L, 25_000L, 50_000L)
        const val KEY_CATEGORY = "category"
        const val KEY_MIN_FOLLOWERS = "min_followers"
        const val KEY_MIN_BLOCKS = "min_blocks"
        const val KEY_SORT = "sort"
    }
}
