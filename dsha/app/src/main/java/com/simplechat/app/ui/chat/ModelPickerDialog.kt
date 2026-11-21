package com.simplechat.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.simplechat.app.data.Res
import com.simplechat.app.net.ModelInfo
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.ModelMark
import com.simplechat.app.ui.theme.LocalChatColors
import com.simplechat.app.ui.common.ContainerShape
import com.simplechat.app.ui.common.ModelBadge
import com.simplechat.app.ui.common.PillShape
import com.simplechat.app.ui.settings.SettingsTag
import com.simplechat.app.ui.settings.SettingsTextAction

/**
 * 选择"要出现在切换气泡里的模型"。
 *
 * 尺寸是这一版的重点：官方那张弹窗占了大半屏（宽 88%、高 75%），
 * 这里压到 **300dp 宽、列表最高 300dp** —— 它只是一个筛选项列表，
 * 不需要摆出"整页"的架势。
 *
 * 交互上只有一个决定要做：勾哪些。所以底部只有「取消 / 确定」，
 * 没有"全选""清空"这类会让人犹豫的按钮 —— 要全选就点标题右侧那一下。
 */
@Composable
fun ModelPickerDialog(
    models: List<ModelInfo>,
    selected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val colors = LocalChatColors.current
    var query by remember { mutableStateOf("") }
    /*
     * 初值两种：勾过就用勾过的，没勾过就是"全部" —— 与 ModelCatalog.visibleModels
     * 的口径保持一致，否则用户第一次打开会看到"一个都没勾、气泡里却有一堆"。
     */
    var picked by remember { mutableStateOf(if (selected.isEmpty()) models.map { it.id }.toSet() else selected) }

    val visible = remember(query, models) {
        val keyword = query.trim().lowercase()
        if (keyword.isEmpty()) {
            models
        } else {
            models.filter { it.id.lowercase().contains(keyword) || it.displayName.lowercase().contains(keyword) }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .fillMaxWidth()
                // 圆角与全应用其它弹窗同一个值；尺寸仍按这张弹窗自己的"小"来
                .clip(ContainerShape)
                .background(colors.card)
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Res.get(com.simplechat.app.R.string.model_picker_title),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                // 标题右侧的"全选 / 全不选"。比底部多两个按钮省地方得多
                val allPicked = visible.isNotEmpty() && visible.all { it.id in picked }
                Text(
                    text = Res.get(
                        if (allPicked) com.simplechat.app.R.string.model_picker_none
                        else com.simplechat.app.R.string.model_picker_all,
                    ),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(PillShape)
                        .clickable {
                            picked = if (allPicked) {
                                picked - visible.map { it.id }.toSet()
                            } else {
                                picked + visible.map { it.id }
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(10.dp))

            SearchField(value = query, onValueChange = { query = it })

            Spacer(Modifier.height(6.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (visible.isEmpty()) {
                    Text(
                        text = Res.get(com.simplechat.app.R.string.model_picker_empty),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = colors.placeholder,
                        modifier = Modifier.padding(vertical = 18.dp),
                    )
                }
                visible.forEach { model ->
                    ModelCheckRow(
                        model = model,
                        checked = model.id in picked,
                        onToggle = {
                            picked = if (model.id in picked) picked - model.id else picked + model.id
                        },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Res.get(
                        com.simplechat.app.R.string.model_picker_count,
                        picked.size,
                        models.size,
                    ),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                    color = colors.placeholder,
                    modifier = Modifier.weight(1f),
                )
                DialogTextAction(
                    text = Res.get(com.simplechat.app.R.string.action_cancel),
                    // 「取消」是中性灰，只有「确定」是主题色：两个都是蓝字时分不出哪个是主要动作
                    primary = false,
                    onClick = onDismiss,
                )
                Spacer(Modifier.width(4.dp))
                DialogTextAction(
                    text = Res.get(com.simplechat.app.R.string.action_confirm),
                    enabled = picked.isNotEmpty(),
                    onClick = { onConfirm(picked) },
                )
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            // 搜索框是胶囊（全应用的输入框都是）
            .clip(PillShape)
            .background(colors.fieldBackground)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = AppIcons.Search,
            contentDescription = null,
            tint = colors.placeholder,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(7.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    text = Res.get(com.simplechat.app.R.string.model_picker_search),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = colors.placeholder,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ModelCheckRow(model: ModelInfo, checked: Boolean, onToggle: () -> Unit) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 2.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        /*
         * 行首放厂商 logo，勾选框挪到最右。
         *
         * 一屏十几个模型名（`deepseek-v4-pro`、`glm-4.6`、`claude-sonnet-4`），
         * 光读名字要一行一行辨认；图标先给出"这是谁家的"，眼睛再去找具体型号。
         * 勾选框在最右也更符合"这一行是一个开关"的意思。
         */
        // 带品牌色的圆形徽标：与设置里「模型」单选弹窗同一种行首
        ModelBadge(model = model, size = 28.dp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 显示名与 id 不同时补一行 id —— 中转站的显示名常常是"DeepSeek V4"，
            // 而真正要填进请求的是 `deepseek-v4-flash`，混起来会选错
            if (model.displayName != model.id) {
                Text(
                    text = model.id,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = colors.placeholder,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (model.supportsVision) {
            SettingsTag(text = Res.get(com.simplechat.app.R.string.model_picker_vision))
            Spacer(Modifier.width(8.dp))
        }
        CheckBox(checked = checked)
    }
}

/** 方形勾选框。Material 的 Checkbox 是 20dp 圆角方块带涟漪，比这里的字号大一圈。 */
@Composable
private fun CheckBox(checked: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val colors = LocalChatColors.current
    Box(
        modifier = Modifier
            .size(17.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (checked) primary else androidx.compose.ui.graphics.Color.Transparent)
            .border(
                width = if (checked) 0.dp else 1.2.dp,
                color = if (checked) primary else colors.outline,
                shape = RoundedCornerShape(5.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = AppIcons.Check,
                contentDescription = null,
                // 跟主题走，不写死白色
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

/** 弹窗底部的文字动作：就是设置里通用的那一种（见 `SettingsKit.kt`），这里用收窄的版本。 */
@Composable
private fun DialogTextAction(
    text: String,
    enabled: Boolean = true,
    primary: Boolean = true,
    onClick: () -> Unit,
) {
    SettingsTextAction(
        text = text,
        onClick = onClick,
        primary = primary,
        enabled = enabled,
        compact = true,
    )
}
