package top.yukonga.mishka.data.repository

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode

/** 从某个订阅里提取出的一个节点（原始 YAML 映射）。 */
class ChainNode(val name: String, val proxy: YamlMap)

/** 节点目录条目：节点 + 它所属的订阅。 */
class ChainCatalogEntry(val profileId: String, val profileName: String, val node: ChainNode)

/**
 * 链式代理的配置拼装（仿 NekoBox 的代理链）：把多个已有节点按顺序串起来，
 * 流量先经过排在前面的节点，最后从末尾节点出去。mihomo 侧靠 dialer-proxy 表达：
 * 除入口节点外，每个节点的 dialer-proxy 指向它前面的节点，最终被选中的是出口节点。
 *
 * 节点映射直接沿用源配置里解析出的 YAML 节点原样发射，只剥掉源节点自带的
 * dialer-proxy 并按链序补上；同名节点自动改名并同步改写其 name 字段。
 */
object ChainYaml {

    /** 从一份订阅配置文本里提取 proxies；解析失败或没有节点时返回空列表。 */
    fun extractProxies(configYaml: String): List<ChainNode> {
        val root = runCatching { Yaml.default.parseToYamlNode(configYaml) }.getOrNull()
        val rootMap = root as? YamlMap ?: return emptyList()
        val proxies = mapValue(rootMap, "proxies") as? YamlList ?: return emptyList()
        return proxies.items.mapNotNull { item ->
            val map = item as? YamlMap ?: return@mapNotNull null
            val name = (mapValue(map, "name") as? YamlScalar)?.content?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            ChainNode(name, map)
        }
    }

    /**
     * 把按链路顺序（入口在前、出口在后）选好的节点拼成一份完整配置。
     * 调用方保证至少两个节点。
     */
    fun buildChainConfig(ordered: List<ChainNode>): String {
        // 同名去重：后续同名节点改名为 "名字 (n)"，引用一律用新名
        val usedNames = mutableSetOf<String>()
        val uniqueNames = ordered.map { node ->
            var candidate = node.name
            var suffix = 2
            while (!usedNames.add(candidate)) {
                candidate = "${node.name} (${suffix++})"
            }
            candidate
        }
        val sb = StringBuilder()
        sb.append("proxies:\n")
        ordered.forEachIndexed { index, node ->
            val dialer = if (index >= 1) uniqueNames[index - 1] else null
            emitProxyItem(sb, node.proxy, nameOverride = uniqueNames[index], dialerProxy = dialer)
        }
        sb.append(
            """
            dns:
              enable: true
              ipv6: false
              enhanced-mode: fake-ip
              fake-ip-range: 198.18.0.1/16
              nameserver:
                - https://dns.alidns.com/dns-query
                - https://doh.pub/dns-query
            proxy-groups:
              - name: Sakura
                type: select
                proxies:
            """.trimIndent()
        )
        sb.append("\n      - ").append(scalarText(uniqueNames.last())).append('\n')
        sb.append("rules:\n  - MATCH,Sakura\n")
        return sb.toString()
    }

    private fun mapValue(map: YamlMap, key: String): YamlNode? =
        map.entries.entries.firstOrNull { (it.key as? YamlScalar)?.content == key }?.value

    /** 发射一个 proxies 条目：剥掉源 dialer-proxy、按需改写 name、链序非首个补 dialer-proxy。 */
    private fun emitProxyItem(sb: StringBuilder, map: YamlMap, nameOverride: String, dialerProxy: String?) {
        val block = StringBuilder()
        var first = true
        for ((keyNode, valueNode) in map.entries) {
            val key = (keyNode as? YamlScalar)?.content ?: continue
            if (key == "dialer-proxy") continue
            block.append(if (first) "  - " else "    ")
            first = false
            if (key == "name") {
                block.append("name: ").append(scalarText(nameOverride)).append('\n')
            } else {
                block.append(scalarText(key)).append(':')
                emitValue(block, valueNode, indent = 4)
            }
        }
        if (dialerProxy != null) {
            block.append("    dialer-proxy: ").append(scalarText(dialerProxy)).append('\n')
        }
        sb.append(block)
    }

