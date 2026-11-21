package com.simplechat.app.agent

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 工作区来源。绑定的是什么，决定界面上那句提示怎么写。 */
enum class WorkspaceKind {
    /**
     * 还没选过工作区 —— 就是**普通对话**。
     *
     * 工具仍然落在内置目录下（否则一进来就没有可写的路径，什么也干不了），
     * 但这个状态**不出现在界面上**：没有标签、没有筛选组以外的身份感。
     * 把"没选"当成一种选择去展示，等于对用户说"你有个工作区叫内置工作区"，
     * 而他并没有做过这个决定。
     */
    NONE,

    /** App 私有目录（`files/workspace`）。无需任何权限，永远可用。 */
    INTERNAL,

    /** 本机真实目录，例如 `/storage/emulated/0/Download/proj`。需要「所有文件访问」。 */
    DEVICE,

    /** 容器内的工作区，例如 Ubuntu rootfs 里的 `/home/user/app`（以宿主机路径绑定）。 */
    CONTAINER,
}

/**
 * 当前绑定的工作区。
 *
 * [path] 是**绝对路径**，始终指向一个宿主机上真实存在的目录 ——
 * 容器工作区也一样：proot/chroot 的 rootfs 落在存储上，对它来说那就是一条普通路径。
 * 因此下层（Shell / 工具 / 终端）不需要关心 [kind]，只认 [File]。
 */
data class WorkspaceInfo(
    val kind: WorkspaceKind,
    val path: String,
    val label: String,
) {
    val root: File get() = File(path)
}

/**
 * 工作区：Agent、终端、技能、MCP 的**唯一**根目录。
 *
 * 为什么要有这层：工具是模型驱动的，模型会照着用户的话拼路径。没有边界的话
 * 一句"帮我清理一下缓存"就可能删到 `/sdcard/DCIM`。这里把所有路径解析都收成
 * 一个函数，越界一律抛异常，工具层不自己做字符串判断（那种判断永远漏）。
 *
 * ### 绑定
 *
 * 三种来源（见 [WorkspaceKind]），同一时刻只有一份生效：
 * ```
 * 内置：/data/data/<pkg>/files/workspace      ← 默认，永远可用
 * 本机：/storage/emulated/0/Download/proj     ← 需要「所有文件访问」
 * 容器：/storage/emulated/0/ubuntu/rootfs/app ← Ubuntu rootfs 里的目录
 * ```
 * 绑定关系落在 SharedPreferences，而不是 DataStore：它必须在**任何界面之前**
 * 生效（终端页、技能页都可能先于聊天页打开），同步读一行比等 Flow 稳。
 *
 * ### 目录结构（内置工作区）
 * ```
 * files/workspace/
 *   ├── notes/     ← 默认给模型放产出
 *   ├── uploads/   ← 用户导入的文件副本
 *   └── .dsha/     ← 运行期元数据（不进用户视野）
 * ```
 */
object Workspace {

    private const val PrefsName = "workspace"
    private const val KeyPath = "root_path"
    private const val KeyKind = "root_kind"
    private const val KeyLabel = "root_label"

    private var context: Context? = null

    /** App 私有 files 目录。技能、MCP 配置、截屏都放这儿 —— **与绑定的工作区无关**。 */
    @Volatile
    private var appFiles: File? = null

    private val _current = MutableStateFlow(WorkspaceInfo(WorkspaceKind.NONE, "", ""))
    val current: StateFlow<WorkspaceInfo> = _current.asStateFlow()

    /**
     * 启动时调用一次。
     *
     * 先绑内置目录（保证立刻可用），再尝试恢复上次绑定；恢复失败（目录被删、
     * 权限被收回）就静静留在内置目录 —— 这比抛异常让 App 起不来合理得多。
     */
    fun init(app: Context, filesDir: File) {
        context = app.applicationContext
        appFiles = filesDir
        val internal = File(filesDir, "workspace").apply {
            mkdirs()
            File(this, "notes").mkdirs()
            File(this, "uploads").mkdirs()
        }
        // 未设置：工具落在内置目录，但对外是"普通对话"（见 WorkspaceKind.NONE）
        _current.value = WorkspaceInfo(WorkspaceKind.NONE, internal.absolutePath, "")

        val prefs = app.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
        val path = prefs.getString(KeyPath, null) ?: return
        val kind = runCatching { WorkspaceKind.valueOf(prefs.getString(KeyKind, "")!!) }
            .getOrDefault(WorkspaceKind.DEVICE)
        val label = prefs.getString(KeyLabel, null) ?: File(path).name
        restore(path, kind, label)
    }

