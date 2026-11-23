package com.example.clashmeta.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 内置 GeoIP 数据库安装器。
 *
 * 内核解析 `GEOIP,...` 规则时，如果工作目录里没有 geoip.metadb，会按 geox-url 去 GitHub
 * 下载；而这段下载发生在 VPN/TUN 建立之前，墙内直连 GitHub 必然失败，报
 * `can't download MMDB`，整份配置解析失败、VPN 起不来。数据库内容变化很慢（上游基本每天
 * 重建，但中国 IP 段变动很小），所以随 APK 带一份快照，启动时复制到工作目录即可。
 *
 * 需要重新安装的判定依据：
 *  - assets 快照版本号（[MMDB_ASSET_VERSION]）与上次安装记录不一致；
 *  - 目标文件缺失，或不是完整 MMDB（[isUsable] 检查文件尾部标记，能识别上次下载到一半的残文件）。
 *
 * 复制先写 `.tmp` 再改名，进程中途被杀也不会在工作目录留下半截数据库——内核校验不过会删掉
 * 重新下载，那就又回到墙内下载失败的老路了。
 */
object GeoDataInstaller {

    private const val TAG = "GeoDataInstaller"
    private const val PREFS_NAME = "geo_assets"
    private const val KEY_MMDB_VERSION = "mmdb_version"

    /** assets 文件名。换新快照时同步 +1 [MMDB_ASSET_VERSION]，老文件才会被覆盖。 */
    private const val MMDB_ASSET = "geoip.metadb"
    private const val MMDB_ASSET_VERSION = 20261123

    private const val TEMP_SUFFIX = ".tmp"

    /** 完整 MMDB 文件末尾带 "MaxMind.com" 元数据标记；半截下载不会有。 */
    private const val MMDB_MARKER = "MaxMind.com"
    private const val TAIL_BYTES = 16 * 1024

    /** 使用当前数据目录的便捷重载。 */
    fun ensureGeoIp(context: Context): Boolean =
        ensureGeoIp(context, com.example.clashmeta.ClashMetaApp.instance.getClashDir())

    /**
     * 确保 [clashDir] 里有可用的内置 geoip.metadb。
     * 需在 IO 线程调用（首次安装要复制 8MB 左右）。返回 true 表示文件已就绪。
     */
    fun ensureGeoIp(context: Context, clashDir: File): Boolean {
        val dest = File(clashDir, MMDB_ASSET)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_MMDB_VERSION, 0) == MMDB_ASSET_VERSION && isUsable(dest)) {
            return true
        }

        val tmp = File(clashDir, MMDB_ASSET + TEMP_SUFFIX)
        return try {
            if (!clashDir.exists() && !clashDir.mkdirs()) {
                Log.w(TAG, "clash dir not creatable: ${clashDir.absolutePath}")
                return false
            }
            context.assets.open(MMDB_ASSET).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            }
            if (!isUsable(tmp)) {
                // assets 打包坏了或文件被裁掉：不覆盖现有文件，交回内核自行处理
                Log.w(TAG, "bundled $MMDB_ASSET is invalid, skip install")
                tmp.delete()
                return false
            }
            try {
                Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                // 部分外置存储不支持原子 move，退回普通覆盖
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            prefs.edit().putInt(KEY_MMDB_VERSION, MMDB_ASSET_VERSION).apply()
            Log.d(TAG, "installed $MMDB_ASSET (${dest.length()} bytes) -> ${dest.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "install $MMDB_ASSET failed", e)
            tmp.delete()
            // 旧文件还能用就继续用，让内核自己去决定是否重下
            isUsable(dest)
        }
    }

    /** 快速校验：完整 MMDB 尾部一定带 "MaxMind.com" 元数据标记。 */
    private fun isUsable(file: File): Boolean {
        if (!file.isFile || file.length() < TAIL_BYTES) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(maxOf(0L, raf.length() - TAIL_BYTES))
                val buf = ByteArray(TAIL_BYTES)
                var read = 0
                while (read < buf.size) {
                    val n = raf.read(buf, read, buf.size - read)
                    if (n <= 0) break
                    read += n
                }
                read > 0 && String(buf, 0, read, Charsets.ISO_8859_1).contains(MMDB_MARKER)
            }
        } catch (e: Exception) {
            Log.w(TAG, "check $file failed: ${e.message}")
            false
        }
    }
}
