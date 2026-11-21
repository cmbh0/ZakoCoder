package com.simplechat.app.ui.common

import android.os.SystemClock

/**
 * 浮层「再点一次原控件就收起来」的仲裁器。
 *
 * ### 它解决的是什么
 *
 * 菜单 / 面板都是 `Popup`。点原控件想收起时，系统会**同时**走两条路：
 *
 * 1. 给 Popup 发一次窗口外触摸 → `onDismissRequest()` 把状态置为关闭；
 * 2. 同一次手指落到下面的控件上 → 控件的 `onClick` 再 toggle 一次。
 *
 * 两条路的先后不确定。若控件先 toggle（关 → 开），Popup 的 dismiss 随后
 * 再关掉，用户看到"闪了一下"；若 dismiss 先，控件读到的已经是关闭态，
 * 于是**重新打开** —— 用户看到的是"再点一次还是开着"。
 *
 * 所以不猜顺序，只记时间：刚被"点外面"关掉的 [guardMs] 内，控件上的
 * 点击一律忽略。人手不可能在 300ms 内点两处，不会误吞真实操作；
 * 过了这个窗口，点击照常打开。
 */
class OverlayGate(private val guardMs: Long = 300L) {

    private var closedAt = 0L

    /** 浮层因为"点外面"或返回键关掉时调用。 */
    fun markClosed() {
        closedAt = SystemClock.uptimeMillis()
    }

    /** 控件上的点击是否应该生效（刚被关掉的那一下不算）。 */
    fun acceptToggle(): Boolean =
        SystemClock.uptimeMillis() - closedAt > guardMs
}
