package top.yukonga.mishka.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.mishka.BuildConfig
import top.yukonga.mishka.data.repository.SubscriptionProxyResolver
import top.yukonga.mishka.service.ProfileFileOps
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 应用内更新 Geo 数据（geoip.metadb / geosite.dat / ASN.mmdb），下载源与构建期
 * DownloadGeoFilesTask 相同（MetaCubeX/meta-rules-dat 的 latest release）。
 *
 * 按仓库约定，app 内下载一律走本机 mixed-port：经 [SubscriptionProxyResolver] 解析
 * （requireUserToggle = false，与图标等通用资源同级）；代理未运行则直连。
 *
 * 两阶段替换：三份文件先全部下到 geodata 目录的 .part 并逐个校验体积下限，齐了才
 * 逐个 rename 覆盖，中途失败不污染现有数据。替换后文件 mtime 为下载时刻，
 * MishkaApplication 的启动提取只在 app 本身更新后才用预制版覆盖，不会冲掉本次更新。
 * 正在运行的 mihomo 不热加载新数据，UI 侧提示重启代理后生效。
 */
class GeoDataUpdater(
    private val context: Context,
    private val proxyResolver: SubscriptionProxyResolver,
) {
    suspend fun update() {
        val proxy = proxyResolver.resolve(requireUserToggle = false)?.let { url ->
            val uri = URI(url)
            Proxy(Proxy.Type.HTTP, InetSocketAddress(uri.host, uri.port))
        }
        withContext(Dispatchers.IO) {
            val dir = ProfileFileOps.getGeodataDir(context)
            val parts = SOURCES.map { (url, name) -> download(url, File(dir, "$name.part"), proxy) }
            try {
                parts.forEach { part ->
                    check(part.length() >= MIN_FILE_BYTES) { "${part.name} is only ${part.length()} bytes" }
                }
                parts.forEach { part ->
                    val target = File(dir, part.name.removeSuffix(".part"))
                    Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                parts.forEach { if (it.exists()) it.delete() }
            }
        }
    }

    private fun download(url: String, target: File, proxy: Proxy?): File {
        val connection = (URI(url).toURL().openConnection(proxy ?: Proxy.NO_PROXY) as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", "ClashMetaForAndroid/${BuildConfig.VERSION_NAME}")
        }
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "$url responded HTTP ${connection.responseCode}"
            }
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
        return target
    }

    companion object {
        /** (下载地址, 落盘文件名)；与 app/build.gradle.kts 的 DownloadGeoFilesTask 保持一致。 */
        val SOURCES: List<Pair<String, String>> = listOf(
            "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.metadb" to "geoip.metadb",
            "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat" to "geosite.dat",
            "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/GeoLite2-ASN.mmdb" to "ASN.mmdb",
        )

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000

        // 与构建期任务一致的体积下限：防止 404/限流的 HTML 被当作数据文件落盘
        private const val MIN_FILE_BYTES = 512 * 1024L
    }
}
