package com.simplechat.app.ui.common

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset

/**
 * 全局转场规格。
 *
 * **所有页面切换都从这里取** —— 时长、缓动、位移量只定义一次。
 * 不然「设置页滑 1/4 屏、搜索页滑 1/3 屏」这种不一致会一处长出来，
 * 而转场恰恰是那种每一处都差一点、整体就是不对劲的东西。
 */

/**
 * 转场时长 300ms。
 *
 * 对齐 Android 平台自己的 Activity 切换（Material 的 medium 档）。
 * 再短会"闪一下"，再长有拖沓感 —— 平台选这个数是有原因的。
 *
 * 抽屉展开成搜索页也用它，两处节奏才是一致的。
 */
const val MotionDurationMs = 300

/**
 * 折叠 / 展开的时长 220ms。
 *
 * 比转场快一档 —— 折叠的距离往往很大（一条几万像素的消息），拖久了会像在等它。
 * **用户气泡与思考面板共用这一个数**：两处都是"折叠"，节奏不一致会很明显。
 */
const val FoldAnimMs = 220

/** 缓动：平台标准的先快后慢。 */
val MotionEasing: Easing = FastOutSlowInEasing

/**
 * 页面转场。
 *
 * ```
 * 前进 ── 新页从右侧整屏推入并淡入，旧页向左让出 1/4 屏后淡出
 * 后退 ── 反过来
 * ```
 *
 * 三个刻意的选择：
 *
 * 1. **新页整屏推、旧页只让 1/4**。两页都整屏推时是"两块板各自平移"，
 *    观感生硬；新页从右边压上来、旧页被顶开一小段，才有**纵深** ——
 *    这是 iOS 与 DeepSeek 官方客户端共用的手法。
 * 2. **叠一层淡入**。位移本身没有明暗变化，长距离平移在眼里会"跳"；
 *    180~220ms 的淡入把这条边抹平，同时不产生半透明重影（新页从第 60ms
 *    起就已经不透明了）。
 * 3. **不叠淡出到底**。旧页只淡到 0，但在新页下面 —— 它唯一的可见窗口是
 *    边缘那几像素，淡出太快反而露出底色。
 *
 * @param forward 目标页比当前页**更深**（往栈里压）时为 true。
 *   同级之间互不跳转，所以只判深度就够。
 */
fun AnimatedContentTransitionScope<*>.screenTransform(forward: Boolean): ContentTransform {
    val slide = tween<IntOffset>(MotionDurationMs, easing = MotionEasing)
    val fadeInSpec = tween<Float>(TransitionFadeInMs, easing = MotionEasing)
    val fadeOutSpec = tween<Float>(TransitionFadeOutMs, easing = MotionEasing)

    val enter = slideInHorizontally(slide) { width ->
        if (forward) width else -width / 4
    } + fadeIn(fadeInSpec)
    val exit = slideOutHorizontally(slide) { width ->
        if (forward) -width / 4 else width
    } + fadeOut(fadeOutSpec)

    return (enter togetherWith exit).apply {
        // 新页永远在上：旧页"让开"的那一段才不会被它自己盖住
        targetContentZIndex = if (forward) 1f else -1f
    }
}

/** 转场淡入时长。比位移短一点：先看得见，再到位。 */
private const val TransitionFadeInMs = 200

/** 淡出比淡入更短 —— 旧页只是"让位"，不该还在抢注意力。 */
private const val TransitionFadeOutMs = 160
