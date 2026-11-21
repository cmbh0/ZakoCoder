package com.simplechat.app.agent

import android.os.SystemClock
import android.system.Os
import com.simplechat.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 不经过 apt 的 Python。
 *
 * ### 为什么需要这条路
 *
 * 走 apt 装 `python3` 在这台设备上会死在 `dpkg` 上：
 *
 * ```
 * E: Could not open file /var/lib/dpkg/status - open (38: Function not implemented)
 * ```
 *
 * `38` 是 `ENOSYS`。它**不是权限问题、也不是文件不存在** —— 是那个 `open(2)`
 * 在当前 proot / 内核组合下压根没被翻译，直接透传给了内核。`apt-get update`
 * 能过（它只写 `/var/lib/apt/lists`），一到 `install` 读 `status` 就断，
 * 于是整条 apt 链在部分设备上等于不可用。
 *
 * 换 proot（[UbuntuEnv] 里现在首选 termux 的移植构建）能救大多数设备，
 * 但**不该把所有设备都押在"proot 这次一定能翻译对"上**。所以这里再给一条
 * 完全不碰 `dpkg` 的通道：下载一份预编译的 CPython，解压，建软链。
 *
 * ### 为什么是 python-build-standalone
 *
 * 它是 astral-sh（uv / ruff 那家）维护的可重定位 CPython 构建，产物就是
 * 一个 tar.gz，解开即用，**自带 pip**，不依赖系统 `libpython`、不写
 * `/usr/lib/python3/dist-packages`、也不需要 `dpkg` 记账。
 *
 * 对 Ubuntu 22.04 的要求只有一条：宿主 glibc ≥ 2.17（22.04 是 2.35）。
 *
 * ### 装在哪、怎么被找到
 *
 * - 实体：`rootfs/opt/python/`（解压后带 `bin/python3`、`lib/python3.12/`）
 * - 入口：`rootfs/usr/local/bin/{python3,python,pip3,pip}` 四条软链
 *
 * `/usr/local/bin` 在 `PATH` 里排在 `/usr/bin` 前面（见 [UbuntuEnv] 启动参数），
 * 所以 apt 哪天又能用了、真装了一个 `/usr/bin/python3`，也不会把这份盖掉。
 *
 * ### 卸载
 *
 * 删 `opt/python` 与那四条软链即可，[uninstall] 就是做这件事。
 */
object PythonBundle {

    private const val Tag = "PythonBundle"

    /** 固定的发布标签与版本。改了就等于换一份基线，已装的环境要重装。 */
    private const val Release = "20250818"
    private const val Version = "3.12.11"

    /** 装完之后，guest 里的解释器路径。 */
    const val GuestPython = "/opt/python/bin/python3"

    /**
     * 下载前缀，**按可用性排序**。
     *
     * 前两个是国内能连通的 GitHub 反代 —— 直连 `github.com` 在移动网络下
     * 经常几十 KB/s，30 MB 要下到天荒地老，用户会以为卡死。
     * 空串表示直连，放最后兜底。
     */
    private val Mirrors = listOf(
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "",
    )

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** 装好之后写下的标记。**只有它在了才算装成**，见 [isInstalled]。 */
    private const val MarkerName = ".dsha-python-ok"

    /** 已经装好了吗。判据是文件在不在，不起进程。 */
    fun isInstalled(): Boolean {
        if (!UbuntuEnv.isInstalled()) return false
        val root = UbuntuEnv.rootfs()
        /*
         * **必须看标记，不能只看 `bin/python3`。**
         *
         * 解压是"先删目录、再整包铺进去"，中途断掉（用户切后台被系统杀、
         * 存储满）会留下一个**有 bin/python3、没有标准库**的残缺树。
         * 只看文件在不在的话，下一次会认为"已经装好了"，然后 `python3 -V`
         * 报一长串 ImportError —— 比"没装"难查得多。
         */
        if (!File(root, "opt/python/$MarkerName").isFile) return false
        val interpreter = File(root, "opt/python/bin/python3")
        return interpreter.exists() || isLink(interpreter)
    }

    /** 解压后的占用（给"存储够不够"用）。 */
    fun installedBytes(): Long = runCatching {
        File(UbuntuEnv.rootfs(), "opt/python").walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }.getOrDefault(0L)