    /** 把一个值写在 "key:" 之后：标量同行，复合结构换行缩进。 */
    private fun emitValue(sb: StringBuilder, node: YamlNode, indent: Int) {
        when (node) {
            is YamlScalar -> sb.append(' ').append(scalarText(node.content)).append('\n')
            is YamlNull -> sb.append(" null\n")
            is YamlTaggedNode -> emitValue(sb, node.innerNode, indent)
            is YamlList -> {
                if (node.items.isEmpty()) {
                    sb.append(" []\n")
                } else {
                    sb.append('\n')
                    node.items.forEach { item -> emitListItem(sb, item, indent) }
                }
            }

            is YamlMap -> {
                if (node.entries.isEmpty()) {
                    sb.append(" {}\n")
                } else {
                    sb.append('\n')
                    node.entries.forEach { (k, v) -> emitMapEntry(sb, k, v, indent) }
                }
            }
        }
    }

    private fun emitMapEntry(sb: StringBuilder, keyNode: YamlNode, valueNode: YamlNode, indent: Int) {
        val key = (keyNode as? YamlScalar)?.content ?: return
        sb.append(" ".repeat(indent)).append(scalarText(key)).append(':')
        emitValue(sb, valueNode, indent + 2)
    }

    private fun emitListItem(sb: StringBuilder, node: YamlNode, indent: Int) {
        when (node) {
            is YamlScalar -> sb.append(" ".repeat(indent)).append("- ").append(scalarText(node.content)).append('\n')
            is YamlNull -> sb.append(" ".repeat(indent)).append("- null\n")
            is YamlTaggedNode -> emitListItem(sb, node.innerNode, indent)
            is YamlList -> {
                if (node.items.isEmpty()) {
                    sb.append(" ".repeat(indent)).append("- []\n")
                } else {
                    // 嵌套列表：先写一个孤立的 "- "，子项再缩进
                    sb.append(" ".repeat(indent)).append("-\n")
                    node.items.forEach { emitListItem(sb, it, indent + 2) }
                }
            }

            is YamlMap -> {
                if (node.entries.isEmpty()) {
                    sb.append(" ".repeat(indent)).append("- {}\n")
                } else {
                    var first = true
                    for ((k, v) in node.entries) {
                        val key = (k as? YamlScalar)?.content ?: continue
                        if (first) {
                            sb.append(" ".repeat(indent)).append("- ").append(scalarText(key)).append(':')
                            first = false
                            emitValue(sb, v, indent + 4)
                        } else {
                            sb.append(" ".repeat(indent + 2)).append(scalarText(key)).append(':')
                            emitValue(sb, v, indent + 4)
                        }
                    }
                }
            }
        }
    }

    /**
     * 标量发射：内容是保守的安全纯量时原样输出（数字/布尔也保持纯量，
     * 让 YAML 读回原类型），否则用双引号并转义。
     */
    internal fun scalarText(raw: String): String {
        if (isPlainSafe(raw)) return raw
        val sb = StringBuilder("\"")
        for (ch in raw) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.append('"').toString()
    }

    private fun isPlainSafe(raw: String): Boolean {
        if (raw.isEmpty()) return false
        if (raw.first() in "-?:,[]{}#&*!|>'\"%@`" && raw.length == 1) return false
        if (raw.first() in "!&*[]{},#|>@`\"'") return false
        if (raw.startsWith("- ") || raw.startsWith("? ") || raw.startsWith(": ")) return false
        if (raw.endsWith(':') || raw.endsWith(' ')) return false
        if (raw.contains(": ") || raw.contains(" #")) return false
        if (raw.any { it.code < 0x20 }) return false
        return true
    }
}
