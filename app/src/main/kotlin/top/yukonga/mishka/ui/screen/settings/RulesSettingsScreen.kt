package top.yukonga.mishka.ui.screen.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.DpSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.mishka.R
import top.yukonga.mishka.service.ProfileFileOps
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.CardItem
import top.yukonga.mishka.ui.component.HintCard
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.groupedCardItems
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.ui.util.WideContentBox
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.viewmodel.OverrideProfileViewModel
import top.yukonga.mishka.viewmodel.SubscriptionViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

// 顶层 `rules:` 的行匹配：`rules:` 后只允许跟注释
private val RULES_KEY = Regex("""^rules:\s*(#.*)?$""")

/**
 * 极简 YAML 解析：只认顶层 `rules:` 下 `- ` 开头的列表项。
 * 项目里没有 snakeyaml 依赖，而规则行本身是无嵌套的纯字符串列表，
 * 这种行解析足够用；复杂写法请走「文件管理」直接编辑订阅 YAML。
 *
 * @return 有 `rules:` 段时返回解析结果（可能为空），否则返回 null
 */
private fun parseRulesYaml(yaml: String?): List<String>? {
    if (yaml == null) return null
    val lines = yaml.lines()
    val start = lines.indexOfFirst { RULES_KEY.matches(it.trim()) }
    if (start == -1) return null
    val result = mutableListOf<String>()
    for (i in start + 1 until lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
        // 顶格且不是 `-` 开头 = 下一个顶层 key，rules 段结束
        if (!line.startsWith(" ") && !line.startsWith("\t") && !trimmed.startsWith("-")) break
        if (trimmed.startsWith("- ")) {
            result.add(trimmed.removePrefix("- ").trim())
        } else if (trimmed.startsWith("-") && trimmed.length > 1) {
            // 容错：`-` 后没空格的写法
            result.add(trimmed.removePrefix("-").trim())
        }
    }
    return result
}

private fun buildRulesYaml(rules: List<String>): String = buildString {
    // 固定文案不嵌覆写名：名字随界面语言变，嵌进文件会让切语言后保存被误判为内容变化
    appendLine("# 由规则设置页自动生成，会整体替换订阅自带的 rules")
    appendLine("rules:")
    rules.forEach { appendLine("  - $it") }
}

/** 读当前生效的规则：优先上次保存的覆写内容，没有再读订阅自带的 config.yaml。 */
private suspend fun loadRules(
    context: Context,
    overrideViewModel: OverrideProfileViewModel,
    subscriptionId: String,
    rulesName: String,
): List<String> = withContext(Dispatchers.IO) {
    val overrideProfile = overrideViewModel.resolveRulesOverride(rulesName)
    val overrideYaml = overrideProfile?.let {
        runCatching { overrideViewModel.readContent(it.id) }.getOrNull()
    }
    parseRulesYaml(overrideYaml)?.let { return@withContext it }
    val configFile = ProfileFileOps.getSubscriptionConfigFile(context, subscriptionId)
    if (configFile.isFile) {
        parseRulesYaml(configFile.readText()) ?: emptyList()
    } else {
        emptyList()
    }
}

