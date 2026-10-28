package top.yukonga.mishka.ui.screen.subscription

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.mishka.R
import top.yukonga.mishka.data.repository.ChainCatalogEntry
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.viewmodel.SubscriptionViewModel
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
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
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 链式代理 —— 从已有订阅里按顺序挑节点串成一条链（仿 NekoBox 的代理链），
 * 保存后生成一份本地配置：流量先经过排在前面的节点，最后从末尾节点出去。
 */
@Composable
fun SubscriptionChainScreen(
    viewModel: SubscriptionViewModel? = null,
    onBack: () -> Unit = {},
    onSaved: () -> Unit = {},
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    val catalog by viewModel?.chainCatalog?.collectAsStateWithLifecycle()
        ?: return
    LaunchedEffect(Unit) { viewModel?.loadChainCatalog() }

    var chainName by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<ChainCatalogEntry>() }
    fun toggle(entry: ChainCatalogEntry) {
        if (!selected.remove(entry)) selected.add(entry)
    }
    val groups = remember(catalog) { catalog.groupBy { it.profileName } }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.subscription_chain),
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
            item(key = "name_title") {
                SmallTitle(text = stringResource(R.string.chain_name))
            }
            item(key = "name_field") {
                TextField(
                    value = chainName,
                    onValueChange = { chainName = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 6.dp),
                    label = stringResource(R.string.chain_name_placeholder),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
            }
            item(key = "hint") {
                Text(
                    text = if (selected.isEmpty()) {
                        stringResource(R.string.chain_order_hint)
                    } else {
                        selected.joinToString(" → ") { it.node.name } +
                            "  ·  " + stringResource(R.string.chain_selected_count, selected.size)
                    },
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 6.dp),
                )
            }
            if (catalog.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.chain_no_nodes),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            for ((profileName, entries) in groups) {
                item(key = "group_$profileName") {
                    SmallTitle(text = profileName)
                }
                item(key = "card_$profileName") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 6.dp),
                    ) {
                        for (entry in entries) {
                            val orderIndex = selected.indexOf(entry)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { toggle(entry) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    state = if (orderIndex >= 0) {
                                        ToggleableState.On
                                    } else {
                                        ToggleableState.Off
                                    },
                                    onClick = { toggle(entry) },
                                )
                                Text(
                                    text = entry.node.name,
                                    fontSize = 15.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 12.dp),
                                )
                                if (orderIndex >= 0) {
                                    Text(
                                        text = "${orderIndex + 1}",
                                        fontSize = 14.sp,
                                        color = MiuixTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item(key = "save") {
                TextButton(
                    text = stringResource(R.string.common_save),
                    onClick = {
                        viewModel?.addChainProfile(
                            name = chainName.trim(),
                            nodes = selected.map { it.node },
                            onComplete = onSaved,
                        )
                    },
                    enabled = selected.size >= 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(top = 6.dp, bottom = 12.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
            if (selected.size < 2) {
                item(key = "save_hint") {
                    Text(
                        text = stringResource(R.string.chain_empty_hint),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                }
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
