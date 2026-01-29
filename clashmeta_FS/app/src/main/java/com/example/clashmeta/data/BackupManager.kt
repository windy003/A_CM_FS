package com.example.clashmeta.data

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * 备份管理器 - 将数据备份到外部存储，卸载应用后数据仍然保留
 * 备份位置: Documents/ClashMetaBackup/
 */
object BackupManager {
    private const val TAG = "BackupManager"
    private const val BACKUP_FOLDER = "ClashMetaBackup"

    /**
     * 获取备份目录
     */
    private fun getBackupDir(): File? {
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        if (!documentsDir.exists()) {
            documentsDir.mkdirs()
        }
        val backupDir = File(documentsDir, BACKUP_FOLDER)
        if (!backupDir.exists()) {
            if (!backupDir.mkdirs()) {
                Log.e(TAG, "Failed to create backup directory: ${backupDir.absolutePath}")
                return null
            }
        }
        return backupDir
    }

    /**
     * 备份订阅数据
     */
    fun backupSubscriptions(context: Context) {
        try {
            val backupDir = getBackupDir() ?: return
            val sourceFile = SubscriptionManager.getSubscriptionsFile(context)
            if (sourceFile.exists()) {
                val destFile = File(backupDir, "subscriptions.json")
                sourceFile.copyTo(destFile, overwrite = true)
                Log.d(TAG, "Subscriptions backed up to: ${destFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to backup subscriptions", e)
        }
    }

    /**
     * 备份配置文件
     */
    fun backupConfig(context: Context, clashDir: File) {
        try {
            val backupDir = getBackupDir() ?: return

            // 备份 config.yaml
            val configFile = File(clashDir, "config.yaml")
            if (configFile.exists()) {
                configFile.copyTo(File(backupDir, "config.yaml"), overwrite = true)
                Log.d(TAG, "Config backed up")
            }

            // 备份所有订阅文件 (sub_*.yaml)
            clashDir.listFiles()?.filter { it.name.startsWith("sub_") && it.name.endsWith(".yaml") }?.forEach { file ->
                file.copyTo(File(backupDir, file.name), overwrite = true)
                Log.d(TAG, "Subscription file backed up: ${file.name}")
            }

            // 备份 active_config.txt
            val activeConfigFile = File(clashDir, "active_config.txt")
            if (activeConfigFile.exists()) {
                activeConfigFile.copyTo(File(backupDir, "active_config.txt"), overwrite = true)
                Log.d(TAG, "Active config backed up")
            }

            // 备份分应用代理配置
            val appProxyFile = File(clashDir, "app_proxy_config.json")
            if (appProxyFile.exists()) {
                appProxyFile.copyTo(File(backupDir, "app_proxy_config.json"), overwrite = true)
                Log.d(TAG, "App proxy config backed up")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to backup config", e)
        }
    }

    /**
     * 从备份恢复数据
     * @return true 如果恢复了数据
     */
    fun restoreFromBackup(context: Context, clashDir: File): Boolean {
        var restored = false
        try {
            val backupDir = getBackupDir()
            if (backupDir == null || !backupDir.exists()) {
                Log.d(TAG, "No backup directory found")
                return false
            }

            // 检查内部存储是否已有数据（如果有就不恢复，避免覆盖）
            val subscriptionsFile = SubscriptionManager.getSubscriptionsFile(context)
            if (subscriptionsFile.exists() && subscriptionsFile.length() > 10) {
                Log.d(TAG, "Internal data exists, skip restore")
                return false
            }

            // 恢复订阅数据
            val backupSubscriptions = File(backupDir, "subscriptions.json")
            if (backupSubscriptions.exists()) {
                backupSubscriptions.copyTo(subscriptionsFile, overwrite = true)
                Log.d(TAG, "Subscriptions restored from backup")
                restored = true
            }

            // 恢复配置文件
            val backupConfig = File(backupDir, "config.yaml")
            if (backupConfig.exists()) {
                if (!clashDir.exists()) clashDir.mkdirs()
                backupConfig.copyTo(File(clashDir, "config.yaml"), overwrite = true)
                Log.d(TAG, "Config restored from backup")
                restored = true
            }

            // 恢复所有订阅文件
            backupDir.listFiles()?.filter { it.name.startsWith("sub_") && it.name.endsWith(".yaml") }?.forEach { file ->
                file.copyTo(File(clashDir, file.name), overwrite = true)
                Log.d(TAG, "Subscription file restored: ${file.name}")
                restored = true
            }

            // 恢复 active_config.txt
            val backupActiveConfig = File(backupDir, "active_config.txt")
            if (backupActiveConfig.exists()) {
                backupActiveConfig.copyTo(File(clashDir, "active_config.txt"), overwrite = true)
                Log.d(TAG, "Active config restored from backup")
                restored = true
            }

            // 恢复分应用代理配置
            val backupAppProxy = File(backupDir, "app_proxy_config.json")
            if (backupAppProxy.exists()) {
                backupAppProxy.copyTo(File(clashDir, "app_proxy_config.json"), overwrite = true)
                Log.d(TAG, "App proxy config restored from backup")
                restored = true
            }

            if (restored) {
                Log.d(TAG, "Data restored from backup successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore from backup", e)
        }
        return restored
    }

    /**
     * 备份所有数据
     */
    fun backupAll(context: Context, clashDir: File) {
        backupSubscriptions(context)
        backupConfig(context, clashDir)
    }

    /**
     * 检查是否有备份
     */
    fun hasBackup(): Boolean {
        val backupDir = getBackupDir()
        return backupDir != null && backupDir.exists() &&
               (File(backupDir, "subscriptions.json").exists() || File(backupDir, "config.yaml").exists())
    }

    /**
     * 强制从备份恢复数据（忽略现有数据检查）
     * @return true 如果恢复了数据
     */
    fun forceRestoreFromBackup(context: Context, clashDir: File): Boolean {
        var restored = false
        try {
            val backupDir = getBackupDir()
            if (backupDir == null || !backupDir.exists()) {
                Log.d(TAG, "No backup directory found")
                return false
            }

            if (!clashDir.exists()) clashDir.mkdirs()

            // 恢复订阅数据
            val subscriptionsFile = SubscriptionManager.getSubscriptionsFile(context)
            val backupSubscriptions = File(backupDir, "subscriptions.json")
            if (backupSubscriptions.exists()) {
                subscriptionsFile.parentFile?.mkdirs()
                backupSubscriptions.copyTo(subscriptionsFile, overwrite = true)
                Log.d(TAG, "Subscriptions force restored from backup")
                restored = true
            }

            // 恢复配置文件
            val backupConfig = File(backupDir, "config.yaml")
            if (backupConfig.exists()) {
                backupConfig.copyTo(File(clashDir, "config.yaml"), overwrite = true)
                Log.d(TAG, "Config force restored from backup")
                restored = true
            }

            // 恢复所有订阅文件
            backupDir.listFiles()?.filter { it.name.startsWith("sub_") && it.name.endsWith(".yaml") }?.forEach { file ->
                file.copyTo(File(clashDir, file.name), overwrite = true)
                Log.d(TAG, "Subscription file force restored: ${file.name}")
                restored = true
            }

            // 恢复 active_config.txt
            val backupActiveConfig = File(backupDir, "active_config.txt")
            if (backupActiveConfig.exists()) {
                backupActiveConfig.copyTo(File(clashDir, "active_config.txt"), overwrite = true)
                Log.d(TAG, "Active config force restored from backup")
                restored = true
            }

            // 恢复分应用代理配置
            val backupAppProxy = File(backupDir, "app_proxy_config.json")
            if (backupAppProxy.exists()) {
                backupAppProxy.copyTo(File(clashDir, "app_proxy_config.json"), overwrite = true)
                Log.d(TAG, "App proxy config force restored from backup")
                restored = true
            }

            if (restored) {
                Log.d(TAG, "Data force restored from backup successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to force restore from backup", e)
        }
        return restored
    }
}
