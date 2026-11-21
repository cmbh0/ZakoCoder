package com.simplechat.app.ui.common

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.simplechat.app.ui.theme.LocalChatColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 侧边栏控制器。
 *
 * 只保留一个 0..1 的进度：`0` = 收起，`1` = 完全展开。宽度、蒙版、
 * 投影全部由它推导 —— 三个值各存一份的话，拖到一半必然对不齐。
 */
class SlideDrawerState {

    var fraction by mutableFloatStateOf(0f)
        private set

    /** 抽屉是不是"处于打开意图"（动画目标 / 拖过一半）。 */
    val isOpen: Boolean get() = fraction > 0.001f

    private var job: Job? = null

    fun open(scope: CoroutineScope) = animateTo(scope, 1f)

    fun close(scope: CoroutineScope) = animateTo(scope, 0f)

    private fun animateTo(scope: CoroutineScope, target: Float) {
        job?.cancel()
        job = scope.launch {
            animate(
                initialValue = fraction,
                targetValue = target,
                animationSpec = tween(MotionDurationMs, easing = MotionEasing),
            ) { value, _ -> fraction = value }
        }
        // 动画结束把精度收干净，避免停在 0.9999 让"露出一个像素"
        if (target == 0f) job?.invokeOnCompletion { fraction = 0f }
    }

    /** 拖动。delta 是"进度增量"，由调用方按宽度换算好。 */
    fun dragBy(delta: Float) {
        job?.cancel()
        fraction = (fraction + delta).coerceIn(0f, 1f)
    }

    /** 松手：拖过 15% 就全开，否则回弹。 */
    fun settle(scope: CoroutineScope) {
        if (fraction >= 0.15f) open(scope) else close(scope)
    }

    fun toggle(scope: CoroutineScope) {
        if (isOpen) close(scope) else open(scope)
    }
}

@Composable
fun rememberSlideDrawerState(): SlideDrawerState = remember { SlideDrawerState() }

/*
 * 没有蒙版，也没有投影。
 *
 * 这是照官方 chat-demo 抄的：它的移动端抽屉 `drawerMask` **不带任何背景色**，
 * 只负责点击关闭；抽屉本体也没有 `box-shadow`。层次只靠两样东西 ——
 * 比页面暗/亮一档的底色，和一条 1px 右边框。
 *
 * 加蒙版（哪怕 0.32）会立刻变成"弹窗"，把"同一页里拉出来的一列"讲成
 * "另一件事压上来了"；投影同理。官方克制到这个程度，是有道理的。
 */

/** 抽屉宽度上限。官方桌面 261px、移动 300px，这里取 300dp。 */
private val DrawerMaxWidth = 300.dp

/** 抽屉右侧至少要让出的宽度：官方 `calc(100vw - 48px)` 的 48。 */
private val DrawerMinGap = 48.dp

/**
 * 侧边栏：**盖在主页面上的抽屉**，页面本身不动。
 *
 * ### 三版之间的取舍
 *
 * - 缩一圈加圆角（最早）：主页面被塞进一个框，观感像"应用被降级"。
 * - 整页右推（1.6.0）：页面被推出屏幕，右边只剩一条缝。
 * - 拼接一列（1.7.x）：页面被压窄，输入栏和标题一起挤成一团 —— 手机上
 *   两边都不够宽，这条路走不通。
 *
 * 这一版回到最朴素的抽屉，**尺寸与表面规格全部照官方 chat-demo**：
 * 主页面保持原样，抽屉从左边滑出来盖住它，宽度 300dp 封顶、右侧至少
 * 让出 48dp；底色比页面暗/亮一档，右边缘一条 1px 边框。
 *
 * **没有蒙版、没有投影**。官方的 `drawerMask` 是透明的、`.drawer` 没有
 * `box-shadow` —— 抽屉和页面是同一层的东西，压暗或投影都会把它讲成
 * "弹窗压上来了"。层次只靠那一点点底色差和那条线。
 *
 * 主页面不缩放、不位移、不加圆角、不变窄 —— 被盖住的部分不用管，
 * 用户要看它就把抽屉收起来。
 */