@Composable
fun RulesSettingsScreen(
    subscriptionViewModel: SubscriptionViewModel,
    overrideViewModel: OverrideProfileViewModel,
    onBack: () -> Unit = {},
) {
    val subscriptionState by subscriptionViewModel.uiState.collectAsStateWithLifecycle()
    val overrideState by overrideViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val rulesName = stringResource(R.string.settings_rules_override_name)
    val activeSubscription = subscriptionState.subscriptions.find { it.isActive }
    val activeId = activeSubscription?.id

    val rules = remember { mutableStateListOf<String>() }
    var loaded by remember { mutableStateOf(false) }
    var newRule by remember { mutableStateOf("") }

    // 只在激活订阅变化时重载，避免保存后 profiles 刷新把正在编辑的内容冲掉
    LaunchedEffect(activeId) {
        rules.clear()
        loaded = false
        if (activeId != null) {
            rules.addAll(loadRules(context, overrideViewModel, activeId, rulesName))
        }
        loaded = true
    }
    DisposableEffect(overrideViewModel) { onDispose { overrideViewModel.clearError() } }

    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
    val canSave = loaded && activeId != null && !overrideState.isLoading

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.settings_rules_title),
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
                    actions = {
                        IconButton(
                            enabled = canSave,
                            onClick = {
                                activeId?.let { id ->
                                    overrideViewModel.saveRulesOverride(
                                        subscriptionId = id,
                                        name = rulesName,
                                        content = buildRulesYaml(rules),
                                    )
                                }
                            },
                        ) {
                            if (overrideState.isLoading) {
                                CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    imageVector = MiuixIcons.Ok,
                                    contentDescription = stringResource(R.string.common_save),
                                    tint = if (canSave) MiuixTheme.colorScheme.onSurface
                                    else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
                                )
                            }
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        WideContentBox { sidePadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalCutoutPadding()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    start = sidePadding,
                    end = sidePadding,
                ),
            ) {
                item(key = "hint") {
                    HintCard(text = stringResource(R.string.settings_rules_hint))
                }

                if (overrideState.error.isNotEmpty()) {
                    item(key = "error") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                            insideMargin = PaddingValues(16.dp),
                        ) {
                            Text(text = overrideState.error, color = StatusColors.danger)
                        }
                    }
                }

                if (activeId == null) {
                    item(key = "no_subscription") {
                        HintCard(text = stringResource(R.string.settings_rules_empty_subscription))
                    }
                } else {
                    item(key = "list_title") {
                        SmallTitle(text = stringResource(R.string.settings_rules_list_title))
                    }
                    if (rules.isEmpty()) {
                        item(key = "rules_empty") {
                            HintCard(text = stringResource(R.string.settings_rules_empty))
                        }
                    } else {
                        groupedCardItems(
                            keyPrefix = "rules_list",
                            items = rules.mapIndexed { index, rule ->
                                CardItem("rule_$index") {
                                    BasicComponent(
                                        title = rule,
                                        endActions = {
                                            IconButton(
                                                onClick = { rules.removeAt(index) },
                                                minHeight = 35.dp,
                                                minWidth = 35.dp,
                                                backgroundColor = Color.Transparent,
                                            ) {
                                                Icon(
                                                    modifier = Modifier.size(20.dp),
                                                    imageVector = MiuixIcons.Delete,
                                                    contentDescription = stringResource(R.string.common_delete),
                                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                )
                                            }
                                        },
                                    )
                                }
                            },
                        )
                        item(key = "rules_clear") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)
                                    .padding(bottom = 6.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(
                                    text = stringResource(R.string.settings_rules_clear),
                                    onClick = { rules.clear() },
                                )
                            }
                        }
                    }

                    item(key = "add_title") {
                        SmallTitle(text = stringResource(R.string.settings_rules_add_title))
                    }
                    item(key = "add_row") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextField(
                                value = newRule,
                                onValueChange = { newRule = it },
                                // 与两侧 40dp 圆形按钮等高，默认 16dp 垂直内边距会把高度撑到 ~53dp
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp),
                                insideMargin = DpSize(16.dp, 8.dp),
                                label = stringResource(R.string.settings_rules_add_placeholder),
                                useLabelAsPlaceholder = true,
                            )
                            IconButton(
                                onClick = {
                                    val rule = newRule.trim()
                                    if (rule.isNotEmpty() && rule !in rules) {
                                        rules.add(rule)
                                        newRule = ""
                                    }
                                },
                                minHeight = 40.dp,
                                minWidth = 40.dp,
                                backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
                            ) {
                                Icon(
                                    modifier = Modifier.size(20.dp),
                                    imageVector = MiuixIcons.Add,
                                    contentDescription = stringResource(R.string.settings_rules_add),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }

                item(key = "bottom_spacer") {
                    Spacer(Modifier.height(24.dp).navigationBarsPadding())
                }
            }
        }
    }
}
