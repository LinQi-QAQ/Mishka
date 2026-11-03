package top.yukonga.mishka.ui.component.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.interfaces.HoldDownInteraction

/**
 * 按压反馈：在按下位置晕开一枚光斑，松手时淡出。
 *
 * 替换 miuix 默认的 [top.yukonga.miuix.kmp.utils.MiuixIndication]——后者把整个可点区域
 * 均匀压暗一层，在玻璃卡片上看不出「按的是哪儿」。光斑圆心取自 PressInteraction 的
 * pressPosition，因此切换按压点会换位置。
 *
 * 光斑只取按下那一刻的位置：手指后续移动不会更新它，因为 PressInteraction 只在按下时
 * 发一次、之后不再带坐标。想让光斑跟手得自己挂 pointerInput，那会把 indication 变成
 * 手势拦截者，与滚动冲突，不做。
 *
 * 只画按压，不画 hover / focus：这套 UI 触摸优先，为它们常驻三份 animatable 不值当。
 */
class GlassIndication(
    private val color: Color,
    private val maxAlpha: Float = 0.14f,
) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        GlassIndicationNode(interactionSource, color, maxAlpha)

    // IndicationNodeFactory 用 equals 参与 Modifier 的相等性比较。用默认引用相等会让
    // 每次重组都重建节点、打断正在跑的淡出动画，故按数据类语义手写。
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GlassIndication) return false
        return color == other.color && maxAlpha == other.maxAlpha
    }

    override fun hashCode(): Int = 31 * color.hashCode() + maxAlpha.hashCode()
}

private val PressSpec = spring<Float>(0.5f, 300f, 0.001f)

private class GlassIndicationNode(
    private val interactionSource: InteractionSource,
    private val color: Color,
    private val maxAlpha: Float,
) : Modifier.Node(), DrawModifierNode {

    private val progress = Animatable(0f, 0.001f)
    private var spot = Offset.Zero
    // HoldDown 不带坐标，此时光斑点应落在控件中心而非上一次按压处
    private var hasSpot = false
    private var collectJob: Job? = null

    override fun onAttach() {
        collectJob = coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        spot = interaction.pressPosition
                        hasSpot = true
                        launch { progress.animateTo(1f, PressSpec) }
                    }

                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        hasSpot = false
                        launch { progress.animateTo(0f, PressSpec) }
                    }

                    // 弹窗入口行在对话框打开期间保持按下态，走的是 miuix 自己的
                    // HoldDownInteraction 而非 PressInteraction；漏了这条，那些行
                    // 打开弹窗后就会悄悄「松」掉
                    is HoldDownInteraction.HoldDown -> {
                        launch { progress.animateTo(1f, PressSpec) }
                    }

                    is HoldDownInteraction.Release -> {
                        launch { progress.animateTo(0f, PressSpec) }
                    }
                }
            }
        }
    }

    override fun onDetach() {
        collectJob?.cancel()
        collectJob = null
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        // draw 阶段读 Animatable.value：快照读只失效绘制，不触发重组
        val alpha = progress.value
        if (alpha <= 0f) return
        // 整块轻叠一层，按压位置落在控件之外（长按后滑出）时仍有点反馈
        drawRect(color.copy(alpha = maxAlpha * 0.35f * alpha))
        if (!hasSpot) {
            // HoldDown 没有坐标：铺整块即可，别拿上一次按压点画到不相干的位置
            return
        }
        val center = Offset(
            x = spot.x.coerceIn(0f, size.width),
            y = spot.y.coerceIn(0f, size.height),
        )
        // 半径取短边 1.2 倍：光斑要盖过控件长边，否则在宽而矮的列表行上只显成一个小点
        val radius = (size.minDimension * 1.2f).coerceAtLeast(1f)
        drawRect(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to color.copy(alpha = maxAlpha * alpha),
                    0.6f to color.copy(alpha = maxAlpha * 0.45f * alpha),
                    1f to color.copy(alpha = 0f),
                ),
                center = center,
                radius = radius,
            ),
        )
    }
}
