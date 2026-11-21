package com.simplechat.app.ui.settings

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.simplechat.app.R
import com.simplechat.app.data.Repeat
import com.simplechat.app.data.ScheduleAction
import com.simplechat.app.data.ScheduleItem
import com.simplechat.app.data.ScheduleStore
import com.simplechat.app.data.daysLabel
import com.simplechat.app.data.nextTrigger
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppSwitch
import com.simplechat.app.ui.theme.LocalChatColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.simplechat.app.ui.common.ContainerShape
import com.simplechat.app.ui.common.MenuShape
import com.simplechat.app.ui.common.PillShape

/**
 * 定时任务页。
 *
 * 结构三块：**说明一句、列表一份、底部一个"新建"**。
 *
 * 这一版补的是"完整性"：
 * - 可以**编辑**（点行） —— 早先只能删了重建，改一个字都要重来；
 * - 重复规则多了**每周几**（写作、健身、周报这类按周的事，工作日/每天都不对）；
 * - 行里直接写清"下次什么时候"，不用自己心算；
 * - 空态有图标与按钮，不再是一句灰字吊在中间。
 */
@Composable
fun SchedulePage() {
    val colors = LocalChatColors.current
    val items by ScheduleStore.items.collectAsState()
    /** 正在编辑的那条；id 为空 = 新建。 */
    var editing by remember { mutableStateOf<ScheduleItem?>(null) }
    var pendingDelete by remember { mutableStateOf<ScheduleItem?>(null) }
    val sorted = remember(items) { items.sortedWith(compareBy({ it.hour }, { it.minute })) }

    // 这一页被设置页的滚动容器包着：自己不再滚动，也不吃 weight
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SettingsHint(
                text = stringResource(R.string.schedule_page_hint),
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )

            if (sorted.isEmpty()) {
                // 空态用设置里通用的那一种（卡片 + 圆形淡底图标）。图标与设置首页
                // 「定时任务」入口是同一枚钟 —— 此前这里是一只铃铛，和入口对不上
                SettingsCard {
                    SettingsEmptyState(
                        icon = AppIcons.Clock,
                        title = stringResource(R.string.schedule_empty),
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(SettingsDimens.CardCorner))
                        .background(colors.card),
                ) {
                    sorted.forEachIndexed { index, item ->
                        ScheduleRow(
                            item = item,
                            onToggle = { ScheduleStore.setEnabled(item.id, it) },
                            onEdit = { editing = item },
                            onDelete = { pendingDelete = item },
                        )
                        if (index != sorted.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp)
                                    .height(0.7.dp)
                                    .background(colors.divider),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        /*
         * 「添加」是一颗胶囊按钮、卡片色底。
         *
         * 此前是一条 10dp 圆角的浅灰横条 —— 浅灰压在灰色页面上，浅色主题里几乎
         * 看不出边界，只剩中间一个加号和两个字，不像能点的东西。
         */
        SettingsButton(
            text = stringResource(R.string.schedule_add),
            onClick = {
                editing = ScheduleItem(id = "", title = "", hour = 9, minute = 0)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 24.dp),
            style = SettingsButtonStyle.Surface,
            icon = AppIcons.Plus,
        )
        Spacer(Modifier.navigationBarsPadding())
    }

    editing?.let { draft ->
        ScheduleEditor(
            initial = draft,
            isNew = draft.id.isBlank(),
            onDismiss = { editing = null },
            onConfirm = { title, hour, minute, repeat, note, days, action, command, quiet ->
                if (draft.id.isBlank()) {
                    ScheduleStore.add(
                        title = title,
                        hour = hour,
                        minute = minute,
                        repeat = repeat,
                        note = note,
                        days = days,
                        action = action,
                        command = command,
                        quietOnSuccess = quiet,
                    )
                } else {
                    ScheduleStore.update(
                        draft.copy(
                            title = title,
                            hour = hour,
                            minute = minute,
                            repeat = repeat,
                            note = note,
                            days = days,
                            action = action,
                            command = command,
                            quietOnSuccess = quiet,
                        ),
                    )
                }
                editing = null
            },
        )
    }

    pendingDelete?.let { item ->
        com.simplechat.app.ui.common.AppConfirmDialog(
            title = stringResource(R.string.schedule_delete),
            message = item.title,
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                ScheduleStore.remove(item.id)
                pendingDelete = null
            },
        )
    }
}

@Composable
private fun ScheduleRow(
    item: ScheduleItem,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalChatColors.current
    val next = remember(item) { item.nextTrigger()?.let { formatNext(it) } }
    val rule = when (item.repeat) {
        Repeat.WEEKLY -> "每周${daysLabel(item.days)}"
        else -> item.repeat.label
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.clock,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 19.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = if (item.enabled) MaterialTheme.colorScheme.onSurface else colors.placeholder,
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.action == ScheduleAction.COMMAND) {
                    /*
                     * 命令类任务给一个终端图标：列表里一眼能分出
                     * "这是会动手的"和"这是只会响一声的"。
                     */
                    Icon(
                        imageVector = AppIcons.Terminal,
                        contentDescription = null,
                        tint = colors.processText,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(12.dp),
                    )
                }
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = if (item.enabled) MaterialTheme.colorScheme.onSurface else colors.placeholder,
                    maxLines = 1,
                )
            }
            Text(
                text = next?.let { "$rule · $it" } ?: rule,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = colors.placeholder,
                maxLines = 1,
            )
            if (item.action == ScheduleAction.COMMAND && item.command.isNotBlank()) {
                Text(
                    text = item.command.lineSequence().first().take(48),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = colors.placeholder,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = AppIcons.Trash,
            contentDescription = stringResource(R.string.schedule_delete),
            tint = colors.placeholder,
            modifier = Modifier
                .size(32.dp)
                // 图标按钮的点按区是圆的（全应用一致）
                .clip(CircleShape)
                .clickable(onClick = onDelete)
                .padding(8.dp),
        )
        AppSwitch(
            checked = item.enabled,
            onCheckedChange = onToggle,
        )
    }
}

