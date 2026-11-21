package com.simplechat.app.agent

import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 一条文件快照记录。
 *
 * [seq] 是**全局递增**的：回滚靠它划边界（"某条消息之前的最后一条"），
 * 而不是靠时间戳 —— 同一毫秒里连写两个文件时，时间戳分不出先后。
 *
 * [blob] 是备份文件名，落在 App 私有目录里，与工作区无关：
 * 用户的工作区可能是 `Download/`，快照不能往那儿堆（没权限，也脏）。
 */
@Serializable
internal data class RewindEntry(
    val seq: Long,
    /** 产生这次改动的 AI 消息 id。回滚的边界就是它。 */
    val messageId: String,
    val toolCallId: String? = null,
    /** `local` 或 `cloud:<envId>`。云端与本地同名路径互不干扰。 */
    val scope: String,
    /** 本地是绝对路径；云端是相对工作区根的路径。 */
    val path: String,
    /** 改动之前这个文件存不存在。false = 这次是新建，回滚要删掉它。 */
    val existed: Boolean,
    /** 备份文件名；null 表示没有备份（文件过大 / 读失败 / 当时不存在）。 */
    val blob: String? = null,
    val size: Long = 0,
    val at: Long = 0,
)

/**
 * 回滚预览里的一行：一个文件 + 它会变成什么样。
 *
 * [known] 为 false 表示"算不出行数"（云端路径、备份缺失、超大文件）——
 * 界面据此只显示路径与动作，不要显示 `+0 −0` 冒充统计。
 */
data class FileChange(
    val path: String,
    /** true = 这个文件是那条消息新建的，回滚会把它删掉。 */
    val delete: Boolean,
    val added: Int,
    val removed: Int,
    val known: Boolean = true,
)

/** 回滚结果。给界面与工具回执共用。 */
data class RewindReport(
    val restored: List<String> = emptyList(),
    val deleted: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val dryRun: Boolean = false,
) {
    val changed: Int get() = restored.size + deleted.size

    /** 一句能直接显示给人看的话。 */
    fun summary(): String = when {
        dryRun && changed == 0 && missing.isEmpty() -> "没有需要回滚的文件。"
        dryRun -> buildString {
            append("将回滚 ${changed} 个文件：")
            if (restored.isNotEmpty()) append("\n· 恢复：").append(restored.joinToString("、"))
            if (deleted.isNotEmpty()) append("\n· 删除：").append(deleted.joinToString("、"))
            if (missing.isNotEmpty()) append("\n· 缺少备份，无法还原：").append(missing.joinToString("、"))
        }
        changed == 0 && failed.isEmpty() && missing.isEmpty() -> "没有需要回滚的文件。"
        else -> buildString {
            append("已回滚 ${changed} 个文件")
            // 列表太长时只报前几个：这是给顶部提示条用的，不是日志
            if (restored.isNotEmpty()) append("\n· 已恢复：").append(preview(restored))
            if (deleted.isNotEmpty()) append("\n· 已删除：").append(preview(deleted))
            if (missing.isNotEmpty()) append("\n· 备份缺失，未还原：").append(missing.joinToString("、"))
            if (failed.isNotEmpty()) append("\n· 失败：").append(failed.joinToString("、"))
        }
    }

    private fun preview(paths: List<String>): String =
        if (paths.size <= 4) {
            paths.joinToString("、")
        } else {
            paths.take(4).joinToString("、") + " 等 ${paths.size} 个"
        }

    companion object {
        fun of(vararg paths: String) = RewindReport(restored = paths.toList())
    }
}

