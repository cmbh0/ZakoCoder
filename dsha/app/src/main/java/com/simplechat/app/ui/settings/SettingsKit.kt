package com.simplechat.app.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.simplechat.app.R
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.ContainerShape
import com.simplechat.app.ui.common.MenuShape
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.theme.LocalChatColors

/*
 * 设置体系的**公共零件**。
 *
 * 为什么要有这个文件：设置首页那几个组件（卡片 / 入口行 / 开关行）一直是统一的，
 * 但从设置点进去的页面 —— 权限、屏幕操作、云端、MCP、技能、定时任务 —— 是分几次、
 * 各写各的。结果同一件事有好几种长相：
 *
 *   - 顶栏三种（50dp 居中标题 / 52dp 左对齐 17sp / 36dp 圆按钮 + titleMedium）；
 *   - 卡片圆角四种（10 / 12 / 14 / 16）；页面左右留白两种（16 / 20）；
 *   - 弹窗两套（28dp 圆角 + 胶囊按钮  vs  16dp 圆角 + 8dp 方角按钮）；
 *   - 输入框三种圆角（胶囊 / 20 / 10），"取消"有的灰有的更灰；
 *   - 说明文字 11 / 12 / 13sp 都有，左缩进 4dp 和 16dp 混着用；
 *   - 单选的"当前项"有的是蓝字 + 黑勾，有的是蓝勾，有的是 20dp 的勾。
 *
 * 单看每一处都不算错，放在一起就是"这页和那页不是一个 App"。这里把这些
 * 决定收到一处：度量只有 [SettingsDimens] 一份，行只有 [SettingsRow] 一种，
 * 弹窗只有 [SettingsDialogFrame] 一种，输入框只有 [SettingsField] 一种。
 *
 * 颜色一律取自 `LocalChatColors` / `MaterialTheme.colorScheme`，这个文件里
 * 没有任何写死的色值 —— 换主题色、切深色时所有设置页一起变。
 */

// ══════════════════════════════════════════════════════════
//  度量
// ══════════════════════════════════════════════════════════

/**
 * 设置页的度量，**全体设置页共用这一份**。
 *
 * 数值沿用设置首页已经调好的那一套（行高 50、左右 16、字号 15 / 13 / 12），
 * 没有重新发明；变的只是"谁都得用它"。
 */
object SettingsDimens {
    /** 页面内容的左右留白。与对话页消息区、输入栏同一条边线。 */
    val ScreenPadding = 16.dp

    /** 卡片圆角。 */
    val CardCorner = 10.dp

    /** 行的左右 / 上下内边距与最小高度。 */
    val RowPadH = 16.dp
    val RowPadV = 11.dp
    val RowMinHeight = 50.dp

    /** 行首图标：19dp 的线性图标，装在 28dp 的固定列里居中。 */
    val IconColumn = 28.dp
    val Icon = 19.dp
    val IconGap = 10.dp

    /** 行尾箭头。 */
    val Chevron = 14.dp

    /** 顶栏高度（不含状态栏）。 */
    val BarHeight = 50.dp

    /** 弹窗内边距。 */
    val DialogPadding = 20.dp
}

/**
 * 语义色调。设置页里"带颜色的字"只有这四种意思，别的地方不要再自己挑颜色。
 *
 * - [Neutral]：普通说明；
 * - [Accent]：正面状态（已连接 / 已授权 / 已保存）—— 用主题色，不另起一种绿；
 * - [Warning]：需要注意但不是出错；
 * - [Danger]：失败、破坏性操作。
 */
enum class SettingsTone { Neutral, Accent, Warning, Danger }

@Composable
fun SettingsTone.color(): Color {
    val colors = LocalChatColors.current
    return when (this) {
        SettingsTone.Neutral -> colors.placeholder
        SettingsTone.Accent -> MaterialTheme.colorScheme.primary
        SettingsTone.Warning -> colors.warning
        SettingsTone.Danger -> colors.danger
    }
}

// ══════════════════════════════════════════════════════════
//  文字
// ══════════════════════════════════════════════════════════

/**
 * 卡片外的说明文字：12sp、灰、行高 17。
 *
 * 左右缩进与卡片内的文字**同一条竖线**（[SettingsDimens.RowPadH]）。此前多数页面
 * 给的是 4dp —— 分组标题缩进 16、说明缩进 4，上下两行字的左边缘差着 12dp。
 */
