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
import androidx.core.view.isVisible
import com.google.android.material.chip.Chip
import moe.tenshii.blockextremedroite.Account
import moe.tenshii.blockextremedroite.AccountAdapter
import moe.tenshii.blockextremedroite.AccountRow
import moe.tenshii.blockextremedroite.BlockCounter
import moe.tenshii.blockextremedroite.BlockStatus
import moe.tenshii.blockextremedroite.Platform
import moe.tenshii.blockextremedroite.PortraitLoader
import moe.tenshii.blockextremedroite.Profile
import moe.tenshii.blockextremedroite.ProgressStore
import moe.tenshii.blockextremedroite.R
import moe.tenshii.blockextremedroite.SettingsStore
import moe.tenshii.blockextremedroite.SiteCategory
import moe.tenshii.blockextremedroite.databinding.TabAccountsBinding
import java.text.NumberFormat
import java.util.Locale

/**
 * The Comptes tab: the selected accounts and their status on the platform picked in the Bloquer tab, in a recycled
 * list so thousands of rows can scroll. Its filters only narrow what is shown here; a blocking run still covers the
 * whole selection.
 */
class AccountsTab(
    private val activity: AppCompatActivity,
    private val binding: TabAccountsBinding,
    savedState: Bundle?,
) {
    private enum class Sort(@param:StringRes val label: Int) {
        SITE(R.string.sort_site),
        BLOCKS(R.string.sort_blocks),
        FOLLOWERS(R.string.sort_followers),
    }

    private val progress = ProgressStore(activity)
    private val settings = SettingsStore(activity)
    private val counter = BlockCounter.get(activity)
    private val portraits = PortraitLoader.get(activity)
    private val compactNumbers = CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT)
    private val numbers = NumberFormat.getIntegerInstance()

    /** The adapter shows one platform's profiles, so it is rebuilt when the Bloquer tab switches platform. */
    private var platform: Platform? = null
    private var adapter: AccountAdapter? = null
    private var category: SiteCategory? = SiteCategory.fromKey(savedState?.getString(KEY_CATEGORY))
    private var minFollowers = savedState?.getLong(KEY_MIN_FOLLOWERS) ?: 0L
    private var minBlocks = savedState?.getLong(KEY_MIN_BLOCKS) ?: 0L
    private var sort = savedState?.let { Sort.entries.getOrNull(it.getInt(KEY_SORT)) } ?: Sort.SITE

    /** Null until the main screen has read the saved list. */
    var accounts: List<Account>? = null

    var loadFailed = false

    init {
        binding.filterCategory.setOnMenu {
            listOf(activity.getString(R.string.filter_category_all) to { category = null }) +
                categories().map { option -> activity.getString(option.label) to { category = option } }
        }
        binding.filterFollowers.setOnMenu {
            listOf(activity.getString(R.string.filter_any) to { minFollowers = 0 }) + FOLLOWER_STEPS.map { step ->
                activity.getString(R.string.filter_followers_min, compactNumbers.format(step)) to { minFollowers = step }
            }
        }
        binding.filterBlocks.setOnMenu {
            listOf(activity.getString(R.string.filter_any) to { minBlocks = 0 }) + BLOCK_STEPS.map { step ->
                activity.getString(R.string.filter_blocks_min, numbers.format(step)) to { minBlocks = step }
            }
        }
        binding.sort.setOnMenu {
            Sort.entries.map { option -> activity.getString(option.label) to { sort = option } }
        }
        binding.accounts.itemAnimator = null
    }

    fun saveState(outState: Bundle) {
        outState.putString(KEY_CATEGORY, category?.key)
        outState.putLong(KEY_MIN_FOLLOWERS, minFollowers)
        outState.putLong(KEY_MIN_BLOCKS, minBlocks)
        outState.putInt(KEY_SORT, sort.ordinal)
    }

    fun render(scrollToTop: Boolean = false) {
        val platform = settings.platform
        val adapter = adapter.takeIf { this.platform == platform } ?: AccountAdapter(portraits, platform, ::openProfile).also {
            this.platform = platform
            adapter = it
            binding.accounts.adapter = it
        }
        binding.hint.text = activity.getString(R.string.accounts_hint, platform.label)
        renderFilters()
        val all = accounts
        if (all == null) {
            binding.accountsSummary.isVisible = false
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
            (category == null || row.account.siteCategory == category) &&
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
        binding.accountsSummary.isVisible = selected.isNotEmpty()
        binding.accountsSummary.text = activity.getString(
            R.string.summary,
            selected.count { !it.status.isDone },
            selected.count { it.status.isBlocked },
            selected.count { it.status == BlockStatus.UNAVAILABLE },
        )
    }

    /** Makes the chip open a menu of (label, apply) options; picking one re-filters from the top of the list. */
    private fun Chip.setOnMenu(options: () -> List<Pair<String, () -> Unit>>) {
        setOnClickListener {
            // A tap toggles the chip itself; its checked state must keep showing whether the filter is set.
            renderFilters()
            val choices = options()
            val popup = PopupMenu(activity, this)
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

    private fun categories(): List<SiteCategory> =
        settings.select(accounts.orEmpty()).mapNotNull { it.siteCategory }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.map { it.key }

    private fun renderFilters() {
        binding.filterCategory.text = activity.getString(category?.label ?: R.string.filter_category)
        binding.filterCategory.isChecked = category != null
        binding.filterFollowers.text = if (minFollowers > 0) {
            activity.getString(R.string.filter_followers_min, compactNumbers.format(minFollowers))
        } else {
            activity.getString(R.string.filter_followers)
        }
        binding.filterFollowers.isChecked = minFollowers > 0
        binding.filterBlocks.text = if (minBlocks > 0) {
            activity.getString(R.string.filter_blocks_min, numbers.format(minBlocks))
        } else {
            activity.getString(R.string.filter_blocks)
        }
        binding.filterBlocks.isChecked = minBlocks > 0
        binding.sort.setText(sort.label)
        binding.sort.isChecked = sort != Sort.SITE
    }

    private fun openProfile(profile: Profile) {
        val platform = platform ?: return
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, platform.appLink(profile).toUri()).setPackage(platform.packageName))
        } catch (_: ActivityNotFoundException) {
            activity.startActivity(Intent(Intent.ACTION_VIEW, profile.url.toUri()))
        }
    }

    private companion object {
        val FOLLOWER_STEPS = listOf(10_000L, 100_000L, 1_000_000L, 10_000_000L)
        val BLOCK_STEPS = listOf(10L, 100L, 1_000L, 10_000L, 25_000L, 50_000L)
        const val KEY_CATEGORY = "accounts_category"
        const val KEY_MIN_FOLLOWERS = "accounts_min_followers"
        const val KEY_MIN_BLOCKS = "accounts_min_blocks"
        const val KEY_SORT = "accounts_sort"
    }
}