/**
 * 新建 / 编辑。
 *
 * 一个对话框两用：`isNew=false` 时预填原值、保存走 [ScheduleStore.update]。
 * 分成两个对话框会让"改一个字"和"新建一条"变成两套几乎一样的界面。
 */
@Composable
private fun ScheduleEditor(
    initial: ScheduleItem,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (
        title: String,
        hour: Int,
        minute: Int,
        repeat: Repeat,
        note: String,
        days: List<Int>,
        action: ScheduleAction,
        command: String,
        quiet: Boolean,
    ) -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalChatColors.current
    var title by remember(initial.id) { mutableStateOf(initial.title) }
    var note by remember(initial.id) { mutableStateOf(initial.note) }
    var hour by remember(initial.id) { mutableStateOf(initial.hour) }
    var minute by remember(initial.id) { mutableStateOf(initial.minute) }
    var action by remember(initial.id) { mutableStateOf(initial.action) }
    var command by remember(initial.id) { mutableStateOf(initial.command) }
    var quiet by remember(initial.id) { mutableStateOf(initial.quietOnSuccess) }
    var repeat by remember(initial.id) {
        mutableStateOf(if (isNew) Repeat.ONCE else initial.repeat)
    }
    var days by remember(initial.id) {
        mutableStateOf(
            initial.days.ifEmpty {
                // 新选"每周"时默认勾今天，省得用户面对一排空圈
                listOf(Calendar.getInstance().get(Calendar.DAY_OF_WEEK))
            }.toSet(),
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                // 弹窗外框与设置里其它弹窗同一套：28dp 圆角、20dp 内边距
                .clip(ContainerShape)
                .background(colors.card)
                .padding(SettingsDimens.DialogPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(
                    if (isNew) R.string.schedule_new_title else R.string.schedule_edit_title,
                ),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )

            DialogField(
                value = title,
                onValueChange = { title = it.take(40) },
                placeholder = stringResource(R.string.schedule_field_title),
            )

            /*
             * 动作：提醒 / 运行命令。
             *
             * 放在标题下面而不是最后：它决定**下面出现哪些字段**
             * （提醒有备注，命令有命令行），先选它才不会出现"填了一半再改类型"。
             */
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScheduleAction.entries.forEach { option ->
                    val selected = option == action
                    Text(
                        text = stringResource(
                            if (option == ScheduleAction.REMIND) {
                                R.string.schedule_action_remind
                            } else {
                                R.string.schedule_action_command
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            colors.placeholder
                        },
                        modifier = Modifier
                            // 选项是胶囊：与输入栏、菜单里的选项同一种形状
                            .clip(PillShape)
                            .background(if (selected) colors.capsuleSelected else colors.fieldBackground)
                            .clickable { action = option }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }

            if (action == ScheduleAction.COMMAND) {
                DialogMultilineField(
                    value = command,
                    onValueChange = { command = it.take(2000) },
                    placeholder = stringResource(R.string.schedule_command_placeholder),
                )
                Text(
                    text = stringResource(R.string.schedule_action_command_hint),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = colors.placeholder,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            } else {
                DialogField(
                    value = note,
                    onValueChange = { note = it.take(80) },
                    placeholder = stringResource(R.string.schedule_field_note),
                )
                Text(
                    text = stringResource(R.string.schedule_action_remind_hint),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = colors.placeholder,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PillShape)
                    .clickable {
                        TimePickerDialog(
                            context,
                            { _, h, m -> hour = h; minute = m },
                            hour,
                            minute,
                            true,
                        ).show()
                    }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.schedule_time),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = colors.placeholder,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "%02d:%02d".format(hour, minute),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Repeat.entries.forEach { option ->
                    val selected = option == repeat
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            colors.placeholder
                        },
                        modifier = Modifier
                            // 选项是胶囊：与输入栏、菜单里的选项同一种形状
                            .clip(PillShape)
                            .background(if (selected) colors.capsuleSelected else colors.fieldBackground)
                            .clickable { repeat = option }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }

            if (repeat == Repeat.WEEKLY) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 周一在前、周日在后：中文习惯，不用管 Calendar 的 1=周日
                    listOf(
                        Calendar.MONDAY to "一",
                        Calendar.TUESDAY to "二",
                        Calendar.WEDNESDAY to "三",
                        Calendar.THURSDAY to "四",
                        Calendar.FRIDAY to "五",
                        Calendar.SATURDAY to "六",
                        Calendar.SUNDAY to "日",
                    ).forEach { (day, label) ->
                        val on = day in days
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (on) colors.capsuleSelected else colors.fieldBackground)
                                .clickable {
                                    days = if (on) days - day else days + day
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                                color = if (on) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    colors.placeholder
                                },
                            )
                        }
                    }
                }
            }

            if (action == ScheduleAction.COMMAND) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MenuShape)
                        .clickable { quiet = !quiet }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.schedule_quiet),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.schedule_quiet_hint),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = colors.placeholder,
                        )
                    }
                    AppSwitch(checked = quiet, onCheckedChange = { quiet = it })
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsTextAction(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                )
                Spacer(Modifier.width(8.dp))
                val canSave = title.isNotBlank() &&
                    (repeat != Repeat.WEEKLY || days.isNotEmpty()) &&
                    (action != ScheduleAction.COMMAND || command.isNotBlank())
                SettingsTextAction(
                    text = stringResource(R.string.action_save),
                    primary = true,
                    enabled = canSave,
                    onClick = {
                        onConfirm(
                            title,
                            hour,
                            minute,
                            repeat,
                            note,
                            days.toList(),
                            action,
                            command,
                            quiet,
                        )
                    },
                )
            }
        }
    }
}

/** 对话框里的输入框：无描边、浅灰底、胶囊形（全应用的单行输入框都是胶囊）。 */
@Composable
private fun DialogField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    val colors = LocalChatColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(PillShape)
            .background(colors.fieldBackground)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = colors.placeholder,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 多行输入（命令用）。
 *
 * 等宽字体 + 最多 5 行高度：shell 命令里的引号、管道、缩进在比例字体里很难核对，
 * 而这里最容易出的错正是少一个引号。
 */
@Composable
private fun DialogMultilineField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    val colors = LocalChatColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp, max = 132.dp)
            // 多行框用 20dp 圆角：胶囊拉到五行高会变成一个大椭圆
            .clip(MenuShape)
            .background(colors.fieldBackground)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = colors.placeholder,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatNext(at: Long): String =
    "下次 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))