@Composable
fun SlideDrawer(
    state: SlideDrawerState,
    modifier: Modifier = Modifier,
    /** 展开后的宽度占屏宽的比例。 */
    widthFraction: Float = 0.78f,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        /*
         * 宽度照官方那条 CSS：`width: 300px; max-width: calc(100vw - 48px)`。
         *
         * 比例参数只是上限，真正的尺寸由 300dp 封顶、并且必须给右侧留出
         * 48dp —— 手机再窄也不会压到"抽屉盖满、看不出底下还有页面"。
         * 搜索态传 1f，走全宽分支。
         */
        val drawerWidth = if (widthFraction >= 0.999f) {
            maxWidth
        } else {
            minOf(maxWidth * widthFraction, DrawerMaxWidth, maxWidth - DrawerMinGap)
                .coerceAtLeast(200.dp)
        }
        val drawerPx = with(density) { drawerWidth.toPx() }
        val edgeWidth = 28.dp
        val t = state.fraction

        /*
         * 主页面：原宽、原位、原样。
         *
         * 手势层与内容层分开 —— 这一层不动，只负责判"按下点是不是在左缘"。
         * 不能用 `detectHorizontalDragGestures`：它一越过水平 slop 就 consume，
         * 而这一层盖住整页 —— 结果是所有横向滑动（标签页、横向列表、
         * 文本选择）全被它吃掉。手写一遍：按下点不在左缘就**直接退出、
         * 一个事件都不消费**。
         */
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(drawerPx, edgeWidth) {
                    val edgePx = edgeWidth.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.position.x > edgePx && !state.isOpen) {
                            return@awaitEachGesture
                        }
                        var overSlop = 0f
                        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                            change.consume()
                            overSlop = over
                        } ?: return@awaitEachGesture
                        state.dragBy(overSlop / drawerPx)
                        horizontalDrag(drag.id) { change ->
                            state.dragBy(change.positionChange().x / drawerPx)
                            change.consume()
                        }
                        state.settle(scope)
                    }
                },
        ) {
            content()
        }

        /*
         * 空白区域的点击层：**透明**，不加任何底色。
         *
         * 与官方 `drawerMask` 一样，它只做一件事 —— 点一下把抽屉收起来。
         * 不加灰，是因为抽屉和页面本来是同一层的东西，压暗就把关系讲错了。
         */
        if (t > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f)
                    .pointerInput(Unit) {
                        detectTapGestures { state.close(scope) }
                    },
            )
        }

        /*
         * 抽屉本体。
         *
         * `translationX` 从 -drawerPx（完全在屏外）走到 0 —— 只做平移，
         * 不做缩放：缩放会让里面的字号在动画期间"呼吸"，很廉价。
         */
        if (t > 0f) {
            Box(
                modifier = Modifier
                    .zIndex(2f)
                    .width(drawerWidth)
                    .fillMaxHeight()
                    .offset { IntOffset((-drawerPx * (1f - t)).roundToInt(), 0) }
                    .pointerInput(drawerPx) {
                        /*
                         * 收手必须在 `onDragEnd` 里做。
                         *
                         * `detectHorizontalDragGestures` 是个不会返回的挂起循环，
                         * 写在它后面的代码永远不会执行 —— 之前那样写，松手时不吸附，
                         * 抽屉停在拖到的一半。
                         */
                        detectHorizontalDragGestures(
                            onDragEnd = { state.settle(scope) },
                            onDragCancel = { state.settle(scope) },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                state.dragBy(amount / drawerPx)
                            },
                        )
                    },
            ) {
                drawerContent()
            }
        }
    }
}

/**
 * 侧边栏的"纸面"。
 *
 * 三件事，全部照官方 chat-demo 的 `.sider`：
 *
 * 1. 底色用 `sidebar`（比页面暗/亮一档），不是卡片白；
 * 2. 右边缘一条 1px 边框 —— 这是抽屉与页面之间唯一的"线"；
 * 3. 没有圆角、没有投影。
 *
 * 圆角会在屏幕边缘露出底下的内容（上一版"白边"的来源），投影会把
 * "同一页里拉出来的一列"讲成"浮起来的弹窗"。两者都不加。
 */
@Composable
fun DrawerSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalChatColors.current
    /*
     * 只铺底色，不描边。
     *
     * 之前加过一条 1.2dp 的右缘线，想着"两页之间要有一条分界" ——
     * 实际看起来像给抽屉套了个框，比不加更难收拾。侧边栏和页面本来就
     * 差一档底色（`sidebar` vs `pageBackground`），边界由那点色差说，
     * 加线是重复表达，而且永远比色差显眼。
     *
     * 官方 chat-demo 那条 `border-l1` 之所以成立，是因为它的侧边栏和页面
     * **同色**、只能靠线分开；我们的底色已经分开了，就不需要。
     */
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.sidebar),
    ) {
        content()
    }
}
