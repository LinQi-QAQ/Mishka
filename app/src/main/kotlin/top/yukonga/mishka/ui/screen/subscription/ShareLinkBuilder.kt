package top.yukonga.mishka.ui.screen.subscription

import android.util.Base64
import org.json.JSONObject

/**
 * 手动设置：把表单字段拼成标准分享链接，之后走与剪贴簿导入相同的本地配置管道，
 * 由内核侧的 sharelink 转换器生成节点。这里的 query 参数名与转换器的解析保持一致。
 */
object ShareLinkBuilder {

    /** 百分号编码：只保留 unreserved 字符，其余按 UTF-8 字节编码。 */
    private fun pct(value: String): String {
        val sb = StringBuilder()
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                ch == '-' || ch == '.' || ch == '_' || ch == '~'
            ) {
                sb.append(ch)
            } else {
                sb.append('%').append(c.toString(16).uppercase().padStart(2, '0'))
            }
        }
        return sb.toString()
    }

    private fun b64UrlNoPad(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun b64Std(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun query(params: List<Pair<String, String>>): String {
        val filled = params.filter { it.second.isNotEmpty() }
        if (filled.isEmpty()) return ""
        return filled.joinToString("&", prefix = "?") { "${it.first}=${pct(it.second)}" }
    }

    private fun userinfo(user: String, pass: String): String = when {
        user.isEmpty() && pass.isEmpty() -> ""
        pass.isEmpty() -> "${pct(user)}@"
        else -> "${pct(user)}:${pct(pass)}@"
    }

    /**
     * @param f 文本/下拉字段，@param s 开关字段。
     * 必填（server / port / 该协议的凭据）缺失时返回 null。
     */
    fun build(protocol: String, f: Map<String, String>, s: Map<String, Boolean>): String? {
        val server = f["server"].orEmpty().trim()
        val port = f["port"].orEmpty().trim().toIntOrNull() ?: return null
        if (server.isEmpty()) return null
        val name = f["name"].orEmpty().trim()
        val frag = if (name.isEmpty()) "" else "#${pct(name)}"
        val hostPort = "$server:$port"
        fun v(key: String) = f[key].orEmpty().trim()
        fun on(key: String) = s[key] == true

        return when (protocol) {
            "socks5" -> "socks5://${userinfo(v("username"), v("password"))}$hostPort$frag"

            "http" -> "http://${userinfo(v("username"), v("password"))}$hostPort$frag"

            "ss" -> {
                val method = v("method").ifEmpty { "aes-256-gcm" }
                val password = v("password")
                if (password.isEmpty()) return null
                val plugin = when (v("plugin")) {
                    "obfs" -> "obfs-local;obfs=${v("obfsMode").ifEmpty { "http" }}" +
                        (if (v("obfsHost").isNotEmpty()) ";obfs-host=${v("obfsHost")}" else "")

                    "v2ray-plugin" -> buildString {
                        append("v2ray-plugin;mode=websocket")
                        if (v("pluginHost").isNotEmpty()) append(";host=${v("pluginHost")}")
                        if (v("pluginPath").isNotEmpty()) append(";path=${v("pluginPath")}")
                        if (on("pluginTls")) append(";tls")
                    }

                    else -> ""
                }
                val q = if (plugin.isEmpty()) "" else "?plugin=${pct(plugin)}"
                "ss://${b64UrlNoPad("$method:$password")}@$hostPort$q$frag"
            }

            "vmess" -> {
                val uuid = v("uuid")
                if (uuid.isEmpty()) return null
                val transport = v("transport").ifEmpty { "tcp" }
                val json = JSONObject().apply {
                    put("v", "2")
                    put("ps", name)
                    put("add", server)
                    put("port", port.toString())
                    put("id", uuid)
                    put("aid", v("alterId").ifEmpty { "0" })
                    put("scy", v("cipher").ifEmpty { "auto" })
                    put("net", transport)
                    put("type", "none")
                    if (v("host").isNotEmpty()) put("host", v("host"))
                    if (v("path").isNotEmpty()) put("path", v("path"))
                    put("tls", if (on("tls")) "tls" else "")
                    if (v("sni").isNotEmpty()) put("sni", v("sni"))
                }
                "vmess://${b64Std(json.toString())}"
            }

            "vless" -> {
                val uuid = v("uuid")
                if (uuid.isEmpty()) return null
                val transport = v("transport").ifEmpty { "tcp" }
                val security = v("security").ifEmpty { "none" }
                val params = mutableListOf(
                    "encryption" to "none",
                    "security" to security,
                    "type" to transport,
                    "flow" to v("flow"),
                    "sni" to v("sni"),
                    "fp" to v("fp"),
                )
                if (security == "reality") {
                    params += "pbk" to v("pbk")
                    params += "sid" to v("sid")
                }
                when (transport) {
                    "ws", "h2", "http", "xhttp" -> {
                        params += "host" to v("host")
                        params += "path" to v("path")
                    }

                    "grpc" -> params += "serviceName" to v("path")
                }
                "vless://$uuid@$hostPort${query(params)}$frag"
            }

            "trojan" -> {
                val password = v("password")
                if (password.isEmpty()) return null
                val transport = v("transport").ifEmpty { "tcp" }
                val params = mutableListOf(
                    "sni" to v("sni"),
                    "type" to transport,
                )
                if (on("allowInsecure")) params += "allowInsecure" to "1"
                when (transport) {
                    "ws" -> {
                        params += "host" to v("host")
                        params += "path" to v("path")
                    }

                    "grpc" -> params += "serviceName" to v("path")
                }
                "trojan://${pct(password)}@$hostPort${query(params)}$frag"
            }

            "hysteria" -> {
                val params = mutableListOf(
                    "auth" to v("auth"),
                    "peer" to v("sni"),
                    "obfs" to v("obfs"),
                )
                if (on("allowInsecure")) params += "insecure" to "1"
                "hysteria://$hostPort${query(params)}$frag"
            }

            "hysteria2" -> {
                val password = v("password")
                if (password.isEmpty()) return null
                val params = mutableListOf("sni" to v("sni"))
                if (v("obfsPassword").isNotEmpty()) {
                    params += "obfs" to "salamander"
                    params += "obfs-password" to v("obfsPassword")
                }
                if (on("allowInsecure")) params += "insecure" to "1"
                "hy2://${pct(password)}@$hostPort${query(params)}$frag"
            }

            "tuic" -> {
                val uuid = v("uuid")
                val password = v("password")
                if (uuid.isEmpty() || password.isEmpty()) return null
                val params = mutableListOf(
                    "congestion_control" to v("congestion").ifEmpty { "bbr" },
                    "sni" to v("sni"),
                    "alpn" to v("alpn"),
                )
                if (on("allowInsecure")) params += "allow_insecure" to "1"
                "tuic://$uuid:${pct(password)}@$hostPort${query(params)}$frag"
            }

            "anytls" -> {
                val password = v("password")
                if (password.isEmpty()) return null
                val params = mutableListOf(
                    "sni" to v("sni"),
                    "fp" to v("fp"),
                )
                if (on("allowInsecure")) params += "insecure" to "1"
                "anytls://${pct(password)}@$hostPort${query(params)}$frag"
            }

            "wireguard" -> {
                val privateKey = v("privateKey")
                val publicKey = v("publicKey")
                if (privateKey.isEmpty() || publicKey.isEmpty()) return null
                val params = mutableListOf(
                    "publickey" to publicKey,
                    "presharedkey" to v("presharedKey"),
                    "address" to v("address"),
                    "mtu" to v("mtu"),
                )
                "wireguard://${pct(privateKey)}@$hostPort${query(params)}$frag"
            }

            "ssr" -> {
                val password = v("password")
                if (password.isEmpty()) return null
                val inner = buildString {
                    append("$server:$port:")
                    append(v("protocol").ifEmpty { "origin" }).append(':')
                    append(v("method").ifEmpty { "aes-256-cfb" }).append(':')
                    append(v("obfs").ifEmpty { "plain" }).append(':')
                    append(b64UrlNoPad(password))
                    append("/?remarks=").append(b64UrlNoPad(name))
                    if (v("protocolParam").isNotEmpty()) {
                        append("&protoparam=").append(b64UrlNoPad(v("protocolParam")))
                    }
                    if (v("obfsParam").isNotEmpty()) {
                        append("&obfsparam=").append(b64UrlNoPad(v("obfsParam")))
                    }
                }
                "ssr://${b64UrlNoPad(inner)}"
            }

            else -> null
        }
    }
}
