package com.simplechat.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplechat.app.R
import com.simplechat.app.data.Res
import com.simplechat.app.terminal.TerminalLine
import com.simplechat.app.terminal.TerminalSessions
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.theme.LocalChatColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/* 黑底终端的固定配色：不跟主题走，保证任何主题下对比度都够。 */
private val TerminalOutputWhite = Color(0xFFE8E8E8)
private val TerminalInputGreen = Color(0xFF7EC699)
private val TerminalErrorRed = Color(0xFFFF7B72)
private val TerminalInfoGray = Color(0xFF9A9A9A)

/**
 * 内嵌终端。
 *
 * 布局照着 Operit 的终端页来：**一行会话标签 + 一块输出 + 一排快捷键 + 一条输入**。
 *
 * 为什么要有标签：终端是唯一一个"跑着跑着就想开第二条"的地方 —— 一条 pip install
 * 在跑，你还想同时 `ls` 一下别的目录。单会话时用户只能等，或者把两条命令的输出
 * 搅在一起。标签把这件事变回"一次只做一件"。
 *
 * 快捷键条不是装饰：手机键盘上没有 Esc / Ctrl / Tab / 方向键，而没有它们，
 * 一些命令的中断、历史翻阅都做不了。
 */
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val colors = LocalChatColors.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    val sessions by TerminalSessions.list.collectAsState()
    val activeId by TerminalSessions.activeId.collectAsState()
    // 进来就给一个可用会话（退出页面不销毁，下次进来还是它）
    val entry = remember(sessions, activeId) {
        TerminalSessions.ensureActive()
        TerminalSessions.activeEntry()
    }
    val session = entry?.session ?: return

    val lines by session.lines.collectAsState()
    val busy by session.busy.collectAsState()
    val ubuntu by session.ubuntu.collectAsState()
    val listState = rememberLazyListState()
    var input by remember(entry?.id) { mutableStateOf("") }
    var runJob by remember(entry?.id) { mutableStateOf<Job?>(null) }
    /*
     * 终端字号：**双指捏合缩放**，与 Operit 的终端一致。
     *
     * 手机上 11.5sp 对长日志太密，对老人又太小；摆一排 A+ / A- 按钮要占掉
     * 本来就不宽的快捷键条，捏合是最不占地方的做法。状态留在页面级，
     * 切标签不重置（用户是按自己的眼睛调的，不是按会话调的）。
     */
    var fontSize by remember { mutableStateOf(11.5f) }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    fun submit(text: String) {
        if (text.isBlank()) return
        input = ""
        runJob = scope.launch { session.submit(text) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // ── 顶栏 ─────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCircleButton(
                icon = AppIcons.ChevronLeft,
                contentDescription = Res.get(R.string.action_back),
                onClick = onBack,
                size = 36.dp,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = Res.get(R.string.terminal_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            // AI 记录不切 shell：那些命令已经跑完了，切换只改标签上的字
            if (session.ubuntuAvailable() && entry?.ai != true) {
                Text(
                    text = if (ubuntu) "Ubuntu" else "sh",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (ubuntu) MaterialTheme.colorScheme.primary else colors.placeholder,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .border(
                            width = 1.dp,
                            color = if (ubuntu) MaterialTheme.colorScheme.primary else colors.outline,
                            shape = RoundedCornerShape(7.dp),
                        )
                        .clickable { session.toggleShell() }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            IconCircleButton(
                icon = AppIcons.Trash,
                contentDescription = Res.get(R.string.terminal_clear),
                onClick = { session.clear() },
                size = 36.dp,
            )
            IconCircleButton(
                icon = AppIcons.Plus,
                contentDescription = Res.get(R.string.terminal_new),
                onClick = {
                    TerminalSessions.create()
                    keyboard?.show()
                },
                size = 36.dp,
            )
        }

        // ── 会话标签 ─────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            ) {
                items(sessions, key = { it.id }) { item ->
                    val selected = item.id == entry?.id
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) colors.capsuleSelected else colors.fieldBackground)
                            .clickable { TerminalSessions.select(item.id) }
                            .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = if (selected) MaterialTheme.colorScheme.primary else colors.processText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(74.dp),
                        )
                        // AI 记录不可关闭 —— 它不是一次会话，是终端的一部分
                        if (selected && sessions.size > 1 && !item.ai) {
                            Icon(
                                imageVector = AppIcons.Close,
                                contentDescription = Res.get(R.string.terminal_close),
                                tint = colors.placeholder,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .clickable { TerminalSessions.close(item.id) }
                                    .padding(3.dp),
                            )
                        }
                    }
                }
            }
        }

        // ── 输出 ─────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                /*
                 * 输出区**永远是纯黑底**，不跟随主题。
                 *
                 * 终端看的是等宽字符与命令输出，不是阅读正文：浅底上灰色输出
                 * 与背景的对比度只有 2:1 左右，阳光下一整片糊住。黑底 + 近白字符
                 * 是所有真实终端的默认值，也是这里要的可见度。
                 */
                .background(Color.Black),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            if (zoom == 1f) return@detectTransformGestures
                            fontSize = (fontSize * zoom).coerceIn(8f, 26f)
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(lines) { line ->
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * 1.4f).sp,
                        ),
                        color = when (line.kind) {
                            TerminalLine.Kind.INPUT -> TerminalInputGreen
                            TerminalLine.Kind.ERROR -> TerminalErrorRed
                            TerminalLine.Kind.INFO -> TerminalInfoGray
                            TerminalLine.Kind.OUTPUT -> TerminalOutputWhite
                        },
                    )
                }
                if (lines.isEmpty()) {
                    item {
                        Text(
                            text = Res.get(R.string.terminal_hint),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * 1.5f).sp,
                            ),
                            color = TerminalInfoGray,
                        )
                    }
                }
            }
        }

        /*
         * AI 会话是只读记录：没有快捷键、没有输入行。
         *
         * 留一排 Ctrl-C / Tab 给它反而是误导 —— 那些键是给"正在跑的这条
         * shell"用的，而这里的命令早就在别处执行完了。整块换一行说明，
         * 告诉用户"要去别处敲就去别的标签"，比给一堆按不动的键干净。
         */
        if (entry?.ai == true) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = Res.get(R.string.terminal_ai_footnote),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
                    color = colors.placeholder,
                )
            }
            return@Column
        }

        // ── 快捷键 ───────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TerminalKey("Ctrl-C") {
                runJob?.cancel()
                session.abort()
            }
            TerminalKey("↑") { session.historyUp()?.let { input = it } }
            TerminalKey("↓") { session.historyDown()?.let { input = it } }
            TerminalKey("Tab") { input += "  " }
            TerminalKey("cd ..") { submit("cd ..") }
            TerminalKey("ls -la") { submit("ls -la") }
            TerminalKey(Res.get(R.string.terminal_clear)) { session.clear() }
        }

        // ── 输入行 ───────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = session.prompt(),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = colors.placeholder,
                maxLines = 1,
                modifier = Modifier.widthIn(max = 96.dp),
            )
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.fieldBackground)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (input.isEmpty()) {
                    Text(
                        text = Res.get(R.string.terminal_placeholder),
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        color = colors.placeholder,
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit(input) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                IconCircleButton(
                    icon = AppIcons.ArrowUp,
                    contentDescription = Res.get(R.string.action_send),
                    onClick = {
                        submit(input)
                        keyboard?.hide()
                    },
                    size = 36.dp,
                )
            }
        }
    }
}

/** 快捷键小胶囊。等宽字，比普通胶囊矮一点 —— 它属于"键盘"而不是"按钮"。 */
@Composable
private fun TerminalKey(label: String, onClick: () -> Unit) {
    val colors = LocalChatColors.current
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        ),
        color = colors.processText,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(colors.card)
            .border(0.5.dp, colors.divider, RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
    )
}
