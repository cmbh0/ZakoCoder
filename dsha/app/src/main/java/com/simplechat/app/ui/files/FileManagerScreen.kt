package com.simplechat.app.ui.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.simplechat.app.R
import com.simplechat.app.agent.LocalPreviewSource
import com.simplechat.app.agent.PreviewServer
import com.simplechat.app.agent.Workspace
import com.simplechat.app.data.Res
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppMenu
import com.simplechat.app.ui.common.AppMenuItem
import com.simplechat.app.ui.preview.WebPreviewScreen
import com.simplechat.app.ui.theme.LocalChatColors
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 双页文件管理器。
 *
 * 照 MT 管理器的形态做：左右两栏各自停在某个目录，长按弹一张动作面板，
 * 复制 / 移动直接落到**另一栏**当前目录 —— 这是双栏真正的用处。选文件、
 * 切目录、再回到原处粘贴的单栏流程，在手机上要来回三四趟。
 *
 * 和 [com.simplechat.app.ui.workspace.WorkspaceScreen] 的分工：
 * 那一页回答"这段对话的根目录绑在哪、要不要换"，是**设置**；
 * 这一页是**干活**的地方。两者都不该塞进对方。
 *
 * ### 写操作的边界
 *
 * 全部落在 IO 线程，失败一律回一句人话（权限不足 / 目录非空 / 磁盘满），
 * 不吞异常、不静默。删除**不进回收站** —— 这一页没有回收站，靠二次确认。
 */
