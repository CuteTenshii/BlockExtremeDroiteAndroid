package moe.tenshii.blockextremedroite

import android.icu.text.CompactDecimalFormat
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.AttrRes
import androidx.annotation.StringRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import moe.tenshii.blockextremedroite.databinding.ItemAccountBinding
import java.text.NumberFormat
import java.util.Locale

/** [profile] is the account's on the shown platform; [blockCount] is the site's count there, null until the counter loads. */
data class AccountRow(val account: Account, val profile: Profile, val status: BlockStatus, val blockCount: Long?)

class AccountAdapter(
    private val portraits: PortraitLoader,
    private val platform: Platform,
    private val onOpen: (Profile) -> Unit,
) :
    ListAdapter<AccountRow, AccountAdapter.Holder>(Diff) {

    private val numbers = NumberFormat.getIntegerInstance()
    private val compactNumbers = CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT)

    class Holder(val binding: ItemAccountBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemAccountBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val (account, profile, status, blockCount) = getItem(position)
        val context = holder.itemView.context
        with(holder.binding) {
            accountName.text = account.name
            accountInitials.text = account.name.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2)
                .joinToString("") { it.take(1) }.uppercase(Locale.getDefault())
            portraits.load(accountPortrait, account.avatarUrl(platform), context.resources.getDimensionPixelSize(R.dimen.avatar_size))
            accountMeta.text = listOfNotNull(
                profile.handle?.let { "@$it" },
                account.category,
                profile.followers?.let {
                    context.resources.getQuantityString(R.plurals.account_followers, pluralQuantity(it), compactNumbers.format(it))
                },
                blockCount?.let {
                    context.resources.getQuantityString(R.plurals.account_blocks, pluralQuantity(it), numbers.format(it))
                },
            ).joinToString(" · ")
            accountStatus.setText(status.label)
            accountStatus.setTextColor(
                if (status == BlockStatus.BLOCKED || status == BlockStatus.ALREADY_BLOCKED) {
                    context.getColor(R.color.success)
                } else {
                    MaterialColors.getColor(root, status.colorAttr)
                },
            )
            root.setOnClickListener { onOpen(profile) }
        }
    }

    private val BlockStatus.label: Int
        @StringRes get() = when (this) {
            BlockStatus.PENDING -> R.string.status_pending
            BlockStatus.BLOCKED -> R.string.status_blocked
            BlockStatus.ALREADY_BLOCKED -> R.string.status_already
            BlockStatus.UNAVAILABLE -> R.string.status_unavailable
            BlockStatus.FAILED -> R.string.status_failed
        }

    private val BlockStatus.colorAttr: Int
        @AttrRes get() = if (this == BlockStatus.FAILED) {
            androidx.appcompat.R.attr.colorError
        } else {
            com.google.android.material.R.attr.colorOnSurfaceVariant
        }

    private object Diff : DiffUtil.ItemCallback<AccountRow>() {
        override fun areItemsTheSame(oldItem: AccountRow, newItem: AccountRow) = oldItem.account.siteId == newItem.account.siteId
        override fun areContentsTheSame(oldItem: AccountRow, newItem: AccountRow) = oldItem == newItem
    }
}
