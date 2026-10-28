package top.yukonga.mishka.ui.component.blur

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import top.yukonga.mishka.ui.theme.LocalAppDarkMode
import top.yukonga.mishka.ui.theme.TopBarBlurStyle
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

val LocalBlurEnabled = staticCompositionLocalOf { true }
val LocalTopBarBlurStyle = staticCompositionLocalOf { TopBarBlurStyle.Gaussian }
val LocalCardLiquidGlass = staticCompositionLocalOf { false }
val LocalCardBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
val LocalCardGlassBlur = staticCompositionLocalOf { 20f }
val LocalCardGlassTint = staticCompositionLocalOf { 0.5f }
val LocalCardBackgroundImage = staticCompositionLocalOf<String?> { null }

/** 卡片液态玻璃可用的 backdrop：开关开启且当前界面提供了 backdrop 时非空，否则卡片走普通表面 */
@Composable
fun cardGlassBackdrop(): LayerBackdrop? =
    if (LocalCardLiquidGlass.current) LocalCardBackdrop.current else null

/**
 * 卡片液态玻璃专用的背景层：铺在列表背后、单独录入一份 backdrop 供卡片采样。
 * 卡片不能采样录有自己那一层的 backdrop（RenderNode 自引用会让 HWUI 崩溃），
 * 所以玻璃采样源只能是这层不含任何卡片的纯背景。玻璃关闭时整层不渲染。
 */
@Composable
fun CardGlassBackground(backdrop: LayerBackdrop?) {
    val imagePath = LocalCardBackgroundImage.current
    // 自定义背景图与玻璃无关：设了就常驻渲染（玻璃关时卡片不采样、只当壁纸）；
    // 渐变是玻璃的「底片」，仅玻璃开启时渲染
    if (imagePath == null && !LocalCardLiquidGlass.current) return
    val isDark = LocalAppDarkMode.current
    val container = MiuixTheme.colorScheme.surfaceContainer
    val bitmap = if (imagePath != null) rememberBackgroundBitmap(imagePath) else null
    // 本层在窗口中的顶部偏移：背景图按整屏高度对齐渲染（见下方图片分支）
    val topInWindowPx = remember { mutableIntStateOf(0) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { topInWindowPx.intValue = it.positionInWindow().y.toInt() }
            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
    ) {
        if (bitmap != null) {
            // 按窗口整屏高度 Crop 并上移对齐：本层通常只占内容区（顶栏之下），若按自身
            // 高度裁图，会与导航层整屏渲染的同一张图在顶栏接缝处错位；撑回整屏高度后
            // 两层像素重合，顶栏透出的全局图与内容区无缝衔接
            val windowHeightPx = LocalWindowInfo.current.containerSize.height
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(LocalDensity.current) { windowHeightPx.toDp() })
                    .offset { IntOffset(0, -topInWindowPx.intValue) },
            ) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // 壁纸上压一层淡雾面：分组标题等直接落在图上的文字才有足够对比；
                // 雾面同在记录层内，卡片采样到的也是压过雾面的图，不会色差
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            MiuixTheme.colorScheme.surface.copy(
                                alpha = if (isDark) 0.30f else 0.20f,
                            )
                        ),
                )
            }
        } else if (imagePath == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color(0xFFF06292).copy(alpha = if (isDark) 0.13f else 0.20f),
                                0.35f to Color.Transparent,
                                0.72f to Color(0xFF64B5F6).copy(alpha = if (isDark) 0.06f else 0.10f),
                                1.0f to container.copy(alpha = 0.65f),
                            ),
                        ),
                    ),
            )
        }
    }
}

/** 背景图在入库时已压缩到 1440px 内，这里直接解码；解码走 IO，别堵首帧。 */
@Composable
private fun rememberBackgroundBitmap(path: String): ImageBitmap? {
    val state = produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }
    return state.value
}

/**
 * 液态玻璃卡片表面，与悬浮底栏同一套配方：采样 backdrop 模糊 + 半透明着色 + 玻璃高光描边。
 * 顶/底圆角分开传，兼容 GroupedCardItems 的分段卡片（首段只有顶角、末段只有底角）。
 */
@Composable
fun Modifier.cardGlassSurface(
    backdrop: LayerBackdrop,
    topCornerRadius: Dp,
    bottomCornerRadius: Dp,
    tint: Color = MiuixTheme.colorScheme.surfaceContainer,
): Modifier {
    val isDark = LocalAppDarkMode.current
    return textureBlur(
        backdrop = backdrop,
        shape = RoundedCornerShape(
            topStart = topCornerRadius,
            topEnd = topCornerRadius,
            bottomEnd = bottomCornerRadius,
            bottomStart = bottomCornerRadius,
        ),
        blurRadius = LocalCardGlassBlur.current,
        colors = BlurDefaults.blurColors(
            blendColors = listOf(
                BlendColorEntry(color = tint.copy(alpha = LocalCardGlassTint.current)),
            ),
        ),
        highlight = if (isDark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight,
    )
}

@Composable
fun Modifier.defaultBlurEffect(
    backdrop: LayerBackdrop,
): Modifier = this.textureBlur(
    backdrop = backdrop,
    shape = RectangleShape,
    blurRadius = 25f,
    colors = BlurColors(
        blendColors = listOf(
            BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.8f)),
        ),
    ),
)

@Composable
fun rememberBlurEnabled(): State<Boolean> =
    rememberUpdatedState(LocalBlurEnabled.current && isRuntimeShaderSupported())

@Composable
fun rememberBlurBackdrop(enabled: Boolean = LocalBlurEnabled.current): LayerBackdrop? {
    if (!enabled || !isRuntimeShaderSupported()) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

@Composable
fun BlurredBar(
    backdrop: LayerBackdrop?,
    blurActive: Boolean = rememberBlurEnabled().value,
    blurStyle: TopBarBlurStyle = LocalTopBarBlurStyle.current,
    content: @Composable () -> Unit,
) {
    // 设了自定义壁纸时顶栏不要独立背景：不画模糊层、纯透明，透出全局背景图融为一体
    if (LocalCardBackgroundImage.current != null) {
        content()
        return
    }
    val progressive = blurStyle == TopBarBlurStyle.Progressive
    Box(
        modifier = if (blurActive && backdrop != null && !progressive) {
            Modifier.defaultBlurEffect(backdrop)
        } else {
            Modifier
        },
    ) {
        if (blurActive && backdrop != null && progressive) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .progressiveTextureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        gradient = ProgressiveBlur.Top.copy(curve = 2.2f),
                        blurRadius = 10f,
                        colors = BlurColors(
                            blendColors = listOf(
                                BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(0.3f)),
                            ),
                        ),
                    ),
            )
        }
        content()
    }
}