@Composable
fun SettingsHint(
    text: String,
    modifier: Modifier = Modifier,
    tone: SettingsTone = SettingsTone.Neutral,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
        color = tone.color(),
        modifier = modifier.padding(horizontal = SettingsDimens.RowPadH),
    )
}

/** 小标签：能力（识图 / 思考）、状态（当前 / 免 Key）。胶囊形，比正文小两档。 */
@Composable
fun SettingsTag(
    text: String,
    modifier: Modifier = Modifier,
    tone: SettingsTone = SettingsTone.Neutral,
) {
    val colors = LocalChatColors.current
    val tint = if (tone == SettingsTone.Neutral) colors.processText else tone.color()
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, lineHeight = 14.sp),
        color = tint,
        maxLines = 1,
        modifier = modifier
            .clip(PillShape)
            .background(
                if (tone == SettingsTone.Neutral) colors.fieldBackground else tint.copy(alpha = 0.12f),
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

// ══════════════════════════════════════════════════════════
//  行
// ══════════════════════════════════════════════════════════

/** 行尾箭头。大小、颜色全应用一份。 */
@Composable
fun SettingsChevron() {
    Icon(
        imageVector = AppIcons.ChevronRight,
        contentDescription = null,
        // 箭头用比文字更浅的一档：它是"这条路通向别处"的标点，不是内容
        tint = LocalChatColors.current.placeholder.copy(alpha = 0.7f),
        modifier = Modifier.size(SettingsDimens.Chevron),
    )
}

/**
 * 单选行里的"当前项"标记：一颗**中性色**的勾。
 *
 * 口径与 `AppMenuCheck` 一致 —— 蓝勾是整页最跳的一笔，看起来像"可点的链接"；
 * 而这里要说的只是"现在是这一项"。没选中时保留同样大小的空位，行高不跳。
 */
@Composable
fun SettingsCheck(selected: Boolean) {
    Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        if (selected) {
            Icon(
                imageVector = AppIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * 设置里的**一行** —— 所有行都是它。
 *
 * 结构固定：`[图标] 标题 / 副标题 ······ 值 [尾部] [箭头]`。入口行、动作行、
 * 开关行、单选行、只读信息行，区别只在给了哪几个参数：
 *
 * | 用途 | 参数 |
 * |---|---|
 * | 入口（进二级页 / 弹窗） | `value` + `chevron = true` |
 * | 动作（导出、新建） | `subtitle`，不给箭头 |
 * | 开关 | `trailing = { AppSwitch(…) }` |
 * | 单选 | `trailing = { SettingsCheck(…) }` |
 * | 只读信息 | 不给 `onClick` |
 *
 * 此前每个页面各写一份 Row：标题有的 Medium 有的 Normal、有的 14sp 有的 16sp，
 * 图标列有 24 / 28 / 32 三种宽度，于是页与页之间文字起点对不齐。
 *
 * @param leading 自定义行首（头像、品牌徽标）。给了它就不画 [icon]。
 * @param valueMaxWidth 值一列的最大宽度；超出省略。
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    value: String? = null,
    valueTone: SettingsTone = SettingsTone.Neutral,
    valueMaxWidth: Dp = 180.dp,
    chevron: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
    subtitleMaxLines: Int = Int.MAX_VALUE,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalChatColors.current
    val titleColor = when {
        !enabled -> colors.placeholder
        destructive -> colors.danger
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SettingsDimens.RowMinHeight)
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(
                start = SettingsDimens.RowPadH,
                end = SettingsDimens.RowPadH,
                top = SettingsDimens.RowPadV,
                bottom = SettingsDimens.RowPadV,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(SettingsDimens.IconGap))
        } else if (icon != null) {
            Box(
                modifier = Modifier.size(SettingsDimens.IconColumn),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = titleColor,
                    modifier = Modifier.size(SettingsDimens.Icon),
                )
            }
            Spacer(Modifier.width(SettingsDimens.IconGap))
        }
        // 标题吃掉全部剩余空间 → 值 / 开关 / 箭头被顶到最右，形成一条整齐的右边线
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal,
                ),
                color = titleColor,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = colors.placeholder,
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                text = value,
                // 值比标题小一档、灰一档：它是状态，不是第二个标题
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = valueTone.color(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = valueMaxWidth),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
        if (chevron) {
            Spacer(Modifier.width(4.dp))
            SettingsChevron()
        }
    }
}

