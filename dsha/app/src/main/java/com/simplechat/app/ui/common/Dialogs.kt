package com.simplechat.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.simplechat.app.agent.FileChange
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.simplechat.app.R
import com.simplechat.app.ui.theme.LocalChatColors

/** 通用文本输入弹窗（重命名 / 编辑等）。 */
@Composable
fun AppTextInputDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    placeholder: String = "",
) {
    val colors = LocalChatColors.current
    var value by remember { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    val shape = PillShape

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Dialog(
        onDismissRequest = onDismiss,
        // 见 AiEditDialog 里同一处的注释：不吃掉 inset，imePadding() 才有值
        properties = DialogProperties(decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .clip(ContainerShape)
                .background(colors.card)
                .padding(20.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(14.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(colors.fieldBackground)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                    decorationBox = { inner ->
                        Box {
                            if (value.isEmpty() && placeholder.isNotEmpty()) {
                                Text(
                                    text = placeholder,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.placeholder,
                                )
                            }
                            inner()
                        }
                    },
                )
            }

            Spacer(Modifier.size(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                DialogTextAction(stringResource(R.string.action_cancel), onClick = onDismiss)
                Spacer(Modifier.size(8.dp))
                DialogTextAction(stringResource(R.string.action_save), primary = true, onClick = { onConfirm(value) })
            }
        }
    }
}

/** 通用确认弹窗（删除等破坏性操作）。 */
@Composable
fun AppConfirmDialog(
    title: String,
    message: String? = null,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    destructive: Boolean = false,
) {
    val colors = LocalChatColors.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ContainerShape)
                .background(colors.card)
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            message?.let {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.placeholder,
                )
            }

            Spacer(Modifier.size(20.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PillShape)
                    .background(
                        if (destructive) colors.danger else MaterialTheme.colorScheme.primary,
                    )
                    .clickable(onClick = onConfirm)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = confirmText,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }

            Spacer(Modifier.size(6.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PillShape)
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.action_cancel),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}


/**
 * 「回滚到此处」的确认弹窗。
 *
 * 与 [AppConfirmDialog] 的区别只有一个：它**把要动的文件列出来**。
 * 回滚是不可逆的（虽然回滚本身也能再回滚），只给一句"将回滚这条消息之后的
 * 文件改动"等于让人闭眼按确认 —— 用户根本不知道自己会失去什么。
 *
 * 每一行是一份"变更单"：文件图标 + 文件名 + `+n −n`（或"将删除"）。
 * 行数算不出来的（云端路径、备份缺失、超大文件）只显示动作，不编数字。
 */
@Composable
fun AppRewindDialog(
    title: String,
    files: List<FileChange>,
    emptyText: String,
    note: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalChatColors.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ContainerShape)
                .background(colors.card)
                .padding(horizontal = 20.dp, vertical = 22.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.size(12.dp))

            if (files.isEmpty()) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.placeholder,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    files.forEachIndexed { index, change ->
                        if (index > 0) Spacer(Modifier.size(10.dp))
                        FileChangeRow(change)
                    }
                }
            }

            Spacer(Modifier.size(14.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
            )
            Spacer(Modifier.size(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                DialogTextAction(stringResource(R.string.action_cancel), onClick = onDismiss)
                Spacer(Modifier.size(8.dp))
                Box(
                    modifier = Modifier
                        .clip(PillShape)
                        .background(colors.danger)
                        .clickable(onClick = onConfirm)
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = confirmText,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

/** 一行"文件变更单"。 */
@Composable
private fun FileChangeRow(change: FileChange) {
    val colors = LocalChatColors.current
    val name = change.path.substringAfterLast('/').ifBlank { change.path }
    val dir = change.path.dropLast(name.length).trimEnd('/')
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = iconForPath(change.path),
            contentDescription = null,
            tint = colors.placeholder,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (dir.isNotEmpty()) {
                Text(
                    text = dir,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.placeholder,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.size(10.dp))
        if (change.delete) {
            Text(
                text = stringResource(R.string.rewind_will_delete),
                style = MaterialTheme.typography.labelSmall,
                color = colors.danger,
            )
        } else if (change.known) {
            Text(
                text = "+${change.added}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = DiffAddColor,
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = "−${change.removed}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = colors.danger,
            )
        } else {
            Text(
                text = stringResource(R.string.rewind_will_restore),
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
            )
        }
    }
}

/**
 * 按扩展名挑图标：图是图，其余一律给一张纸。
 *
 * 早先脚本 / 源码（`py`、`sh`、`js`……）用的是终端图标 —— 那是"可执行"的
 * 暗示，不是"文件"的暗示。产物条上排一排终端图标，看起来像五个快捷命令，
 * 而不是五个交付文件。文件就是文件，扩展名靠文件名那行字自己说。
 */
internal fun iconForPath(path: String): androidx.compose.ui.graphics.vector.ImageVector {
    val ext = path.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "svg" -> AppIcons.Image
        else -> AppIcons.FileText
    }
}

/** 新增行用的绿。深色主题下同一个值偏暗，所以取主题的 success 而不是硬编码。 */
private val DiffAddColor = androidx.compose.ui.graphics.Color(0xFF2E9E5B)

@Composable
private fun DialogTextAction(
    text: String,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    val colors = LocalChatColors.current
    Box(
        modifier = Modifier
            .clip(PillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = if (primary) MaterialTheme.colorScheme.primary else colors.processText,
        )
    }
}
