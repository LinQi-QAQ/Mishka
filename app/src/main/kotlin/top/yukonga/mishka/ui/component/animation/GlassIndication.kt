package top.yukonga.mishka.ui.component.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
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
import kotlinx.coroutines.delay
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
    private val maxAlpha: Float = 0.22f,
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

// 按下即满亮，不做入场动画；松手后先保持 90ms 再淡出。
//
// 依据实测日志而不是手感猜测：miuix 的 pressable 用 delayJob + tryAwaitRelease
// 发事件，短按时 Press 与 Release 会落在同一毫秒（多次记录：37.856 Press / 37.856
// Release），两者之间没有任何一帧被绘制。这意味着在这个 UI 里，短按根本承载不了
// 逐帧推进的入场动画——不管入场取 40ms、60ms 还是 73ms 的弹簧，都跑不出一帧。
//
// 所以落点放在「按下的那一帧就是一帧完整可见」：snapTo 让它在唯一被绘制的那一帧
// 就是满强度；松手后再靠出场动画留一段可见时间，让人看得见它退下去。
//
// holdAfterRelease 是手机上的实际观感：不保持的话，动画刚开始就被下一帧的淡出
// 接走，仍然只闪一下。90ms 约等于三帧，足够形成「亮—留—退」的三段感。
//
// 出场仍保留 tween：snap 到 0 会像被掐断，而这 150ms 是在松手之后跑的，
// 不受「Press/Release 同帧」那条约束。缓动取 FastOutLinearIn——开头快，
// 让人觉得反馈立刻收回，尾部拖长则显拖沓。
private const val HoldAfterReleaseMillis = 90L
private val PressExitSpec = tween<Float>(durationMillis = 150, easing = FastOutLinearInEasing)

// 整块底噪的强度。miuix 默认按下是 0.1，这里压在它下方——真正表达「按下去」的是
// 光斑；底噪只兜住光斑够不着的角落，压到 0.1 就会盖过光斑、变回一整片灰
private const val FlatAlpha = 0.06f

private class GlassIndicationNode(
    private val interactionSource: InteractionSource,
    private val color: Color,
    private val maxAlpha: Float,
) : Modifier.Node(), DrawModifierNode {

    private val progress = Animatable(0f, 0.001f)
    private var spot = Offset.Zero
    // HoldDown 不带坐标，只能画整块；此时不画光斑，免得落在上一次按压的位置
    private var hasSpot = false
    private var collectJob: Job? = null

    override fun onAttach() {
        collectJob = coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        spot = interaction.pressPosition
                        hasSpot = true
                        progress.snapTo(1f)
                    }

                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        // 不清 hasSpot：淡出期间还要靠它继续画光斑。
                        // 先保持一小段再淡，否则短按只会闪一帧。
                        launch {
                            delay(HoldAfterReleaseMillis)
                            progress.animateTo(0f, PressExitSpec)
                        }
                    }

                    // 弹窗入口行在对话框打开期间保持按下态，走的是 miuix 自己的
                    // HoldDownInteraction 而非 PressInteraction；漏了这条，那些行
                    // 打开弹窗后就会悄悄「松」掉
                    is HoldDownInteraction.HoldDown -> {
                        progress.snapTo(1f)
                    }

                    is HoldDownInteraction.Release -> {
                        launch { progress.animateTo(0f, PressExitSpec) }
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
        drawRect(color.copy(alpha = FlatAlpha * alpha))
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
                // 内部一半保持满强度再向外衰减：纯线性渐变的可见面积太小，
                // 玻璃卡片本来就浅，一眼看过去只剩中心一点点亮
                colorStops = arrayOf(
                    0f to color.copy(alpha = maxAlpha * alpha),
                    0.45f to color.copy(alpha = maxAlpha * 0.8f * alpha),
                    1f to color.copy(alpha = 0f),
                ),
                center = center,
                radius = radius,
            ),
        )
    }
}
