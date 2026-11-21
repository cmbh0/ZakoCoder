package com.simplechat.app.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.simplechat.app.R
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.MotionEasing
import com.simplechat.app.ui.theme.LocalChatColors

/**
 * 预设页：**这段对话**启用哪几条提示词模块。
 *
 * 为什么是一整页而不是输入栏上方那张小卡片：
 * 那里的可用高度只有三百来像素，一条预设挤成一行（名字 + 单行摘要 + 圆点），
 * 既看不出它到底写了什么，也放不下"顺序"这种真正影响结果的信息。
 * 这里是独立的一屏，每条预设都有自己的块 —— 名字、两行摘要、开关、
 * 以及在启用集合里的**位次**。
 *
 * 关于位次：拼进系统提示词的顺序是**预设列表的顺序**，不是勾选的先后
 * （见 [ConversationSettings.composedSystemPrompt]）。所以启用后角标显示
 * 1、2、3，用户看到的就是真实的拼接次序；先勾哪条不影响结果。
 *
 * 会话级：勾选写进 `ConversationSettings.enabledPresetIds`，切个话题就是
 * 另一套开关。这一页**不是**设置页的一部分 —— 设置页只管建/改/删。
 */
@Composable
fun PresetSheet(
    presets: List<Preset>,
    enabledIds: List<String>,
    /** (id, 是否启用)。去重交给调用方，这里只报事实。 */
    onToggle: (String, Boolean) -> Unit,
    /** 去设置页新建 / 修改预设。 */
    onManage: () -> Unit,
    /** 标题、补充提示词、导出这些还在原来的对话设置面板里。 */
    onOpenChatSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalChatColors.current
    val primary = MaterialTheme.colorScheme.primary

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        var appeared by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            // 先等窗口真正上屏再起动画。否则第一帧就已经接近终点，
            // 用户看到的是"啪"地出现，而不是从底下升上来。
            withFrameNanos { }
            appeared = true
        }
        val rise by animateDpAsState(
            targetValue = if (appeared) 0.dp else 30.dp,
            animationSpec = tween(durationMillis = 240, easing = MotionEasing),
            label = "presetSheetRise",
        )
        val fade by animateFloatAsState(
            targetValue = if (appeared) 1f else 0f,
            animationSpec = tween(durationMillis = 190),
            label = "presetSheetFade",
        )

        BoxWithConstraints(Modifier.fillMaxSize()) {
            /*
             * 列表的高度上限跟着屏幕走。
             *
             * 写死 392dp 在小屏（或分屏）上会把底部按钮顶出屏幕 —— 用户看到
             * 的是一页没有出口的列表。取屏高的 52%，再夹到 240–392 之间：
             * 矮屏至少露出三条，高屏不至于留一大片空白。
             */
            val listMaxHeight = (maxHeight * 0.52f).coerceIn(240.dp, 392.dp)

            /*
             * 遮罩。
             *
             * Dialog 自带一层系统 dim，但那是固定的黑，深色模式下压不住
             * 近黑的页面。这里再盖一层主题化的 scrim，与抽屉、长按菜单
             * 用的是同一个"暗下去"的量。
             *
             * 点遮罩关闭 —— 手势判定交给它自己的 clickable，底部卡片
             * 会吃掉落在自己身上的点击，不会误关。
             */
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.scrim.copy(alpha = 0.34f * fade))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = rise)
                    .alpha(fade)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                    .background(colors.card)
                    /*
                     * 吃掉落在卡片上的点击。
                     *
                     * 底下那层遮罩是全屏 clickable（点它关闭）。Compose 的命中
                     * 测试会穿过没有任何手势的容器继续往下找 —— 不给这里挂一个
                     * 空的 clickable，点在卡片空白处（比如标题旁、两行之间）
                     * 就会一路穿到遮罩，整页"自己关掉"。
                     */
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .navigationBarsPadding()
                    .padding(bottom = 8.dp),
            ) {
                // 顶部把手：一个可拖拽的暗示，也是"这层可以关掉"的形状语言
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 34.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(colors.outline.copy(alpha = 0.8f)),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp, end = 22.dp, top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.preset_sheet_title),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    if (presets.isNotEmpty()) {
                        // 计数只数库里还在的：删预设后残留的悬空 id 不该让分子虚高
                        val active = presets.count { it.id in enabledIds }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(primary.copy(alpha = 0.10f))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.preset_sheet_count, active, presets.size),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = primary,
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.preset_sheet_subtitle),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    color = colors.placeholder,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 7.dp),
                )

                if (presets.isEmpty()) {
                    PresetSheetEmpty(onManage = onManage)
                } else {
                    /*
                     * 列表自己滚，且**只滚列表**：标题和底部按钮固定。
                     *
                     * 高度上限按"一屏能看见五条左右"定，再多就滚 —— 让整页
                     * 随预设条数无限长会把按钮推到屏幕外，用户以为没有出口。
                     */
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = listMaxHeight)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        // 生效顺序 = 列表顺序，与拼接逻辑同一口径
                        val activeOrder = presets.filter { it.id in enabledIds }.map { it.id }
                        presets.forEach { preset ->
                            val enabled = preset.id in enabledIds
                            PresetCard(
                                preset = preset,
                                enabled = enabled,
                                order = if (enabled) activeOrder.indexOf(preset.id) + 1 else 0,
                                onToggle = { onToggle(preset.id, !enabled) },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SheetButton(
                        text = stringResource(R.string.preset_sheet_manage),
                        filled = false,
                        modifier = Modifier.weight(1f),
                        onClick = onManage,
                    )
                    SheetButton(
                        text = stringResource(R.string.preset_sheet_done),
                        filled = true,
                        modifier = Modifier.weight(1.25f),
                        onClick = onDismiss,
                    )
                }

                /*
                 * 脚注：对话设置还在。
                 *
                 * 顶栏「⋯」的第一项现在就是这一页，标题 / 补充提示词 / 导出
                 * 如果没地方去就等于被删了功能。做成一行不抢眼的灰字，
                 * 需要的人找得到，不需要的人不会被它分散注意力。
                 */
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onOpenChatSettings)
                        .padding(vertical = 7.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.preset_sheet_more),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = colors.placeholder,
                    )
                    Spacer(Modifier.width(3.dp))
                    Icon(
                        imageVector = AppIcons.ChevronRight,
                        contentDescription = null,
                        tint = colors.placeholder.copy(alpha = 0.7f),
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

/**
 * 一条预设。
 *
 * 启用与未启用的差别做在**整张卡**上：底色、边框、左侧徽记、开关四处同时变，
 * 扫一眼就知道哪几条开着。只改开关的话，一屏七八条时要逐个去读那个小圆点。
 */
@Composable
private fun PresetCard(
    preset: Preset,
    enabled: Boolean,
    /** 在启用集合里的位次（1 起）；0 表示未启用。 */
    order: Int,
    onToggle: () -> Unit,
) {
    val colors = LocalChatColors.current
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(14.dp)

    val surface by animateColorAsState(
        targetValue = if (enabled) {
            primary.copy(alpha = 0.055f)
        } else {
            colors.fieldBackground.copy(alpha = 0.55f)
        },
        animationSpec = tween(durationMillis = 170),
        label = "presetCardSurface",
    )
    val stroke by animateColorAsState(
        targetValue = if (enabled) primary.copy(alpha = 0.22f) else Color.Transparent,
        animationSpec = tween(durationMillis = 170),
        label = "presetCardStroke",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(surface)
            .border(1.dp, stroke, shape)
            .clickable(onClick = onToggle)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 首字徽记：比统一的灰色图标多一层辨识，扫列表时靠它区分
        val initial = preset.name.trim().take(1).uppercase().ifEmpty { "·" }
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (enabled) primary.copy(alpha = 0.13f) else colors.outline.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = if (enabled) primary else colors.placeholder,
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (order > 0) {
                    Spacer(Modifier.width(7.dp))
                    // 位次角标：告诉用户"它是第几个进提示词的"
                    Box(
                        modifier = Modifier
                            .size(17.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = order.toString(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            color = primary,
                        )
                    }
                }
            }
            Text(
                text = preset.content,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                color = colors.placeholder,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        PresetSwitch(checked = enabled)
    }
}

/**
 * 开关。自己画而不直接用 Material `Switch`：
 *
 * 系统那枚在 15sp 的标题旁边像一颗钉子 —— 轨道宽、颜色重、还带一个 48dp
 * 的触摸热区，行内留白全被它吃掉。这一版 46×28，轨道用主色 / 30% 灰，
 * 圆点带一点投影，和整页的圆角语言对得上。
 *
 * 触摸目标仍是整张卡，所以这里不需要额外的热区。
 */
@Composable
private fun PresetSwitch(checked: Boolean) {
    val colors = LocalChatColors.current
    val primary = MaterialTheme.colorScheme.primary

    val track by animateColorAsState(
        targetValue = if (checked) primary else colors.outline.copy(alpha = 0.65f),
        animationSpec = tween(durationMillis = 180),
        label = "presetSwitchTrack",
    )
    val knobX by animateDpAsState(
        targetValue = if (checked) 20.dp else 3.dp,
        animationSpec = tween(durationMillis = 180, easing = MotionEasing),
        label = "presetSwitchKnob",
    )

    Box(
        modifier = Modifier
            .size(width = 46.dp, height = 28.dp)
            .clip(CircleShape)
            .background(track),
    ) {
        Box(
            modifier = Modifier
                .offset(x = knobX, y = 3.dp)
                .size(22.dp)
                .shadow(1.5.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

/** 空态：一个圆底图标 + 一句"接下来点哪"，不做成一行干巴巴的灰字。 */
@Composable
private fun PresetSheetEmpty(onManage: () -> Unit) {
    val colors = LocalChatColors.current
    val primary = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = AppIcons.Prompt,
                contentDescription = null,
                tint = primary.copy(alpha = 0.9f),
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.size(14.dp))
        Text(
            text = stringResource(R.string.preset_sheet_empty),
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = stringResource(R.string.preset_sheet_empty_hint),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
            color = colors.placeholder,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(16.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(primary.copy(alpha = 0.09f))
                .clickable(onClick = onManage)
                .padding(horizontal = 16.dp, vertical = 9.dp),
        ) {
            Text(
                text = stringResource(R.string.preset_sheet_empty_action),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                color = primary,
            )
        }
    }
}

/** 底部按钮：实心 = 主操作，淡底 = 次操作。 */
@Composable
private fun SheetButton(
    text: String,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    val primary = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (filled) primary else colors.fieldBackground)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
            color = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