// ══════════════════════════════════════════════════════════
//  空态与提示条
// ══════════════════════════════════════════════════════════

/**
 * 空态：圆形淡底图标 + 一句标题 + 一句"接下来点哪"。
 *
 * 放在 [SettingsCard] 里用。此前三处空态三种画法（圆形主题色淡底 / 灰色圆角方块 /
 * 光秃秃一枚灰图标），这里取预设页那一版 —— 它是唯一一个看上去"做完了"的。
 */
@Composable
fun SettingsEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
) {
    val colors = LocalChatColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.size(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (body != null) {
            Spacer(Modifier.size(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                color = colors.placeholder,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 结果提示条：图标 + 一段话，底色跟着 [tone] 走。
 *
 * 用于"测试连接"这类**有成败之分**的结果。此前结果是卡片下面一行灰字，
 * 成功和失败长得一模一样，用户得把整句话读完才知道通没通。
 *
 * @param selectable 报错原文允许长按复制 —— 用户要把它发给别人看。
 */
@Composable
fun SettingsBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: SettingsTone = SettingsTone.Neutral,
    selectable: Boolean = false,
) {
    val colors = LocalChatColors.current
    val neutral = tone == SettingsTone.Neutral
    val tint = if (neutral) colors.processText else tone.color()
    val icon = when (tone) {
        SettingsTone.Accent -> AppIcons.CircleCheck
        SettingsTone.Neutral -> AppIcons.Info
        else -> AppIcons.Warning
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SettingsDimens.CardCorner))
            .background(if (neutral) colors.card else tint.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(15.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (selectable) {
                SelectionContainer {
                    BannerText(text = text, color = tint)
                }
            } else {
                BannerText(text = text, color = tint)
            }
        }
    }
}

@Composable
private fun BannerText(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
        color = color,
    )
}

// ══════════════════════════════════════════════════════════
//  按钮
// ══════════════════════════════════════════════════════════

/** [SettingsButton] 的几种分量。 */
enum class SettingsButtonStyle {
    /** 主题色实底：这一屏**唯一**的主要动作。 */
    Primary,

    /** 浅灰底：**卡片里**的次要动作（测试连接）。 */
    Tonal,

    /**
     * 卡片色底：**直接放在页面上**的次要动作（自检）。
     *
     * 页面底色和浅灰底几乎是同一个灰，Tonal 放上去等于只剩一行字 ——
     * 此前屏幕操作页的「自检」就是这样，看不出那是一颗按钮。
     */
    Surface,

    /** 危险色实底：不可撤销的动作。 */
    Danger,
}

/**
 * 设置页里的按钮：**胶囊形**，分量见 [SettingsButtonStyle]。
 *
 * 全应用的规则是"带文字的按钮 = 胶囊"（见 `ui/common/Controls.kt`）。此前屏幕操作页
 * 和工作区页直接用了 Material 的 `Button` 配 10 / 12dp 圆角 —— 同一个 App 里
 * 只有这两页的按钮是圆角矩形，而且禁用态走的是 Material 默认配色。
 *
 * @param loading 进行中：前面转一个小圈，同时不可点。文字由调用方换成"正在…"。
 */
@Composable
fun SettingsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: SettingsButtonStyle = SettingsButtonStyle.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    val colors = LocalChatColors.current
    val active = enabled && !loading
    val container = when {
        !enabled -> colors.fieldBackground
        style == SettingsButtonStyle.Primary -> MaterialTheme.colorScheme.primary
        style == SettingsButtonStyle.Danger -> colors.danger
        style == SettingsButtonStyle.Surface -> colors.card
        else -> colors.fieldBackground
    }
    val content = when {
        !enabled -> colors.placeholder
        style == SettingsButtonStyle.Primary -> MaterialTheme.colorScheme.onPrimary
        style == SettingsButtonStyle.Danger -> MaterialTheme.colorScheme.onError
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(PillShape)
            .background(container)
            .clickable(enabled = active, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(15.dp),
                strokeWidth = 2.dp,
                color = content,
            )
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp),
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 纯文字动作：弹窗底部的「取消 / 保存」，以及卡片里的"次要链接"（卸载、修复）。
 *
 * 胶囊形点击区，四种颜色只表达四件事：普通 / 主要 / 危险 / 不可用。
 *
 * @param compact 左右内边距收窄。一排要放三四个动作时用（「删除 导出 … 取消 保存」），
 *   否则英文界面下四个词排不进一行。
 * 此前各页的"取消"有 `processText`、`placeholder` 两种灰，按钮的点击区圆角有
 * 6 / 7 / 8dp 和胶囊四种。
 */
@Composable
fun SettingsTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val colors = LocalChatColors.current
    val tint = when {
        !enabled -> colors.placeholder
        destructive -> colors.danger
        primary -> MaterialTheme.colorScheme.primary
        else -> colors.processText
    }
    Box(
        modifier = modifier
            .clip(PillShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 10.dp else 16.dp, vertical = 9.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = tint,
            maxLines = 1,
        )
    }
}

// ══════════════════════════════════════════════════════════
//  弹窗
// ══════════════════════════════════════════════════════════

/**
 * 设置里所有弹窗的**外框**：28dp 圆角卡片、20dp 内边距、标题用 `titleMedium`。
 *
 * 与 `ui/common/Dialogs.kt` 里的通用弹窗同一套度量。几个细节是踩过之后定下的：
 * - `decorFitsSystemWindows = false` + `imePadding()`：键盘弹起时整张卡往上让，
 *   否则带输入框的弹窗底部按钮会被键盘盖住；
 * - 标题下留 14dp，底部按钮由 [SettingsDialogActions] 自己留 18dp ——
 *   内容区之间的间距交给调用方，这里不替它决定。
 *
 * @param titleTrailing 标题右侧的小东西（计数、全选）。
 */
@Composable
fun SettingsDialogFrame(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    titleTrailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalChatColors.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .imePadding()
                .clip(ContainerShape)
                .background(colors.card)
                .padding(SettingsDimens.DialogPadding),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (titleTrailing != null) {
                    Spacer(Modifier.width(8.dp))
                    titleTrailing()
                }
            }
            Spacer(Modifier.size(14.dp))
            content()
        }
    }
}

