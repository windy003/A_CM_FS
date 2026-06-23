package com.example.clashmeta.ui.profile

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.clashmeta.data.Subscription
import com.example.clashmeta.databinding.ItemSubscriptionBinding
import com.google.android.material.color.MaterialColors

class SubscriptionAdapter(
    private val onActivate: (Subscription) -> Unit,
    private val onUpdate: (Subscription) -> Unit,
    private val onMore: (View, Subscription) -> Unit
) : RecyclerView.Adapter<SubscriptionAdapter.VH>() {

    private var items: List<Subscription> = emptyList()
    var activeConfigId: String? = null
    var updatingId: String? = null

    fun submit(newItems: List<Subscription>, activeId: String?) {
        items = newItems
        activeConfigId = activeId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSubscriptionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val binding: ItemSubscriptionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(sub: Subscription) {
            val isActive = activeConfigId == sub.id
            val isUpdating = updatingId == sub.id

            val primary = MaterialColors.getColor(
                binding.root, com.google.android.material.R.attr.colorPrimary
            )
            val outline = MaterialColors.getColor(
                binding.root, com.google.android.material.R.attr.colorOutline
            )
            val primaryContainer = MaterialColors.getColor(
                binding.root, com.google.android.material.R.attr.colorPrimaryContainer
            )
            val surface = MaterialColors.getColor(
                binding.root, com.google.android.material.R.attr.colorSurface
            )

            binding.root.setCardBackgroundColor(if (isActive) primaryContainer else surface)

            binding.iconStatus.setImageResource(
                if (isActive) com.example.clashmeta.R.drawable.ic_check_circle
                else com.example.clashmeta.R.drawable.ic_description
            )
            binding.iconStatus.imageTintList =
                ColorStateList.valueOf(if (isActive) primary else outline)

            binding.textName.text = sub.name
            binding.textUrl.text =
                sub.url.take(40) + if (sub.url.length > 40) "..." else ""
            binding.textActive.visibility = if (isActive) View.VISIBLE else View.GONE

            // 更新进度 / 更新按钮
            if (isUpdating) {
                binding.progressUpdating.visibility = View.VISIBLE
                binding.btnUpdate.visibility = View.GONE
            } else {
                binding.progressUpdating.visibility = View.GONE
                binding.btnUpdate.visibility = View.VISIBLE
            }

            binding.root.setOnClickListener { onActivate(sub) }
            binding.btnUpdate.setOnClickListener { onUpdate(sub) }
            binding.btnMore.setOnClickListener { onMore(it, sub) }
        }
    }
}
