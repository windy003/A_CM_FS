package com.example.clashmeta.ui.proxy

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.clashmeta.databinding.ItemProxyBinding
import com.google.android.material.color.MaterialColors

/** 列表项数据：节点名 + 信息 */
data class ProxyRow(val name: String, val info: ProxyInfo)

class ProxyAdapter(
    private val onSelect: (String) -> Unit,
    private val onTest: (String) -> Unit,
    private val onMenu: (String, View) -> Unit
) : RecyclerView.Adapter<ProxyAdapter.VH>() {

    private var rows: List<ProxyRow> = emptyList()
    var selectedProxy: String? = null
    val delayResults = mutableMapOf<String, Int>()
    var testingProxy: String? = null

    fun submit(newRows: List<ProxyRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemProxyBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(rows[position])
    }

    inner class VH(private val binding: ItemProxyBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: ProxyRow) {
            val name = row.name
            val info = row.info
            val isSelected = selectedProxy == name
            val delay = delayResults[name]
            val isTesting = testingProxy == name

            binding.textName.text = name
            binding.textType.text = info.type

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

            // 卡片背景：选中高亮
            binding.root.setCardBackgroundColor(if (isSelected) primaryContainer else surface)

            // 状态指示点颜色
            val dotColor = when {
                delay != null && delay > 0 && delay < 300 -> Color.parseColor("#4CAF50")
                delay != null && delay in 300..600 -> Color.parseColor("#FF9800")
                delay != null && delay > 600 -> Color.parseColor("#F44336")
                delay == -1 -> Color.parseColor("#9E9E9E")
                info.alive -> primary
                else -> outline
            }
            binding.statusDot.backgroundTintList = ColorStateList.valueOf(dotColor)

            // 延迟与测试进度
            if (isTesting) {
                binding.progressTesting.visibility = View.VISIBLE
                binding.textDelay.visibility = View.GONE
            } else {
                binding.progressTesting.visibility = View.GONE
                if (delay != null) {
                    binding.textDelay.visibility = View.VISIBLE
                    binding.textDelay.text = if (delay == -1) "超时" else "${delay}ms"
                    binding.textDelay.setTextColor(
                        when {
                            delay == -1 -> Color.parseColor("#9E9E9E")
                            delay < 300 -> Color.parseColor("#4CAF50")
                            delay < 600 -> Color.parseColor("#FF9800")
                            else -> Color.parseColor("#F44336")
                        }
                    )
                } else {
                    binding.textDelay.visibility = View.GONE
                }
            }

            // 选中标记
            binding.iconSelected.visibility = if (isSelected) View.VISIBLE else View.GONE

            // 测试按钮
            binding.btnTest.isEnabled = !isTesting
            binding.btnTest.setOnClickListener { onTest(name) }

            // 更多操作按钮（弹出菜单：复制节点信息等）
            binding.btnMenu.setOnClickListener { onMenu(name, it) }

            // 点击整行选择节点
            binding.root.setOnClickListener { onSelect(name) }
        }
    }
}
