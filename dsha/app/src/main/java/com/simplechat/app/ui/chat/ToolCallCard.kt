package com.simplechat.app.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate as rotateDraw
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import com.simplechat.app.R
import com.simplechat.app.data.Res
import com.simplechat.app.agent.ApprovalDecision
import com.simplechat.app.agent.ToolRisk
import com.simplechat.app.agent.label
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.CapsuleButton
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.common.ShimmerText
import com.simplechat.app.ui.common.ImageLoadState
import com.simplechat.app.ui.common.imageBytesOf
import com.simplechat.app.ui.common.rememberFileSaver
import com.simplechat.app.ui.common.rememberImageLoad
import com.simplechat.app.ui.theme.LocalChatColors
import kotlinx.coroutines.launch

/**
 * 审批回调。
 *
 * 用 CompositionLocal 而不是一路加参数：卡片在 `ProcessTimeline` → `MessageList`
 * → `ChatScreen` 之间被调用四次，为了一个"点按钮"把回调穿过四层签名，
 * 每个中间层都得声明一个自己不用的参数。审批是**全局只有一个接收者**的动作，
 * 正是 CompositionLocal 该做的事。
 */
val LocalToolApproval = compositionLocalOf<(ApprovalDecision) -> Unit> { {} }

/**
 * 工具调用卡片。
 *
 * 形态参照 DeepSeek Harness：**一行说清"做了什么"，点开才看细节**。
 *
 * 几个刻意的选择：
 *
 * - 不画边框、不用卡片阴影，只用一层极浅的填充。工具调用在一轮回答里可能出现
 *   五六次，每个都做成带框卡片，正文会被切成一堆方块，读起来很碎。
 * - 收起态就是一行。参数压成摘要（路径 / 命令首行），不把 JSON 摊开 ——
 *   用户关心的永远是"它读了哪个文件"，不是 `{"path": "...", "limit": 400}`。
 * - 运行中有一个转动的弧，不是全屏 loading。工具是局部的，动效也该是局部的。
 */
