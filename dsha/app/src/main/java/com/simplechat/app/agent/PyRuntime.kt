package com.simplechat.app.agent

import java.io.File

/**
 * Python 运行时。
 *
 * 两条路，优先嵌入式：
 *
 * 1. **Chaquopy**（若构建时接入了）：进程内 CPython，启动快、不用装 APK 外的
 *    任何东西。这里**全部用反射**调用 —— 反射的好处是即使构建里没有 Chaquopy，
 *    这个文件也能正常编译，功能自动降级，而不是让整个工程编不过。
 * 2. **系统 python3**：部分 ROM / 用户自己装过 Termux 时存在。走 `sh -c` 调起，
 *    输出重定向到文件再读回。
 *
 * 两条都不通时，`python` 工具返回一句人话，让模型改用 `run_shell` ——
 * 而不是抛异常把整轮对话打断。
 */
object PyRuntime {

    enum class Kind {
        /** 嵌入式解释器可用。 */
        EMBEDDED,

        /**
         * 本机 Ubuntu 环境里的 python3。
         *
         * 这是**正路**：Ubuntu 装好之后 `apt install python3 python3-pip`，
         * 得到的是一个完整的 CPython + 全部标准库 —— 而不是让用户自己去
         * 找 Termux 或往手机里塞一个 python 二进制。
         *
         * 为什么不直接用 `linux_shell` 让模型自己敲：模型每次都要写
         * `python3 - <<'EOF' … EOF`，引号与 heredoc 一错就是"命令没输出"，
         * 它再猜两轮。一个结构化工具把这段噪音全省掉。
         */
        UBUNTU,

        /** 系统里有 python3 可执行文件（部分 ROM / Termux）。 */
        SYSTEM,

        /** 都没有。 */
        NONE,
    }

    @Volatile
    private var cachedKind: Kind? = null

    fun kind(): Kind {
        cachedKind?.let { return it }
        val detected = when {
            embedded() != null -> Kind.EMBEDDED
            ubuntuPython() != null -> Kind.UBUNTU
            systemPython() != null -> Kind.SYSTEM
            else -> Kind.NONE
        }
        cachedKind = detected
        return detected
    }

    fun isAvailable(): Boolean = kind() != Kind.NONE

    fun displayName(): String = when (kind()) {
        Kind.EMBEDDED -> "内置 Python"
        Kind.UBUNTU -> "Ubuntu Python"
        Kind.SYSTEM -> "系统 Python"
        Kind.NONE -> "未安装"
    }

    /** 版本号，取不到就给空串（UI 上显示成"—"）。 */
    fun version(): String = when (kind()) {
        Kind.EMBEDDED -> runCatching {
            (embedded()?.let { callModule(it, "__main__") }?.let { readGlobal(it, "_v") } ?: "").trim()
        }.getOrDefault("")
        Kind.UBUNTU -> runCatching {
            kotlinx.coroutines.runBlocking {
                UbuntuEnv.run("python3 -V", timeoutSeconds = 30).let { result ->
                    (result.stdout + result.stderr).trim()
                }
            }
        }.getOrDefault("")
        Kind.SYSTEM -> runCatching {
            val out = ProcessBuilder(systemPython()!!, "--version").redirectErrorStream(true).start()
            out.inputStream.bufferedReader().readText().trim()
        }.getOrDefault("")
        Kind.NONE -> ""
    }

    /**
     * 执行一段代码，返回 stdout。
     *
     * 嵌入式路径把 stdout 重定向到内存缓冲 —— 不重定向的话 `print` 会进 logcat，
     * 模型拿到的回执永远是空的，它会以为代码没跑。
     */
    suspend fun run(code: String, cwd: File): ToolOutcome {
        if (code.isBlank()) return ToolOutcome.fail("代码为空")
        return when (kind()) {
            Kind.EMBEDDED -> runEmbedded(code)
            Kind.UBUNTU -> runUbuntu(code, cwd)
            Kind.SYSTEM -> runSystem(code, cwd)
            Kind.NONE -> ToolOutcome.fail(
                "当前没有可用的 Python 运行时。可以改用 linux_shell，或在设置 → 运行环境里装好 Ubuntu —— python3 随 Ubuntu 一起装好。",
            )
        }
    }

    // ── 嵌入式 ────────────────────────────────────────────────