/**
 * 弹窗底部的按钮行：主要动作靠右，[leading]（删除、导出这类）靠左。
 *
 * 危险动作放左边、离"保存"最远，是为了不让人在收尾时顺手点错。
 */
@Composable
fun SettingsDialogActions(
    modifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) leading()
        Spacer(Modifier.weight(1f))
        actions()
    }
}

// ══════════════════════════════════════════════════════════
//  输入框
// ══════════════════════════════════════════════════════════

/**
 * 输入框的外壳：浅灰底、无描边；**单行是胶囊，多行是 20dp 圆角**。
 *
 * 这条规则写在 `Controls.kt` 最上面（"所有输入框、搜索框一律胶囊"），但 MCP、
 * 定时任务的弹窗各自写了 10dp 圆角的方框，和设置里别的输入框并排看明显不是一家。
 */
@Composable
private fun SettingsFieldFrame(
    singleLine: Boolean,
    modifier: Modifier,
    minHeight: Dp,
    maxHeight: Dp,
    leadingIcon: ImageVector?,
    trailing: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    val colors = LocalChatColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight, max = maxHeight)
            .clip(if (singleLine) PillShape else MenuShape)
            .background(colors.fieldBackground)
            .padding(
                start = 16.dp,
                end = if (trailing != null) 6.dp else 16.dp,
                top = if (trailing != null) 6.dp else 12.dp,
                bottom = if (trailing != null) 6.dp else 12.dp,
            ),
        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = colors.placeholder,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
        ) {
            content()
        }
        if (trailing != null) {
            Spacer(Modifier.width(4.dp))
            trailing()
        }
    }
}

/**
 * 设置里的输入框（字符串版）。
 *
 * @param mono 等宽字体：命令行、JSON、URL —— 少一个引号多一个空格都要看得出来。
 * @param leadingIcon 框内左侧的小图标（搜索框的放大镜）。
 * @param trailing 框内右侧的小按钮（显示 / 隐藏、清空）。
 */
