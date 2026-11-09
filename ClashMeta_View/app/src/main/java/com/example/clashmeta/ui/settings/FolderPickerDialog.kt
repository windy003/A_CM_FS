package com.example.clashmeta.ui.settings

import android.content.Context
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import com.example.clashmeta.R
import com.example.clashmeta.databinding.DialogFolderPickerBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * 简单的文件夹选择器。
 *
 * 内核（gomobile）只认真实的文件系统路径，SAF 的 content:// URI 没法直接交给它，
 * 所以这里用 File API 自己遍历目录。应用已经申请了 MANAGE_EXTERNAL_STORAGE，
 * 正常情况下 /sdcard 下的目录都能读写。
 */
object FolderPickerDialog {

    /**
     * @param startDir 打开时定位到的目录，不存在时退回外部存储根目录
     * @param onPicked 用户点「选择此文件夹」时回调，目录是否可写由调用方再校验
     */
    fun show(context: Context, startDir: File, onPicked: (File) -> Unit) {
        val binding = DialogFolderPickerBinding.inflate(LayoutInflater.from(context))
        val dirs = mutableListOf<File>()
        val names = mutableListOf<String>()
        val adapter = ArrayAdapter(context, R.layout.item_dropdown, names)
        binding.listFolder.adapter = adapter

        var current = if (startDir.isDirectory) startDir else externalRoot()

        fun load() {
            binding.textFolderPath.text = current.absolutePath
            dirs.clear()
            names.clear()

            val children = try {
                current.listFiles()
            } catch (e: Exception) {
                null
            }

            if (children == null) {
                binding.textFolderEmpty.text = "无法读取此文件夹（可能缺少存储权限）"
                binding.textFolderEmpty.visibility = View.VISIBLE
            } else {
                children.asSequence()
                    .filter { it.isDirectory && !it.name.startsWith(".") }
                    .sortedBy { it.name.lowercase() }
                    .forEach {
                        dirs.add(it)
                        names.add(it.name)
                    }
                binding.textFolderEmpty.text = "此文件夹下没有子文件夹"
                binding.textFolderEmpty.visibility =
                    if (dirs.isEmpty()) View.VISIBLE else View.GONE
            }
            adapter.notifyDataSetChanged()
            // 回到根目录后不能再往上
            binding.btnFolderUp.isEnabled =
                current.parentFile != null && current.absolutePath != "/"
        }

        binding.listFolder.setOnItemClickListener { _, _, position, _ ->
            val dir = dirs.getOrNull(position) ?: return@setOnItemClickListener
            current = dir
            load()
        }

        binding.btnFolderUp.setOnClickListener {
            val parent = current.parentFile ?: return@setOnClickListener
            if (parent != current) {
                current = parent
                load()
            }
        }

        load()

        MaterialAlertDialogBuilder(context)
            .setTitle("选择数据文件夹")
            .setView(binding.root)
            .setPositiveButton("选择此文件夹") { _, _ -> onPicked(current) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun externalRoot(): File =
        Environment.getExternalStorageDirectory() ?: File("/sdcard")
}