@Composable
fun ToolCallCard(
    call: ToolCallUi,
    modifier: Modifier = Modifier,
    /** 点「预览」时把路径交给聊天页，由它打开内置 Web 预览。 */
    onPreview: (String) -> Unit = {},
) {
    val colors = LocalChatColors.current
    /*
     * 思考默认展开。
     *
     * 默认收起：卡片是日志，不是正文。要看得自己点开。
     */
    var expanded by remember(call.id) { mutableStateOf(false) }
    val hasOutput = call.output.isNotBlank()
    val hasDiff = call.diff.isNotEmpty()
    val hasArgs = call.argsJson.isNotBlank() && call.argsJson != "{}"
    // 有任意一种细节就能点开 —— 早先只认 output，于是"只改了文件"的卡片点不动
    val expandable = hasOutput || hasDiff || hasArgs
    val diffStat = remember(call.diff) { com.simplechat.app.agent.Diffs.summary(call.diff) }
    // 这次调用写的是不是网页：是的话给一颗"预览"，省得用户自己去文件管理器里找
    val htmlPath = remember(call.id, call.status, call.argsJson) { previewablePath(call) }
    // 思考的正文在参数里（thinking），不在返回里 —— 展开时直接当段落读，不要摊 JSON
    val thinkingText = remember(call.id, call.argsJson) { thinkingOf(call) }

    // 箭头旋转而不是换图标：同一个形状转过去，中间没有"两个箭头交替"的闪
    val chevronAngle by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 620f),
        label = "toolChevron",
    )

    /*
     * 一行，没有底色、没有边框、没有卡片。
     *
     * 上一版是带圆角底色的"卡片"，一轮 Agent 里五六次调用就是五六个方块，
     * 正文被切成碎片 —— 那正是老张说的"气泡一条一条"。Harness 那边从头到尾
     * 只有**文字行**：一行一次调用，细节点开才展开，读起来像一份操作日志。
     *
     * 层级靠缩进表达，不靠背景：展开后的参数/变更/返回整体右缩 20dp，
     * 与上面那行的图标列对齐，一眼就知道"这些细节属于上一行"。
     */
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = expandable) { expanded = !expanded }
            .padding(vertical = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusGlyph(status = call.status)
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = iconFor(call.name),
                contentDescription = null,
                tint = colors.processText,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(7.dp))
            /*
             * 与折叠态那一行（ToolRunStrip）同色同动画：两处是同一个东西的
             * 两种展开程度，颜色不一样会让人以为"展开后换了个工具"。
             */
            ShimmerText(
                text = toolDisplayName(call.name),
                style = MaterialTheme.typography.labelMedium,
                color = colors.processText,
                active = call.status == ToolCallUi.Status.RUNNING,
            )
            if (call.summary.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                ShimmerText(
                    text = call.summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.processText,
                    active = call.status == ToolCallUi.Status.RUNNING,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Spacer(Modifier.weight(1f))
            if (htmlPath != null) {
                IconCircleButton(
                    icon = AppIcons.Globe,
                    contentDescription = Res.get(R.string.web_preview),
                    onClick = { onPreview(htmlPath) },
                    size = 24.dp,
                    iconSize = 13.dp,
                    tint = colors.processText,
                )
                Spacer(Modifier.width(2.dp))
            }
            /*
             * 变更统计放在折叠态。
             *
             * 只在展开时给 diff 是不够的 —— 用户扫一眼列表要能立刻看出
             * "这条改动大不大"，再决定点不点开。`+12 −3` 就是回答这个问题。
             */
            if (diffStat != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "+${diffStat.first}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = DiffAdd,
                )
                Text(
                    text = " −${diffStat.second}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = DiffRemove,
                )
            }
            if (call.elapsedMs > 0) {
                Spacer(Modifier.width(5.dp))
                Text(
                    text = formatElapsed(call.elapsedMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.placeholder,
                )
            }
            if (expandable) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = AppIcons.ChevronDown,
                    contentDescription = null,
                    tint = colors.placeholder,
                    modifier = Modifier
                        .size(13.dp)
                        .rotate(chevronAngle),
                )
            }
        }

        if (call.status == ToolCallUi.Status.AWAITING) {
            Column(modifier = Modifier.padding(start = 20.dp)) {
                ApprovalRow(call)
            }
        }

        /*
         * 绘图：生成中给一块会呼吸的占位，出了图就摆出来。
         *
         * 不放进"展开后的返回"里：用户让它画图，要的是**看见那张图**，
         * 而不是点开一行日志去找一个文件路径。占位与结果都放在卡片正文下方，
         * 与微博/小红书的"图片生成中"是同一个位置感。
         */
        if (call.name == "generate_image") {
            if (call.status == ToolCallUi.Status.RUNNING) {
                DrawingPlaceholder(
                    aspect = call.aspectRatio,
                    modifier = Modifier.padding(start = 20.dp, top = 6.dp),
                )
            }
            if (call.images.isNotEmpty()) {
                GeneratedImages(
                    paths = call.images,
                    aspect = call.aspectRatio,
                    modifier = Modifier.padding(start = 20.dp, top = 6.dp, end = 4.dp),
                )
            }
        }

        /*
         * 展开用 AnimatedVisibility 而不是 `if`。
         *
         * 工具卡点开的那一下会多出十几行（参数 + diff + 返回），硬切等于让
         * 下面的正文瞬间被推走一大截；一次 200ms 的展开让眼睛能跟上。
         */
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(200)) + fadeIn(tween(150)),
            exit = shrinkVertically(tween(170)) + fadeOut(tween(100)),
        ) {
            Column(modifier = Modifier.padding(start = 20.dp, top = 5.dp, end = 4.dp)) {
                if (thinkingText != null) {
                    Text(
                        text = thinkingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.processText,
                    )
                } else {
                if (hasArgs) {
                    DetailSection(
                        title = Res.get(R.string.tool_detail_args),
                        content = formatArgs(call.argsJson),
                    )
                }

                if (hasDiff) {
                    if (hasArgs) Spacer(Modifier.size(9.dp))
                    DetailLabel(Res.get(R.string.tool_detail_diff))
                    Spacer(Modifier.size(4.dp))
                    DiffBlock(call.diff)
                }

                if (hasOutput) {
                    if (hasArgs || hasDiff) Spacer(Modifier.size(9.dp))
                    DetailSection(
                        title = Res.get(R.string.tool_detail_output),
                        content = call.output,
                        danger = call.status == ToolCallUi.Status.FAILED,
                    )
                }
                }
            }
        }
    }
}