@Composable
fun FileManagerScreen(
    start: File,
    onBack: () -> Unit,
) {
    val colors = LocalChatColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /*
     * 两栏各自一份状态：路径、条目、历史。
     *
     * 不抽成 "Pane 数据类 + 列表"：两栏永远是两份，抽象成列表只是把
     * `if (index == 0)` 换个写法，读起来反而更绕。
     */
    var leftPath by remember { mutableStateOf(start) }
    var rightPath by remember { mutableStateOf(defaultRightPane(start)) }
    var active by remember { mutableStateOf(0) }

    var leftEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var rightEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var showHidden by remember { mutableStateOf(false) }

    val leftBack = remember { mutableStateListOf<File>() }
    val leftForward = remember { mutableStateListOf<File>() }
    val rightBack = remember { mutableStateListOf<File>() }
    val rightForward = remember { mutableStateListOf<File>() }

    var refreshTick by remember { mutableStateOf(0) }

    var sheet by remember { mutableStateOf<File?>(null) }
    var viewing by remember { mutableStateOf<File?>(null) }
    /** 用内置预览服务打开的网页文件。null = 不在预览。 */
    var previewing by remember { mutableStateOf<File?>(null) }
    var renaming by remember { mutableStateOf<File?>(null) }
    var creating by remember { mutableStateOf(false) }
    var creatingFile by remember { mutableStateOf(false) }
    var properties by remember { mutableStateOf<File?>(null) }
    var confirmingDelete by remember { mutableStateOf<File?>(null) }
    var bookmarksOpen by remember { mutableStateOf(false) }
    var toolsOpen by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    /*
     * 多选：两栏各自一份。
     *
     * 不共用一份是有意的 —— 左栏选中的是"要复制走的"，右栏选中的是"要删掉的"，
     * 共用之后在左右之间切换会让选择凭空消失或串台。底栏只作用于**当前栏**。
     */
    var leftSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var rightSelection by remember { mutableStateOf<Set<String>>(emptySet()) }
    /** 底栏「新建」弹出的二选一（文件夹 / 文件）。 */
    var newMenuOpen by remember { mutableStateOf(false) }
    /** 待确认批量删除的文件。 */
    var batchDelete by remember { mutableStateOf<List<File>>(emptyList()) }

    val activeEntries = if (active == 0) leftEntries else rightEntries
    val activeSelection = if (active == 0) leftSelection else rightSelection
    fun setActiveSelection(next: Set<String>) {
        if (active == 0) leftSelection = next else rightSelection = next
    }

    /** 两栏一起清 —— 批量操作之后"选择"这件事整体结束了，只清一栏会留下残影。 */
    fun clearSelections() {
        leftSelection = emptySet()
        rightSelection = emptySet()
    }

    /** 当前栏的选中项 —— 复制 / 移动只对它们生效（目标栏由 [otherPath] 决定）。 */
    fun activeSelectedFiles(): List<File> =
        activeEntries.filter { it.file.absolutePath in activeSelection }.map { it.file }

    /**
     * **两栏**所有选中项。
     *
     * 删除用它。早先这里只按 `activeEntries` 过滤，于是"左栏选两个、右栏选一个"
     * 之后按删除只会删掉当前栏那两个 —— 用户看到的是"选了三项，只删了一个"，
     * 而界面上没有任何地方告诉他选择集分栏独立。多选就该是"我选中的都算数"。
     */
    fun selectedFiles(): List<File> =
        (leftEntries.filter { it.file.absolutePath in leftSelection } +
            rightEntries.filter { it.file.absolutePath in rightSelection })
            .distinctBy { it.file.absolutePath }
            .map { it.file }

    fun otherPath(): File = if (active == 0) rightPath else leftPath

    LaunchedEffect(leftPath, showHidden, refreshTick) {
        leftEntries = withContext(Dispatchers.IO) { listEntries(leftPath, showHidden) }
    }
    LaunchedEffect(rightPath, showHidden, refreshTick) {
        rightEntries = withContext(Dispatchers.IO) { listEntries(rightPath, showHidden) }
    }

    toast?.let { message ->
        LaunchedEffect(message) {
            kotlinx.coroutines.delay(2200)
            if (toast == message) toast = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.settingsBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // ── 顶栏 ────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(start = 6.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.ArrowLeft,
                    contentDescription = Res.get(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = Res.get(R.string.files_title),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconAction(AppIcons.Bookmark, Res.get(R.string.files_bookmarks)) { bookmarksOpen = true }
            IconAction(AppIcons.More, Res.get(R.string.files_tools)) { toolsOpen = true }
        }

        // ── 双栏 ────────────────────────────────────────────
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Pane(
                path = leftPath,
                entries = leftEntries,
                active = active == 0,
                showHidden = showHidden,
                selection = leftSelection,
                modifier = Modifier.weight(1f),
                onActivate = { active = 0 },
                onOpenDir = { dir ->
                    leftBack.add(leftPath)
                    leftForward.clear()
                    leftPath = dir
                    active = 0
                },
                onLongPress = { file -> active = 0; sheet = file },
                onTapFile = { file ->
                    active = 0
                    if (PreviewServer.isPreviewable(file.name)) previewing = file
                    else openFile(context, file) { viewing = it }
                },
                onToggleSelect = { entry ->
                    active = 0
                    leftSelection = toggleSelection(leftSelection, entry.file.absolutePath)
                },
                onSwipeSelect = { from, to ->
                    active = 0
                    leftSelection = rangeSelection(leftSelection, leftEntries, from, to)
                },
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxSize()
                    .background(colors.divider),
            )
            Pane(
                path = rightPath,
                entries = rightEntries,
                active = active == 1,
                showHidden = showHidden,
                selection = rightSelection,
                modifier = Modifier.weight(1f),
                onActivate = { active = 1 },
                onOpenDir = { dir ->
                    rightBack.add(rightPath)
                    rightForward.clear()
                    rightPath = dir
                    active = 1
                },
                onLongPress = { file -> active = 1; sheet = file },
                onTapFile = { file ->
                    active = 1
                    if (PreviewServer.isPreviewable(file.name)) previewing = file
                    else openFile(context, file) { viewing = it }
                },
                onToggleSelect = { entry ->
                    active = 1
                    rightSelection = toggleSelection(rightSelection, entry.file.absolutePath)
                },
                onSwipeSelect = { from, to ->
                    active = 1
                    rightSelection = rangeSelection(rightSelection, rightEntries, from, to)
                },
            )
        }

        toast?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .background(colors.card, RoundedCornerShape(8.dp))
                    .padding(vertical = 6.dp),
            )
        }

        // ── 底栏 ────────────────────────────────────────────
        /*
         * 两种形态，同一个位置：
         *
         * - **多选态**（当前栏有选中）：全选 / 反选 / 复制 / 移动 / 删除 / 取消；
         * - **常态**：后退 / 前进 / 新建 / 刷新 / 上级。
         *
         * 换形而不是叠成两行：底栏高度只有 52dp，两行会把文件区挤掉一截，
         * 而这两种形态本来就不会同时需要。
         */
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(colors.card)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            if (leftSelection.isNotEmpty() || rightSelection.isNotEmpty()) {
                IconAction(AppIcons.ListChecks, Res.get(R.string.files_select_all)) {
                    setActiveSelection(activeEntries.map { it.file.absolutePath }.toSet())
                }
                IconAction(AppIcons.Copy, Res.get(R.string.files_select_invert)) {
                    /*
                     * 反选：当前栏里"没被选的"变成选中，"选中的"取消。
                     * 顺序上按整栏算，而不是只在已选集合里取反 ——
                     * 用户的心智是"这一屏里其他的都选上"。
                     */
                    val all = activeEntries.map { it.file.absolutePath }.toSet()
                    setActiveSelection(all - activeSelection)
                }
                IconAction(AppIcons.ArrowRight, Res.get(R.string.files_batch_copy)) {
                    val files = activeSelectedFiles()
                    val destination = otherPath()
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            files.count { transfer(it, destination, move = false).isSuccess }
                        }
                        toast = Res.get(R.string.files_batch_done, ok)
                        clearSelections()
                        refreshTick++
                    }
                }
                IconAction(AppIcons.ArrowLeft, Res.get(R.string.files_batch_move)) {
                    val files = activeSelectedFiles()
                    val destination = otherPath()
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            files.count { transfer(it, destination, move = true).isSuccess }
                        }
                        toast = Res.get(R.string.files_batch_done, ok)
                        clearSelections()
                        refreshTick++
                    }
                }
                IconAction(AppIcons.Trash, Res.get(R.string.files_batch_delete)) {
                    batchDelete = selectedFiles()
                }
                IconAction(AppIcons.Close, Res.get(R.string.files_select_clear)) {
                    clearSelections()
                }
            } else {
                IconAction(AppIcons.ArrowLeft, Res.get(R.string.files_back)) {
                    if (active == 0) {
                        leftBack.removeLastOrNull()?.let { leftForward.add(leftPath); leftPath = it }
                    } else {
                        rightBack.removeLastOrNull()?.let { rightForward.add(rightPath); rightPath = it }
                    }
                }
                IconAction(AppIcons.ArrowRight, Res.get(R.string.files_forward)) {
                    if (active == 0) {
                        leftForward.removeLastOrNull()?.let { leftBack.add(leftPath); leftPath = it }
                    } else {
                        rightForward.removeLastOrNull()?.let { rightBack.add(rightPath); rightPath = it }
                    }
                }
                /*
                 * 「新建」= 文件夹或文件。
                 *
                 * 早先这颗钉子只会建文件夹，建一个空文件要绕到「更多」里 ——
                 * 而空文件（`.nomedia`、`config.yml`）是最常用的一种新建。
                 * 现在点它弹两个选项，图标仍用 FolderPlus（主体动作没变）。
                 */
                Box {
                    IconAction(AppIcons.FolderPlus, Res.get(R.string.files_new_folder)) {
                        newMenuOpen = true
                    }
                    AppMenu(
                        expanded = newMenuOpen,
                        onDismissRequest = { newMenuOpen = false },
                    ) {
                        AppMenuItem(
                            stringResource(R.string.files_new_folder),
                            AppIcons.FolderPlus,
                        ) {
                            newMenuOpen = false
                            creating = true
                        }
                        AppMenuItem(
                            stringResource(R.string.files_new_file),
                            AppIcons.File,
                        ) {
                            newMenuOpen = false
                            creatingFile = true
                        }
                    }
                }
                IconAction(AppIcons.Refresh, Res.get(R.string.files_refresh)) { refreshTick++ }
                IconAction(AppIcons.ArrowUpDir, Res.get(R.string.files_up)) {
                    val current = if (active == 0) leftPath else rightPath
                    val parent = current.parentFile
                    if (parent != null && parent.canRead()) {
                        if (active == 0) {
                            leftBack.add(leftPath); leftForward.clear(); leftPath = parent
                        } else {
                            rightBack.add(rightPath); rightForward.clear(); rightPath = parent
                        }
                    } else {
                        toast = Res.get(R.string.files_no_parent)
                    }
                }
            }
        }
    }

    // 文本预览盖在最上层：它自己带状态栏内边距，不需要再套一层
    /*
     * 网页预览。
     *
     * 预览根取**工作区根**（文件在更外层的目录时退回它的父目录），
     * 这样页面里的 `../assets/x.css` 也能解析 —— 只把父目录当根，
     * 稍微带点目录结构的项目就会一片 404。
     */
    previewing?.let { file ->
        val base = remember(file.absolutePath) {
            val ws = Workspace.rootOrNull()
            if (ws != null && file.absolutePath.startsWith(ws.absolutePath + File.separator)) {
                ws
            } else {
                file.parentFile ?: file
            }
        }
        val entry = remember(file.absolutePath, base) {
            runCatching { file.relativeTo(base).invariantSeparatorsPath }.getOrDefault(file.name)
        }
        WebPreviewScreen(
            source = remember(base) { LocalPreviewSource(base) },
            entry = entry,
            title = file.name,
            onClose = { previewing = null },
        )
    }

    viewing?.let { file -> TextPreviewScreen(file = file, onBack = { viewing = null }) }
    }

    // ── 动作面板 ────────────────────────────────────────────
    sheet?.let { file ->
        ActionSheet(
            file = file,
            target = otherPath(),
            onDismiss = { sheet = null },
            onAction = { action ->
                when (action) {
                    FileAction.COPY, FileAction.MOVE -> {
                        val destination = otherPath()
                        sheet = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                transfer(file, destination, move = action == FileAction.MOVE)
                            }
                            toast = result.getOrElse {
                                Res.get(R.string.files_failed, it.message ?: "")
                            }
                            refreshTick++
                        }
                    }

                    FileAction.RENAME -> {
                        sheet = null
                        renaming = file
                    }

                    FileAction.DELETE -> {
                        sheet = null
                        confirmingDelete = file
                    }

                    FileAction.PROPERTIES -> {
                        sheet = null
                        properties = file
                    }

                    FileAction.COMPRESS -> {
                        val destination = otherPath()
                        sheet = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { compress(file, destination) }
                            toast = result.getOrElse {
                                Res.get(R.string.files_failed, it.message ?: "")
                            }
                            refreshTick++
                        }
                    }

                    FileAction.SHARE -> {
                        sheet = null
                        share(context, file) { toast = it }
                    }

                    FileAction.OPEN_WITH -> {
                        sheet = null
                        openWith(context, file) { toast = it }
                    }

                    FileAction.BOOKMARK -> {
                        sheet = null
                        Bookmarks.add(context, file.absolutePath)
                        toast = Res.get(R.string.files_bookmarked)
                    }

                    FileAction.TOOLS -> {
                        sheet = null
                        toolsOpen = true
                    }

                    FileAction.VIEW_TEXT -> {
                        sheet = null
                        viewing = file
                    }
                }
            },
            onViewText = { sheet = null; viewing = file },
        )
    }

    renaming?.let { file ->
        TextPromptDialog(
            title = Res.get(R.string.files_rename),
            initial = file.name,
            confirm = Res.get(R.string.action_save),
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { file.renameTo(File(file.parentFile, name)) }.getOrDefault(false)
                    }
                    if (!ok) toast = Res.get(R.string.files_failed, file.name)
                    refreshTick++
                }
            },
        )
    }

    if (creating || creatingFile) {
        TextPromptDialog(
            title = Res.get(if (creating) R.string.files_new_folder else R.string.files_new_file),
            initial = "",
            confirm = Res.get(R.string.action_create),
            onDismiss = { creating = false; creatingFile = false },
            onConfirm = { name ->
                val dir = if (active == 0) leftPath else rightPath
                // 先记下是"文件夹"还是"文件"：下面两个状态马上要清掉
                val asFolder = creating
                creating = false
                creatingFile = false
                if (name.isNotBlank()) {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                val target = File(dir, name)
                                if (name.contains('/')) false
                                else if (target.exists()) false
                                else if (asFolder) target.mkdirs() else target.createNewFile()
                            }.getOrDefault(false)
                        }
                        if (!ok) toast = Res.get(R.string.files_failed, name)
                        refreshTick++
                    }
                }
            },
        )
    }

    confirmingDelete?.let { file ->
        ConfirmDialog(
            title = Res.get(R.string.files_delete_title),
            body = Res.get(R.string.files_delete_body, file.name),
            confirm = Res.get(R.string.action_delete),
            onDismiss = { confirmingDelete = null },
            onConfirm = {
                confirmingDelete = null
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { deleteRecursive(file) }.getOrDefault(false) }
                    if (!ok) toast = Res.get(R.string.files_failed, file.name)
                    refreshTick++
                }
            },
        )
    }

    /*
     * 批量删除。
     *
     * 与单条删除共用一套确认语义，但文案必须说清**几条** —— "删除所选"
     * 在几十个文件的情况下是个危险的模糊说法。
     */
    if (batchDelete.isNotEmpty()) {
        ConfirmDialog(
            title = Res.get(R.string.files_batch_delete_title),
            body = Res.get(R.string.files_batch_delete_body, batchDelete.size),
            confirm = Res.get(R.string.action_delete),
            onDismiss = { batchDelete = emptyList() },
            onConfirm = {
                val targets = batchDelete
                batchDelete = emptyList()
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        targets.count { runCatching { deleteRecursive(it) }.getOrDefault(false) }
                    }
                    toast = Res.get(R.string.files_batch_done, ok)
                    clearSelections()
                    refreshTick++
                }
            },
        )
    }

    properties?.let { file ->
        PropertiesDialog(file = file, onDismiss = { properties = null })
    }

    if (bookmarksOpen) {
        BookmarksDialog(
            onDismiss = { bookmarksOpen = false },
            onPick = { path ->
                bookmarksOpen = false
                val dir = File(path)
                if (!dir.exists()) {
                    toast = Res.get(R.string.files_missing, path)
                } else {
                    if (active == 0) {
                        leftBack.add(leftPath); leftForward.clear(); leftPath = dir
                    } else {
                        rightBack.add(rightPath); rightForward.clear(); rightPath = dir
                    }
                }
            },
        )
    }

    if (toolsOpen) {
        ToolsDialog(
            showHidden = showHidden,
            onToggleHidden = { showHidden = !showHidden; toolsOpen = false },
            onNewFolder = { toolsOpen = false; creating = true },
            onNewFile = { toolsOpen = false; creatingFile = true },
            onDismiss = { toolsOpen = false },
        )
    }

}

