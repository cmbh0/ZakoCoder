package com.simplechat.app.agent

import java.io.File

/**
 * 把 Python 装进本机 Ubuntu 环境。
 *
 * ### 为什么要单独一个文件
 *
 * 之前有**两份**几乎一样的实现：
 *
 * - [UbuntuEnv.ensurePip] —— apt 装 `python3 python3-pip python3-venv`，
 *   装之前先 `dpkg --configure -a`，装完再验一次；
 * - [PyRuntime.installInterpreter] —— apt 装 `python3 python3-pip`，
 *   不恢复 dpkg、不验 update 的结果、失败**被吞掉**。
 *
 * 而 Ubuntu 安装流程走的是**后者**（见 `UbuntuEnv.install` 末尾那步
 * "安装 Python…"），也就是说：用户装完 Ubuntu，看到的是"完成"，
 * 点开终端敲 `python3` 却得到 `command not found` ——
 * **而安装日志里那句真正的错误（多半是 `Unable to locate package python3`）
 * 被 `runCatching { }` 丢掉了，谁都看不见。**
 *
 * 现在只有这一份实现，装不上的话它会把**真实的失败原因**带出去。
 *
 * ### 装不上的三种典型原因，和各自的动作
 *
 * | 原因 | 症状 | 处理 |
 * |---|---|---|
 * | 上一次装到一半被打断 | `dpkg was interrupted` / `另一个进程持有锁` | 先 `dpkg --configure -a` |
 * | 镜像没拉到（网络/限速） | `Unable to locate package python3` | 重跑 `apt-get update`；仍失败则清掉 lists 再来一次 |
 * | 装到一半失败 | 后续所有 apt 命令都报 dpkg 错 | 收尾时再跑一次 `dpkg --configure -a`，不让环境被"毒化" |
 *
 * 第三条最要命：**一次失败的安装会让后面每一次都失败**，而用户看到的
 * 只有"装不上"。所以收尾的修复不能省。
 */
object PythonInstaller {

    /** 要装的包。`python3-venv` 一起装上 —— `python3 -m venv` 单独用会缺。 */
    private const val PACKAGES = "python3 python3-pip python3-venv"

    /**
     * 解释器可能落在这几个位置。
     *
     * - `usr/local/bin/python3`：预编译包（[PythonBundle]）的入口软链；
     * - `usr/bin/python3`：apt 装的那种，在能用的设备上依然会被认。
     *
     * **故意不列 `opt/python/bin/python3`。** 那是解压出来的实体，而解压
     * 是"先删目录再整包铺"，断在中途会留下一个有解释器、没标准库的残树。
     * 预编译包在不在，一律由 [PythonBundle.isInstalled] 看标记回答。
     */
    private val PYTHON_PATHS = listOf(
        "usr/local/bin/python3",
        "usr/bin/python3",
    )

    private val PIP_PATHS = listOf(
        "usr/local/bin/pip3",
        "usr/bin/pip3",
    )

    /** 环境当前的状态。给界面用：说清楚"卡在哪一环"。 */
    data class Status(
        /** Ubuntu 环境本身在不在。 */
        val envInstalled: Boolean,
        /** apt 能不能用（源可达、dpkg 状态正常）。 */
        val aptOk: Boolean,
        /** python3 在不在。 */
        val pythonPresent: Boolean,
        /** pip 在不在。 */
        val pipPresent: Boolean,
        /** 一句话结论，给用户看。 */
        val summary: String,
        /** 失败时的原始输出（诊断用）。 */
        val detail: String? = null,
    ) {
        /**
         * 能不能直接用。
         *
         * **故意不含 [aptOk]**：Python 是预编译包铺进来的（见 [PythonBundle]），
         * 跟 apt 通不通没关系。早先这里要求 `aptOk`，于是 apt 一坏，界面上
         * 明明 `python3 -V` 能跑，卡片还是显示"未就绪"并催用户重装。
         */
        val ready: Boolean get() = envInstalled && pythonPresent
    }