/**
 * 等待批准时那一条。
 *
 * 刻意放在卡片**内部**而不是弹窗：用户要看的正是"它要动哪个文件、跑哪条命令"，
 * 而那些字就在这张卡里。弹窗会把它们盖住，等于让人闭着眼睛点同意。
 *
 * 三个选项的排序有讲究：「允许」在前（最常见），「总是允许」被压在最后并加一句
 * "本次会话" —— 那是一个扩大授权的动作，不该和"允许一次"长得一样顺手。
 */
@Composable
private fun ApprovalRow(call: ToolCallUi) {
    val colors = LocalChatColors.current
    val approve = LocalToolApproval.current
    val risk = call.risk

    Spacer(Modifier.size(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (risk != null && risk != ToolRisk.WRITE && risk != ToolRisk.EXEC) {
                Res.get(R.string.approval_needs, risk.label())
            } else {
                Res.get(R.string.approval_waiting)
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (risk == ToolRisk.PRIVILEGED || risk == ToolRisk.SCREEN) {
                colors.warning
            } else {
                colors.processText
            },
            modifier = Modifier.weight(1f),
        )
        CapsuleButton(
            text = Res.get(R.string.approval_deny),
            onClick = { approve(ApprovalDecision.Deny) },
        )
        CapsuleButton(
            text = Res.get(R.string.approval_allow),
            selected = true,
            onClick = { approve(ApprovalDecision.Allow) },
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            text = Res.get(R.string.approval_always),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { approve(ApprovalDecision.AllowForSession) }
                .padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

/** 三个状态的小标记，直径 12dp。 */
@Composable
private fun StatusGlyph(status: ToolCallUi.Status) {
    val colors = LocalChatColors.current
    val primary = MaterialTheme.colorScheme.primary

    if (status == ToolCallUi.Status.AWAITING) {
        // 一个静止的描边圈：不转，因为它在等人，不是在干活
        Canvas(Modifier.size(12.dp)) {
            val stroke = size.minDimension * 0.14f
            drawCircle(
                color = primary,
                radius = size.minDimension / 2f - stroke,
                style = Stroke(width = stroke),
            )
        }
        return
    }

    if (status == ToolCallUi.Status.RUNNING) {
        // 转动的 3/4 圆弧：比三点省略更"局部"，也更能说明"正在跑"
        val transition = rememberInfiniteTransition(label = "tool-spin")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "tool-spin-angle",
        )
        Canvas(Modifier.size(12.dp)) {
            val stroke = size.minDimension * 0.16f
            val inset = stroke / 2f
            rotateDraw(angle) {
                drawArc(
                    color = primary,
                    startAngle = 0f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        return
    }

    val color = if (status == ToolCallUi.Status.FAILED) colors.danger else colors.placeholder
    Canvas(Modifier.size(12.dp)) {
        val radius = size.minDimension * 0.26f
        drawCircle(color = color, radius = radius, center = center)
    }
}

private fun iconFor(name: String) = when {
    // 终端命令：终端图标。早先这里挂的是对话气泡（`Prompt`），
    // 一张"在跑命令"的卡片顶着聊天气泡的图标，语义完全错位。
    name == "run_shell" -> AppIcons.Terminal
    // Ubuntu 那一族：同为终端，但用 Database 区分"容器里的系统"与"手机的 toybox"
    name == "linux_shell" -> AppIcons.Terminal
    name == "install_packages" -> AppIcons.Download
    name == "linux_env" -> AppIcons.Server
    name == "python" -> AppIcons.Sparkles
    name == "generate_image" -> AppIcons.Image
    name == "web_fetch" -> AppIcons.Globe
    name == "list_dir" -> AppIcons.Database
    name.startsWith("mcp__") -> AppIcons.Server
    else -> AppIcons.FileText
}

/** `0.3s` / `1.2s` / `12s`。工具耗时很少超过一分钟，不必做分钟级格式。 */
private fun formatElapsed(ms: Long): String {
    val seconds = ms / 1000.0
    return if (seconds < 10) String.format(java.util.Locale.US, "%.1fs", seconds) else "${seconds.toInt()}s"
}

// ══════════════════════════════════════════════════════════
//  绘图：生成中占位与结果
// ══════════════════════════════════════════════════════════

/**
 * "正在绘制"。
 *
 * 一块会扫光的圆角矩形，而不是一个转圈：出图要十几秒到一分钟，转圈看久了像卡死；
 * 扫光暗示"画面正在被画出来"，符合这个动作本身的语义。
 *
 * 宽高比跟着**这次真正请求的尺寸**走：用户要竖版壁纸，等待时就该是一个竖框。
 * 固定 224×152 的横块会让人以为"它又要画方图"，最后蹦出竖图反而像换了张。
 */
@Composable
private fun DrawingPlaceholder(aspect: Float, modifier: Modifier = Modifier) {
    val colors = LocalChatColors.current
    val accent = MaterialTheme.colorScheme.primary
    val base = colors.fieldBackground
    val transition = rememberInfiniteTransition(label = "draw")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "shift",
    )
    val highlight = accent.copy(alpha = 0.13f)
    val (boxW, boxH) = previewBox(aspect)

    Box(
        modifier = modifier
            .width(boxW)
            .height(boxH)
            .clip(RoundedCornerShape(12.dp))
            .drawBehind {
                val w = size.width
                val brush = androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = androidx.compose.ui.geometry.Offset(-w + shift * 2 * w, 0f),
                    end = androidx.compose.ui.geometry.Offset(shift * 2 * w, size.height),
                )
                drawRect(brush)
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = accent.copy(alpha = 0.7f),
            )
            Spacer(Modifier.size(9.dp))
            Text(
                text = Res.get(R.string.image_drawing),
                style = MaterialTheme.typography.labelSmall,
                color = colors.processText,
            )
        }
    }
}

/**
 * 预览框尺寸：宽上限 224dp，高落在 96–320dp。
 *
 * 竖图（9:16）宽度收到 180dp 才放得下 320dp 高；方图 224×224；
 * 超宽图（3:1）高度兜到 96dp，否则会变成一条看不清的细线。
 */
private fun previewBox(aspect: Float): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> {
    val a = aspect.takeIf { it.isFinite() && it > 0.05f } ?: 1f
    val h = (224f / a).coerceIn(96f, 320f)
    val w = (h * a).coerceAtMost(224f)
    return w.dp to h.dp
}

/** 生成结果：一张张摆出来，点开看整屏；右下角可以直接存走。 */
@Composable
private fun GeneratedImages(
    paths: List<String>,
    aspect: Float,
    modifier: Modifier = Modifier,
) {
    var viewing by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        paths.forEach { source ->
            GeneratedImageTile(
                source = source,
                aspectHint = aspect,
                onOpen = { viewing = source },
            )
        }
    }

    viewing?.let { source ->
        val state = rememberImageLoad(source, 1600)
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { viewing = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black)
                    .clickable { viewing = null },
                contentAlignment = Alignment.Center,
            ) {
                when (state) {
                    is ImageLoadState.Ready -> androidx.compose.foundation.Image(
                        bitmap = state.image,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )

                    ImageLoadState.Loading -> androidx.compose.material3.CircularProgressIndicator(
                        color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(26.dp),
                    )

                    ImageLoadState.Failed -> Text(
                        text = Res.get(R.string.image_load_failed),
                        style = MaterialTheme.typography.labelSmall,
                        color = androidx.compose.ui.graphics.Color.White,
                    )
                }
            }
        }
    }
}