// ══════════════════════════════════════════════════════════
//  栏
// ══════════════════════════════════════════════════════════

private data class FileEntry(val file: File, val isDir: Boolean, val size: Long, val modified: Long)

/** 右滑多少距离算"一次选中"。太小会与滚动打架，太大要滑两次才触发。 */
private val SwipeSelectThreshold = 34.dp

/**
 * 跟手位移的上限。
 *
 * 给到 76dp：比阈值多出一截，越过阈值之后手指还有地方走 ——
 * 一旦顶到上限就不再移动，等于用"走不动了"再确认一次"够了"。
 */
private val SwipeSelectMax = 76.dp

/** 点一下：在集合里加 / 减一个。 */
private fun toggleSelection(current: Set<String>, path: String): Set<String> =
    if (path in current) current - path else current + path

/**
 * 右滑串联：把 [from]..[to] 之间（含两端）整段加进选择集。
 *
 * 这就是 MT 的机制：滑第一个、再滑第三个，中间那条也被选上。
 * 只加不减 —— 区间选择是"往外扩"，取消某条靠点它或反选。
 */
private fun rangeSelection(
    current: Set<String>,
    entries: List<FileEntry>,
    from: Int,
    to: Int,
): Set<String> {
    if (entries.isEmpty()) return current
    val lo = minOf(from, to).coerceIn(0, entries.lastIndex)
    val hi = maxOf(from, to).coerceIn(0, entries.lastIndex)
    val range = entries.subList(lo, hi + 1).map { it.file.absolutePath }
    return current + range
}

