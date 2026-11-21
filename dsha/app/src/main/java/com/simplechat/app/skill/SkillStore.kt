package com.simplechat.app.skill

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 技能的现场安装与启用。
 *
 * "现场"指用户不需要重装 App：把 zip 丢进来就装好，关掉开关就不生效。
 * 启用状态存在 `skills/.enabled.json`，和技能文件放一起 —— 整个目录拷走
 * 就是一份可迁移的技能集，不需要额外的备份逻辑。
 */
object SkillStore {

    private const val EnabledFile = ".enabled.json"

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    /**
     * 技能目录固定在 **App 私有目录**下，与用户绑定的工作区解耦。
     *
     * 早先挂在 `工作区/..`：一旦用户把工作区绑到 `/sdcard/xxx`，技能就会被写进
     * `/sdcard`，既污染存储，也会在换了工作区之后"技能全丢"。
     */
    private fun root(): File = File(com.simplechat.app.agent.Workspace.appDir(), "skills")

    fun rootDir(): File = root().apply { mkdirs() }

    private fun enabledIds(): Set<String> {
        val file = File(root(), EnabledFile)
        if (!file.isFile) return emptySet()
        return runCatching {
            val array = json.parseToJsonElement(file.readText()) as? JsonArray ?: return emptySet()
            array.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun writeEnabled(ids: Set<String>) {
        val file = File(root(), EnabledFile)
        val payload = buildJsonArray { ids.sorted().forEach { add(JsonPrimitive(it)) } }
        runCatching { file.writeText(json.encodeToString(JsonArray.serializer(), payload)) }
    }

    /** 重新扫描磁盘。安装 / 删除 / 外部改动之后调一次。 */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val enabled = enabledIds()
        val list = rootDir().listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir -> SkillParser.parse(dir.name, dir, dir.name in enabled) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        _skills.value = list
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val ids = enabledIds().toMutableSet()
        if (enabled) ids += id else ids -= id
        writeEnabled(ids)
        refresh()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val dir = File(root(), id)
        if (dir.canonicalPath.startsWith(root().canonicalPath) && dir.isDirectory) {
            dir.deleteRecursively()
        }
        val ids = enabledIds().toMutableSet().apply { remove(id) }
        writeEnabled(ids)
        refresh()
    }

    /**
     * 从 zip 安装。压缩包里可以有多个技能目录（例如一次装一整套）。
     *
     * 安全点：解压时逐个校验目标路径，防止 `../` 逃逸（zip 里带这种条目是
     * 常见的恶意写法，不校验就等于给了任意写权限）。
     */
    suspend fun installZip(bytes: ByteArray): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val installed = mutableListOf<String>()
            val base = rootDir().canonicalFile
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.replace('\\', '/')
                    if (!entry.isDirectory && name.endsWith(".md", ignoreCase = true)) {
                        // 只解压 Markdown 与脚本 / 模板，跳过二进制（体积与安全都划不来）
                        val target = File(base, name).canonicalFile
                        if (!target.path.startsWith(base.path + File.separator)) {
                            throw SecurityException("压缩包含非法路径：$name")
                        }
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out -> zip.copyTo(out) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            // 找出所有含 SKILL.md 的目录，作为技能根
            base.listFiles()?.forEach { top ->
                val candidates = if (top.isDirectory) listOf(top) + top.listFiles().orEmpty().filter { it.isDirectory } else emptyList()
                candidates.forEach { dir ->
                    if (File(dir, "SKILL.md").isFile || File(dir, "skill.md").isFile) {
                        // 只装一层：如果顶层目录本身含 SKILL.md，就不再看子目录
                        if (dir != top || File(top, "SKILL.md").isFile) installed += dir.name
                    }
                }
            }
            refresh()
            installed.distinct()
        }
    }

    /**
     * 把一个技能打包成**可以直接再导入**的 zip。
     *
     * 结构必须与 [installZip] 认的形态一致：压缩包顶层就是 `<id>/SKILL.md`。
     * 只导出 SKILL.md 是不够的 —— 相当一部分技能带脚本 / 模板 / 参考资料，
     * 缺了它们，导出去的是一份"能看不能用"的说明书。
     *
     * 不导出 `.enabled.json`：启用状态是本机的选择，跟着技能走会在别人的
     * 机器上莫名其妙地生效或静默。
     */
    suspend fun exportZip(id: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(rootDir(), id)
            require(dir.isDirectory) { "技能不存在：$id" }
            val files = dir.walkTopDown().filter { it.isFile }.sortedBy { it.absolutePath }.toList()
            require(files.isNotEmpty()) { "技能目录是空的" }

            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                files.forEach { file ->
                    val relative = file.relativeTo(dir).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry("$id/$relative"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            out.toByteArray()
        }
    }

    /**
     * 拼进系统提示词的那一段。
     *
     * 只放"名字 + 一句话 + 目录"，**不放正文**。正文动辄几百行，全塞进 system
     * 会让普通对话也背上这个开销；模型真要用了，会自己去读 `SKILL.md`。
     */
    fun enabledPromptBlock(): String {
        val enabled = _skills.value.filter { it.enabled }
        if (enabled.isEmpty()) return ""
        return buildString {
            appendLine("以下技能已启用。与任务相关时，先 read_skill 读它的说明再动手，不要凭印象猜用法：")
            enabled.forEach { appendLine(it.promptLine()) }
        }.trim()
    }
}
