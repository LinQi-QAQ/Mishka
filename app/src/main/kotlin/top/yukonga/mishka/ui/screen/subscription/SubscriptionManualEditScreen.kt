package top.yukonga.mishka.ui.screen.subscription

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.mishka.R
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 手动设置支持的协议：key 与内核侧 sharelink 转换器、本地 ShareLinkBuilder 对齐。 */
val MANUAL_PROTOCOLS: List<Pair<String, String>> = listOf(
    "socks5" to "SOCKS5",
    "http" to "HTTP",
    "ss" to "Shadowsocks",
    "vmess" to "VMess",
    "vless" to "VLESS",
    "trojan" to "Trojan",
    "hysteria" to "Hysteria",
    "hysteria2" to "Hysteria2",
    "tuic" to "TUIC",
    "anytls" to "AnyTLS",
    "wireguard" to "WireGuard",
    "ssr" to "ShadowsocksR",
)

private sealed interface ManualField {
    val key: String

    data class Text(
        override val key: String,
        @param:StringRes val label: Int,
        val default: String = "",
        val numeric: Boolean = false,
    ) : ManualField

    data class Choice(
        override val key: String,
        @param:StringRes val label: Int,
        val options: List<String>,
        val default: String = "",
    ) : ManualField

    data class Toggle(
        override val key: String,
        @param:StringRes val label: Int,
        val default: Boolean = false,
    ) : ManualField
}

private fun defaultPort(protocol: String): String = when (protocol) {
    "socks5" -> "1080"
    "http" -> "8080"
    "ss", "ssr" -> "8388"
    "wireguard" -> "51820"
    else -> "443"
}

private fun pathLabelRes(transport: String): Int =
    if (transport == "grpc") R.string.manual_service_name else R.string.manual_path

/**
 * 表单字段表。[get] 读同表其他字段的当前值（未改动时回退到该字段默认值），
 * 供条件字段（插件 / Reality / 传输相关）判断是否出现；[all] 时返回全部字段。
 */