    /**
     * 查一下现在的状态。**只读，不改任何东西。**
     *
     * 判据一律看**文件在不在**，而不是"跑一次 python3 -V" ——
     * 后者要起一次 proot，慢，而且环境没调好时会挂住界面。
     */
    suspend fun diagnose(): Status {
        if (!UbuntuEnv.isInstalled()) {
            return Status(
                envInstalled = false,
                aptOk = false,
                pythonPresent = false,
                pipPresent = false,
                summary = "还没装 Ubuntu 环境",
            )
        }
        val root = UbuntuEnv.rootfs()
        val bundled = PythonBundle.isInstalled()
        val python = bundled || PYTHON_PATHS.any { File(root, it).exists() }
        val pip = bundled || PIP_PATHS.any { File(root, it).exists() }
        val apt = File(root, "usr/bin/apt-get").isFile
        val sources = File(root, "etc/apt/sources.list").isFile ||
            File(root, "etc/apt/sources.list.d").listFiles()?.isNotEmpty() == true

        return Status(
            envInstalled = true,
            aptOk = apt && sources,
            pythonPresent = python,
            pipPresent = pip,
            summary = when {
                python && pip -> "Python 已就绪"
                python -> "有 python3，但没有 pip"
                !apt -> "环境里没有 apt，装不了软件包"
                !sources -> "没有配置软件源"
                else -> "还没装 Python"
            },
        )
    }