/**
 * 文件回滚（rewind）。
 *
 * 一句话：**每次 AI 写文件之前，先把原内容存一份；回滚时按消息边界把文件放回去。**
 *
 * ### 为什么不是 git
 *
 * 工作区可以是任意目录（`Download/`、Ubuntu rootfs、云端机器），不一定是仓库；
 * 用户也没同意让我们在他目录里 `git init`。自带一套快照，不改工作区一根毫毛。
 *
 * ### 覆盖面
 *
 * 只覆盖**结构化写工具**（`write_file` / `edit_file` / `patch_file`）。
 * `run_shell` 里 `echo > f` 这种改写抓不到 —— 内容在执行那一刻就被覆盖了，
 * 没有内核级的写时复制就没法事后补救。这是与 Operit 同一档的取舍：
 * 正常让 AI 改文件它会走写工具，shell 主要用来跑构建与命令。
 *
 * ### 回滚本身也能回滚
 *
 * 回滚前会把**当前**内容再拍一张快照，记在 `rewind` 这个虚拟消息名下。
 * 于是"手滑回滚了"还能再回滚一次回到回滚前 —— 破坏性操作不该是单向门。
 *
 * ### 存储
 *
 * ```
 * files/rewind/<conversationId>/
 *   ├── journal.json     ← 快照索引
 *   └── blobs/<id>.bin   ← 文件原内容
 * ```
 * 单条会话最多保留 [MaxEntries] 条，超出丢最老的（连同备份）。
 */
object Rewind {

    /** 单个文件最多备份 8 MB。再大的文件备份一次就是几秒 IO，且很少是"改代码"。 */
    private const val MaxBackupBytes = 8L * 1024 * 1024

    /** 每条会话保留的快照条数。800 条足够覆盖几十轮密集改动。 */
    private const val MaxEntries = 800

    /** 回滚动作本身的虚拟消息 id：它不属于任何一条真实消息。 */
    const val ACTOR = "rewind"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val serializer = ListSerializer(RewindEntry.serializer())

    private val lock = Any()
    private val cache = HashMap<String, MutableList<RewindEntry>>()

    /**
     * 单测用的根目录。
     *
     * 生产路径永远是 `Workspace.appDir()`；但快照逻辑（取最早的一条、恢复顺序、
     * 回滚可回滚）值得单独测 —— 这是唯一会**改用户文件**的代码，
     * 靠手点是测不干净的。只在本进程内可见，不影响 App。
     */
    internal var testRoot: File? = null

    // ── 路径 ────────────────────────────────────────────────

