package com.example.clashmeta.ui.apps

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.clashmeta.data.AppInfo
import com.example.clashmeta.data.AppProxyManager
import com.example.clashmeta.databinding.ItemAppBinding
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppsAdapter(
    private val scope: CoroutineScope,
    private val isSelected: (String) -> Boolean,
    private val onToggle: (String) -> Unit
) : RecyclerView.Adapter<AppsAdapter.VH>() {

    private var apps: List<AppInfo> = emptyList()

    fun submit(newApps: List<AppInfo>) {
        apps = newApps
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemAppBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = apps.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(apps[position])

    inner class VH(private val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(app: AppInfo) {
            binding.textAppName.text = app.appName
            binding.textPackage.text = app.packageName

            val selected = isSelected(app.packageName)
            binding.checkbox.isChecked = selected

            val primaryContainer = MaterialColors.getColor(
                binding.root, com.google.android.material.R.attr.colorPrimaryContainer
            )
            val surface = MaterialColors.getColor(
                binding.root, com.google.android.material.R.attr.colorSurface
            )
            binding.root.setCardBackgroundColor(if (selected) primaryContainer else surface)

            // 默认占位图标，异步加载真实图标
            binding.iconApp.setImageResource(com.example.clashmeta.R.drawable.ic_android)
            binding.iconApp.imageTintList = null
            val pkg = app.packageName
            binding.iconApp.tag = pkg
            scope.launch {
                val icon = withContext(Dispatchers.IO) {
                    AppProxyManager.getAppIcon(binding.root.context, pkg)
                }
                // 防止 ViewHolder 复用导致图标错位
                if (binding.iconApp.tag == pkg && icon != null) {
                    binding.iconApp.setImageDrawable(icon)
                }
            }

            binding.root.setOnClickListener { onToggle(app.packageName) }
        }
    }
}