    private fun runEmbedded(code: String): ToolOutcome = runCatching {
        val py = embedded() ?: return ToolOutcome.fail("内置解释器未就绪")
        val main = callModule(py, "__main__")
        // 用一个独立的命名空间跑，避免用户代码里的变量污染 __main__
        callAttr(main, "exec", preamble(code))
        val out = readGlobal(main, "_dsha_out").orEmpty()
        val err = readGlobal(main, "_dsha_err").orEmpty()
        val text = buildString {
            if (out.isNotBlank()) append(out)
            if (err.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append("--- stderr ---\n").append(err)
            }
            if (isEmpty()) append("（无输出）")
        }
        ToolOutcome.ok(text)
    }.getOrElse { ToolOutcome.fail("Python 执行失败：${it.message}") }

    /** 把用户代码包进捕获 stdout/stderr 的壳里。 */
    private fun preamble(code: String): String {
        val escaped = code.replace("\\", "\\\\").replace("\"\"\"", "\\\"\\\"\\\"")
        return buildString {
            appendLine("import io as _io, sys as _sys, traceback as _tb")
            appendLine("_dsha_buf = _io.StringIO()")
            appendLine("_dsha_errbuf = _io.StringIO()")
            appendLine("_dsha_src = \"\"\"$escaped\"\"\"")
            appendLine("_dsha_ns = {'__name__': '__main__'}")
            appendLine("_o, _e = _sys.stdout, _sys.stderr")
            appendLine("_sys.stdout, _sys.stderr = _dsha_buf, _dsha_errbuf")
            appendLine("try:")
            appendLine("    exec(compile(_dsha_src, '<dsha>', 'exec'), _dsha_ns)")
            appendLine("except Exception:")
            appendLine("    _dsha_errbuf.write(_tb.format_exc())")
            appendLine("finally:")
            appendLine("    _sys.stdout, _sys.stderr = _o, _e")
            appendLine("_dsha_out = _dsha_buf.getvalue()")
            appendLine("_dsha_err = _dsha_errbuf.getvalue()")
        }
    }

    private fun embedded(): Any? = runCatching {
        val cls = Class.forName("com.chaquo.python.Python")
        cls.getMethod("getInstance").invoke(null)
    }.getOrNull()

    private fun callModule(python: Any, name: String): Any = python.javaClass
        .getMethod("getModule", String::class.java)
        .invoke(python, name)

    /**
     * 调一次 Python 侧的 `callAttr(name, ...)`。
     *
     * 走反射而不是直接调：Chaquopy 存在与否是构建期决定的，这里不能对它产生
     * 编译期依赖 —— 否则"没接 Chaquopy 的构建"整个编不过，而它只是可选加速。
     */
    private fun callAttr(target: Any, name: String, vararg args: Any?): Any? = runCatching {
        val method = target.javaClass.methods.firstOrNull {
            it.name == "callAttr" && it.parameterTypes.isNotEmpty() &&
                it.parameterTypes[0] == String::class.java
        } ?: return@runCatching null
        method.invoke(target, name, *args)
    }.getOrNull()

    private fun readGlobal(module: Any, name: String): String? = runCatching {
        val obj = module.javaClass.getMethod("get", String::class.java).invoke(module, name)
        obj?.toString()
    }.getOrNull()

    // ── Ubuntu ────────────────────────────────────────────────

    /**
     * 在 Ubuntu 里跑一段代码。
     *
     * 落地成 `/workspace/.dsha/tmp_xxx.py` 再执行，而不是 `python3 -c "..."`：
     * 代码里的引号、换行、中文都不需要转义，出错时行号也是对的。
     * 工作区在 Ubuntu 里映射为 `/workspace`，所以宿主路径要换算一下。
     */
    private suspend fun runUbuntu(code: String, cwd: File): ToolOutcome {
        val root = Workspace.rootOrNull()
            ?: return ToolOutcome.fail("工作区还没绑定，Python 脚本无处落地")
        val relative = runCatching {
            cwd.canonicalFile.toRelativeString(root.canonicalFile).replace('\\', '/').trim('/')
        }.getOrDefault("")
        val dir = File(root, ".dsha").apply { mkdirs() }
        val script = File(dir, "tmp_${System.nanoTime()}.py")
        return runCatching {
            script.writeText(code)
            val guestDir = if (relative.isEmpty()) "/workspace" else "/workspace/$relative"
            val result = UbuntuEnv.run(
                command = "python3 /workspace/.dsha/${script.name}",
                cwd = guestDir,
                timeoutSeconds = 120,
            )
            if (result.exitCode == 0) {
                ToolOutcome.ok(result.stdout.ifBlank { "（无输出）" })
            } else {
                ToolOutcome.fail(
                    buildString {
                        append("exit=").append(result.exitCode).append('\n')
                        if (result.stdout.isNotBlank()) append(result.stdout).append('\n')
                        if (result.stderr.isNotBlank()) append(result.stderr)
                    }.trim(),
                )
            }
        }.getOrElse { ToolOutcome.fail("Python 执行失败：${it.message}") }
            .also { script.delete() }
    }