@Composable
fun SettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    mono: Boolean = false,
    minHeight: Dp = 46.dp,
    maxHeight: Dp = 320.dp,
    fieldModifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalChatColors.current
    val style = MaterialTheme.typography.bodyMedium.copy(
        fontSize = if (mono) 13.sp else 15.sp,
        lineHeight = if (mono) 19.sp else 22.sp,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
    )
    SettingsFieldFrame(
        singleLine = singleLine,
        modifier = modifier,
        minHeight = minHeight,
        maxHeight = maxHeight,
        leadingIcon = leadingIcon,
        trailing = trailing,
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = style,
                color = colors.placeholder,
                maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = fieldModifier.fillMaxWidth(),
            textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = singleLine,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
        )
    }
}

/**
 * 设置里的输入框（`TextFieldValue` 版）。
 *
 * 需要**控制光标**时用它：编辑已有的值时光标应该停在末尾（接着改），
 * 而字符串版的 `BasicTextField` 初始光标在开头 —— 用户想在 URL 后面补一段路径，
 * 得先把光标拖过去。
 */
@Composable
fun SettingsField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    mono: Boolean = false,
    minHeight: Dp = 46.dp,
    maxHeight: Dp = 320.dp,
    fieldModifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalChatColors.current
    val style = MaterialTheme.typography.bodyMedium.copy(
        fontSize = if (mono) 13.sp else 15.sp,
        lineHeight = if (mono) 19.sp else 22.sp,
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
    )
    SettingsFieldFrame(
        singleLine = singleLine,
        modifier = modifier,
        minHeight = minHeight,
        maxHeight = maxHeight,
        leadingIcon = leadingIcon,
        trailing = trailing,
    ) {
        if (value.text.isEmpty()) {
            Text(
                text = placeholder,
                style = style,
                color = colors.placeholder,
                maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = fieldModifier.fillMaxWidth(),
            textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = singleLine,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
        )
    }
}

/** 输入框内的小圆按钮（显示 / 隐藏、清空）。34dp 点击区，图标 16dp、灰色。 */
@Composable
fun SettingsFieldButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = LocalChatColors.current.placeholder,
            modifier = Modifier.size(16.dp),
        )
    }
}

// ══════════════════════════════════════════════════════════
//  顶栏动作
// ══════════════════════════════════════════════════════════

/**
 * 顶栏右侧的图标动作（新增、编辑、刷新）。
 *
 * 与左侧返回箭头**同一个尺寸**：44dp 的圆形点击区、20dp 的图标、不带灰底。
 * 此前云端 / 工作区页用的是 36dp 灰底圆按钮，和设置里其它页的顶栏放在一起，
 * 一眼就能看出是两套东西。
 */