/**
 * 单张成品图。
 *
 * 右下角那颗下载键是**必须**的：图在 CDN 上（或 App 私有目录里），用户
 * 在系统相册里找不到它。走 SAF 让用户自己挑落点，不需要任何存储权限 ——
 * 也不会往存储根目录里塞东西。
 */
@Composable
private fun GeneratedImageTile(
    source: String,
    aspectHint: Float,
    onOpen: () -> Unit,
) {
    val colors = LocalChatColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val state = rememberImageLoad(source, 768)
    val aspect = (state as? ImageLoadState.Ready)
        ?.let { it.image.width.toFloat() / it.image.height }
        ?.takeIf { it.isFinite() && it > 0.05f }
        ?: aspectHint
    val (boxW, boxH) = previewBox(aspect)

    val saver = rememberFileSaver(mime = "image/png") { ok ->
        android.widget.Toast.makeText(
            context,
            Res.get(if (ok) R.string.image_saved else R.string.image_save_failed),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    Box(
        modifier = Modifier
            .width(boxW)
            .height(boxH)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.fieldBackground)
            .clickable(onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is ImageLoadState.Ready -> androidx.compose.foundation.Image(
                bitmap = state.image,
                contentDescription = Res.get(R.string.image_generated),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )

            ImageLoadState.Loading -> androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            )

            ImageLoadState.Failed -> Text(
                text = Res.get(R.string.image_load_failed),
                style = MaterialTheme.typography.labelSmall,
                color = colors.placeholder,
            )
        }

        if (state is ImageLoadState.Ready) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(28.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
                    .clickable {
                        scope.launch {
                            val bytes = imageBytesOf(source)
                            if (bytes == null) {
                                android.widget.Toast.makeText(
                                    context,
                                    Res.get(R.string.image_save_failed),
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                saver("dsha-image-${System.currentTimeMillis()}.png", bytes)
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.Download,
                    contentDescription = Res.get(R.string.image_save),
                    tint = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

/** 一行工具调用的容器：多条之间留一点缝，但比分段更大。 */
@Composable
fun ToolCallList(
    calls: List<ToolCallUi>,
    modifier: Modifier = Modifier,
    onPreview: (String) -> Unit = {},
) {
    if (calls.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        calls.forEach { call -> ToolCallCard(call, onPreview = onPreview) }
    }
}

/**
 * 写文件工具且目标是 HTML → 返回它的路径。
 *
 * 只认 `write_file` / `edit_file` / `patch_file`：这三类才会让一个网页"变成现在的样子"。
 * `read_file` 也带 path，但读完给个预览按钮没有意义。
 */
private fun previewablePath(call: ToolCallUi): String? {
    if (call.status != ToolCallUi.Status.OK) return null
    if (call.name != "write_file" && call.name != "edit_file" && call.name != "patch_file") return null
    val path = runCatching {
        val element = kotlinx.serialization.json.Json.parseToJsonElement(call.argsJson)
        val obj = element as? kotlinx.serialization.json.JsonObject
        (obj?.get("path") as? kotlinx.serialization.json.JsonPrimitive)?.content
    }.getOrNull() ?: return null
    return path.takeIf { com.simplechat.app.agent.PreviewServer.isPreviewable(it) }
}

// ══════════════════════════════════════════════════════════
//  连续调用的"一行"
// ══════════════════════════════════════════════════════════

/**
 * 一连串工具调用 = **一行灰字**。
 *
 * 上一版是一张一张的卡片竖着排。一轮 Agent 里读过三个文件、跑过两次命令，
 * 正文就被切成七八段，中间夹着一列长得差不多的行 —— 用户的原话是
 * "一串一串工具调用看得难受"。问题不在于单行做得不好看，而在于**它们
 * 占的视觉重量和正文一样多**：工具调用是过程，正文才是结果。
 *
 * 所以这里把一整段连续的调用压成一行：只显示**当前那一次**，名字 + 摘要，
 * 灰色；执行中那行字会呼吸（透明度 0.55 ↔ 1.0），换到下一个工具时交叉淡入。
 * 于是眼睛看到的是"一串闪烁的灰字"，而不是"一串气泡"。
 *
 * 想看细节就点开 —— 展开后仍是原来那一张张 `ToolCallCard`，信息一点没少。
 * 需要审批的调用**始终展开**：那是必须被看见、被点的一条。
 */
@Composable
fun ToolRunStrip(
    calls: List<ToolCallUi>,
    modifier: Modifier = Modifier,
    onPreview: (String) -> Unit = {},
) {
    if (calls.isEmpty()) return
    val colors = LocalChatColors.current

    var expanded by remember(calls.first().id) { mutableStateOf(false) }
    val stripChevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 620f),
        label = "toolStripChevron",
    )

    /*
     * 钉住"当前那一次"：有正在跑的、有在等批准的就取最后一个（最新的），
     * 否则取最后一次调用 —— 全部结束后不该回到第一行，那会显得"倒回去了"。
     */
    val active = calls.lastOrNull {
        it.status == ToolCallUi.Status.RUNNING || it.status == ToolCallUi.Status.AWAITING
    } ?: calls.last()
    val awaiting = calls.firstOrNull { it.status == ToolCallUi.Status.AWAITING }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusGlyph(status = active.status)
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = iconFor(active.name),
                contentDescription = null,
                tint = colors.processText,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(7.dp))
            /*
             * 名字与摘要整行交叉淡入。
             *
             * `AnimatedContent` 的 key 用整条 call 而不是文本：同一个工具连着跑两次
             * 读同一个文件，文本一模一样，用文本当 key 就不会有过渡 —— 看起来
             * 像"没反应"。用 id 至少能看出"又动了一次"。
             */
            AnimatedContent(
                targetState = active,
                transitionSpec = {
                    fadeIn(tween(160)) togetherWith fadeOut(tween(120))
                },
                modifier = Modifier.weight(1f, fill = false),
                label = "toolRun",
            ) { call ->
                val running = call.status == ToolCallUi.Status.RUNNING
                Row(verticalAlignment = Alignment.CenterVertically) {
                    /*
                     * 运行中的那一行灰字走流光 —— 与思考标题同一套（见 ShimmerText）。
                     *
                     * 上一版是整行透明度呼吸。两段文字（工具名 + 摘要）一起明暗，
                     * 看起来像整块在闪；流光有方向，读起来是"在往前跑"。
                     * 停下来的调用立刻回到静态灰字，不再抢注意力。
                     */
                    ShimmerText(
                        text = toolDisplayName(call.name),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.processText,
                        active = running,
                        maxLines = 1,
                    )
                    if (call.summary.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        ShimmerText(
                            text = call.summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.placeholder,
                            active = running,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (calls.size > 1) {
                Text(
                    text = Res.get(R.string.tool_run_steps, calls.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.placeholder,
                )
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                imageVector = AppIcons.ChevronDown,
                contentDescription = Res.get(R.string.tool_run_expand, calls.size),
                tint = colors.placeholder,
                modifier = Modifier
                    .size(13.dp)
                    .rotate(stripChevron),
            )
        }

        /*
         * 审批行只在**折叠态**出现在这里。
         *
         * 展开后每一张 `ToolCallCard` 自己会画一条（见上面的 `AWAITING` 分支），
         * 而这一条如果照旧留着，同一个申请就会在屏幕上出现两次 —— 用户看到的是
         * 「下面还有一条一模一样的执行工具申请」。折叠时这张卡还没展开，
         * 卡片内部那条看不见，所以必须在外面补一条；展开后由内部接管。
         */
        if (awaiting != null && !expanded) {
            Column(modifier = Modifier.padding(start = 18.dp)) {
                ApprovalRow(awaiting)
            }
        }

        /*
         * 展开：逐条回到原来的卡片。
         *
         * `AnimatedVisibility` 而不是硬切 —— 这行只有 20dp 高，突然长出十几行
         * 会把下面整段正文往下推一大截，用户会以为滚错了位置。
         */
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(180)) + fadeIn(tween(140)),
            exit = shrinkVertically(tween(160)) + fadeOut(tween(100)),
        ) {
            Column(
                modifier = Modifier.padding(start = 18.dp, top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                calls.forEach { call -> ToolCallCard(call, onPreview = onPreview) }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════
//  展开后的三段细节
// ══════════════════════════════════════════════════════════

/*
 * diff 的两种颜色。
 *
 * 用固定的深绿 / 深红而不是主题色：diff 是可读性优先的东西，配色在深色主题里
 * 也必须一眼分得清"加"和"减"。主题色会随品牌色变，绿可能变成蓝 —— 那就废了。
 * 不用纯 #00FF00 / #FF0000：纯色在高对比度下会"发光"，长 diff 看着刺眼。
 */
private val DiffAdd = Color(0xFF3E9E6E)
private val DiffRemove = Color(0xFFC4514E)

/** 展开区里的小标题（"参数" / "变更" / "返回"）。 */
@Composable
private fun DetailLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = LocalChatColors.current.placeholder,
    )
}

/** 一段带标题的等宽文本（参数 / 返回共用）。 */
@Composable
private fun DetailSection(
    title: String,
    content: String,
    danger: Boolean = false,
) {
    val colors = LocalChatColors.current
    Column {
        DetailLabel(title)
        Spacer(Modifier.size(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .background(
                    if (danger) colors.danger.copy(alpha = 0.06f) else colors.markdownCodeBlock,
                    RoundedCornerShape(8.dp),
                )
                .padding(9.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = content,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = if (danger) colors.danger else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * 逐行差异。
 *
 * 横向可滚动：代码行一折行，`+` / `-` 的对齐就断了，整块会变得难以扫读。
 * 宁可让用户横滑，也不要让它软换行。
 */
@Composable
private fun DiffBlock(lines: List<com.simplechat.app.agent.DiffLine>) {
    val colors = LocalChatColors.current
    /*
     * 横向滚动放在**外层**，所有行一起动。
     *
     * 早先给每行各自套一个 horizontalScroll —— 滑第三行时上面几行不动，
     * 同一份 diff 里出现错位的列，完全没法读。滚动是"看这一整块"的动作。
     */
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .background(colors.markdownCodeBlock, RoundedCornerShape(8.dp))
            .padding(vertical = 6.dp)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState()),
    ) {
        lines.forEach { line ->
            val (mark, tint, background) = when (line.kind) {
                com.simplechat.app.agent.DiffLine.Kind.ADD ->
                    Triple("+", DiffAdd, DiffAdd.copy(alpha = 0.10f))
                com.simplechat.app.agent.DiffLine.Kind.REMOVE ->
                    Triple("−", DiffRemove, DiffRemove.copy(alpha = 0.10f))
                com.simplechat.app.agent.DiffLine.Kind.SAME ->
                    Triple(" ", colors.placeholder, Color.Transparent)
            }
            Row(
                modifier = Modifier
                    .background(background)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = mark,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = tint,
                    modifier = Modifier.width(10.dp),
                )
                Text(
                    text = line.text.ifEmpty { " " },
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (line.kind == com.simplechat.app.agent.DiffLine.Kind.SAME) {
                        colors.placeholder
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * 参数 JSON → 可读的多行文本。
 *
 * 不直接 `Text(argsJson)`：模型传的参数常常是一整行几百字符的 JSON，
 * 全挤在一次软换行里，读不出结构。这里按 key 拆行，长值缩进续行。
 *
 * 解析失败就原样返回 —— 参数本来就可能是半成品，显示原文比显示"解析失败"有用。
 */
private fun formatArgs(raw: String): String {
    val element = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(raw)
    }.getOrNull() as? kotlinx.serialization.json.JsonObject ?: return raw
    if (element.isEmpty()) return raw

    return element.entries.joinToString("\n") { (key, value) ->
        val text = runCatching {
            val primitive = value as? kotlinx.serialization.json.JsonPrimitive
            primitive?.content ?: value.toString()
        }.getOrNull().orEmpty()
        if (text.contains('\n')) {
            "$key:\n" + text.lineSequence().joinToString("\n") { "    $it" }
        } else {
            "$key: ${if (text.length > 240) text.take(240) + "…" else text}"
        }
    }
}

/**
 * 从 `think` 的参数里取出思考正文。
 *
 * 非 think 调用返回 null —— 调用方据此走原来的三段式（参数 / 变更 / 返回）。
 */
private fun thinkingOf(call: ToolCallUi): String? {
    if (call.name != "think") return null
    val text = runCatching {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(call.argsJson)
            as? kotlinx.serialization.json.JsonObject
        (obj?.get("thinking") as? kotlinx.serialization.json.JsonPrimitive)?.content
    }.getOrNull()
    return text?.takeIf { it.isNotBlank() }
}