    /** Ubuntu 根文件系统里是否已经有 python3。比跑一次 proot 便宜得多。 */
    private fun ubuntuPython(): String? {
        // 文件在但环境跑不起来（没测出档位）时不算 —— 否则模型会去用一个必然失败的解释器
        if (!UbuntuEnv.isReady()) return null
        /*
         * 不只认 `/usr/bin/python3`。
         *
         * apt 装出来的是 `/usr/bin/python3`，但也有环境把它放在
         * `/usr/local/bin/python3`（源码编译装的），或者只留了
         * `/usr/bin/python3.10` 这种带版本号的名字。少认一条路径，
         * 表现就是"明明装好了，App 却说没有 Python"。
         */
        val root = UbuntuEnv.rootfs()
        val candidates = listOf(
            "usr/bin/python3",
            "usr/local/bin/python3",
            "usr/bin/python3.10",
            "usr/bin/python3.11",
            "usr/bin/python3.12",
        )
        return candidates
            .map { File(root, it) }
            .firstOrNull { it.isFile }
            ?.absolutePath
    }

    /**
     * 在 Ubuntu 里装解释器本身。
     *
     * 这一步就是"装好 Ubuntu 之后点一下安装 Python"：走 apt，清华源已经在
     * [UbuntuEnv.install] 里配好了。装完 `kind()` 会自己变成 UBUNTU。
     *
     * 实现收到 [PythonInstaller] 去了 —— 那边才是**唯一**一份"怎么装"的代码。
     *
     * 旧版这里的问题很具体：
     *
     * - `apt-get update` 的**结果被丢掉了**，更新失败也照样往下装，
     *   于是报的是 `Unable to locate package python3`（真正的原因在更新那一步）；
     * - 没有 `dpkg --configure -a`，上一次装到一半被打断之后，
     *   **每一次** apt 命令都会先死在 dpkg 上；
     * - 失败被 `runCatching { }` 吞掉，安装界面照样显示"完成"，
     *   而用户到终端里敲 `python3` 才发现根本没有。
     *
     * 现在失败会把原因带回来，界面可以显示出来。
     */
    suspend fun installInterpreter(onLine: (String) -> Unit): Boolean {
        val result = PythonInstaller.install(onLine)
        cachedKind = null
        result.onFailure { error ->
            onLine(error.message ?: "安装 Python 失败")
        }
        return result.isSuccess
    }

    // ── 系统 ──────────────────────────────────────────────────

    private fun runSystem(code: String, cwd: File): ToolOutcome = runCatching {
        val py = systemPython() ?: return ToolOutcome.fail("系统 Python 不可用")
        val dir = File(cwd, ".dsha").apply { mkdirs() }
        val script = File(dir, "tmp_${System.nanoTime()}.py").apply { writeText(code) }
        try {
            val result = kotlinx.coroutines.runBlocking { Shell.run("$py ${script.absolutePath}", cwd, 60) }
            if (result.exitCode == 0) {
                ToolOutcome.ok(result.stdout.ifBlank { "（无输出）" })
            } else {
                ToolOutcome.fail(
                    buildString {
                        append("exit=").append(result.exitCode).append('\n')
                        if (result.stdout.isNotBlank()) append(result.stdout).append('\n')
                        if (result.stderr.isNotBlank()) append(result.stderr)
                    }.trim(),
                )
            }
        } finally {
            script.delete()
        }
    }.getOrElse { ToolOutcome.fail("Python 执行失败：${it.message}") }

    /** 找系统 python3：先看常见绝对路径，再问 `command -v`。 */
    private fun systemPython(): String? {
        val candidates = listOf(
            "/system/bin/python3",
            "/usr/bin/python3",
            "/data/data/com.termux/files/usr/bin/python3",
        )
        candidates.firstOrNull { File(it).canExecute() }?.let { return it }
        return runCatching {
            val process = ProcessBuilder("/system/bin/sh", "-c", "command -v python3")
                .redirectErrorStream(true)
                .start()
            val path = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            path.takeIf { it.startsWith("/") && File(it).canExecute() }
        }.getOrNull()
    }

    /*
     * 这里原来还有"运行期补装包"与 `CommonPackages`（requests / bs4 / lxml …）。
     *
     * 现在界面只装 Ubuntu：要什么库，模型在 Ubuntu 里 `apt install` /
     * `pip install` 就行。App 侧再维护一份"常用包"清单，等于给用户两个
     * 互相打架的包管理入口 —— 且它绕过 apt，装出来的 wheel 和系统包管理器
     * 记录不一致，出问题更难查。
     */
}