    /**
     * 装 Python。装不上时**把原因带出来**，不再吞掉。
     *
     * [onLine] 是给界面实时显示用的（"正在恢复 dpkg 状态…"之类）。
     */
    suspend fun install(onLine: (String) -> Unit = {}): Result<Status> {
        if (!UbuntuEnv.isInstalled()) {
            return Result.failure(IllegalStateException("还没装 Ubuntu 环境。先在上面的卡片里装一次。"))
        }

        /* ── 0. 先走不碰 dpkg 的那条路 ────────────────────────────
         *
         * 次序在这里是**功能问题，不是偏好问题**。
         *
         * apt 在部分设备上会死在：
         * `E: Could not open file /var/lib/dpkg/status - open (38: Function not implemented)`
         * —— `38` 是 `ENOSYS`，`open(2)` 根本没被 proot 翻译，直接透传给内核。
         * 那条路一旦断，后面全是死路：`apt-get update` 倒是能过（它只写
         * `/var/lib/apt/lists`），一到 `install` 读 status 就崩。
         *
         * [PythonBundle] 那条路只做三件事：下载 tar.gz、解压、建软链。
         * **一步都不经过 dpkg**，proot 的 syscall 翻译再差也拦不住它。
         *
         * 已经用 apt 装好 python3 的老环境在这里直接认，不会再下一遍 30 MB。
         */
        diagnose().let { status ->
            if (status.pythonPresent) {
                if (!status.pipPresent) {
                    onLine("有 python3，补一个 pip…")
                    val ensured = UbuntuEnv.run(
                        "python3 -m ensurepip --upgrade 2>&1 | tail -n 5",
                        timeoutSeconds = 240,
                    )
                    ensured.output().lineSequence().filter { it.isNotBlank() }
                        .toList().takeLast(3).forEach(onLine)
                }
                val after = diagnose()
                if (after.ready) return Result.success(after)
            }
        }

        if (!PythonBundle.isInstalled()) {
            onLine("下载预编译的 Python（不经过 apt）…")
            val bundled = PythonBundle.install { _, text -> onLine(text) }
            if (bundled.isSuccess) {
                val status = diagnose()
                if (status.ready) return Result.success(status)
            } else {
                val reason = bundled.exceptionOrNull()?.message.orEmpty()
                onLine("预编译包没装上（$reason）")
                onLine("改走 apt…")
            }
        }

        /* ── 1. 恢复 dpkg ────────────────────────────────────────
         *
         * 上一次装到一半被打断（用户强制停止、进程被杀）之后，dpkg 会留下
         * 半配置状态，此时**任何** apt 命令都会先报 dpkg 错并拒绝执行。
         * 所以修复必须排在最前面 —— 不然第一步 `apt-get update` 就已经挂了。
         */
        /*
         * apt 能不能用，不只取决于 dpkg 状态 —— 还取决于三件"环境前提"：
         * sandbox 用户是 root、`/var/lib/dpkg` 目录齐全、status 是个正常 inode。
         * 这三件事在**运行期**从来没做过（只有装环境那一次会碰），所以老环境
         * 上一点「安装 Python」就报 `Could not open file /var/lib/dpkg/status`。
         */
        onLine("准备 apt 环境…")
        UbuntuEnv.prepareApt(onLine)

        onLine("检查并修复 dpkg 状态…")
        val repair = UbuntuEnv.run(
            "dpkg --configure -a 2>&1 | tail -n 20",
            timeoutSeconds = 240,
        )
        val dpkgBroken = repair.exitCode != 0 && repair.output().isNotBlank()
        if (dpkgBroken) onLine("dpkg 处于中断状态，已尝试恢复。")

        /* ── 1. 拉索引 ──────────────────────────────────────────── */
        onLine("apt-get update…")
        var update = aptUpdate()
        if (update.exitCode != 0) {
            /*
             * 重试前**再整理一次 dpkg**。
             *
             * 第一次 `prepareApt` 有可能整段没跑成：proot 刚换档、第一次
             * 起进程时 loader 还没落盘，命令直接退了 255。那种情况下库还是
             * 坏的，只清 lists 再 update 一遍会原样失败。
             */
            onLine("索引拉取失败，重试一次…")
            UbuntuEnv.prepareApt(onLine)
            UbuntuEnv.run(
                "apt-get clean >/dev/null 2>&1; rm -rf /var/lib/apt/lists/*; true",
                timeoutSeconds = 120,
            )
            update = aptUpdate()
        }
        if (update.exitCode != 0) {
            /*
             * **这里必须把真实原因带出去。**
             *
             * 旧版把这一段的输出丢掉，用户只看到"安装完成"，然后在终端里
             * 发现 `python3: command not found`，完全不知道是网络还是环境坏了。
             */
            val reason = explainUpdateFailure(update.output())
            UbuntuEnv.run("dpkg --configure -a >/dev/null 2>&1 || true", timeoutSeconds = 180)
            return Result.failure(
                IllegalStateException(
                    "拉取软件源索引失败：$reason\n" +
                        "可以检查网络，或到「设置 → 运行环境」里重装一次 Ubuntu。",
                ),
            )
        }

        /* ── 2. 装 ──────────────────────────────────────────────── */
        onLine("安装 $PACKAGES…")
        var install = UbuntuEnv.run(
            "DEBIAN_FRONTEND=noninteractive apt-get -o APT::Sandbox::User=root install " +
                "-y -qq --no-install-recommends $PACKAGES",
            timeoutSeconds = 900,
        )
        install.output().lineSequence().filter { it.isNotBlank() }.toList().takeLast(8).forEach(onLine)

        if (install.exitCode != 0) {
            /* ── 3. 重来一次 ──────────────────────────────────────
             *
             * 装包失败最常见的原因是索引不完整（下载到一半断了）。
             * 清干净重来一次通常就好了。
             */
            onLine("安装失败，清理后重试…")
            UbuntuEnv.run(
                "apt-get clean >/dev/null 2>&1; rm -rf /var/lib/apt/lists/*; " +
                    "dpkg --configure -a >/dev/null 2>&1; true",
                timeoutSeconds = 240,
            )
            val retryUpdate = aptUpdate()
            if (retryUpdate.exitCode == 0) {
                install = UbuntuEnv.run(
                    "DEBIAN_FRONTEND=noninteractive apt-get -o APT::Sandbox::User=root install " +
                        "-y -qq --no-install-recommends $PACKAGES",
                    timeoutSeconds = 900,
                )
                install.output().lineSequence().filter { it.isNotBlank() }.toList().takeLast(8).forEach(onLine)
            }
        }

        /* ── 4. 收尾 ──────────────────────────────────────────────
         *
         * 哪怕装失败了，也要把 dpkg 拉回干净状态。
         * 留着半配置状态 = 后面每一次 apt 都会失败 = 用户永远装不上东西。
         */
        UbuntuEnv.run("dpkg --configure -a >/dev/null 2>&1 || true", timeoutSeconds = 240)

        if (install.exitCode != 0) {
            /*
             * apt 全线失败，但预编译包其实已经在位 —— 这种情况**必须报成功**。
             *
             * 否则用户看到的是红色"安装失败"，而终端里 `python3 -V` 明明有输出。
             * 那比装不上更让人困惑。
             */
            if (PythonBundle.isInstalled()) {
                onLine("apt 没装上，但预编译的 Python 已经在位，直接用了。")
                return Result.success(diagnose())
            }
            val detail = install.output().trim().takeLast(1_200)
            /*
             * 附一段**现场快照**：dpkg 的 status 到底是什么样子。
             *
             * apt 在 proot 下的失败常常只有一句 errno，没有这条信息就只能靠猜。
             * 命令全是只读的，失败也无所谓。
             */
            val snapshot = UbuntuEnv.run(
                "ls -l /var/lib/dpkg/status /var/lib/dpkg/available 2>&1; " +
                    "stat -c '%i %h %s %F' /var/lib/dpkg/status 2>&1; " +
                    "cat /etc/apt/apt.conf.d/99dsha-sandbox 2>&1",
                timeoutSeconds = 60,
            ).output().trim()
            return Result.failure(
                IllegalStateException(
                    "安装 Python 失败。${explainInstallFailure(detail)}\n$detail" +
                        if (snapshot.isBlank()) "" else "\n\n[环境快照]\n$snapshot",
                ),
            )
        }

        /* ── 5. 验证 ──────────────────────────────────────────────
         *
         * apt 返回 0 **不等于** python3 真的能用（--no-install-recommends
         * 加上缺依赖时会出现"装上了但跑不起来"）。所以要真的查一遍文件。
         */
        val status = diagnose()
        if (!status.ready) {
            return Result.failure(
                IllegalStateException(
                    "包管理器说装好了，但 ${PYTHON_PATHS.first()} / ${PIP_PATHS.first()} 仍然不在。\n" +
                        "多半是 proot 下的 dpkg 没有真正解包（设备兼容问题）。" +
                        "详细信息：${install.output().trim().takeLast(600)}",
                ),
            )
        }
        return Result.success(status)
    }