    /** 恢复一个已保存的绑定。目录不存在或不合法时返回 false，当前绑定不变。 */
    fun restore(path: String, kind: WorkspaceKind, label: String): Boolean {
        val dir = File(path)
        if (!dir.isDirectory) return false
        _current.value = WorkspaceInfo(kind, dir.absolutePath, label.ifBlank { dir.name })
        return true
    }

    /**
     * 绑定工作区。成功才落盘 —— 失败时保持原绑定，界面不必回滚。
     *
     * 这里**只做存在性 / 可写性检查**，不做权限申请：权限是设置页的事，
     * 混进来会让"绑定失败"和"没给权限"变成同一个说不清的错误。
     */
    fun bind(path: String, kind: WorkspaceKind, label: String? = null): Result<WorkspaceInfo> {
        val dir = runCatching { File(path).canonicalFile }.getOrElse {
            return Result.failure(IllegalArgumentException("路径不合法：$path"))
        }
        if (!dir.isDirectory) return Result.failure(IllegalArgumentException("目录不存在：${dir.path}"))
        if (!dir.canRead()) return Result.failure(SecurityException("没有读取权限：${dir.path}"))
        val name = label?.takeIf { it.isNotBlank() } ?: dir.name.ifBlank { dir.path }
        if (kind == WorkspaceKind.INTERNAL) {
            runCatching {
                File(dir, "notes").mkdirs()
                File(dir, "uploads").mkdirs()
            }
        }
        val info = WorkspaceInfo(kind, dir.path, name)
        _current.value = info
        context?.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)?.edit()
            ?.putString(KeyPath, info.path)
            ?.putString(KeyKind, kind.name)
            ?.putString(KeyLabel, info.label)
            ?.apply()
        return Result.success(info)
    }

    /** 回到"没有工作区"（普通对话），并清掉保存的绑定。 */
    fun unbind() {
        val files = appFiles ?: return
        val internal = File(files, "workspace")
        _current.value = WorkspaceInfo(WorkspaceKind.NONE, internal.absolutePath, "")
        context?.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
    }

    /**
     * 绑定内置工作区；[name] 非空时在私有目录下开一个同名子文件夹。
     *
     * 为什么允许命名：内置目录是唯一"永远可用"的地方，但所有项目都落在同一个
     * `workspace/` 里，文件会混成一锅。给一个名字，等于给这条对话一个抽屉 ——
     * 路径仍然是私有的，不需要任何权限。
     */
    fun bindInternal(name: String? = null): Result<WorkspaceInfo> {
        val files = appFiles ?: return Result.failure(IllegalStateException("Workspace 未初始化"))
        val base = File(files, "workspace").apply { mkdirs() }
        val folder = name?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            // 只取最后一段并剔掉路径分隔符：这个名字会被拼进路径，不能让它跳出 base
            val safe = raw.replace('/', '_').replace('\\', '_').trim().trim('.')
            if (safe.isEmpty()) null else File(base, safe)
        } ?: base
        if (!folder.exists() && !folder.mkdirs()) {
            return Result.failure(IllegalStateException("无法创建目录：${folder.path}"))
        }
        File(folder, "notes").mkdirs()
        File(folder, "uploads").mkdirs()
        return bind(
            path = folder.absolutePath,
            kind = WorkspaceKind.INTERNAL,
            label = if (folder == base) "内置工作区" else folder.name,
        )
    }

    /**
     * 系统自己建的子目录，**永远不出现在工作区列表里**。
     *
     * `notes` / `uploads` 是 [bind] / [bindInternal] 一进去就 `mkdirs()` 的两只
     * 抽屉：一个是笔记落点，一个是上传落点，工作区服务与工具都直接按名字找它们。
     * 用户没建过它们，却会看到两个"工作区"，还能一键删掉 —— 删了之后上传和
     * 笔记各自在别处重建，看起来像"文件凭空丢了"。
     */
    private val reservedFolders = setOf("notes", "uploads")

    /**
     * 这个路径是不是内置工作区**根目录本身**（而不是它下面的命名子文件夹）。
     *
     * 顶栏副标题要区分"内置工作区"和"内置工作区 · 爬虫项目"：两者都是
     * `WorkspaceKind.INTERNAL`，光看 kind 分不开，而 label 在根目录时是
     * 一句硬编码的中文 —— 不能拿它跟界面文案比对。
     */
    fun isInternalRoot(path: String): Boolean {
        val files = appFiles ?: return false
        return runCatching {
            File(path).canonicalPath == File(files, "workspace").canonicalPath
        }.getOrDefault(false)
    }

    /** 内置目录下已有的子文件夹，给"新建/切换"列出候选。 */
    fun internalFolders(): List<File> {
        val files = appFiles ?: return emptyList()
        val base = File(files, "workspace")
        return base.listFiles()
            ?.filter {
                it.isDirectory &&
                    !it.name.startsWith(".") &&
                    it.name.lowercase() !in reservedFolders
            }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }

    /**
     * 删除一个内置工作区文件夹。
     *
     * 只接受 `files/workspace` 的**直接子目录**：这个 API 一旦放开成"任意路径"，
     * 就等于在 Agent 层开了一个删任意目录的口子，而它唯一的调用方是工作区
     * 浮层里那张列表。
     *
     * 当前绑定的正好是它（或它里面）时先解绑 —— 不解绑的话，绑定会指向一个
     * 已经不存在的目录，下一次读写直接 `FileNotFoundException`，用户还找不到原因。
     */
    fun deleteInternal(folder: File): Result<Unit> {
        val files = appFiles ?: return Result.failure(IllegalStateException("Workspace 未初始化"))
        val target = runCatching { folder.canonicalFile }.getOrElse { return Result.failure(it) }
        val base = runCatching { File(files, "workspace").canonicalFile }.getOrElse {
            return Result.failure(it)
        }
        if (target.parentFile != base) {
            return Result.failure(IllegalArgumentException("只能删除内置工作区"))
        }
        /*
         * 名单外的兜底。列表那边已经过滤掉了，但 `deleteInternal` 是公开函数 ——
         * 别处（以后的批量清理、脚本）传进来也必须挡住。
         */
        if (target.name.lowercase() in reservedFolders) {
            return Result.failure(IllegalArgumentException("${target.name} 是系统目录，不能删除"))
        }
        val bound = _current.value.path
        if (bound.isNotBlank()) {
            val cur = runCatching { File(bound).canonicalPath }.getOrDefault(bound)
            if (cur == target.path || cur.startsWith(target.path + File.separator)) {
                unbind()
            }
        }
        return runCatching {
            if (!target.deleteRecursively()) error("删除失败：${target.name}")
        }
    }

    /**
     * 内置工作区目录，无论当前绑的是哪里。
     *
     * 选择页需要"点一下就设成内置" —— 没有这个函数，只能先 `unbind()` 再读
     * `requireRoot()`，而 `unbind()` 是"清掉绑定"的语义，拿它当"切换"用会让
     * 调用方看起来像在做破坏性操作。
     */
    fun internalPath(): String {
        val files = appFiles ?: return ""
        return File(files, "workspace").apply {
            mkdirs()
            File(this, "notes").mkdirs()
            File(this, "uploads").mkdirs()
        }.absolutePath
    }

    /** App 私有 files 目录（技能 / MCP / 截屏）。 */
    fun appDir(): File = appFiles ?: error("Workspace 未初始化")

    /**
     * App 上下文。只给需要 `PackageManager` / `nativeLibraryDir` 的地方用
     * （目前只有 [UbuntuEnv] 取 proot 的路径）—— 其余一律走 [appDir]，别把
     * Context 散播到工具层里，那会让它们离不开 Activity 生命周期。
     */
    fun appContextOrNull(): Context? = context

    /** 当前根目录；未绑定时抛异常（说明初始化漏了，属于编码错误）。 */
    fun requireRoot(): File {
        val info = _current.value
        return if (info.path.isBlank()) error("Workspace 未绑定") else File(info.path)
    }

    fun rootOrNull(): File? = _current.value.path.takeIf { it.isNotBlank() }?.let(::File)

    /**
     * 把用户附件复制进工作区的 `uploads/`，返回相对路径（`uploads/xxx`）。
     *
     * 模型只能看见工作区里的路径；把附件丢在 App 私有的 attachments 目录，
     * 它既读不到也不该读到。复制一份是"让 AI 能干活"的最小代价 ——
     * 几十 MB 以内的文件复制一次可以接受，视频那种大文件由调用方按需跳过。
     *
     * 重名不覆盖：加 `-1` / `-2` 后缀，否则同一个文件名传两次，第一条消息
     * 里的路径会悄悄指向第二份内容。
     */
    fun storeUpload(source: File, name: String): String {
        val root = rootOrNull() ?: error("工作区未初始化")
        val dir = File(root, "uploads").apply { mkdirs() }
        val safe = sanitizeFileName(name)
        var dest = File(dir, safe)
        var index = 1
        while (dest.exists()) {
            val base = safe.substringBeforeLast('.', safe)
            val ext = safe.substringAfterLast('.', "")
            dest = File(dir, if (ext.isEmpty()) "$base-$index" else "$base-$index.$ext")
            index++
        }
        source.copyTo(dest, overwrite = false)
        return "uploads/${dest.name}"
    }

    /** 只保留文件系统安全字符；空名给一个兜底。 */
    fun sanitizeFileName(raw: String): String {
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\u0000-\u001F]"), "_").trim()
        return cleaned.ifBlank { "file" }.take(120)
    }

    fun label(): String = _current.value.label

    fun kind(): WorkspaceKind = _current.value.kind

    /**
     * 把模型给的路径解析成真实文件。
     *
     * 规则：
     * - 绝对路径按**相对根目录**处理（模型常写 `/notes/a.md`，这在沙箱里就是根下的 a）
     * - `..` 越界直接拒绝，不做"就地截断"——截断会让模型以为写成功了
     */
    fun resolve(path: String): File {
        val base = requireRoot().canonicalFile
        val cleaned = path.trim().removePrefix("file://")
        val candidate = if (cleaned.startsWith("/")) {
            File(base, cleaned.trimStart('/'))
        } else {
            File(base, cleaned)
        }
        val canonical = candidate.canonicalFile
        val basePath = base.path
        val target = canonical.path
        if (target != basePath && !target.startsWith(basePath + File.separator)) {
            throw SecurityException("路径越界：$path")
        }
        return canonical
    }

    /** 相对根目录的展示路径，用于工具回执里给模型看。 */
    fun relative(file: File): String {
        val base = requireRoot().canonicalFile.path
        val path = runCatching { file.canonicalFile.path }.getOrDefault(file.path)
        return if (path.startsWith(base)) path.removePrefix(base).trimStart(File.separatorChar) else path
    }

    /**
     * 常用的候选目录，给绑定界面当快捷入口。
     *
     * 只列**存在**的那些 —— 列一堆不存在的路径，用户点进去只会得到一句"目录不存在"。
     */
    fun candidates(): List<WorkspaceInfo> {
        val files = appFiles ?: return emptyList()
        val list = mutableListOf<WorkspaceInfo>()
        val internal = File(files, "workspace")
        list += WorkspaceInfo(WorkspaceKind.INTERNAL, internal.absolutePath, "内置工作区")
        listOf(
            "/storage/emulated/0" to "内部存储",
            "/storage/emulated/0/Download" to "下载",
            "/storage/emulated/0/Documents" to "文档",
            "/storage/emulated/0/ubuntu" to "Ubuntu 容器",
            "/storage/emulated/0/ubuntu/rootfs" to "Ubuntu rootfs",
            File(files, "rootfs").absolutePath to "内置 rootfs",
            "/data/local/tmp" to "临时目录",
        ).forEach { (path, label) ->
            val dir = File(path)
            if (dir.isDirectory) list += WorkspaceInfo(WorkspaceKind.DEVICE, dir.absolutePath, label)
        }
        return list.distinctBy { it.path }
    }
}
