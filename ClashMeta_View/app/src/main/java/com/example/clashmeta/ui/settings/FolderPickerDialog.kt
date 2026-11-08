package com.example.clashmeta.ui.settings

import android.content.Context
import android.os.Environment
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.Toast
import com.example.clashmeta.R
import com.example.clashmeta.databinding.DialogFolderPickerBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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

        binding.btnFolderNew.setOnClickListener {
            createFolder(context, current) { newDir ->
                current = newDir
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

    private fun createFolder(context: Context, parent: File, onCreated: (File) -> Unit) {
        val input = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            hint = "文件夹名称"
        }
        val layout = TextInputLayout(context).apply {
            hint = "文件夹名称"
            addView(input)
        }
        // AlertDialog 自带左右 24dp 的边距，这里只补上下
        val container = FrameLayout(context).apply {
            val padding = (8 * resources.displayMetrics.density).toInt()
            setPadding(0, padding, 0, 0)
            addView(
                layout,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("新建文件夹")
            .setView(container)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    toast(context, "名称不能为空")
                    return@setPositiveButton
                }
                if (name.contains('/') || name.contains('\\')) {
                    toast(context, "名称不能包含 / 或 \\")
                    return@setPositiveButton
                }
                val dir = File(parent, name)
                if (dir.exists()) {
                    toast(context, "已存在同名文件夹")
                    return@setPositiveButton
                }
                if (dir.mkdirs()) {
                    onCreated(dir)
                } else {
                    toast(context, "创建失败，请检查存储权限")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