private fun listEntries(dir: File, showHidden: Boolean): List<FileEntry> {
    val children = runCatching { dir.listFiles() }.getOrNull().orEmpty()
    return children
        .asSequence()
        .filter { showHidden || !it.name.startsWith(".") }
        .map {
            FileEntry(
                file = it,
                isDir = it.isDirectory,
                size = if (it.isDirectory) 0L else runCatching { it.length() }.getOrDefault(0L),
                modified = runCatching { it.lastModified() }.getOrDefault(0L),
            )
        }
        // 目录在前，同类按名字排（忽略大小写）—— 与系统文件管理器一致
        .sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.file.name.lowercase() })
        .toList()
}

@Composable
private fun Pane(
    path: File,
    entries: List<FileEntry>,
    active: Boolean,
    showHidden: Boolean,
    /** 本栏已选中的条目（绝对路径）。非空 = 处于多选态。 */
    selection: Set<String>,
    modifier: Modifier = Modifier,
    onActivate: () -> Unit,
    onOpenDir: (File) -> Unit,
    onLongPress: (File) -> Unit,
    onTapFile: (File) -> Unit,
    /** 点击条目：多选态下切换勾选，否则按原语义打开。 */
    onToggleSelect: (FileEntry) -> Unit,
    /** 右滑选中：`(锚点下标, 当前下标)` —— 两个下标之间整段一起选（MT 的串联语义）。 */
    onSwipeSelect: (Int, Int) -> Unit,
) {
    val colors = LocalChatColors.current
    val listState = rememberLazyListState()

    /*
     * 右滑串联的锚点。
     *
     * 第一次右滑只是"选中这一条"，把它记成锚点；第二次右滑另一个条目时，
     * 锚点到它之间的整段一起进选择集 —— 这就是 MT 里"滑第一个、再滑第三个，
     * 中间那条也被选上"的那个机制。
     *
     * 选择集被清空时锚点必须一起清：否则下一次右滑会莫名其妙选中一大片。
     */
    var swipeAnchor by remember(path) { mutableStateOf(-1) }
    LaunchedEffect(selection.isEmpty()) {
        if (selection.isEmpty()) swipeAnchor = -1
    }

    // 切目录回到顶部：留在原来的滚动位置会让人以为没进去
    LaunchedEffect(path) { listState.scrollToItem(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .clickable(onClick = onActivate)
            .background(if (active) colors.card else colors.settingsBackground),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 30.dp)
                .background(if (active) colors.card else colors.settingsBackground)
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 2.dp, height = 12.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = path.absolutePath,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                color = if (active) MaterialTheme.colorScheme.onSurface else colors.placeholder,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            if (entries.isEmpty()) {
                Text(
                    text = Res.get(if (path.canRead()) R.string.files_empty else R.string.files_no_permission),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = colors.placeholder,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                )
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(entries, key = { _, it -> it.file.absolutePath }) { index, entry ->
                    FileRow(
                        entry = entry,
                        selected = entry.file.absolutePath in selection,
                        onClick = {
                            if (selection.isEmpty()) {
                                if (entry.isDir) onOpenDir(entry.file) else onTapFile(entry.file)
                            } else {
                                onToggleSelect(entry)
                            }
                        },
                        /*
                         * 多选态下长按 = 切换这一条的选中状态。
                         *
                         * 否则长按会弹出**单条**动作面板，里面那颗「删除」
                         * 只认这一条 —— 用户明明选了一屏，点完却只少了一个，
                         * 多选的意义被这个入口一次性抹掉。
                         */
                        onLongClick = {
                            if (selection.isEmpty()) onLongPress(entry.file) else onToggleSelect(entry)
                        },
                        /*
                         * 右滑选中 —— MT 管理器的做法。
                         *
                         * 为什么值得做：一屏几十个条目，长按再逐条点勾选要十几次操作，
                         * 而"右滑一下、再右滑一下"是两下。手指本来就在列表上滑来滑去，
                         * 这个手势不需要额外的目标瞄准。
                         *
                         * 也让多选有了"连续串联"的语义：先滑第 1 个，再滑第 3 个，
                         * 1–3 全被选中（中间那条跟着进集合，与 MT 一致）。
                         */
                        onSwipeRight = {
                            val anchor = if (swipeAnchor < 0 || selection.isEmpty()) index else swipeAnchor
                            onSwipeSelect(anchor, index)
                            swipeAnchor = index
                        },
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }

        if (entries.isNotEmpty()) {
            val dirs = entries.count { it.isDir }
            Text(
                text = Res.get(R.string.files_count, dirs, entries.size - dirs),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = colors.placeholder,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: FileEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    /** 向右滑过阈值、松手时触发一次。 */
    onSwipeRight: () -> Unit,
) {
    val colors = LocalChatColors.current
    val accent = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val thresholdPx = with(density) { SwipeSelectThreshold.toPx() }
    val maxPx = with(density) { SwipeSelectMax.toPx() }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    /*
     * 跟手位移。
     *
     * 这里要的是 MT 那种手感：**手指拖多少，行就走多少**，松手才决定选不选。
     * 早先一到阈值就直接触发选中、行本身纹丝不动 —— 手势是生效了，但没有任何
     * 反馈告诉用户"我这一下到底滑够没有"，滑浅了没选中会以为功能坏了。
     *
     * 用 `Animatable` 而不是 `animateFloatAsState`：拖动期间必须**立即**跟手
     * （不能有弹簧延迟），松手后又要一条回弹曲线。同一份状态里只有它能同时
     * 干净地做这两件事。
     */
    val drag = remember { Animatable(0f) }
    /** 当前是否已越过阈值（越过的瞬间震一下，给一条触觉确认）。 */
    var armed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent),
    ) {
        /*
         * 左侧露出来的勾选圈。
         *
         * 它压在行下面，行往右走多少就露出多少 —— 于是"滑到一半"这件事
         * 在视觉上是连续的，而不是某个瞬间突然变蓝。越过阈值时填成实心，
         * 等于告诉用户"现在松手就是选中"。
         */
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp)
                .graphicsLayer {
                    val progress = (drag.value / thresholdPx).coerceIn(0f, 1f)
                    alpha = progress
                    val s = 0.72f + 0.28f * progress
                    scaleX = s
                    scaleY = s
                }
                .size(20.dp)
                .clip(CircleShape)
                .background(if (armed) accent else accent.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            if (armed) {
                Icon(
                    imageVector = AppIcons.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                /*
                 * 选中底整行铺 —— 一眼扫下去能数出选了几条。
                 * 位移用 lambda 版 `offset`：只走布局阶段，不触发重组。
                 */
                .offset { IntOffset(drag.value.roundToInt(), 0) }
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                /*
                 * 右滑手势。
                 *
                 * 用 `detectHorizontalDragGestures` 而不是自己判 PointerEvent：
                 * 它内部先按 touchSlop 判方向，垂直滚动时不会误触 ——
                 * 这一页的主要操作就是上下滚，手势必须让路给列表。
                 */
                .pointerInput(entry.file.absolutePath) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            armed = false
                            scope.launch { drag.snapTo(0f) }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            // 只认"向右"：左滑留给别的用途（这里暂时没有）
                            if (dragAmount <= 0) return@detectHorizontalDragGestures
                            change.consume()
                            val next = (drag.value + dragAmount).coerceAtMost(maxPx)
                            scope.launch { drag.snapTo(next) }
                            val nowArmed = next >= thresholdPx
                            if (nowArmed != armed) {
                                armed = nowArmed
                                // 越过阈值的那一下：短震，确认"够数了"
                                if (nowArmed) {
                                    haptics.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                    )
                                }
                            }
                        },
                        onDragEnd = {
                            if (armed) onSwipeRight()
                            armed = false
                            scope.launch {
                                drag.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = 0.68f,
                                        stiffness = 900f,
                                    ),
                                )
                            }
                        },
                        onDragCancel = {
                            armed = false
                            scope.launch { drag.animateTo(0f, spring(stiffness = 900f)) }
                        },
                    )
                }
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        Icon(
            imageVector = when {
                entry.isDir -> AppIcons.Folder
                isText(entry.file) -> AppIcons.FileText
                isArchive(entry.file) -> AppIcons.Archive
                else -> AppIcons.File
            },
            contentDescription = null,
            tint = when {
                entry.isDir -> accent
                else -> colors.processText
            },
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.file.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                ),
                color = if (entry.file.name.startsWith(".")) colors.placeholder else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (entry.isDir) formatTime(entry.modified) else "${formatSize(entry.size)}  ${formatTime(entry.modified)}",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = colors.placeholder,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        }
    }
}

