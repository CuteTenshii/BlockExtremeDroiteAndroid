package moe.tenshii.blockextremedroite

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

/** [blockCount] is the site's collective count for this account, null until the counter loads. */
data class AccountRow(val account: Account, val status: BlockStatus, val blockCount: Long?)

class AccountAdapter(private val onOpen: (Account) -> Unit) :
    ListAdapter<AccountRow, AccountAdapter.Holder>(Diff) {

    private val numbers = NumberFormat.getIntegerInstance()

    class Holder(val binding: ItemAccountBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemAccountBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val (account, status, blockCount) = getItem(position)
        val context = holder.itemView.context
        with(holder.binding) {
            accountName.text = account.name
            accountMeta.text = if (blockCount == null) {
                context.getString(R.string.account_meta, account.handle, account.category)
            } else {
                context.resources.getQuantityString(
                    R.plurals.account_meta_count, pluralQuantity(blockCount),
                    account.handle, account.category, numbers.format(blockCount),
                )
            }
            accountStatus.setText(status.label)
            accountStatus.setTextColor(
                if (status == BlockStatus.BLOCKED || status == BlockStatus.ALREADY_BLOCKED) {
                    context.getColor(R.color.success)
                } else {
                    MaterialColors.getColor(root, status.colorAttr)
                },
            )
            root.setOnClickListener { onOpen(account) }
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
        override fun areItemsTheSame(oldItem: AccountRow, newItem: AccountRow) = oldItem.account.key == newItem.account.key
        override fun areContentsTheSame(oldItem: AccountRow, newItem: AccountRow) = oldItem == newItem
    }
}