@Composable
fun SettingsBarAction(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                LocalChatColors.current.placeholder
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

// ══════════════════════════════════════════════════════════
//  单选弹窗
// ══════════════════════════════════════════════════════════

/** 选项超过这个数才出搜索框：五六项一眼扫得完，多一个输入框反而是噪声。 */
private const val ChoiceSearchThreshold = 8

/**
 * 单选弹窗里的一项。
 *
 * @param detail 第二行小字（模型 id、域名）。搜索时也会匹配它。
 * @param tags 行尾的小标签（识图 / 思考 / 免 Key），最多放两三个。
 */
class SettingsChoice<T>(
    val value: T,
    val label: String,
    val detail: String? = null,
    val tags: List<String> = emptyList(),
)

/** 单选弹窗列表下方的动作（拉取列表、手动填写、新增）。与"选哪一项"无关。 */
class SettingsChoiceAction(
    val label: String,
    val icon: ImageVector? = null,
    val onClick: () -> Unit,
)

/**
 * 单选弹窗：标题 +（可选）搜索 + 列表 +（可选）说明 +（可选）动作。
 *
 * 几处是照着实际用起来的毛病改的：
 * - **搜索**：自定义端点的 `/models` 动辄四五十个，此前只能一屏一屏往下翻；
 *   给了 [searchHint] 且选项超过 [ChoiceSearchThreshold] 个时，顶上出一个搜索框；
 * - **列表最高半屏**：写死 420dp 在矮屏 / 横屏上会把弹窗顶出窗口；
 * - **当前项**只用一颗中性的勾标出（见 [SettingsCheck]），文字不再变成主题色 ——
 *   与菜单里的单选同一个口径；
 * - 行首可以放任意内容（[leading]）：服务商、模型用品牌徽标，一眼认得出是谁家的。
 *
 * @param emptyHint 一个选项都没有时显示的话。
 * @param hint 列表下面的一行小字（拉取中 / 拉取结果），颜色跟 [hintTone]。
 */
@Composable
fun <T> SettingsChoiceDialog(
    title: String,
    options: List<SettingsChoice<T>>,
    selected: T?,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
    emptyHint: String? = null,
    hint: String? = null,
    hintTone: SettingsTone = SettingsTone.Neutral,
    searchHint: String? = null,
    actions: List<SettingsChoiceAction> = emptyList(),
    leading: (@Composable (T) -> Unit)? = null,
) {
    val colors = LocalChatColors.current
    var query by remember { mutableStateOf("") }
    val searchable = searchHint != null && options.size > ChoiceSearchThreshold
    val visible = remember(query, options, searchable) {
        val keyword = query.trim().lowercase()
        if (!searchable || keyword.isEmpty()) {
            options
        } else {
            options.filter { option ->
                option.label.lowercase().contains(keyword) ||
                    option.detail.orEmpty().lowercase().contains(keyword)
            }
        }
    }
    val listMax = minOf(420.dp, (LocalConfiguration.current.screenHeightDp * 0.5f).dp)

    Dialog(
        onDismissRequest = onDismiss,
        // 有搜索框就会有键盘：不吃掉 inset，imePadding() 才有值
        properties = DialogProperties(decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .clip(ContainerShape)
                .background(colors.card)
                .padding(vertical = 8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp),
            )

            if (searchable) {
                SettingsField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = searchHint.orEmpty(),
                    leadingIcon = AppIcons.Search,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                )
            }

            if (visible.isEmpty()) {
                val message = if (options.isEmpty()) {
                    emptyHint
                } else {
                    stringResource(R.string.choice_no_match)
                }
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, lineHeight = 19.sp),
                        color = colors.placeholder,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
                    )
                }
            } else {
                /*
                 * 用 LazyColumn 而不是 Column：四五十个选项一次性铺开，弹窗会被顶穿屏幕，
                 * 底部超出窗口的部分点不到也看不见。
                 */
                LazyColumn(modifier = Modifier.heightIn(max = listMax)) {
                    items(visible.size) { index ->
                        val option = visible[index]
                        val current = option.value == selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 50.dp)
                                .clickable { onSelect(option.value) }
                                .padding(horizontal = 20.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (leading != null) {
                                leading(option.value)
                                Spacer(Modifier.width(12.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = option.label,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 15.sp,
                                        fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                option.detail?.let { detail ->
                                    Text(
                                        text = detail,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp,
                                        ),
                                        color = colors.placeholder,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 1.dp),
                                    )
                                }
                            }
                            option.tags.forEach { tag ->
                                Spacer(Modifier.width(6.dp))
                                SettingsTag(text = tag)
                            }
                            Spacer(Modifier.width(10.dp))
                            SettingsCheck(selected = current)
                        }
                    }
                }
            }

            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                    color = hintTone.color(),
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 6.dp),
                )
            }

            if (actions.isNotEmpty()) {
                /*
                 * 列表与动作之间一根细线：列表会滚，动作不会。没有这根线时，
                 * 最后一个选项和第一个动作连在一起，像是同一组里的又一项。
                 */
                Box(
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .fillMaxWidth()
                        .height(0.7.dp)
                        .background(colors.divider),
                )
                actions.forEach { action ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(onClick = action.onClick)
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        action.icon?.let { glyph ->
                            Icon(
                                imageVector = glyph,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            text = action.label,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════
//  输入弹窗
// ══════════════════════════════════════════════════════════

/** 读剪贴板里的第一段文字；读不到（空 / 不是文字 / 系统不给）一律给空串。 */
private fun clipboardText(context: Context): String {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        ?: return ""
    val clip = runCatching { manager.primaryClip }.getOrNull() ?: return ""
    if (clip.itemCount == 0) return ""
    return runCatching { clip.getItemAt(0).coerceToText(context)?.toString() }
        .getOrNull()
        .orEmpty()
}

/**
 * 单值输入弹窗：一个输入框 +「取消 / 保存」。
 *
 * 这一版补的全是"用的时候才发现少了"的东西：
 * - **一打开就能打字**：自动聚焦、光标停在已有内容的**末尾**（此前要先点一下
 *   输入框，而且光标在最前面 —— 想在 URL 后面补一段路径得先把光标拖过去）；
 * - **键盘不挡按钮**：键盘弹起时整张卡往上让，「保存」始终点得到；
 * - **密钥看得见**（[secret]）：默认遮住，右侧一只眼睛切换明文 —— 四十多位的 Key
 *   粘错了一位，此前只能清空重来；
 * - **一键粘贴**（[paste]）：Key 和地址没有人是手打的。粘贴会顺手去掉首尾空白
 *   和换行（从网页复制 Key 时经常带进来，带着它请求必然 401）；
 * - **键盘类型**（[keyboardType]）：填数字给数字键盘、填网址给带 `/` `.` 的键盘；
 * - 键盘上的「完成」等于点「保存」。
 *
 * @param mono 用等宽字体显示（地址、模型 id）。密钥固定等宽。
 */
@Composable
fun SettingsInputDialog(
    title: String,
    initial: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    secret: Boolean = false,
    hint: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    mono: Boolean = false,
    paste: Boolean = false,
    singleLine: Boolean = true,
) {
    val context = LocalContext.current
    val colors = LocalChatColors.current
    var value by remember {
        mutableStateOf(TextFieldValue(text = initial, selection = TextRange(initial.length)))
    }
    var revealed by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val emptyClipboard = stringResource(R.string.clipboard_empty)

    val revealButton: @Composable () -> Unit = {
        SettingsFieldButton(
            icon = if (revealed) AppIcons.EyeOff else AppIcons.Eye,
            contentDescription = stringResource(
                if (revealed) R.string.action_hide_secret else R.string.action_show_secret,
            ),
            onClick = { revealed = !revealed },
        )
    }
    val clearButton: @Composable () -> Unit = {
        SettingsFieldButton(
            icon = AppIcons.Close,
            contentDescription = stringResource(R.string.action_clear),
            onClick = { value = TextFieldValue("") },
        )
    }

    SettingsDialogFrame(title = title, onDismiss = onDismiss) {
        SettingsField(
            value = value,
            onValueChange = { value = it },
            placeholder = placeholder,
            singleLine = singleLine,
            mono = mono || secret,
            fieldModifier = Modifier.focusRequester(focusRequester),
            visualTransformation = if (secret && !revealed) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = if (secret) KeyboardType.Password else keyboardType,
                imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
            ),
            keyboardActions = KeyboardActions(onDone = { onConfirm(value.text) }),
            trailing = when {
                secret -> revealButton
                value.text.isNotEmpty() -> clearButton
                else -> null
            },
        )

        // 放在输入框之后：此时它已经进了组合树，要焦点才不会落空
        LaunchedEffect(Unit) {
            runCatching { focusRequester.requestFocus() }
        }

        if (hint != null) {
            Spacer(Modifier.size(10.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                color = colors.placeholder,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        SettingsDialogActions(
            leading = {
                if (paste) {
                    SettingsTextAction(
                        text = stringResource(R.string.action_paste),
                        onClick = {
                            val text = clipboardText(context).trim()
                            if (text.isEmpty()) {
                                Toast.makeText(context, emptyClipboard, Toast.LENGTH_SHORT).show()
                            } else {
                                val clean = if (singleLine) {
                                    text.replace("\n", "").replace("\r", "")
                                } else {
                                    text
                                }
                                value = TextFieldValue(text = clean, selection = TextRange(clean.length))
                            }
                        },
                    )
                }
            },
        ) {
            SettingsTextAction(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
            )
            Spacer(Modifier.width(8.dp))
            SettingsTextAction(
                text = stringResource(R.string.action_save),
                primary = true,
                onClick = { onConfirm(value.text) },
            )
        }
    }
}