// ══════════════════════════════════════════════════════════
//  动作面板
// ══════════════════════════════════════════════════════════

private enum class FileAction { COPY, MOVE, DELETE, RENAME, TOOLS, COMPRESS, PROPERTIES, SHARE, OPEN_WITH, BOOKMARK, VIEW_TEXT }

@Composable
private fun ActionSheet(
    file: File,
    target: File,
    onDismiss: () -> Unit,
    onAction: (FileAction) -> Unit,
    onViewText: () -> Unit,
) {
    val colors = LocalChatColors.current
    val textFile = isText(file)

    DialogCard(onDismiss = onDismiss) {
        Text(
            text = file.name,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 2.dp),
        )
        Text(
            text = Res.get(R.string.files_copy_to_hint, target.name.ifBlank { target.absolutePath }),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = colors.placeholder,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )

        // 两列网格：十来个动作竖着排会顶出屏幕，两列刚好一屏
        val items = buildList {
            if (textFile && !file.isDirectory) add(Triple(R.string.files_view_text, AppIcons.FileText, FileAction.VIEW_TEXT))
            add(Triple(R.string.files_copy, AppIcons.Copy, FileAction.COPY))
            add(Triple(R.string.files_move, AppIcons.FolderMove, FileAction.MOVE))
            add(Triple(R.string.files_delete, AppIcons.Trash, FileAction.DELETE))
            add(Triple(R.string.files_rename, AppIcons.Edit, FileAction.RENAME))
            add(Triple(R.string.files_tools, AppIcons.Sliders, FileAction.TOOLS))
            add(Triple(R.string.files_compress, AppIcons.Archive, FileAction.COMPRESS))
            add(Triple(R.string.files_properties, AppIcons.Info, FileAction.PROPERTIES))
            add(Triple(R.string.files_share, AppIcons.Share, FileAction.SHARE))
            add(Triple(R.string.files_open_with, AppIcons.ExternalLink, FileAction.OPEN_WITH))
            add(Triple(R.string.files_bookmark, AppIcons.Bookmark, FileAction.BOOKMARK))
        }
        items.chunked(2).forEach { pair ->
            Row(modifier = Modifier.fillMaxWidth()) {
                pair.forEach { (label, icon, action) ->
                    ActionCell(
                        label = Res.get(label),
                        icon = icon,
                        danger = action == FileAction.DELETE,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (action == FileAction.VIEW_TEXT) onViewText() else onAction(action)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ActionCell(
    label: String,
    icon: ImageVector,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalChatColors.current
    val tint = if (danger) colors.danger else colors.processText
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(17.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            color = if (danger) colors.danger else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

// ══════════════════════════════════════════════════════════
//  文本查看（分页）
// ══════════════════════════════════════════════════════════

/** 单页行数。300 行在手机上大约三屏，再少就翻得烦，再多就失去"页"的意义。 */
private const val LinesPerPage = 300

/** 最多读这么多字节；超出的部分截断并明说。 */
private const val MaxPreviewBytes = 2 * 1024 * 1024

private data class Preview(
    val lines: List<String>,
    val truncated: Boolean,
    val bytes: Long,
)

@Composable
private fun TextPreviewScreen(file: File, onBack: () -> Unit) {
    val colors = LocalChatColors.current
    var content by remember(file) { mutableStateOf<Preview?>(null) }
    var page by remember(file) { mutableStateOf(0) }
    var error by remember(file) { mutableStateOf<String?>(null) }

    // 预览是一层覆盖，返回键应该退回目录而不是直接退出管理器
    androidx.activity.compose.BackHandler { onBack() }

    LaunchedEffect(file) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { readPreview(file) }
        }
        loaded.onSuccess { content = it }.onFailure { error = it.message ?: "读取失败" }
    }

    val preview = content
    val pages = preview?.lines?.let { ((it.size + LinesPerPage - 1) / LinesPerPage).coerceAtLeast(1) } ?: 1

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.settingsBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(start = 6.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.ArrowLeft,
                    contentDescription = Res.get(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${formatSize(file.length())}  ·  ${pages} ${Res.get(R.string.files_pages)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = colors.placeholder,
                )
            }
            if (preview != null) {
                Text(
                    text = "${page + 1}/$pages",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                    color = colors.processText,
                )
            }
        }

        when {
            error != null -> Text(
                text = error!!,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = colors.danger,
                modifier = Modifier.padding(16.dp),
            )

            preview == null -> Text(
                text = Res.get(R.string.files_loading),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = colors.placeholder,
                modifier = Modifier.padding(16.dp),
            )

            else -> {
                if (preview.truncated) {
                    Text(
                        text = Res.get(R.string.files_truncated, formatSize(MaxPreviewBytes.toLong())),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = colors.warning,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(colors.card)
                        .padding(horizontal = 12.dp),
                ) {
                    val slice = preview.lines.drop(page * LinesPerPage).take(LinesPerPage)
                    items(slice.size) { index ->
                        Text(
                            text = slice[index].ifEmpty { " " },
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (pages > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .background(colors.card),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        PageButton(Res.get(R.string.files_prev_page), enabled = page > 0) { page-- }
                        PageButton(Res.get(R.string.files_next_page), enabled = page < pages - 1) { page++ }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalChatColors.current
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
        color = if (enabled) colors.processText else colors.placeholder.copy(alpha = 0.5f),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

private fun readPreview(file: File): Preview {
    val total = file.length()
    val bytes = FileInputStream(file).use { input ->
        val buffer = ByteArray(minOf(total, MaxPreviewBytes.toLong()).toInt())
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n <= 0) break
            read += n
        }
        buffer.copyOf(read)
    }
    var text = decodeText(bytes)
    /*
     * 截断必须落在**行边界**上。切在半个汉字或半个 UTF-8 序列中间，
     * 末尾会出现一串替换字符，看起来像文件损坏。
     */
    val cut = text.length
    if (total > MaxPreviewBytes) {
        val lastBreak = text.lastIndexOf('\n')
        if (lastBreak > cut * 3 / 4) text = text.substring(0, lastBreak)
    }
    return Preview(
        lines = text.split('\n'),
        truncated = total > MaxPreviewBytes,
        bytes = total,
    )
}

private fun decodeText(bytes: ByteArray): String {
    val utf8 = String(bytes, Charsets.UTF_8)
    val bad = utf8.count { it == '\uFFFD' }
    // 替换字符超过 1% 基本可以断定不是 UTF-8；国内老文本多半是 GBK
    if (bad * 100 > utf8.length && utf8.isNotEmpty()) {
        return runCatching { String(bytes, charset("GBK")) }.getOrDefault(utf8)
    }
    return utf8
}

// ══════════════════════════════════════════════════════════
//  对话框
// ══════════════════════════════════════════════════════════

/** 自研对话框外壳：白卡片 + 圆角 + 无 Material 主题色。 */
@Composable
private fun DialogCard(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = LocalChatColors.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .background(colors.menuBackground, RoundedCornerShape(14.dp))
                .padding(12.dp),
        ) { content() }
    }
}

@Composable
private fun TextPromptDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colors = LocalChatColors.current
    var value by remember { mutableStateOf(initial) }
    DialogCard(onDismiss = onDismiss) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.fieldBackground)
                .padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(
                    MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            DialogText(Res.get(R.string.action_cancel), colors.processText, onDismiss)
            Spacer(Modifier.width(6.dp))
            DialogText(
                text = confirm,
                color = if (value.isNotBlank()) MaterialTheme.colorScheme.primary else colors.placeholder,
                onClick = { if (value.isNotBlank()) onConfirm(value.trim()) },
            )
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalChatColors.current
    DialogCard(onDismiss = onDismiss) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Text(
            text = body,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
            color = colors.placeholder,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            DialogText(Res.get(R.string.action_cancel), colors.processText, onDismiss)
            Spacer(Modifier.width(6.dp))
            DialogText(confirm, colors.danger, onConfirm)
        }
    }
}

@Composable
private fun DialogText(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun PropertiesDialog(file: File, onDismiss: () -> Unit) {
    val colors = LocalChatColors.current
    val rows = remember(file) {
        buildList {
            add(Res.get(R.string.files_prop_name) to file.name)
            add(Res.get(R.string.files_prop_path) to file.absolutePath)
            add(
                Res.get(R.string.files_prop_type) to
                    if (file.isDirectory) Res.get(R.string.files_prop_dir) else Res.get(R.string.files_prop_file),
            )
            add(Res.get(R.string.files_prop_size) to if (file.isDirectory) "—" else formatSize(file.length()))
            add(Res.get(R.string.files_prop_modified) to formatTime(file.lastModified()))
            add(
                Res.get(R.string.files_prop_permission) to
                    listOf(
                        if (file.canRead()) "r" else "-",
                        if (file.canWrite()) "w" else "-",
                        if (file.canExecute()) "x" else "-",
                    ).joinToString(""),
            )
            if (file.isDirectory) {
                val count = runCatching { file.listFiles()?.size }.getOrNull() ?: 0
                add(Res.get(R.string.files_prop_children) to count.toString())
            }
        }
    }
    DialogCard(onDismiss = onDismiss) {
        Text(
            text = Res.get(R.string.files_properties),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Column(
            modifier = Modifier
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            rows.forEach { (label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = colors.placeholder,
                        modifier = Modifier.width(64.dp),
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            DialogText(Res.get(R.string.action_close), colors.processText, onDismiss)
        }
    }
}

@Composable
private fun ToolsDialog(
    showHidden: Boolean,
    onToggleHidden: () -> Unit,
    onNewFolder: () -> Unit,
    onNewFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalChatColors.current
    DialogCard(onDismiss = onDismiss) {
        Text(
            text = Res.get(R.string.files_tools),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        ToolRow(AppIcons.FolderPlus, Res.get(R.string.files_new_folder), onClick = onNewFolder)
        ToolRow(AppIcons.File, Res.get(R.string.files_new_file), onClick = onNewFile)
        ToolRow(
            icon = if (showHidden) AppIcons.EyeOff else AppIcons.FolderOpen,
            label = Res.get(if (showHidden) R.string.files_hide_hidden else R.string.files_show_hidden),
            onClick = onToggleHidden,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            DialogText(Res.get(R.string.action_cancel), colors.processText, onDismiss)
        }
    }
}

@Composable
private fun ToolRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = LocalChatColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = colors.processText, modifier = Modifier.size(17.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun BookmarksDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val colors = LocalChatColors.current
    var list by remember { mutableStateOf(Bookmarks.list(context)) }
    DialogCard(onDismiss = onDismiss) {
        Text(
            text = Res.get(R.string.files_bookmarks),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        if (list.isEmpty()) {
            Text(
                text = Res.get(R.string.files_bookmarks_empty),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = colors.placeholder,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        } else {
            Column(modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                list.forEach { path ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPick(path) }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Bookmark, null, tint = colors.processText, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = path,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .clickable {
                                    Bookmarks.remove(context, path)
                                    list = Bookmarks.list(context)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(AppIcons.Close, null, tint = colors.placeholder, modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            DialogText(Res.get(R.string.action_close), colors.processText, onDismiss)
        }
    }
}

// ══════════════════════════════════════════════════════════
//  动作实现
// ══════════════════════════════════════════════════════════

private fun defaultRightPane(start: File): File {
    val storage = Environment.getExternalStorageDirectory()
    return when {
        storage != null && storage.isDirectory && storage.canRead() && storage != start -> storage
        start.parentFile?.canRead() == true -> start.parentFile!!
        else -> start
    }
}

private fun openFile(context: Context, file: File, onText: (File) -> Unit) {
    if (isText(file) && file.length() <= MaxPreviewBytes) onText(file) else openWith(context, file) { }
}

private fun openWith(context: Context, file: File, onError: (String) -> Unit) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uriOf(context, file), mimeOf(file))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { onError(Res.get(R.string.files_no_app)) }
}

private fun share(context: Context, file: File, onError: (String) -> Unit) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeOf(file)
        putExtra(Intent.EXTRA_STREAM, uriOf(context, file))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
        .onFailure { onError(Res.get(R.string.files_no_app)) }
}

private fun uriOf(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

private fun transfer(source: File, destinationDir: File, move: Boolean): Result<String> = runCatching {
    require(destinationDir.isDirectory) { "目标不是目录" }
    val target = uniqueTarget(destinationDir, source.name)
    if (source.isDirectory) {
        require(!target.absolutePath.startsWith(source.absolutePath)) { "不能复制到自己的子目录" }
        copyDir(source, target)
        if (move) deleteRecursive(source)
    } else {
        source.copyTo(target, overwrite = false)
        if (move) source.delete()
    }
    Res.get(
        if (move) R.string.files_moved else R.string.files_copied,
        source.name,
        destinationDir.name.ifBlank { destinationDir.absolutePath },
    )
}

private fun uniqueTarget(dir: File, name: String): File {
    var target = File(dir, name)
    if (!target.exists()) return target
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val suffix = if (dot > 0) name.substring(dot) else ""
    var index = 1
    while (target.exists()) {
        target = File(dir, "$base ($index)$suffix")
        index++
    }
    return target
}

private fun copyDir(source: File, target: File): Unit {
    target.mkdirs()
    source.listFiles()?.forEach { child ->
        val childTarget = File(target, child.name)
        if (child.isDirectory) copyDir(child, childTarget) else child.copyTo(childTarget, overwrite = false)
    }
}

private fun deleteRecursive(file: File): Boolean {
    if (file.isDirectory) {
        file.listFiles()?.forEach { deleteRecursive(it) }
    }
    return file.delete()
}

/**
 * 压缩成 zip，落在另一栏里。
 *
 * 文件名取 `原名.zip`；已存在就加序号。压缩**不做进度条** —— 手机上的
 * 目录很少大到需要进度，真大了（几百 MB）也是几十秒的事，用一句 toast
 * 报告结果比一个闪一下就跑完的进度条诚实。
 */
private fun compress(source: File, destinationDir: File): Result<String> = runCatching {
    require(destinationDir.isDirectory) { "目标不是目录" }
    val name = if (source.isDirectory) source.name else source.nameWithoutExtension
    val target = uniqueTarget(destinationDir, "$name.zip")
    ZipOutputStream(FileOutputStream(target)).use { zip ->
        val base = source.parentFile ?: source
        val files = if (source.isDirectory) {
            source.walkTopDown().filter { it.isFile }.toList()
        } else {
            listOf(source)
        }
        files.forEach { file ->
            val entryName = file.relativeTo(base).invariantSeparatorsPath
            zip.putNextEntry(ZipEntry(entryName))
            FileInputStream(file).use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
    Res.get(R.string.files_compressed, target.name)
}

// ══════════════════════════════════════════════════════════
//  小工具
// ══════════════════════════════════════════════════════════

@Composable
private fun IconAction(icon: ImageVector, description: String, onClick: () -> Unit) {
    val colors = LocalChatColors.current
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = colors.processText,
            modifier = Modifier.size(19.dp),
        )
    }
}

private val textExtensions = setOf(
    "txt", "md", "markdown", "json", "xml", "yml", "yaml", "toml", "ini", "conf", "cfg",
    "kt", "java", "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp", "cs", "go", "rs",
    "sh", "bash", "zsh", "sql", "html", "htm", "css", "scss", "gradle", "properties", "log",
    "csv", "tsv", "svg", "env", "gitignore", "pro", "mk", "cmake",
)

private fun isText(file: File): Boolean {
    if (file.isDirectory) return false
    val name = file.name.lowercase()
    val ext = name.substringAfterLast('.', "")
    if (ext in textExtensions) return true
    if (name.startsWith(".") && name.indexOf('.', 1) < 0) return true
    // 没后缀：抽查前 4KB 有没有 NUL 字节，没有就当文本
    if (ext.isEmpty()) {
        return runCatching {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(4096)
                val read = input.read(buffer)
                read > 0 && !buffer.copyOf(read).any { it == 0.toByte() }
            }
        }.getOrDefault(false)
    }
    return false
}

private fun isArchive(file: File): Boolean =
    file.name.lowercase().let { name ->
        listOf(".zip", ".gz", ".tar", ".rar", ".7z", ".xz", ".bz2").any { name.endsWith(it) }
    }

private fun mimeOf(file: File): String = when (file.name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "pdf" -> "application/pdf"
    "apk" -> "application/vnd.android.package-archive"
    "zip" -> "application/zip"
    "json" -> "application/json"
    "txt", "md", "log" -> "text/plain"
    "mp4" -> "video/mp4"
    "mp3" -> "audio/mpeg"
    else -> "application/octet-stream"
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.size - 1) {
        value /= 1024
        index++
    }
    return String.format(Locale.US, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[index])
}

private fun formatTime(time: Long): String {
    if (time <= 0) return "—"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time))
}

/** 书签：路径列表，落在 SharedPreferences 里（不需要 DataStore，量太小）。 */
private object Bookmarks {
    private const val Name = "file_bookmarks"
    private const val Key = "paths"

    private fun prefs(context: Context) = context.getSharedPreferences(Name, Context.MODE_PRIVATE)

    fun list(context: Context): List<String> =
        prefs(context).getStringSet(Key, emptySet()).orEmpty().sorted()

    fun add(context: Context, path: String) {
        val next = (list(context) + path).distinct()
        prefs(context).edit().putStringSet(Key, next.toSet()).apply()
    }

    fun remove(context: Context, path: String) {
        val next = list(context) - path
        prefs(context).edit().putStringSet(Key, next.toSet()).apply()
    }
}
