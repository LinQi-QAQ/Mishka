package top.yukonga.mishka.ui.screen.subscription

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboard
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import top.yukonga.mishka.R
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.platform.getPlainText
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.CardGlassBackground
import top.yukonga.mishka.ui.component.blur.LocalCardBackdrop
import top.yukonga.mishka.ui.component.blur.cardGlassBackdrop
import top.yukonga.mishka.ui.component.blur.cardGlassSurface
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.viewmodel.SubscriptionViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 创建配置 —— 选择添加方式（文件 / URL）
 */
@Composable
fun SubscriptionAddScreen(
    viewModel: SubscriptionViewModel? = null,
    onBack: () -> Unit = {},
    onPickFile: () -> Unit = {},
    onNavigateUrl: () -> Unit = {},
    onScanQR: (() -> Unit)? = null,
    onPickGalleryQr: (() -> Unit)? = null,
    onClipboardImport: (String) -> Unit = {},
    onNavigateManual: () -> Unit = {},
) {
    val scrollBehavior = MiuixScrollBehavior()
    val uiState by viewModel?.uiState?.collectAsStateWithLifecycle()
        ?: return
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    var showQrChooser by remember { mutableStateOf(false) }

    val backdrop = rememberBlurBackdrop()
    val cardBackdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.subscription_create),
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
        CompositionLocalProvider(LocalCardBackdrop provides cardBackdrop) {
            Box(Modifier.fillMaxSize()) {
                CardGlassBackground(cardBackdrop)
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
                    ),
                ) {
                    item {
                        val glassBackdrop = cardGlassBackdrop()
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(top = 12.dp)
                                .then(
                                    if (glassBackdrop != null) {
                                        Modifier.cardGlassSurface(glassBackdrop, 16.dp, 16.dp)
                                    } else {
                                        Modifier
                                    }
                                ),
                            colors = if (glassBackdrop != null) {
                                CardDefaults.defaultColors(color = Color.Transparent)
                            } else {
                                CardDefaults.defaultColors()
                            },
                        ) {
                            if (onScanQR != null && onPickGalleryQr != null) {
                                ArrowPreference(
                                    title = stringResource(R.string.subscription_qr),
                                    summary = stringResource(R.string.subscription_qr_summary),
                                    enabled = !uiState.isLoading,
                                    onClick = { showQrChooser = true },
                                )
                            } else if (onScanQR != null) {
                                ArrowPreference(
                                    title = stringResource(R.string.subscription_qr),
                                    summary = stringResource(R.string.subscription_qr_summary),
                                    enabled = !uiState.isLoading,
                                    onClick = onScanQR,
                                )
                            } else if (onPickGalleryQr != null) {
                                ArrowPreference(
                                    title = stringResource(R.string.subscription_qr_gallery),
                                    summary = stringResource(R.string.subscription_qr_gallery_summary),
                                    enabled = !uiState.isLoading,
                                    onClick = onPickGalleryQr,
                                )
                            }
                            ArrowPreference(
                                title = stringResource(R.string.subscription_clipboard),
                                summary = stringResource(R.string.subscription_clipboard_summary),
                                enabled = !uiState.isLoading,
                                onClick = {
                                    clipboardScope.launch {
                                        onClipboardImport(clipboard.getPlainText().orEmpty())
                                    }
                                },
                            )
                            ArrowPreference(
                                title = stringResource(R.string.subscription_file),
                                summary = stringResource(R.string.subscription_file_summary),
                                enabled = !uiState.isLoading,
                                onClick = onPickFile,
                            )
                            ArrowPreference(
                                title = stringResource(R.string.subscription_url),
                                summary = stringResource(R.string.subscription_url_summary),
                                enabled = !uiState.isLoading,
                                onClick = onNavigateUrl,
                            )
                            ArrowPreference(
                                title = stringResource(R.string.subscription_manual),
                                summary = stringResource(R.string.subscription_manual_summary),
                                enabled = !uiState.isLoading,
                                onClick = onNavigateManual,
                            )
                        }
                    }
                    if (uiState.error.isNotEmpty()) {
                        item(key = "error") {
                            val glassBackdrop = cardGlassBackdrop()
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)
                                    .padding(top = 12.dp)
                                    .then(
                                        if (glassBackdrop != null) {
                                            Modifier.cardGlassSurface(glassBackdrop, 16.dp, 16.dp)
                                        } else {
                                            Modifier
                                        }
                                    ),
                                colors = if (glassBackdrop != null) {
                                    CardDefaults.defaultColors(color = Color.Transparent)
                                } else {
                                    CardDefaults.defaultColors()
                                },
                                insideMargin = PaddingValues(16.dp),
                            ) {
                                Text(
                                    text = uiState.error,
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.primary,
                                )
                            }
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
    }

    ImportProgressDialog(
        show = uiState.isLoading,
        step = uiState.importProgress?.let { importStepLabel(it) } ?: stringResource(R.string.common_processing),
        onCancel = { viewModel.cancelCurrentUpdate() },
    )

    // QR 导入二选一：相机扫码 / 从相册识别
    WindowDialog(
        show = showQrChooser,
        title = stringResource(R.string.subscription_qr),
        onDismissRequest = { showQrChooser = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(
                text = stringResource(R.string.subscription_qr_scan),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    showQrChooser = false
                    onScanQR?.invoke()
                },
            )
            TextButton(
                text = stringResource(R.string.subscription_qr_gallery),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    showQrChooser = false
                    onPickGalleryQr?.invoke()
                },
            )
            TextButton(
                text = stringResource(R.string.common_cancel),
                modifier = Modifier.fillMaxWidth(),
                onClick = { showQrChooser = false },
            )
        }
    }
}
