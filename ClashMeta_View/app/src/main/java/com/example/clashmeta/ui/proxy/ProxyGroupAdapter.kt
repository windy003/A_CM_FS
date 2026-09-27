package com.example.clashmeta.ui.proxy

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.clashmeta.databinding.ItemGroupBinding
import com.google.android.material.color.MaterialColors

/**
 * 节点页顶部的代理组标签（横向）。
 *
 * 组名与成员都来自内核（[ProxyInfo.all] / [ProxyInfo.now]），不是 UI 自己解析 config.yaml——
 * 否则订阅重载或组成员被清洗后，界面显示的和实际生效的会对不上。
 *
 * 不可手选的组（url-test / fallback）也展示：用户需要看到它们当前自动选中了谁，
 * 只是点进去之后组内不能改选（由 [ProxyFragment] 提示）。
 */
class ProxyGroupAdapter(
    private val onPick: (String) -> Unit
) : RecyclerView.Adapter<ProxyGroupAdapter.VH>() {

    private var groups: List<ProxyRow> = emptyList()
    var currentGroup: String? = null

    fun submit(newGroups: List<ProxyRow>, current: String?) {
        groups = newGroups
        currentGroup = current
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemGroupBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = groups.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(groups[position])

    inner class VH(private val binding: ItemGroupBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: ProxyRow) {
            val isCurrent = row.name == currentGroup

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

            binding.cardGroup.setCardBackgroundColor(if (isCurrent) primaryContainer else surface)
            binding.cardGroup.strokeColor = if (isCurrent) primary else outline

            binding.textGroupName.text = row.name
            // 第二行显示该组当前生效的成员；自动组(url-test)的结果也在这里体现
            val now = row.info.now
            if (now.isNullOrEmpty()) {
                binding.textGroupNow.visibility = View.GONE
            } else {
                binding.textGroupNow.visibility = View.VISIBLE
                binding.textGroupNow.text = now
            }

            binding.root.setOnClickListener { onPick(row.name) }
        }
    }
}