    /**
     * 装好之后的版本号（给界面显示）。取不到返回空串。
     *
     * **只认 `Python 3.x.y` 这一种形状。**
     *
     * 从前是把 `python3 -V` 的所有输出原样返回，于是没装的时候卡片副标题
     * 上写的是 `/bin/bash: line 1: python3: command not found` ——
     * 一句 shell 报错被当成"版本号"贴在标题右边，看起来像环境坏了之后
     * 又坏了一次。
     */
    suspend fun version(): String {
        if (!UbuntuEnv.isInstalled()) return ""
        val result = UbuntuEnv.run("python3 -V 2>&1", timeoutSeconds = 60)
        return result.output().lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("Python ") }
            .orEmpty()
    }

    /**
     * 拉一次索引。
     *
     * 刻意**不用 `-qq`**：`-qq` 会把错误也压掉一部分，而这里正是需要
     * 看清错误的时候。输出只留最后若干行。
     */
    private suspend fun aptUpdate(): Shell.Result = UbuntuEnv.run(
        "apt-get -o APT::Sandbox::User=root update 2>&1 | tail -n 30",
        timeoutSeconds = 420,
    )

    /**
     * 把 apt 的报错翻成人话。
     *
     * 用户看到的是 "E: Unable to locate package python3"，但真正的原因
     * 通常在更上面那几行（"无法解析 mirrors.tuna…"）。
     */
    private fun explainUpdateFailure(output: String): String {
        val text = output.lowercase()
        return when {
            "could not resolve host" in text || "temporary failure resolving" in text ->
                "连不上软件源镜像（DNS 或网络不通）"
            "connection timed out" in text || "connection reset" in text ->
                "连接软件源镜像超时"
            "403 forbidden" in text || "404" in text ->
                "软件源返回了 403/404（镜像地址不对或被限流）"
            "no space left" in text ->
                "存储空间不足"
            "dpkg was interrupted" in text || "dpkg was locked" in text ->
                "dpkg 处于中断状态（上一个安装没跑完）"
            "unable to locate package" in text ->
                "索引里没有要的包（软件源没拉全）"
            output.isBlank() -> "没有拿到任何输出"
            else -> output.trim().lineSequence().last().take(200)
        }
    }

    /** 同上，但针对"装包失败"。 */
    private fun explainInstallFailure(detail: String): String {
        val text = detail.lowercase()
        return when {
            "no space left" in text -> "存储空间不足。"
            "could not resolve host" in text -> "网络不通，连不上软件源。"
            "dpkg was interrupted" in text -> "dpkg 处于中断状态。"
            "unable to locate package" in text -> "软件源索引里没有这个包。"
            "broken pipe" in text -> "安装过程被中断。"
            detail.isBlank() -> "没有拿到任何输出。"
            else -> ""
        }
    }

    /** stdout + stderr 拼起来。apt 的错误大多走 stderr。 */
    private fun Shell.Result.output(): String =
        (stdout + "\n" + stderr).trim()
}