    private fun safeName(raw: String): String =
        raw.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64).ifBlank { "default" }

    private fun dirFor(conversationId: String): File? = runCatching {
        val root = testRoot ?: File(Workspace.appDir(), "rewind")
        File(root, safeName(conversationId))
    }.getOrNull()

    private fun blobDir(conversationId: String): File? =
        dirFor(conversationId)?.let { File(it, "blobs").apply { mkdirs() } }

    private fun journalFile(conversationId: String): File? = dirFor(conversationId)?.let {
        it.mkdirs()
        File(it, "journal.json")
    }

    /** 展示用的路径：本地相对工作区，云端原样。 */
    fun display(scope: String, path: String): String = if (scope.startsWith("cloud:")) {
        path
    } else {
        runCatching { Workspace.relative(File(path)) }.getOrDefault(path)
    }

    // ── 读写索引 ────────────────────────────────────────────

    private fun load(conversationId: String): MutableList<RewindEntry> = synchronized(lock) {
        cache[conversationId]?.let { return it }
        val file = journalFile(conversationId)
        val list = runCatching {
            if (file != null && file.isFile) {
                json.decodeFromString(serializer, file.readText()).toMutableList()
            } else {
                mutableListOf()
            }
        }.getOrDefault(mutableListOf())
        cache[conversationId] = list
        list
    }

    private fun persist(conversationId: String) {
        val list = cache[conversationId] ?: return
        val file = journalFile(conversationId) ?: return
        runCatching { file.writeText(json.encodeToString(serializer, list)) }
    }

    private fun nextSeq(list: List<RewindEntry>): Long =
        (list.maxOfOrNull { it.seq } ?: 0L) + 1L

    private fun trim(conversationId: String, list: MutableList<RewindEntry>) {
        while (list.size > MaxEntries) {
            val oldest = list.minByOrNull { it.seq } ?: break
            list.remove(oldest)
            oldest.blob?.let { name -> blobDir(conversationId)?.let { File(it, name).delete() } }
        }
    }

    // ── 快照 ────────────────────────────────────────────────

    /**
     * 拍一张快照，但**先不入账**。
     *
     * 调用方在工具执行成功后才 [commit]，失败就 [discard] ——
     * 否则一次"路径写错、根本没落盘"的调用也会在历史里留下一条假记录。
     *
     * 云端与本地的区别只在"怎么读原内容"，条目结构完全一样。
     */
    internal suspend fun capture(
        conversationId: String,
        messageId: String,
        toolCallId: String?,
        scope: String,
        path: String,
        remote: RemoteWorkspace? = null,
    ): RewindEntry? = withContext(Dispatchers.IO) {
        val blobs = blobDir(conversationId) ?: return@withContext null
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        val cloud = remote != null && scope.startsWith("cloud:")

        if (cloud) {
            val exists = runCatching { remote!!.exists(path) }.getOrDefault(false)
            if (!exists) {
                return@withContext RewindEntry(
                    seq = 0,
                    messageId = messageId,
                    toolCallId = toolCallId,
                    scope = scope,
                    path = path,
                    existed = false,
                    at = System.currentTimeMillis(),
                )
            }
            val bytes = runCatching { remote!!.download(path) }.getOrNull()
                ?: return@withContext RewindEntry(
                    seq = 0,
                    messageId = messageId,
                    toolCallId = toolCallId,
                    scope = scope,
                    path = path,
                    existed = true,
                    at = System.currentTimeMillis(),
                )
            if (bytes.size > MaxBackupBytes) {
                return@withContext RewindEntry(
                    seq = 0,
                    messageId = messageId,
                    toolCallId = toolCallId,
                    scope = scope,
                    path = path,
                    existed = true,
                    size = bytes.size.toLong(),
                    at = System.currentTimeMillis(),
                )
            }
            val name = "$id.bin"
            runCatching { File(blobs, name).writeBytes(bytes) }.getOrElse {
                return@withContext null
            }
            return@withContext RewindEntry(
                seq = 0,
                messageId = messageId,
                toolCallId = toolCallId,
                scope = scope,
                path = path,
                existed = true,
                blob = name,
                size = bytes.size.toLong(),
                at = System.currentTimeMillis(),
            )
        }

        val file = File(path)
        if (!file.isFile) {
            return@withContext RewindEntry(
                seq = 0,
                messageId = messageId,
                toolCallId = toolCallId,
                scope = scope,
                path = path,
                existed = false,
                at = System.currentTimeMillis(),
            )
        }
        if (file.length() > MaxBackupBytes) {
            return@withContext RewindEntry(
                seq = 0,
                messageId = messageId,
                toolCallId = toolCallId,
                scope = scope,
                path = path,
                existed = true,
                size = file.length(),
                at = System.currentTimeMillis(),
            )
        }
        val name = "$id.bin"
        runCatching { file.copyTo(File(blobs, name), overwrite = true) }.getOrElse {
            return@withContext null
        }
        RewindEntry(
            seq = 0,
            messageId = messageId,
            toolCallId = toolCallId,
            scope = scope,
            path = path,
            existed = true,
            blob = name,
            size = file.length(),
            at = System.currentTimeMillis(),
        )
    }

    /** 入账。分配 seq 并落盘。 */
    internal fun commit(conversationId: String, pending: RewindEntry?) {
        if (pending == null) return
        synchronized(lock) {
            val list = load(conversationId)
            list += pending.copy(seq = nextSeq(list))
            trim(conversationId, list)
            persist(conversationId)
        }
    }

    /** 丢弃一张没入账的快照（工具执行失败）。 */
    internal fun discard(conversationId: String, pending: RewindEntry?) {
        val blob = pending?.blob ?: return
        runCatching { blobDir(conversationId)?.let { File(it, blob).delete() } }
    }

    // ── 查询 ────────────────────────────────────────────────

    /** 这些消息（含）之后有没有文件改动。界面用它决定"回滚到此处"该不该亮。 */
    fun changedCount(conversationId: String, messageIds: Set<String>, scope: String): Int =
        load(conversationId).count { it.messageId in messageIds && it.scope == scope }

    /**
     * 回滚到 [messageId] 会动哪些文件 —— 给"确定回滚"那个窗口用。
     *
     * 只算**不执行**：用户要先看见"哪几个文件、加几行、减几行"再点头。
     * 上一版这里只有一句"将回滚这条消息之后的文件改动"，等于让人闭眼按确认。
     *
     * 统计口径是人话而不是 git 的：`added` 是回滚后**多出来**的行，
     * `removed` 是回滚后**消失**的行 —— 也就是 `compute(现在, 回滚后)`。
     */
    fun changes(conversationId: String, messageId: String, scope: String): List<FileChange> {
        val all = load(conversationId).filter { it.scope == scope }
        val boundary = all.filter { it.messageId == messageId }.minOfOrNull { it.seq } ?: return emptyList()
        val affected = all.filter { it.seq >= boundary }.sortedBy { it.seq }

        // 同一个文件被改过多次时，**最早**那条决定回滚后的样子
        val firstByPath = LinkedHashMap<String, RewindEntry>()
        affected.forEach { firstByPath.getOrPut(it.path) { it } }

        val blobs = blobDir(conversationId)
        return firstByPath.map { (path, entry) ->
            val remote = scope.startsWith("cloud:")
            if (remote) {
                // 云端读不到内容就没法算行数：只报"恢复 / 删除"，不编数字
                return@map FileChange(
                    path = display(scope, path),
                    delete = !entry.existed,
                    added = 0,
                    removed = 0,
                    known = false,
                )
            }
            val current = runCatching { File(path).readText() }.getOrNull()
            val before = entry.blob
                ?.let { name -> blobs?.let { File(it, name) } }
                ?.takeIf { it.isFile }
                ?.let { file -> runCatching { file.readText() }.getOrNull() }
            val stat = if (current != null && current.length < 400_000) {
                Diffs.summary(Diffs.compute(current, before.orEmpty()))
            } else {
                null
            }
            FileChange(
                path = display(scope, path),
                delete = !entry.existed,
                added = stat?.first ?: 0,
                removed = stat?.second ?: 0,
                known = current != null && (before != null || !entry.existed),
            )
        }
    }

    /**
     * 从某条消息起（含）往后所有改动所属的消息 id。
     *
     * 工具只知道"把 A 文件恢复到消息 abc 之前" —— 它不需要知道对话树，
     * 只要拿到 `seq >= abc 第一条记录` 的消息集合即可。
     */
    fun messageIdsSince(conversationId: String, messageId: String, scope: String): Set<String> {
        val all = load(conversationId).filter { it.scope == scope }
        val boundary = all.filter { it.messageId == messageId }.minOfOrNull { it.seq } ?: return emptySet()
        return all.filter { it.seq >= boundary }.map { it.messageId }.toSet()
    }

    /**
     * 最近 [turns] 条**产生过改动**的消息（不含 [exclude]，也就是当前这条）。
     *
     * 用"产生过改动的消息"而不是"用户说了几句话"：一句"把 tabs 换成空格"
     * 之后 AI 连改十个文件，用户心里的"刚才那一轮"是这一整串动作，
     * 中间那些纯聊天的消息不该占掉一个 turn。
     */
    fun recentMessageIds(
        conversationId: String,
        scope: String,
        turns: Int,
        exclude: String? = null,
    ): Set<String> {
        val ids = LinkedHashSet<String>()
        load(conversationId)
            .filter { it.scope == scope }
            .sortedByDescending { it.seq }
            .forEach { if (it.messageId != exclude) ids += it.messageId }
        return ids.take(turns.coerceIn(1, 20)).toSet()
    }

    // ── 回滚 ────────────────────────────────────────────────

    /**
     * 把 [messageIds] 这些消息造成的文件改动全部撤销。
     *
     * @param pathFilter 非空时只回滚这一个文件（本地绝对路径 / 云端相对路径）。
     * @param actorMessageId 这次回滚动作记在谁名下，用 [ACTOR] 即可。
     * @param dryRun 只算不动。
     */
    suspend fun rewind(
        conversationId: String,
        messageIds: Set<String>,
        actorMessageId: String = ACTOR,
        scope: String = "local",
        pathFilter: String? = null,
        remote: RemoteWorkspace? = null,
        dryRun: Boolean = false,
    ): RewindReport = withContext(Dispatchers.IO) {
        if (messageIds.isEmpty()) return@withContext RewindReport(dryRun = dryRun)

        val all = load(conversationId)
        val selected = all.filter {
            it.messageId in messageIds &&
                it.scope == scope &&
                (pathFilter == null || it.path == pathFilter)
        }
        if (selected.isEmpty()) return@withContext RewindReport(dryRun = dryRun)

        val restored = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val consumed = mutableListOf<Long>()

        selected.groupBy { it.path }.forEach { (path, list) ->
            val first = list.minByOrNull { it.seq } ?: return@forEach
            val label = display(scope, path)

            if (dryRun) {
                if (!first.existed) deleted += label else if (first.blob == null) missing += label else restored += label
                return@forEach
            }

            /*
             * 先给**当前内容**拍一张，记在 rewind 名下。
             *
             * 这是"回滚可回滚"的唯一实现方式：把回滚当成一次新的修改，
             * 再回滚一次就能回到回滚之前。不要省这一步 —— 省了就是单向门。
             */
            val before = capture(conversationId, actorMessageId, null, scope, path, remote)

            val ok = if (!first.existed) {
                // 目标是"当时不存在"：把现在这个删掉
                deleteTarget(path, scope, remote)
            } else {
                val bytes = first.blob?.let { name ->
                    runCatching { blobDir(conversationId)?.let { File(it, name).readBytes() } }.getOrNull()
                }
                if (bytes == null) {
                    missing += label
                    false
                } else {
                    writeTarget(path, scope, remote, bytes)
                }
            }

            if (ok) {
                commit(conversationId, before)
                consumed += list.map { it.seq }
                if (first.existed) restored += label else deleted += label
            } else {
                discard(conversationId, before)
                if (first.existed && first.blob != null) failed += label
            }
        }

        if (consumed.isNotEmpty()) removeEntries(conversationId, consumed.toSet())

        RewindReport(
            restored = restored,
            deleted = deleted,
            failed = failed.distinct(),
            missing = missing.distinct(),
            dryRun = false,
        )
    }

    private fun removeEntries(conversationId: String, seqs: Set<Long>) {
        synchronized(lock) {
            val list = load(conversationId)
            val removed = list.filter { it.seq in seqs }
            list.removeAll { it.seq in seqs }
            persist(conversationId)
            removed.forEach { entry ->
                entry.blob?.let { name -> blobDir(conversationId)?.let { File(it, name).delete() } }
            }
        }
    }

    private suspend fun deleteTarget(path: String, scope: String, remote: RemoteWorkspace?): Boolean {
        return if (remote != null && scope.startsWith("cloud:")) {
            val exists = runCatching { remote.exists(path) }.getOrDefault(false)
            !exists || runCatching { remote.remove(path) }.isSuccess
        } else {
            val file = File(path)
            !file.exists() || file.delete()
        }
    }

    private suspend fun writeTarget(path: String, scope: String, remote: RemoteWorkspace?, bytes: ByteArray): Boolean {
        return if (remote != null && scope.startsWith("cloud:")) {
            val name = path.substringAfterLast('/')
            val dir = path.substringBeforeLast('/', ".").ifBlank { "." }
            runCatching { remote.upload(dir, name, bytes) }.isSuccess
        } else {
            runCatching {
                val file = File(path)
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
            }.isSuccess
        }
    }

    /** 忘了这段会话的所有快照（删会话时调用）。 */
    fun forget(conversationId: String) {
        synchronized(lock) {
            cache.remove(conversationId)
        }
        runCatching { dirFor(conversationId)?.deleteRecursively() }
    }
}