private fun manualSpecs(
    protocol: String,
    get: (String) -> String,
    all: Boolean = false,
): List<ManualField> {
    val base = listOf(
        ManualField.Text("name", R.string.manual_name),
        ManualField.Text("server", R.string.manual_server),
        ManualField.Text("port", R.string.manual_port, defaultPort(protocol), numeric = true),
    )
    val transport = get("transport").ifEmpty { "tcp" }
    val extra: List<ManualField> = when (protocol) {
        "socks5", "http" -> listOf(
            ManualField.Text("username", R.string.manual_username),
            ManualField.Text("password", R.string.manual_password),
        )

        "ss" -> buildList {
            add(
                ManualField.Choice(
                    "method", R.string.manual_method,
                    listOf(
                        "aes-256-gcm", "aes-128-gcm", "chacha20-ietf-poly1305",
                        "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm",
                        "2022-blake3-chacha20-poly1305",
                    ),
                    "aes-256-gcm",
                )
            )
            add(ManualField.Text("password", R.string.manual_password))
            add(ManualField.Choice("plugin", R.string.manual_plugin, listOf("", "obfs", "v2ray-plugin"), ""))
            if (all || get("plugin") == "obfs") {
                add(ManualField.Choice("obfsMode", R.string.manual_obfs_mode, listOf("http", "tls"), "http"))
                add(ManualField.Text("obfsHost", R.string.manual_obfs_host))
            }
            if (all || get("plugin") == "v2ray-plugin") {
                add(ManualField.Text("pluginHost", R.string.manual_host))
                add(ManualField.Text("pluginPath", R.string.manual_path))
                add(ManualField.Toggle("pluginTls", R.string.manual_tls))
            }
        }

        "vmess" -> buildList {
            add(ManualField.Text("uuid", R.string.manual_uuid))
            add(ManualField.Text("alterId", R.string.manual_alter_id, "0", numeric = true))
            add(
                ManualField.Choice(
                    "cipher", R.string.manual_method,
                    listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none"), "auto",
                )
            )
            add(
                ManualField.Choice(
                    "transport", R.string.manual_transport,
                    listOf("tcp", "ws", "grpc", "h2"), "tcp",
                )
            )
            add(ManualField.Toggle("tls", R.string.manual_tls))
            add(ManualField.Text("sni", R.string.manual_sni))
            if (all || transport != "tcp") {
                add(ManualField.Text("host", R.string.manual_host))
                add(ManualField.Text("path", pathLabelRes(transport)))
            }
        }

        "vless" -> buildList {
            add(ManualField.Text("uuid", R.string.manual_uuid))
            add(ManualField.Choice("flow", R.string.manual_flow, listOf("", "xtls-rprx-vision"), ""))
            add(
                ManualField.Choice(
                    "security", R.string.manual_security,
                    listOf("none", "tls", "reality"), "none",
                )
            )
            add(ManualField.Text("sni", R.string.manual_sni))
            add(ManualField.Text("fp", R.string.manual_fingerprint, "chrome"))
            if (all || get("security") == "reality") {
                add(ManualField.Text("pbk", R.string.manual_reality_public_key))
                add(ManualField.Text("sid", R.string.manual_reality_short_id))
            }
            add(
                ManualField.Choice(
                    "transport", R.string.manual_transport,
                    listOf("tcp", "ws", "grpc", "h2", "xhttp"), "tcp",
                )
            )
            if (all || transport != "tcp") {
                if (transport != "grpc") {
                    add(ManualField.Text("host", R.string.manual_host))
                }
                add(ManualField.Text("path", pathLabelRes(transport)))
            }
        }

        "trojan" -> buildList {
            add(ManualField.Text("password", R.string.manual_password))
            add(ManualField.Text("sni", R.string.manual_sni))
            add(
                ManualField.Choice(
                    "transport", R.string.manual_transport,
                    listOf("tcp", "ws", "grpc"), "tcp",
                )
            )
            if (all || transport == "ws") {
                add(ManualField.Text("host", R.string.manual_host))
                add(ManualField.Text("path", R.string.manual_path))
            }
            if (all || transport == "grpc") {
                add(ManualField.Text("path", R.string.manual_service_name))
            }
            add(ManualField.Toggle("allowInsecure", R.string.manual_allow_insecure))
        }

        "hysteria" -> listOf(
            ManualField.Text("auth", R.string.manual_auth),
            ManualField.Text("sni", R.string.manual_sni),
            ManualField.Text("obfs", R.string.manual_obfs),
            ManualField.Toggle("allowInsecure", R.string.manual_allow_insecure),
        )

        "hysteria2" -> listOf(
            ManualField.Text("password", R.string.manual_password),
            ManualField.Text("sni", R.string.manual_sni),
            ManualField.Text("obfsPassword", R.string.manual_obfs_password),
            ManualField.Toggle("allowInsecure", R.string.manual_allow_insecure),
        )

        "tuic" -> listOf(
            ManualField.Text("uuid", R.string.manual_uuid),
            ManualField.Text("password", R.string.manual_password),
            ManualField.Choice(
                "congestion", R.string.manual_congestion,
                listOf("bbr", "cubic", "new-reno"), "bbr",
            ),
            ManualField.Text("sni", R.string.manual_sni),
            ManualField.Text("alpn", R.string.manual_alpn, "h3"),
            ManualField.Toggle("allowInsecure", R.string.manual_allow_insecure),
        )

        "anytls" -> listOf(
            ManualField.Text("password", R.string.manual_password),
            ManualField.Text("sni", R.string.manual_sni),
            ManualField.Text("fp", R.string.manual_fingerprint, "chrome"),
            ManualField.Toggle("allowInsecure", R.string.manual_allow_insecure),
        )

        "wireguard" -> listOf(
            ManualField.Text("privateKey", R.string.manual_private_key),
            ManualField.Text("publicKey", R.string.manual_public_key),
            ManualField.Text("presharedKey", R.string.manual_preshared_key),
            ManualField.Text("address", R.string.manual_address, "172.16.0.2/32"),
            ManualField.Text("mtu", R.string.manual_mtu, numeric = true),
        )

        "ssr" -> listOf(
            ManualField.Text("password", R.string.manual_password),
            ManualField.Choice(
                "method", R.string.manual_method,
                listOf("aes-256-cfb", "aes-128-cfb", "chacha20-ietf", "rc4-md5", "none"),
                "aes-256-cfb",
            ),
            ManualField.Choice(
                "protocol", R.string.manual_protocol,
                listOf("origin", "auth_sha1_v4", "auth_aes128_md5", "auth_aes128_sha1", "auth_chain_a", "auth_chain_b"),
                "origin",
            ),
            ManualField.Choice(
                "obfs", R.string.manual_obfs,
                listOf("plain", "http_simple", "http_post", "random_head", "tls1.2_ticket_auth", "tls1.2_ticket_fastauth"),
                "plain",
            ),
            ManualField.Text("protocolParam", R.string.manual_protocol_param),
            ManualField.Text("obfsParam", R.string.manual_obfs_param),
        )

        else -> emptyList()
    }
    return base + extra
}