    /**
     * 下载并装好。
     *
     * [onProgress] 的第一位是 0..1，第二位是给人看的一句话。
     */
    suspend fun install(onProgress: (Float, String) -> Unit = { _, _ -> }): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!UbuntuEnv.isInstalled()) {
                    throw IllegalStateException("还没装 Ubuntu 环境。先在上面那张卡片里装一次。")
                }
                if (isInstalled()) return@runCatching

                val root = UbuntuEnv.rootfs()
                val abi = when {
                    File(root, "lib/ld-linux-aarch64.so.1").exists() -> "aarch64"
                    File(root, "lib64/ld-linux-x86-64.so.2").exists() -> "x86_64"
                    else -> throw IllegalStateException("认不出这个环境是 arm64 还是 x86_64")
                }

                val archive = File(root.parentFile, "python-$Version-$abi.tar.gz")
                /*
                 * 先看空间。
                 *
                 * 解压完约 90 MB，加上下载的 26 MB 和临时占用，220 MB 是下限。
                 * 不预检的话，会是"下完 26 MB、解压到 70% 抛 ENOSPC"，
                 * 用户只看到一句 IOException，而且下载白费。
                 */
                val free = runCatching { android.os.StatFs(root.absolutePath).availableBytes }
                    .getOrDefault(0L)
                if (free in 1 until 220L * 1024 * 1024) {
                    throw IllegalStateException(
                        "存储空间不足：装 Python 需要约 220 MB，现在只剩 ${free / 1024 / 1024} MB。",
                    )
                }
                download(archive, abi, onProgress)

                onProgress(0.62f, "解压 Python ${Version}…")
                val target = File(root, "opt").apply { mkdirs() }
                // 上一次解压到一半被打断，留下的是残缺目录 —— 先清干净
                File(target, "python").let { if (it.exists()) it.deleteRecursively() }
                UbuntuEnv.extract(archive, target) { fraction ->
                    onProgress(0.62f + fraction * 0.30f, "解压 Python ${Version}…")
                }

                verify(root)
                onProgress(0.95f, "建立命令入口…")
                linkEntryPoints(root)
                File(root, "opt/python/$MarkerName").writeText("$Version\n")

                archive.delete()
                AppLog.i(Tag, "Python $Version 已就绪：${File(root, "opt/python/bin/python3")}")
                onProgress(1f, "Python 已就绪")
            }
        }

    /** 把 `opt/python` 与四条软链删掉。 */
    fun uninstall() {
        val root = runCatching { UbuntuEnv.rootfs() }.getOrNull() ?: return
        runCatching { File(root, "opt/python").deleteRecursively() }
        listOf("python3", "python", "pip3", "pip").forEach { name ->
            runCatching { File(root, "usr/local/bin/$name").delete() }
        }
    }

    // ── 内部 ────────────────────────────────────────────────

    /**
     * 下载。一个镜像不通就换下一个。
     *
     * 与 rootfs 的下载刻意分开：那个写死要 20 MB 以上才算完整（Ubuntu base
     * 就是 30 MB 级），而这里的包是 12～30 MB，两者阈值不同，混用会让
     * "下载完成"的判定变得没法维护。
     */
    private fun download(archive: File, abi: String, onProgress: (Float, String) -> Unit) {
        /*
         * 用 `_stripped` 那一档，不是 `install_only`。
         *
         * 两者功能完全一样（都自带 pip、都能 `python3 -m venv`），差别只在
         * 调试符号、`test/` 套件和静态库：完整包 42 MB，stripped 26 MB。
         * 对一个要在手机流量下下载的东西，这 16 MB 比"留着以后可能用得上
         * 的符号表"重要得多。
         */
        val file = "cpython-$Version%2B$Release-$abi-unknown-linux-gnu-install_only_stripped.tar.gz"
        val path = "https://github.com/astral-sh/python-build-standalone/releases/download/$Release/$file"
        var lastError: Exception? = null

        for (mirror in Mirrors) {
            val url = "$mirror$path"
            val part = File(archive.absolutePath + ".part")
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "DSHA/PythonBundle")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body
                    val total = body.contentLength()
                    if (total in 1 until 8L * 1024 * 1024) {
                        throw IOException("文件只有 $total 字节，不像 Python 发行包")
                    }
                    var written = 0L
                    var lastReport = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(part).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                written += read
                                val now = SystemClock.uptimeMillis()
                                if (now - lastReport > 200) {
                                    lastReport = now
                                    val fraction = if (total > 0) written.toFloat() / total else 0f
                                    val mb = written / 1024 / 1024
                                    val all = if (total > 0) "${total / 1024 / 1024} MB" else "? MB"
                                    onProgress(fraction * 0.60f, "下载 Python 发行包 $mb / $all")
                                }
                            }
                            output.flush()
                        }
                    }
                    if (written < 8L * 1024 * 1024) throw IOException("下载不完整（$written 字节）")
                }
                /*
                 * **大小过了不算数，CRC 过了才算。**
                 *
                 * 网络断在中途时，文件大小有可能刚好越过上面的下限，
                 * 于是"下载成功"、解压到一半才炸 —— 那时 rootfs 里已经铺了
                 * 半套标准库，下一次重试还得先清理。在这里空读一遍
                 * gzip 流，CRC 与长度不对会当场抛出来，换镜像重来。
                 */
                verifyArchive(part)
                if (archive.exists()) archive.delete()
                check(part.renameTo(archive)) { "无法重命名下载文件" }
                return
            } catch (error: Exception) {
                lastError = error
                runCatching { part.delete() }
                AppLog.w(Tag, "镜像下载失败：$url", error)
            }
        }
        throw IOException(
            "下载 Python 失败：${lastError?.message ?: "所有镜像都不可达"}。" +
                "检查一下网络再试。",
        )
    }

    /** 解压完必须能看见解释器与标准库，否则等于没装。 */
    private fun verify(root: File) {
        val bin = File(root, "opt/python/bin/python3")
        if (!bin.exists() && !isLink(bin)) {
            throw IOException("解压完了但没有 opt/python/bin/python3，发行包可能不完整")
        }
        val stdlib = File(root, "opt/python/lib")
        if (!stdlib.isDirectory) {
            throw IOException("解压完了但没有 opt/python/lib，标准库缺失")
        }
    }

    /**
     * 把整个 gzip 流读干，顺带校验 CRC 与原始长度。
     *
     * 只判断"文件够不够大"是不够的：断流的位置有可能刚好落在阈值以上，
     * 解压到一半才炸，那时已经铺了半套标准库进 rootfs。
     */
    private fun verifyArchive(archive: File) {
        java.util.zip.GZIPInputStream(archive.inputStream().buffered(128 * 1024)).use { input ->
            val buffer = ByteArray(64 * 1024)
            @Suppress("ControlFlowWithEmptyBody")
            while (input.read(buffer) >= 0) {
                // 只是为了把它读干：CRC 与长度由 GZIPInputStream 在末尾校验
            }
        }
    }

    /**
     * 是不是符号链接。
     *
     * `File.isFile` / `exists` 都会一路看穿到目标，断掉的链接在它们眼里
     * 等于"不存在"，`delete()` 却仍然只删链接本身 —— 删之前必须先认出它。
     */
    private fun isLink(file: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    /**
     * 建 `/usr/local/bin` 下的四条软链。
     *
     * 用软链而不是直接改 `PATH`：`#!/usr/bin/env python3` 的脚本、
     * `subprocess` 里起的子解释器、以及 `pip` 自己的 shebang，全都靠
     * `PATH` 里第一个 `python3` 找解释器。软链是唯一能让它们一致的办法。
     */
    private fun linkEntryPoints(root: File) {
        val dir = File(root, "usr/local/bin").apply { mkdirs() }
        val links = mapOf(
            "python3" to "$GuestPython",
            "python" to "$GuestPython",
            "pip3" to "/opt/python/bin/pip3",
            "pip" to "/opt/python/bin/pip3",
        )
        links.forEach { (name, target) ->
            val link = File(dir, name)
            runCatching { if (link.exists() || isLink(link)) link.delete() }
            runCatching { Os.symlink(target, link.absolutePath) }
                .onFailure { AppLog.w(Tag, "建软链失败：$name → $target", it) }
        }
    }
}