/**
 * 手动设置 —— 单个协议的节点表单，保存时拼成分享链接走本地导入管道。
 */
@Composable
fun SubscriptionManualEditScreen(
    protocol: String,
    onBack: () -> Unit = {},
    onSave: (name: String, link: String) -> Unit = { _, _ -> },
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    val textState = remember { mutableStateMapOf<String, String>() }
    val toggleState = remember { mutableStateMapOf<String, Boolean>() }

    fun defaultOf(field: ManualField): String = when (field) {
        is ManualField.Text -> field.default
        is ManualField.Choice -> field.default
        is ManualField.Toggle -> ""
    }
    val allSpecs = remember(protocol) { manualSpecs(protocol, get = { "" }, all = true) }
    fun current(key: String): String =
        textState[key] ?: allSpecs.firstOrNull { it.key == key }?.let { defaultOf(it) }.orEmpty()
    val visibleSpecs = manualSpecs(protocol, get = ::current)

    val displayName = MANUAL_PROTOCOLS.firstOrNull { it.first == protocol }?.second ?: protocol
    val builtLink = ShareLinkBuilder.build(
        protocol,
        allSpecs.filterIsInstance<ManualField.Text>().associate { it.key to current(it.key) } +
            allSpecs.filterIsInstance<ManualField.Choice>().associate { it.key to current(it.key) },
        allSpecs.filterIsInstance<ManualField.Toggle>().associate {
            it.key to (toggleState[it.key] ?: it.default)
        },
    )

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = displayName,
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            val layoutDirection = LocalLayoutDirection.current
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.common_back),
                                tint = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.graphicsLayer {
                                    scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                                },
                            )
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(top = innerPadding.calculateTopPadding()),
        ) {
            for (field in visibleSpecs) {
                when (field) {
                    is ManualField.Text -> {
                        item(key = "title_${field.key}") {
                            SmallTitle(text = stringResource(field.label))
                        }
                        item(key = "field_${field.key}") {
                            TextField(
                                value = current(field.key),
                                onValueChange = { textState[field.key] = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = 6.dp),
                                label = stringResource(field.label),
                                useLabelAsPlaceholder = true,
                                singleLine = true,
                                keyboardOptions = if (field.numeric) {
                                    KeyboardOptions(keyboardType = KeyboardType.Number)
                                } else {
                                    KeyboardOptions.Default
                                },
                            )
                        }
                    }

                    is ManualField.Choice -> {
                        item(key = "choice_${field.key}") {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = 6.dp),
                            ) {
                                OverlayDropdownPreference(
                                    title = stringResource(field.label),
                                    items = field.options.map { it.ifEmpty { "—" } },
                                    selectedIndex = field.options.indexOf(current(field.key))
                                        .coerceAtLeast(0),
                                    onSelectedIndexChange = { index ->
                                        textState[field.key] = field.options[index]
                                    },
                                )
                            }
                        }
                    }

                    is ManualField.Toggle -> {
                        item(key = "toggle_${field.key}") {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = 6.dp),
                            ) {
                                SwitchPreference(
                                    title = stringResource(field.label),
                                    checked = toggleState[field.key] ?: field.default,
                                    onCheckedChange = { toggleState[field.key] = it },
                                )
                            }
                        }
                    }
                }
            }
            item(key = "save") {
                TextButton(
                    text = stringResource(R.string.common_save),
                    onClick = {
                        builtLink?.let { onSave(current("name"), it) }
                    },
                    enabled = builtLink != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(top = 6.dp, bottom = 12.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
            item {
                Spacer(
                    Modifier
                        .height(24.dp)
                        .navigationBarsPadding()
                )
            }
        }
    }
}
