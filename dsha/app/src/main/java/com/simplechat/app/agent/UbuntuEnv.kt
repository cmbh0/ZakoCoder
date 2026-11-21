package com.simplechat.app.agent

import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import com.simplechat.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/**
 * 本机 Ubuntu 环境。
 *
 * 一条 30 MB 的 rootfs 从清华镜像拉下来，解到 App 私有目录，再用 proot 跑起来 ——
 * 于是这台手机上有了一个**真正的** Linux：`apt install gcc` 能装、`pip` 能装、
 * `/bin/bash` 是真的 bash，不是 Android 那个 toybox。
 *
 * 三个绕不开的约束，都写在代码里：
 *
 * 1. **proot 不能放在应用私有目录里执行**。Android 10 起禁止对 home 目录里的文件
 *    `execve`，所以它随 APK 以 `jniLibs/arm64-v8a/libproot.so` 分发，安装时被解到
 *    `nativeLibraryDir` —— 只有那个目录是可执行的。见 build.gradle.kts 里那段说明。
 * 2. **rootfs 里的 /dev 要跳过**。容器里没有 mknod 权限，解压时建不出设备节点；
 *    真正的 /dev 由 proot 从宿主绑进去。
 * 3. **apt 必须换源**。直连 archive.ubuntu.com 在国内经常几十秒超时，而超时之后
 *    apt 留下的是半锁状态，下一条命令会报"另一个进程持有锁"。装完就地写好
 *    清华源的 sources.list，省掉模型自己发现这件事的一轮。
 *
 * ### 挂载与启动参数：整套照 Operit
 *
 * 早期版本只绑 `/dev` 与 `/proc`，理由是"`/sys` 对 apt 没帮助"。这个判断
 * 只对 apt 成立 —— 对用户敲的命令不成立：`lscpu` 读 `/sys`、bash 的
 * `<(cmd)` 读 `/dev/fd`、Python 的 multiprocessing 读 `/dev/shm`。于是出现
 * "大部分命令能用，个别命令莫名用不了"。
 *
 * 现在按 Operit 的 `PRootMountMapping` 补齐 `/sys`、`/dev/pts`、
 * `/dev/fd`、`/dev/std{in,out,err}`、`/dev/shm`、`/data/local/tmp`，
 * 并把它的 `setup_fake_sysdata.sh` 移植成 [UbuntuSysdata]（宿主 `/proc`
 * 条目读不到时绑假数据）。
 *
 * 另外**不再传 `--kill-on-exit`**：Operit 也不传。带上它，`nohup … &`
 * 起后台进程会在 proot 退出时被一并杀掉 —— 不是命令跑不起来，是跑起来又被收了。
 * 诊断改由 p0 那档的 `-v 2` 负责。
 *
 * 启动参数与 Operit / proot-distro 对齐：`-0 -r <rootfs> --link2symlink`、
 * `PROOT_TMP_DIR` / `PROOT_L2S_DIR` / `PROOT_NO_SECCOMP` 全部显式给定，
 * rootfs 布局按 `installed-rootfs/<distro>` 的习惯落在私有目录。
 * 它多出来的是一套自研 launcher（`operit_shell_exec`）和 PTY；我们直接
 * `ProcessBuilder` 起 proot，省掉那层，代价是终端没有真正的 tty —— 对
 * `apt` / `pip` / 编译这类批处理没有影响。
 */
object UbuntuEnv {

    /** 装在这个子目录里：`files/ubuntu/{rootfs,tmp,rootfs.tar.gz}`。 */
    private const val DirName = "ubuntu"

    /**
     * ubuntu-base 的版本。改这里就等于换一个基线，rootfs 要重装。
     *
     * ### 为什么从 24.04 退到 22.04
     *
     * 随包的 proot 是 **5.3.0**（2021 年）。Ubuntu 24.04 带 glibc 2.39，
     * 其启动路径用到 `rseq`，而 proot 5.3 没有处理这个系统调用 ——
     * guest 的 `/usr/bin/env` 一进去就 SIGSEGV，proot 只留一行
     * `vpid 1: terminated with signal 11`，看起来像设备不支持。
     *
     * 22.04（glibc 2.35）是 proot 5.3 长期验证过的组合。等以后换成
     * 5.4+ 的 proot，再把基线抬回去。
     */
    private const val Release = "22.04"
    private const val BaseVersion = "22.04.5"
    private const val Codename = "jammy"

    private val Mirrors = listOf(
        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/$Release/release",
        "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/$Release/release",
        "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/$Release/release",
        "https://cdimage.ubuntu.com/ubuntu-base/releases/$Release/release",
    )

    private const val TunaHost = "https://mirrors.tuna.tsinghua.edu.cn"

    /** apt 用的 http 入口，见 [configure] 里的说明。 */
    private const val TunaHttp = "http://mirrors.tuna.tsinghua.edu.cn"

    /** 解压后的占用。留 1.2 GB 是给 apt 装东西的余量，不是给 rootfs 本身的。 */
    private const val RequiredFreeBytes = 1_200L * 1024 * 1024

    /** 下载超时给足：30 MB 在移动网络下可能要几分钟。 */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // ── 位置 ─────────────────────────────────────────────────

    fun baseDir(): File = File(Workspace.appDir(), DirName)

    fun rootfs(): File = File(baseDir(), "rootfs")

    fun isInstalled(): Boolean = File(rootfs(), "etc/os-release").isFile

    /** rootfs 里 `/etc/os-release` 的 `VERSION_ID`；读不出来返回 null。 */
    private fun installedRelease(): String? = runCatching {
        File(rootfs(), "etc/os-release").readLines()
            .firstOrNull { it.startsWith("VERSION_ID=") }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"')
    }.getOrNull()

    /**
     * 装好了**而且**测出过一套能跑的启动参数。
     *
     * 界面用这个，[isInstalled] 给执行路径用 —— 后者只关心"文件在不在"，
     * 而用户看到的"已安装"必须意味着点开终端真能进去。
     */
    fun isReady(): Boolean = isInstalled() && planFile().isFile

    /** 当前设备的架构。只有 arm64 与 x86_64 两套 proot。 */
    private fun abiTag(): String? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        return when {
            abi.startsWith("arm64") -> "arm64"
            abi.startsWith("x86_64") -> "x86_64"
            else -> null
        }
    }

    /**
     * 一套 proot：可执行文件 + **与它同源**的 loader。
     *
     * loader 是 proot 与 guest 进程之间的握手协议（proot 把自己的 loader 塞成
     * guest 的 ELF 解释器），版本必须与 proot 完全一致。拿 5.1 的 loader 配
     * 5.3 的 proot，proot 会走 `exit(-1)` —— 退出码 255、一个字都不打印。
     * 所以这两个文件名只能成对挑，不能各挑各的。
     */
    private data class ProotBundle(
        val id: String,
        val label: String,
        val binaryName: String,
        val loaderName: String,
    ) {
        fun binary(): File? = inNativeDir(binaryName)
        fun loader(): File? = inNativeDir(loaderName)
        fun ready(): Boolean = binary() != null && loader() != null

        private fun inNativeDir(name: String): File? {
            val dir = Workspace.appContextOrNull()?.applicationInfo?.nativeLibraryDir ?: return null
            return File(dir, name).takeIf { it.isFile }
        }
    }

    /**
     * 随包的 proot，**按可靠性排序**。
     *
     * - `termux`：termux/proot 的 Android 移植（zakocoder 用的就是它，提交
     *   `b27d81a`）。它把 Android 上最要命的那几处都补了：`link2symlink` 的
     *   代理目录、`fake_id0` 的 `chown` 语义、`openat` 在 `O_PATH`/`O_TMPFILE`
     *   下的路径翻译，以及 `seccomp` 加速表与各版内核白名单的差异。
     *   **首选就是它** —— 老 5.3.0 在部分设备上让 apt 读 `/var/lib/dpkg/status`
     *   直接拿到 `ENOSYS`（`Function not implemented`），换到这一套后
     *   `open`/`openat` 不再落到直通内核那条路上。
     * - `bionic`：Android NDK 构建的 5.3.0，PIE + bionic，解释器
     *   `/system/bin/linker64`。和系统库同源，在旧设备上仍然稳。
     * - `static`：上游静态构建，glibc 直接打 syscall。在部分 ROM 上会卡在
     *   `ptrace` 或 loader 注入这一步静默退出，留作最后后备。
     *
     * 三套的失败点完全不同（Android 兼容层 / 动态链接层 / 内核接口层），
     * 一起带上不是"多试几次碰运气"，而是三条独立的可用路径。
     */
    private val Bundles = listOf(
        ProotBundle("termux", "Termux 移植构建", "libproottermux.so", "libproottermuxloader.so"),
        ProotBundle("bionic", "Android 原生构建", "libproot5.so", "libproot5loader.so"),
        ProotBundle("static", "静态构建", "libproot.so", "libprootloader.so"),
    )

    /** 包里真的带齐了的那几套，顺序即优先级。 */
    private fun usableBundles(): List<ProotBundle> = Bundles.filter { it.ready() }

    /** 这套设备的默认 proot（第一套带齐的），UI 判定"能不能装"用它。 */
    fun supported(): Boolean = abiTag() != null && usableBundles().isNotEmpty()

    /** 装不了的原因，给界面直接用。 */
    fun unsupportedReason(): String? = when {
        abiTag() == null -> "当前设备架构（${Build.SUPPORTED_ABIS.firstOrNull() ?: "未知"}）没有随包的 proot，只能使用云端工作区"
        usableBundles().isEmpty() -> "安装包里缺少 proot（jniLibs 没打进去）"
        else -> null
    }

    /** `PRETTY_NAME` 那一行，比如 `Ubuntu 24.04.3 LTS`。 */
    fun versionLabel(): String? {
        val file = File(rootfs(), "etc/os-release")
        if (!file.isFile) return null
        return runCatching {
            file.readLines().firstOrNull { it.startsWith("PRETTY_NAME=") }
                ?.substringAfter('=')
                ?.trim()
                ?.trim('"')
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    // ── 安装 / 卸载 ───────────────────────────────────────────

    /**
     * 下载 + 解压 + 配置。
     *
     * [onProgress] 的第二个参数是"现在在干什么"，直接显示给用户 —— 这一步要跑
     * 一两分钟，只给一个百分比数字，人会以为卡住了。
     */
    suspend fun install(
        force: Boolean = false,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val reason = unsupportedReason()
                if (reason != null) throw IllegalStateException(reason)

                /*
                 * 什么算"已经装好了"：rootfs 在**且**有一份可用的启动档位。
                 *
                 * 只认 rootfs 的话，上一个版本装出来的环境（当时没有档位文件）
                 * 会永远停在"看起来装了、点开就报错"的状态 —— 用户只能卸载重装，
                 * 而界面上的按钮叫「重新安装」，点下去什么都不做。
                 */
                var extracted = isInstalled()
                /*
                 * 基线换过（24.04 → 22.04）时必须清掉旧 rootfs。
                 *
                 * 只改常量而不清盘，老用户会停在"已安装"状态：`isInstalled()`
                 * 为真、plan 文件也在，install 直接返回，于是继续拿 24.04 的
                 * glibc 2.39 去喂 proot 5.3 —— 每次都是 signal 11，
                 * 而界面上没有任何入口能让他换掉它。
                 */
                if (extracted) {
                    val installed = installedRelease()
                    if (installed != null && installed != Release) {
                        onProgress(0.005f, "清理旧基线 Ubuntu $installed…")
                        uninstall()
                        extracted = false
                    }
                }
                if (extracted && planFile().isFile && !force) return@runCatching

                val base = baseDir().apply { mkdirs() }
                if (!extracted) {
                    val free = runCatching { StatFs(base.absolutePath).availableBytes }.getOrDefault(0L)
                    if (free in 1 until RequiredFreeBytes) {
                        throw IllegalStateException(
                            "存储空间不足：需要约 ${RequiredFreeBytes / 1024 / 1024} MB，" +
                                "当前可用 ${free / 1024 / 1024} MB",
                        )
                    }

                    val archive = File(base, "rootfs.tar.gz")
                    onProgress(0.01f, "准备下载…")
                    download(archive, onProgress)

                    onProgress(0.55f, "解开文件系统…")
                    val staging = File(base, "rootfs.new")
                    staging.deleteRecursively()
                    staging.mkdirs()
                    extract(archive, staging) { fraction ->
                        onProgress(0.55f + fraction * 0.35f, "解开文件系统…")
                    }

                    onProgress(0.92f, "写入镜像源…")
                    configure(staging)
                    // 假 /proc 数据：宿主对应条目读不到时兜底（见 UbuntuSysdata）
                    UbuntuSysdata.ensure(staging)

                    // 最后一步才换名：中途失败时，旧目录（可能不存在）不被破坏，
                    // 用户看到的是"没装上"，而不是"装了一半的坏系统"
                    val target = rootfs()
                    target.deleteRecursively()
                    if (!staging.renameTo(target)) {
                        throw IOException("无法把解压结果移动到 ${target.name}")
                    }
                    archive.delete()
                }

                /*
                 * 装完立刻**自检**，而且是**逐档试**。
                 *
                 * 同一份参数在 A 机上能跑、在 B 机上 execve 就 EACCES —— 各家
                 * ROM 的内核对 ptrace / seccomp / 硬链接的态度不一样。与其赌
                 * 一套参数，不如把几种已知能work的组合挨个试一遍，把第一个
                 * 真正跑通的记进 `launch.plan`，之后所有命令都走它。
                 *
                 * 一档都跑不通时才报错，并把**每一档的原始 stderr**带回去 ——
                 * 这条信息是用户下次反馈时最有价值的东西，不该被"启动失败"
                 * 四个字吞掉。
                 */
                onProgress(0.97f, "自检运行环境…")
                val launch = probePlans()
                if (launch == null) {
                    val detail = probeErrors.joinToString("\n") { (id, message) ->
                        "[$id] " + message.ifBlank { "无输出" }
                    }.take(1_000)
                    /*
                     * 结论必须**分三种**，因为三种的下一步完全相反：
                     * - 二进制跑不起来 → 打错包了，重装无用
                     * - p0 就挂        → 设备拦 ptrace，参数救不回来，只能换路
                     * - 某一项 bind 挂 → 是文件系统/权限，重装或去掉那项能好
                     *
                     * 旧版把三种混成一句"这台设备不允许执行随包的 proot"，
                     * 于是明明只是 `/storage/emulated/0` 绑不上的用户，
                     * 也被劝去用云端了。
                     */
                    val (headline, advice) = when {
                        probeErrors.any { it.first.endsWith(":self") } ->
                            "proot 二进制在本机跑不起来" to
                                "这不是 rootfs 的问题，重装没有用。请把下面这段反馈给开发者；" +
                                "期间可以用「云端环境 → SSH」，能力是一样的。"
                        probeErrors.any { it.second.contains("signal 11") } ->
                            "guest 进程在 proot 下崩溃了（SIGSEGV）" to
                                "这是 proot 与 rootfs 里 glibc 版本不兼容的典型症状 —— " +
                                "不是你的设备有问题。先点「删除」再装一次（会重新下载 rootfs）；" +
                                "如果仍然是 signal 11，把下面这段反馈给开发者。"
                        else ->
                            "这台设备上 proot 起不来" to
                                "已经从最完整的 p8 一路降到 p0（裸跑）全部试过，" +
                                "每一条的「加开什么」就是那一档比上一档多的东西。" +
                                "看 p0 那条：它没有输出，说明内核/SELinux 在 ptrace 或 " +
                                "loader 注入这一层就拦住了，换参数救不回来；" +
                                "它有输出而 p3 仍失败，说明 guest 的 /bin/sh 起不来。" +
                                "两种情况都请把下面这段反馈给开发者；" +
                                "期间可以用「云端环境 → SSH」，能力是一样的。"
                    }
                    throw IllegalStateException("$headline。\n$advice\n$detail")
                }
                planFile().writeText(launch.tag)

                /*
                 * 顺手把 Python 装上。
                 *
                 * `ubuntu-base` 里连 python3 都没有 —— 用户装完环境、点开终端
                 * 敲 `python3`，得到的是 command not found，然后以为环境坏了。
                 *
                 * 走的是 [PythonBundle] 那条**不碰 dpkg** 的路（下载预编译包），
                 * 所以 apt 在设备上坏掉时，这一步照样能成。
                 *
                 * 失败**不阻断**安装：环境本身仍然是好的；但原因要留下来，
                 * 否则用户只会看到"完成"，然后在终端里发现 python3 不存在。
                 */
                onProgress(0.97f, "安装 Python…")
                val python = PythonInstaller.install { line ->
                    /*
                     * 把内部进度原样透上去。
                     *
                     * 从前这里是 `install { }` —— 回调被丢掉，界面就停在
                     * "安装 Python…" 几分钟不动（26 MB 在下载），用户以为
                     * 卡死，强制关掉，然后在 rootfs 里留下半套。
                     */
                    onProgress(0.97f, line)
                }
                python.exceptionOrNull()?.let { error ->
                    AppLog.w("UbuntuEnv", "Python 安装失败：${error.message}")
                }

                onProgress(1f, "完成")
                Unit
            }
        }

    suspend fun uninstall(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            rootfs().deleteRecursively()
            File(baseDir(), "tmp").deleteRecursively()
            File(baseDir(), "links").deleteRecursively()
            // 档位是这套 rootfs 测出来的，环境没了它也必须一起没
            planFile().delete()
            Unit
        }
    }

    /**
     * 确保 guest 里有能用的 pip。
     *
     * `ubuntu-base` 是**最小根文件系统**，里面连 python3 都没有，更不会有 pip。
     * 之前 `install_packages(kind=pip)` 直接执行 `python3 -m pip install`，报的是
     * `No module named pip` —— 模型看到这句话会以为"包名写错了"，再试两轮。
     *
     * 实现已经收到 [PythonInstaller] 去了：那是**唯一**一份"怎么装"的代码。
     * 之前这里和 `PyRuntime.installInterpreter` 各写了一份，两边行为不一样
     * （这一份会恢复 dpkg、那一份不会），于是"装 Ubuntu 时顺带装 Python"
     * 走的是**不恢复 dpkg** 的那一份 —— 上一次装到一半被打断之后，
     * 装 Ubuntu 界面照样显示"完成"，而 Python 根本没装上。
     */
    suspend fun ensurePip(onLine: (String) -> Unit = {}): Boolean {
        if (!isInstalled()) return false
        if (run("python3 -m pip --version", timeoutSeconds = 40).exitCode == 0) return true
        return PythonInstaller.install(onLine).isSuccess
    }

    /**
     * 修复环境。
     *
     * 用户遇到"装不上东西""跑不起来"时最需要的一键动作，依次做四件事：
     *
     * 1. 补上 apt 的 sandbox 配置（老环境可能没有，`apt update` 会因
     *    setresuid 直接失败）；
     * 2. 补上 Android 的 gid（否则每条命令都顶着一条 group 告警）；
     * 3. 把中断的 dpkg 状态收拾干净；
     * 4. 重新探一遍启动档位（系统升级、SELinux 策略变化会让旧档位失效）。
     */
    /**
     * 让 apt / dpkg 在这台设备上真的可用（幂等，装包前必跑）。
     *
     * 三件事，缺任何一件都可能让 apt 在 proot 下报一串与真实原因无关的错：
     *
     * 1. `APT::Sandbox::User "root"` —— 不写的话 apt 会 setuid 到 `_apt`，
     *    proot 的 setuid 支持不完整，失败后 apt 报「打不开 /var/lib/dpkg/status」；
     * 2. 补齐 `/var/lib/dpkg` 的目录结构与 status/available；
     * 3. 把 status 换成**新 inode**：rootfs 解压时 `--link2symlink` 可能把它
     *    留成一条指向不存在目标的链接，之后每次 open 都失败。
     */
    suspend fun prepareApt(onLine: (String) -> Unit = {}) {
        val root = rootfs()
        writeAptSandbox(root)
        writeAndroidGroups(root)
        onLine("整理 dpkg 数据库…")
        /*
         * **这一段在宿主侧做，不进 proot。**
         *
         * 从前是一段 guest 里的 `cp -f … && mv -f …`：本意是"把 status
         * 换成新 inode"，可它自己就跑在可能坏掉的那条 syscall 通道上 ——
         * status 打不开的时候，这条命令一样打不开，尾巴上的 `true` 又把
         * 退出码抹平了，于是"整理过了"纯属自我安慰。
         *
         * 现在用 java.io 直接读写 rootfs：既不经过 proot，也不经过 shell。
         */
        rebuildDpkgDatabase(root)
    }

    /**
     * 在宿主侧重写 dpkg 的 status / available。
     *
     * 三件事：
     *
     * 1. 补齐 `/var/lib/dpkg` 的几个必需子目录（apt 会直接进去找）；
     * 2. status 为空时从 `status-old` 恢复，两个都空就留空文件；
     * 3. 把 status 换成**全新的 inode**：先删再写。
     *
     * 第 3 条是重点。rootfs 解压时 `--link2symlink` 可能把 status 留成
     * 一条指向 proot 代理目录的软链；代理目录一没（用户清了缓存、换了一套
     * proot、或者 `PROOT_L2S_DIR` 变了），链接就断，之后**每次打开都失败**，
     * 而 apt 只说一句「Could not open file … /status」，看不出是链接断了。
     *
     * 断链在 `File.exists()` 眼里等于"不存在"，所以删之前要单独认一遍
     * `Files.isSymbolicLink`，否则 `writeText` 会顺着断链接写到虚空中。
     */
    private fun rebuildDpkgDatabase(root: File) {
        val dir = File(root, "var/lib/dpkg")
        listOf("info", "updates", "triggers", "tmp.ci").forEach { File(dir, it).mkdirs() }

        val status = File(dir, "status")
        val statusOld = File(dir, "status-old")
        val existing = runCatching { if (status.isFile) status.readText() else "" }
            .getOrDefault("")
        val body = existing.ifBlank {
            runCatching { if (statusOld.isFile) statusOld.readText() else "" }.getOrDefault("")
        }

        val isLink = runCatching { java.nio.file.Files.isSymbolicLink(status.toPath()) }
            .getOrDefault(false)
        runCatching {
            if (status.exists() || isLink) status.delete()
            status.writeText(body)
        }.onFailure { AppLog.w("UbuntuEnv", "重建 dpkg status 失败", it) }

        val available = File(dir, "available")
        runCatching {
            if (!available.isFile || available.length() == 0L) available.writeText("")
        }
    }

    suspend fun repairEnvironment(onLine: (String) -> Unit = {}): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!isInstalled()) throw IllegalStateException("还没装 Ubuntu 环境。")
                onLine("补齐 apt 配置…")
                writeAptSandbox(rootfs())
                writeAndroidGroups(rootfs())
                onLine("收拾 dpkg 状态…")
                prepareApt(onLine)
                run("dpkg --configure -a 2>&1 | tail -n 10", timeoutSeconds = 240)
                onLine("重新探测启动档位…")
                val plan = probePlans()
                    ?: throw IllegalStateException(
                        "这台设备上 proot 起不来：\n" + probeSummary(),
                    )
                planFile().writeText(plan.tag)
                onLine("环境已恢复。")
                "环境已恢复（启动档位 ${plan.tag}）。"
            }
        }

    private suspend fun download(archive: File, onProgress: (Float, String) -> Unit) {
        val abi = abiTag() ?: throw IllegalStateException("架构不支持")
        /*
         * **Ubuntu 的架构名不叫 x86_64，叫 amd64。**
         *
         * `ubuntu-base-24.04.3-base-amd64.tar.gz` 才是官方文件名；拼成
         * `...-x86_64.tar.gz` 在四个镜像上全是 404。arm64 设备不受影响，
         * 但 x86_64 平板 / 模拟器上会表现为「下载失败」——而 proot 明明是好的。
         */
        val arch = if (abi == "x86_64") "amd64" else abi
        val fileName = "ubuntu-base-$BaseVersion-base-$arch.tar.gz"
        var lastError: Exception? = null

        for (mirror in Mirrors) {
            val url = "$mirror/$fileName"
            var temp: File? = null
            try {
                val request = Request.Builder().url(url).header("User-Agent", "DSHA/UbuntuEnv").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body
                    val total = body.contentLength()
                    if (total in 1 until 10L * 1024 * 1024) {
                        throw IOException("文件只有 $total 字节，不像 rootfs")
                    }
                    val dest = File(archive.absolutePath + ".part")
                    temp = dest
                    var written = 0L
                    var lastReport = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(dest).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                written += read
                                val now = SystemClock.uptimeMillis()
                                if (now - lastReport > 160) {
                                    lastReport = now
                                    val fraction = if (total > 0) {
                                        written.toFloat() / total
                                    } else {
                                        0f
                                    }
                                    onProgress(
                                        0.02f + fraction * 0.52f,
                                        "下载 Ubuntu 镜像 ${written / 1024 / 1024} / ${total / 1024 / 1024} MB",
                                    )
                                }
                            }
                            output.flush()
                        }
                    }
                    if (written < 20L * 1024 * 1024) throw IOException("下载不完整（$written 字节）")
                }
                if (archive.exists()) archive.delete()
                check(temp!!.renameTo(archive)) { "无法重命名下载文件" }
                return
            } catch (error: Exception) {
                lastError = error
                temp?.delete()
                // 一个镜像不通就换下一个，不弹错 —— 换源是这里最正常不过的事
            }
        }
        throw IOException(
            "下载失败：${lastError?.message ?: "所有镜像都不可达"}。" +
                "检查网络，或稍后再试（镜像偶尔会限速）。",
        )
    }

    /**
     * 解 tar.gz。
     *
     * 用 commons-compress 而不是自己切：rootfs 里有 GNU 长文件名、PAX 扩展头、
     * 大量符号链接（`/bin` 在 24.04 里就是指向 `usr/bin` 的软链）。自己实现
     * 漏掉任何一种，装出来的系统都起不来，而报错会出现在很远的地方。
     */
    internal fun extract(archive: File, target: File, onProgress: (Float) -> Unit) {
        val base = target.canonicalFile
        val root = base.path + File.separator
        val totalBytes = archive.length().coerceAtLeast(1L)
        var lastReport = 0L

        FileInputStream(archive).use { raw ->
            CountingStream(BufferedInputStream(raw, 128 * 1024)).use { counted ->
                GzipCompressorInputStream(counted).use { gzip ->
                    TarArchiveInputStream(gzip).use { tar ->
                        while (true) {
                            val entry = tar.nextEntry ?: break
                            // tar 里常见 `./` 前缀，先剥掉再判，否则 ./dev 会漏过
                            val name = entry.name.trimStart('/').trimEnd('/').removePrefix("./")
                            if (name.isEmpty()) continue
                            // /dev 由 proot 从宿主绑进来；容器里也建不出设备节点
                            if (name == "dev" || name.startsWith("dev/")) continue

                            val destination = File(base, name)
                            val path = destination.path
                            if (!path.startsWith(root) && path != base.path) {
                                throw SecurityException("压缩包含非法路径：${entry.name}")
                            }

                            when {
                                entry.isDirectory -> {
                                    destination.mkdirs()
                                    chmod(destination, entry.mode)
                                }
                                entry.isSymbolicLink -> {
                                    destination.parentFile?.mkdirs()
                                    destination.delete()
                                    /*
                                     * **绝对链接改写成相对链接。**
                                     *
                                     * rootfs 里 `lib64/ld-linux-aarch64.so.1` 这类链接写的是
                                     * `/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1`。在 proot 里
                                     * 绝对路径会被翻译回 guest 根，本来也能用；但只要有任何一段
                                     * 代码（包括我们自己的校验）在**宿主侧** stat 它，就会解析到
                                     * Android 的 `/lib` —— 那里没有这个文件，于是"明明解压成功了
                                     * 却找不到动态链接器"。
                                     *
                                     * 改成相对路径之后，两边解析结果一致。
                                     */
                                    val linkName = if (entry.linkName.startsWith("/")) {
                                        runCatching {
                                            File(base, entry.linkName.trimStart('/'))
                                                .relativeTo(destination.parentFile ?: base)
                                                .path
                                        }.getOrDefault(entry.linkName)
                                    } else {
                                        entry.linkName
                                    }
                                    val ok = runCatching {
                                        Os.symlink(linkName, destination.absolutePath)
                                    }.isSuccess
                                    if (!ok) {
                                        /*
                                         * 顶层那几个链接（bin / lib / lib64 / sbin）丢了，
                                         * 整个系统就是废的 —— 必须当场失败，而不是等到
                                         * 启动时报一句看不懂的 ENOENT。
                                         */
                                        val relative = destination.relativeTo(base).path
                                        if (relative.count { it == File.separatorChar } == 0) {
                                            throw IOException("无法创建顶层符号链接 $relative → $linkName")
                                        }
                                    }
                                }
                                entry.isLink -> {
                                    // 硬链接：目标通常在同一次解压里已经落地
                                    val source = File(base, entry.linkName.trimStart('/'))
                                    destination.parentFile?.mkdirs()
                                    destination.delete()
                                    val ok = runCatching { Os.link(source.absolutePath, destination.absolutePath) }
                                        .isSuccess
                                    if (!ok && source.isFile) source.copyTo(destination, overwrite = true)
                                }
                                entry.isFile -> {
                                    destination.parentFile?.mkdirs()
                                    FileOutputStream(destination).use { output ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            val read = tar.read(buffer)
                                            if (read <= 0) break
                                            output.write(buffer, 0, read)
                                        }
                                    }
                                    chmod(destination, entry.mode)
                                }
                                // 字符设备 / FIFO / socket：跳过，proot 下用不到
                                else -> Unit
                            }

                            val now = SystemClock.uptimeMillis()
                            if (now - lastReport > 200) {
                                lastReport = now
                                onProgress((counted.count.toFloat() / totalBytes).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
        }
        /*
         * 解压结果自检。
         *
         * 这里验的是"启动一条命令所必需的东西"，而不是抽查一两个文件：
         * - `/bin/sh` 走两层软链（bin → usr/bin，sh → dash），少任何一层都起不来；
         * - `/usr/bin/env` 是启动参数的第一段（`env -i ...` 靠它清环境）；
         * - `/etc/os-release` 是 [isInstalled] 与版本显示的依据。
         *
         * 早先只验了第一个且用 `||` 短路，`bin` 链接坏掉时会被 `usr/bin/sh`
         * 掩盖过去 —— 装完显示成功，一跑就 execve ENOENT。
         */
        val required = listOf("bin/sh", "usr/bin/env", "usr/bin/apt-get", "etc/os-release")
        val missing = required.filter { !File(base, it).exists() }
        if (missing.isNotEmpty()) {
            throw IOException(
                "解压结果不完整（缺少 ${missing.joinToString("、")}），" +
                    "请重试，或换一个镜像。",
            )
        }
        onProgress(1f)
    }

    private fun chmod(file: File, mode: Int) {
        if (mode == 0) return
        runCatching { Os.chmod(file.absolutePath, mode and 0x1FF) }
    }

    /** 装完立刻要写的几份配置。都在 rootfs 里，proot 起来后就是 `/etc/...`。 */
    private fun configure(rootfs: File) {
        val etc = File(rootfs, "etc").apply { mkdirs() }

        // DNS：容器里没有 systemd-resolved，直接钉两个国内公共 DNS
        File(etc, "resolv.conf").writeText(
            """
            nameserver 223.5.5.5
            nameserver 119.29.29.29
            """.trimIndent() + "\n",
        )
        File(etc, "hostname").writeText("dsha\n")

        val abi = abiTag()
        // arm64 在 Ubuntu 里属于 ports，源路径与 x86 不同 —— 写错就是"一个包都装不上"
        /*
         * 用 **http** 而不是 https。
         *
         * ubuntu-base 里不带 ca-certificates，https 源会全线报
         * "Certificate verification failed"，而 apt 只会 W: 警告然后继续 ——
         * 表现为"源是通的但什么都装不上"，极难排查。实测（amd64 rootfs + 清华源）
         * http 秒过，装完 ca-certificates 之后 https 也就通了。
         */
        val repo = if (abi == "arm64") "$TunaHttp/ubuntu-ports/" else "$TunaHttp/ubuntu/"
        File(etc, "apt/sources.list").apply { parentFile?.mkdirs() }.writeText(
            buildString {
                appendLine("deb $repo $Codename main restricted universe multiverse")
                appendLine("deb $repo $Codename-updates main restricted universe multiverse")
                appendLine("deb $repo $Codename-backports main restricted universe multiverse")
                appendLine("deb $repo $Codename-security main restricted universe multiverse")
            },
        )
        // 24.04 的 base 镜像可能带 deb822 格式的 .sources，它会盖掉 sources.list
        File(etc, "apt/sources.list.d").listFiles()?.forEach { file ->
            if (file.name.endsWith(".sources") || file.name.endsWith(".list")) file.delete()
        }

        /*
         * apt 的几项超时与重试。
         *
         * 移动网络下镜像偶尔抽一下：`apt-get` 默认只试一次，失败后留下一个
         * 半配置的 dpkg 状态，下一条命令就报"另一个进程持有锁" —— 用户看到的是
         * "装什么都失败"，而真正的原因只是某一次下载断了。重试三次、
         * 超时压到 30 秒，能让绝大多数抖动自己过去。
         */
        File(etc, "apt/apt.conf.d/99dsha-network").apply { parentFile?.mkdirs() }.writeText(
            """
            Acquire::Retries "3";
            Acquire::http::Timeout "30";
            Acquire::https::Timeout "30";
            Acquire::ForceIPv4 "true";
            """.trimIndent() + "\n",
        )

        /*
         * 关掉 apt 的 sandbox 降权。
         *
         * apt 的 http/https method 以 root 启动后会 `setresuid` 到 `_apt`。
         * proot 只能伪造 uid 0，真实的身份切换一律 EPERM，于是 `apt update`
         * 报 `Failed to set new user ids - setresuid (1: Operation not permitted)`，
         * 接着 method 死掉、整条命令失败。下载仍以 root 跑，只是不再降权 ——
         * Termux 的 proot-distro 也是这么处理的。
         */
        writeAptSandbox(rootfs)

        /*
         * pip 的默认源。
         *
         * 直连 pypi.org 在国内基本是"读超时"，而 pip 的报错是一大段
         * Retrying…，用户会以为装不上是环境坏了。写进 /etc/pip.conf 之后，
         * 模型**不需要**在每条命令里带 `-i`，也就不存在"忘了带"这件事。
         */
        File(etc, "pip.conf").writeText(
            """
            [global]
            index-url = https://pypi.tuna.tsinghua.edu.cn/simple
            trusted-host = pypi.tuna.tsinghua.edu.cn
            timeout = 60
            disable-pip-version-check = true
            """.trimIndent() + "\n",
        )

        /*
         * npm 源。Node 在 Ubuntu 里是最常用的一类安装（`npx`、`vite`、`pnpm`），
         * 而 npm 默认源国内经常卡在 "idealTree:buildDeps"。与 pip 一样写进
         * rootfs，模型不必在每条命令后面自己带 `--registry`。
         */
        File(rootfs, "root/.npmrc").apply { parentFile?.mkdirs() }.writeText(
            "registry=https://registry.npmmirror.com\n",
        )

        File(rootfs, "root").mkdirs()
        File(rootfs, "tmp").apply { mkdirs(); chmod(this, 0x1FF) }
        File(rootfs, "workspace").mkdirs()
    }

    /**
     * 写 apt 的 sandbox 配置（幂等）。
     *
     * 安装流程里调一次；每次执行命令前也会检查 —— 2.2.3 之前装好的环境
     * 不会重跑安装，只能在这里把缺的配置补上。
     */
    private fun writeAptSandbox(rootfs: File) {
        val file = File(rootfs, "etc/apt/apt.conf.d/99dsha-sandbox")
        val body = "APT::Sandbox::User \"root\";\n"
        runCatching {
            /*
             * 判据从「文件在不在」改成「内容对不对」。
             *
             * 老版本装出来的环境里这个文件可能**不存在** —— 那时只有
             * `configure` 会写它，而运行期装包从不补。apt 于是以 `_apt`
             * 身份跑，proot 对 setuid 的处理不完整，失败会被 apt 报成
             * 「打不开 /var/lib/dpkg/status」，跟真正的原因隔了十万八千里。
             */
            if (file.isFile && file.readText() == body) return
            file.parentFile?.mkdirs()
            file.writeText(body)
        }
    }

    /**
     * 把当前进程的 Android gid 补进 rootfs 的 `/etc/group`（幂等）。
     *
     * gid 从 `/proc/self/status` 的 `Groups:` 行读 —— 不引 `Os` 或
     * `Process` 的额外 API，一行文本最稳。补不进去也不影响执行，所以
     * 整段吞掉异常。
     */
    private fun writeAndroidGroups(rootfs: File) {
        runCatching {
            val gids = File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("Groups:") }
                    ?.removePrefix("Groups:")?.trim()
                    ?.split(' ', '\t')?.mapNotNull { it.toIntOrNull() }
                    .orEmpty()
            }
            if (gids.isEmpty()) return
            val file = File(rootfs, "etc/group")
            if (!file.isFile) return
            val text = file.readText()
            val missing = gids.filter { gid -> !text.contains(":$gid:") }
            if (missing.isEmpty()) return
            file.appendText(
                buildString {
                    missing.forEach { gid -> appendLine("android_group_$gid:x:$gid:") }
                },
            )
        }
    }

    // ── 执行 ─────────────────────────────────────────────────

    /**
     * 在 Ubuntu 里跑一条命令。
     *
     * [cwd] 是**工作区相对路径**，映射到 guest 里的 `/workspace/<cwd>`；
     * 不给就落在 `/root`。这样模型写 `cd /workspace/src` 与写相对路径都能对上。
     */
    suspend fun run(
        command: String,
        cwd: String? = null,
        timeoutSeconds: Long = 120,
    ): Shell.Result = withContext(Dispatchers.IO) {
        val launch = activeLaunch()
        val result = exec(launch.bundle, launch.plan, command, cwd, timeoutSeconds)
        if (!looksLikeLaunchFailure(result)) return@withContext result

        /*
         * **启动失败要自愈，不要直接把一句"失败"丢给模型。**
         *
         * 装的时候测出来的那一档不是终身有效：系统升级、SELinux 策略变化、
         * rootfs 被清理过一半，都会让同一套参数突然跑不起来。早先这种情况下
         * 每一次 `linux_shell` 都失败，模型只能反复重试同一条命令。
         *
         * 现在重新逐档探测一次，找到能跑的就记住并重发这条命令；
         * 连探测都过不了，把每一档的真实原因一起带回去 —— 用户拿着这段
         * 输出来问，才有的说。
         */
        val recovered = probePlans()
        if (recovered != null) {
            runCatching { planFile().writeText(recovered.tag) }
            return@withContext exec(recovered.bundle, recovered.plan, command, cwd, timeoutSeconds)
        }
        result.copy(stderr = (result.stderr + "\n" + probeSummary()).trim())
    }

    /**
     * 这次失败像不像"proot 根本没把命令跑起来"。
     *
     * 判据刻意收得很严：**没有任何标准输出**，并且退出码是 255 或 stderr 里
     * 有 proot/execve 的字样。普通的命令失败（apt 返回 100、脚本自己 exit 1）
     * 绝不能触发重探测 —— 那是任务本身的结果，重跑一遍只会把副作用做两次。
     */
    private fun looksLikeLaunchFailure(result: Shell.Result): Boolean {
        if (result.stdout.isNotBlank()) return false
        val err = result.stderr.lowercase()
        return result.exitCode == 255 ||
            "proot" in err || "execve" in err || "permission denied" in err
    }

    /** 把逐档探测的失败原因拼成一段可读的诊断。 */
    private fun probeSummary(): String {
        if (probeErrors.isEmpty()) return "（没有探测记录）"
        return buildString {
            append("已自动重试全部启动档位，均失败：")
            probeErrors.forEach { (id, reason) ->
                append("\n[").append(id).append("] ").append(reason)
            }
        }
    }

    // ── 启动档位 ─────────────────────────────────────────────

    /**
     * 一套启动参数。
     *
     * 为什么要"几套"而不是一套：proot 压在 Android 内核上的东西太多 ——
     * `ptrace` 能不能用、`seccomp` 拦不拦、私有目录能不能建硬链接、
     * 符号链接能不能建，各家 ROM 的答案都不一样。同一份参数在 A 机上跑得飞起，
     * 在 B 机上就 `execve(/bin/bash): Permission denied`。
     *
     * 与其赌，不如**装完挨个试一遍**，把真正跑通的那套记下来（见 [probePlans]）。
     * 用户看到的只有"装好了"，不会看到我们试了几次。
     */
    private data class LaunchPlan(
        val id: String,
        /** 关掉 proot 的 seccomp 加速。某些内核上开着它反而 EPERM。 */
        val noSeccomp: Boolean,
        /** 用 link2symlink 把硬链接降级成软链 —— apt/dpkg 大量依赖硬链接。 */
        val link2Symlink: Boolean,
        /** 用哪个 shell 起第一条命令。bash 更完整，dash 更轻。 */
        val shell: String,
        /**
         * 下面这几个 `bind*` 决定这一档绑哪些目录。
         *
         * 探测表是**从一个变量都不开的裸跑开始，每次只加一项** —— 因为
         * 「八套全功能组合」失败时，八条报错长得一模一样，谁也说不出是
         * `-b /dev` 还是 `-b /storage/emulated/0:/sdcard` 把 proot 弄死的。
         * 逐项加回去，第一个挂掉的档位就是答案。
         */
        val bindDev: Boolean = true,
        val bindProc: Boolean = true,
        val bindTmp: Boolean = true,
        val bindWorkspace: Boolean = true,
        val bindSdcard: Boolean = true,
        /** `-b /sys`：`lscpu`、`df`、`systemd-detect-virt` 这类命令要读 sysfs。 */
        val bindSys: Boolean = false,
        /**
         * `/dev/pts` 与 `/dev/fd`、`/dev/std{in,out,err}`。
         *
         * bash 的进程替换 `<(cmd)`、`&&` 链里的 `/dev/fd/63`、以及 `tee`、
         * `xargs -o` 都走这几条路径。只绑 `/dev` 不带它们时，`/dev/fd` 是
         * rootfs 里一个空目录 —— 表现为"这条命令在这台机器上用不了"。
         */
        val bindDevFd: Boolean = false,
        /** 把 App 的 tmp 挂到 `/dev/shm`：Python 的 multiprocessing、Chromium 都要它。 */
        val bindShm: Boolean = false,
        /** `/data/local/tmp`：ADB、脚本、以及不少工具的临时落点。 */
        val bindLocalTmp: Boolean = false,
        /** 用 `-lc`（login shell）而不是 `-c`：让 `/etc/profile` 的 PATH 与 locale 生效。 */
        val loginShell: Boolean = false,
        /** `-0`：把 guest 里的 uid 映射成 root。apt 装包要它。 */
        val rootId: Boolean = true,
        /**
         * proot 的 `-v <n>` 日志级别。0 = 不打。
         *
         * 这才是"255 无输出"的正解：proot 默认只在 `-v` 打开时才把
         * `execve` / `ptrace` / loader 注入的每一步打出来，关掉
         * `--kill-on-exit` 只能让它在**报错路径**上说话，说不了"卡在哪"。
         */
        val verbose: Int = 0,
    )

    /**
     * 从"最完整"到"最保守"排列。
     *
     * 顺序有意义：前面几档功能齐全（apt 能装、pip 能装），最后两档去掉
     * link2symlink 只求能跑 —— 装包可能失败，但 `python3 x.py` 这种跑得起来，
     * 比"整个环境打不开"强。
     */
    /**
     * 探测表：**从裸跑开始，每档只比上一档多开一个变量**。
     *
     * 旧版是「八套全功能组合」（换 shell / 换 noSeccomp / 换 link2symlink），
     * 结果八条报错一模一样：`退出码 255，无输出`。那八档之间没有因果，
     * 挂了也定位不到任何东西。
     *
     * 新版是**一条链**：
     *
     * ```
     * p0  什么都不开           ← 只验「proot 能不能在这台设备上 ptrace 起 guest」
     * p1  + rootId
     * p2  + bindDev
     * p3  + bindProc
     * p4  + bindTmp
     * p5  + bindWorkspace
     * p6  + bindSdcard
     * p7  + link2Symlink
     * p8  + bindSys
     * p9  + bindDevFd
     * p10 + bindShm
     * p11 + bindLocalTmp
     * p12 + loginShell（bash -lc）   ← 日常档
     * ```
     *
     * 单调性成立（后面每档包含前面全部开关），所以成功的档位一定是个前缀：
     * 第一个失败的档位 = **元凶就是它比上一档多加的那一项**。这句话能直接
     * 写给用户看，也能直接决定「跳过哪个 bind 继续用」。
     *
     * p8 之后那几项是照 Operit 的挂载矩阵补的，正是"大部分命令能用、个别
     * 命令莫名用不了"的解药。p12 排在最后是因为 `-lc` 会读 `/etc/profile`，
     * 个别镜像里的 profile 脚本会卡在别的地方；真卡了，探测会退回 p11。
     *
     * p0 带 `-v 2`。早期靠 `--kill-on-exit` 暴露早期失败，但那会连后台进程
     * 一起杀；现在可观测性全部交给 `-v`。
     */
    private val Plans = listOf(
        LaunchPlan(
            "p0", noSeccomp = true, link2Symlink = false, shell = "/bin/sh",
            bindDev = false, bindProc = false, bindTmp = false,
            bindWorkspace = false, bindSdcard = false,
            rootId = false, verbose = 2,
        ),
        LaunchPlan(
            "p1", noSeccomp = true, link2Symlink = false, shell = "/bin/sh",
            bindDev = false, bindProc = false, bindTmp = false,
            bindWorkspace = false, bindSdcard = false,
            rootId = true,
        ),
        LaunchPlan(
            "p2", noSeccomp = true, link2Symlink = false, shell = "/bin/sh",
            bindDev = true, bindProc = false, bindTmp = false,
            bindWorkspace = false, bindSdcard = false,
            rootId = true,
        ),
        LaunchPlan(
            "p3", noSeccomp = true, link2Symlink = false, shell = "/bin/sh",
            bindDev = true, bindProc = true, bindTmp = false,
            bindWorkspace = false, bindSdcard = false,
            rootId = true,
        ),
        LaunchPlan(
            "p4", noSeccomp = true, link2Symlink = false, shell = "/bin/sh",
            bindDev = true, bindProc = true, bindTmp = true,
            bindWorkspace = false, bindSdcard = false,
            rootId = true,
        ),
        LaunchPlan(
            "p5", noSeccomp = true, link2Symlink = false, shell = "/bin/sh",
            bindDev = true, bindProc = true, bindTmp = true,
            bindWorkspace = true, bindSdcard = false,
            rootId = true,
        ),
        LaunchPlan(
            "p6", noSeccomp = true, link2Symlink = false, shell = "/bin/sh",
            bindDev = true, bindProc = true, bindTmp = true,
            bindWorkspace = true, bindSdcard = true,
            rootId = true,
        ),
        LaunchPlan(
            "p7", noSeccomp = true, link2Symlink = true, shell = "/bin/sh",
            bindDev = true, bindProc = true, bindTmp = true,
            bindWorkspace = true, bindSdcard = true,
            rootId = true,
        ),
        LaunchPlan(
            "p8", noSeccomp = true, link2Symlink = true, shell = "/bin/sh",
            bindDev = true, bindProc = true, bindTmp = true,
            bindWorkspace = true, bindSdcard = true,
            bindSys = true, rootId = true,
        ),
        LaunchPlan(
            "p9", noSeccomp = true, link2Symlink = true, shell = "/bin/sh",
            bindDev = true, bindProc = true, bindTmp = true,
            bindWorkspace = true, bindSdcard = true,
            bindSys = true, bindDevFd = true, rootId = true,
        ),
        LaunchPlan(
            "p10", noSeccomp = true, link2Symlink = true, shell = "/bin/sh",
            bindDev = true, bindProc = true, bindTmp = true,
            bindWorkspace = true, bindSdcard = true,
            bindSys = true, bindDevFd = true, bindShm = true, rootId = true,
        ),
        LaunchPlan(
            "p11", noSeccomp = true, link2Symlink = true, shell = "/bin/sh",
            bindDev = true, bindProc = true, bindTmp = true,
            bindWorkspace = true, bindSdcard = true,
            bindSys = true, bindDevFd = true, bindShm = true,
            bindLocalTmp = true, rootId = true,
        ),
        LaunchPlan(
            "p12", noSeccomp = true, link2Symlink = true, shell = "/bin/bash",
            bindDev = true, bindProc = true, bindTmp = true,
            bindWorkspace = true, bindSdcard = true,
            bindSys = true, bindDevFd = true, bindShm = true,
            bindLocalTmp = true, loginShell = true, rootId = true,
        ),
        // ── 兜底变体：只在主链全挂、且 p0 通过时补跑 ──
        // 有些内核开着 PROOT_NO_SECCOMP=1 反而更慢更不稳，关掉试试。
        LaunchPlan(
            "x0", noSeccomp = false, link2Symlink = true, shell = "/bin/sh",
            bindDev = true, bindProc = true, bindTmp = true,
            bindWorkspace = true, bindSdcard = false,
            rootId = true,
        ),
        // bash 起不来但 sh 能跑的镜像（根文件系统缺 bash 时）
        LaunchPlan(
            "x1", noSeccomp = true, link2Symlink = true, shell = "/bin/bash",
            bindDev = true, bindProc = true, bindTmp = true,
            bindWorkspace = true, bindSdcard = false,
            rootId = true,
        ),
    )

    /** 日常档：功能最全。探测没跑过时也先用它。 */
    private fun dailyPlan(): LaunchPlan = Plans.first { it.id == "p12" }

    private fun planFile(): File = File(baseDir(), "launch.plan")

    /** 一次可用的组合：哪套 proot + 哪一档参数。 */
    private data class Launch(val bundle: ProotBundle, val plan: LaunchPlan) {
        /** 存盘格式 `bundle:plan`，例如 `bionic:p8`。 */
        val tag: String get() = bundle.id + ":" + plan.id
    }

    /**
     * 当前生效的组合。
     *
     * 存盘格式从 `p8` 变成 `bionic:p8`：老版本只存档位，读到只有档位的那种
     * 文件时按第一套可用的 proot 配上那一档 —— 升级上来的用户不用重装。
     *
     * 档位对不上（旧版存的是 `a`..`h`）时不能回落到 `Plans.first()`（裸跑档：
     * 慢、没绑定、apt 装不了包），而要落到日常档：旧版只要成功过，就说明
     * 全功能可用。
     */
    private fun activeLaunch(): Launch {
        val saved = runCatching { planFile().readText().trim() }.getOrNull().orEmpty()
        val bundleId = saved.substringBefore(':', "").takeIf { it.isNotBlank() }
        val planId = saved.substringAfter(':', saved)

        val bundle = Bundles.firstOrNull { it.id == bundleId && it.ready() }
            ?: usableBundles().firstOrNull()
            ?: Bundles.first()

        /*
         * 读回来之后一律把 verbose 关掉：带 `-v 2` 的只有 p0，它是诊断用的。
         * 日常每条命令都刷 ptrace 日志的话，`apt install` 的输出会被淹没，
         * 而且 24 KB 的缓冲会被日志吃光。
         */
        val plan = (Plans.firstOrNull { it.id == planId } ?: dailyPlan()).copy(verbose = 0)
        return Launch(bundle, plan)
    }

    /**
     * 当前生效的「哪套 proot · 哪一档」，给运行环境页显示。
     *
     * 用户反馈"容器起不来"时，这一行能直接说明他跑的是哪条路径
     * （`bionic · p8` 与 `static · p3` 是两种完全不同的故障），
     * 不用让他去翻 `launch.plan`，也不用猜。
     */
    fun launchLabel(): String? {
        if (!isReady()) return null
        val launch = activeLaunch()
        return launch.bundle.id + " · " + launch.plan.id
    }

    /** 每一档失败时的 stderr，自检报错时一并带出去。 */
    private val probeErrors = mutableListOf<Pair<String, String>>()

    /**
     * 逐档试到有一个能跑为止。
     *
     * 判据只有一条：`printf DSHA_RUNTIME_OK` 的**标准输出**里有那串字。
     * 为什么不看退出码 —— proot 起不来时它经常返回 0（proot 自己正常退出，
     * 只是 guest 里的进程没跑起来），退出码在这件事上完全不可信。
     *
     * 流程（2026-11 重写）：
     * 1. 先验二进制（[binaryError]）。
     * 2. 主链**从 p8 往 p0 降**逐档跑，第一个成功的就是"功能最全的可用档"。
     * 3. 全挂再跑一次带 `-v 2` 的裸跑，把 proot 自己的日志捞出来当证据。
     * 4. 最后试两个兜底变体（关 seccomp / 换 bash）。
     *
     * ### 为什么必须从高往低
     *
     * 旧版从 p0 往上跑、**第一个失败就停**，还规定"p0 挂了就判定设备不支持"。
     * 那是错的：p0 是裸跑，连 `/dev`、`/proc` 都不绑 —— 而 Ubuntu 的
     * `/usr/bin/env` 启动时就要读 `/proc/self/exe`、`/dev/urandom`。
     * **p0 失败是预期行为，不是设备判死刑**。截图里"8 档全 255、p0 就挂"
     * 正是这条规则把一台可能正常的设备直接劝退了。
     *
     * 从高往低还有个好处：不需要"成功集合是前缀"这个假设。任何一档能跑，
     * 就拿到那一档的功能，不会因为中间某一项（比如 sdcard 权限）失败
     * 而丢掉后面所有能力。
     */
    private suspend fun probePlans(): Launch? {
        probeErrors.clear()

        /*
         * **每套 proot 单独探测，第一套跑通的就用它。**
         *
         * 两套 proot 的失败点不一样：bionic 那套死在动态链接层，静态那套
         * 死在内核接口层。只有分别跑，才知道这台机器到底认哪一种。
         * 顺带一提，探不通的那套会在 [probeBundle] 里被"裸跑无输出"提前
         * 判死，不会把 11 档全耗完 —— 一次全量探测最坏要几分钟，装环境
         * 的用户等不起。
         */
        val candidates = usableBundles().ifEmpty { Bundles }
        for (bundle in candidates) {
            val problem = withContext(Dispatchers.IO) { binaryError(bundle) }
            if (problem != null) {
                probeErrors += "${bundle.id}:self" to problem
                continue
            }
            probeBundle(bundle)?.let { return it }
        }
        return null
    }

    /**
     * 用一套 proot 逐档试。
     *
     * 顺序：日常档 → 裸跑（诊断）→ 其余档位。
     *
     * 日常档先试是因为**大多数设备一次就过**，探测只花一条命令的时间；
     * 它挂了才值得去跑裸跑 —— 裸跑带 `-v 2`，能把"卡在 ptrace、loader
     * 注入还是 execve"逼出来。裸跑一个字都不吐，说明这套 proot 在这台
     * 机器上根本起不来，直接返回，把机会留给另一套。
     */
    private suspend fun probeBundle(bundle: ProotBundle): Launch? {
        val started = SystemClock.elapsedRealtime()
        /*
         * 单套 proot 的时间预算。
         *
         * 正常失败是"立刻退出"，每条命令都在一两秒内返回；只有真卡住才会撞上
         * 20 秒超时。留 90 秒是给"多档都超时"这种极端情况兜底 —— 装环境的
         * 用户看着一个不动的进度条，比看到一句失败更难受。
         */
        fun outOfBudget(): Boolean = SystemClock.elapsedRealtime() - started > 90_000L

        val daily = dailyPlan()
        val first = exec(bundle, daily, ProbeCommand, null, ProbeTimeoutSeconds)
        if (first.stdout.contains(ProbeToken)) return Launch(bundle, daily)
        probeErrors += "${bundle.id}/${daily.id}" to describeFailure(daily, first, beforeInChain(daily))

        val bare = Plans.first { it.id == "p0" }
        val bareResult = exec(bundle, bare, ProbeCommand, null, ProbeTimeoutSeconds)
        if (bareResult.stdout.contains(ProbeToken)) return Launch(bundle, bare)
        probeErrors += "${bundle.id}/${bare.id}" to describeFailure(bare, bareResult, null)
        val silent = bareResult.stderr.isBlank() && bareResult.stdout.isBlank()
        if (silent || bareResult.timedOut) return null

        // proot 能动，是某个开关/bind 把它弄死的：沿主链往下降，找到最全的可用档
        for (plan in MainChain.reversed()) {
            if (plan.id == daily.id || plan.id == bare.id) continue
            if (outOfBudget()) break
            val result = exec(bundle, plan, ProbeCommand, null, ProbeTimeoutSeconds)
            if (result.stdout.contains(ProbeToken)) return Launch(bundle, plan)
            probeErrors += "${bundle.id}/${plan.id}" to describeFailure(plan, result, beforeInChain(plan))
        }

        // 主链全挂：试兜底变体（关 seccomp / 换 bash）
        for (plan in Variants) {
            if (outOfBudget()) break
            val result = exec(bundle, plan, ProbeCommand, null, ProbeTimeoutSeconds)
            if (result.stdout.contains(ProbeToken)) return Launch(bundle, plan)
            probeErrors += "${bundle.id}/${plan.id}" to describeFailure(plan, result, daily)
        }
        return null
    }

    /** 链上比这一档少一项的那一档（用来生成"这一档多开了什么"）。 */
    private fun beforeInChain(plan: LaunchPlan): LaunchPlan? {
        val index = MainChain.indexOfFirst { it.id == plan.id }
        return if (index > 0) MainChain[index - 1] else null
    }

    /** 主链：p0..p8，单调累加。 */
    private val MainChain = Plans.filter { it.id.startsWith("p") }

    /** 兜底变体：只在主链无解时试。 */
    private val Variants = Plans.filter { it.id.startsWith("x") }

    private const val ProbeCommand = "printf DSHA_RUNTIME_OK"
    private const val ProbeToken = "DSHA_RUNTIME_OK"
    private const val ProbeTimeoutSeconds = 20L

    /**
     * 这一档比上一档多开了什么。
     *
     * 报错里必须带这句 —— 「p6 失败」本身没有信息量，「p6 失败，它比 p5 多绑了
     * /storage/emulated/0:/sdcard」才是一句能直接照做的话。
     */
    private fun LaunchPlan.addedOver(prev: LaunchPlan?): String {
        if (prev == null || prev.id == id) return "裸跑（无 -0、无 bind、无 link2symlink）"
        val diff = buildList {
            if (rootId && !prev.rootId) add("-0")
            if (bindDev && !prev.bindDev) add("-b /dev")
            if (bindProc && !prev.bindProc) add("-b /proc")
            if (bindTmp && !prev.bindTmp) add("-b <app>/tmp:/tmp")
            if (bindWorkspace && !prev.bindWorkspace) add("-b <工作区>:/workspace")
            if (bindSdcard && !prev.bindSdcard) add("-b /storage/emulated/0:/sdcard")
            if (bindSys && !prev.bindSys) add("-b /sys")
            if (bindDevFd && !prev.bindDevFd) add("-b /dev/pts、/dev/fd")
            if (bindShm && !prev.bindShm) add("-b <app>/tmp:/dev/shm")
            if (bindLocalTmp && !prev.bindLocalTmp) add("-b /data/local/tmp")
            if (loginShell && !prev.loginShell) add("login shell（-lc）")
            if (link2Symlink && !prev.link2Symlink) add("--link2symlink")
            if (noSeccomp != prev.noSeccomp) {
                add("PROOT_NO_SECCOMP=${if (noSeccomp) "1" else "0"}")
            }
            if (shell != prev.shell) add("shell=$shell")
        }
        return if (diff.isEmpty()) "与上一档相同" else diff.joinToString("、")
    }

    /**
     * 把一次探测失败翻译成一句**能指向下一步**的话。
     *
     * 上一版这里只有 `退出码 255，无输出` —— 六个字把六档的差别全抹平了，
     * 而 proot 的 `--kill-on-exit` 恰恰会把所有早期失败都变成
     * 「255 + 静默」。所以这里对 255 单独给解释，并提醒看 g/h 两档。
     */
    /**
     * 能不能把 proot 拉起来。返回 null = 可以，非 null = 原因。
     *
     * 旧版只判「`ProcessBuilder.start()` 没抛异常」——那只能证明**文件可执行**，
     * 证明不了 proot 真能跑。有些 ROM 上 execve 成功、进程被 seccomp 立刻杀掉，
     * `start()` 照样返回，读流拿到空字符串，于是第 0 档"通过"，
     * 后面八档全 255 —— 截图里那个局面就是这么来的。
     *
     * 现在要求三件事同时成立：退出码 0、有输出、输出里能认出 proot。
     */
    private fun binaryError(bundle: ProotBundle): String? {
        val proot = bundle.binary()
            ?: return "${bundle.label}：安装包里没有 ${bundle.binaryName}（jniLibs 没打进去）"

        // 先看 ELF 头：设备是 arm64、包里塞的是 x86 的 proot 时，execve 的报错
        // 会被解释成"权限不足"，非常容易带偏判断
        elfProblem(proot, abiTag())?.let { return "${bundle.label}：$it" }
        // loader 架构不对时，proot 自己 `--version` 能过，一注入 guest 就静默退出 ——
        // 这是"255 无输出"最隐蔽的一种成因
        val loader = bundle.loader()
            ?: return "${bundle.label}：安装包里没有 ${bundle.loaderName}（jniLibs 没打进去）"
        elfProblem(loader, abiTag())?.let { return "${bundle.label}：proot loader 有问题：$it" }

        return runCatching {
            val process = ProcessBuilder(proot.absolutePath, "--version")
                .apply {
                    environment().clear()
                    environment()["PATH"] = "/system/bin"
                }
                .redirectErrorStream(true)
                .start()
            // 必须读干：管道写满之后子进程会卡在 write 上，waitFor 永远等不到
            val text = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(8, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()

            when {
                !finished -> "proot --version 超时 8s 不返回，二进制可能卡在 loader 阶段"
                process.exitValue() != 0 ->
                    "proot --version 退出码 ${process.exitValue()}（${text.take(120).ifBlank { "无输出" }}）"
                text.isBlank() ->
                    "proot --version 退出码 0 但没有任何输出，这个二进制在本机跑不起来"
                !looksLikeProot(text) ->
                    "proot --version 的输出不像 proot（${text.take(80)}）"
                else -> null
            }
        }.getOrElse { error ->
            "proot 无法执行（${error.javaClass.simpleName}: ${error.message.orEmpty()}）"
        }
    }

    /**
     * `--version` 的输出像不像 proot。
     *
     * 不能只认字面量 "proot"：proot 的版本页是**一张 ASCII 图 + 一行版本号**，
     * 正文里根本不出现 "proot" 这五个字母（`print_version` 打的是
     * `cli->logo` 与 `cli->version`）。真正的指纹是它下面那行
     * `built-in accelerators: process_vm = …, seccomp_filter = …`。
     *
     * 早先的判据只认 "proot" 与 "usage"，于是**一套本来能用的 proot 被判成
     * 「不像 proot」**，整套被跳过 —— 探测最后只剩"设备不支持"。
     */
    private fun looksLikeProot(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("proot") || lower.contains("usage") ||
            lower.contains("accelerators") || lower.contains("link2symlink")
    }

    /**
     * 读 ELF 头核对架构。
     *
     * `e_machine`：0xB7 = AArch64，0x3E = x86-64。不匹配时 `execve` 报的是
     * `EACCES`/`ENOEXEC`，在 proot 里常常只剩一句 "255 无输出"，
     * 看起来像"这台设备禁止 ptrace"，其实只是打错了包。
     */
    private fun elfProblem(file: File, expected: String?): String? {
        if (!file.isFile || file.length() < 64) return "${file.name} 不存在或大小异常"
        val machine = runCatching {
            file.inputStream().use { input ->
                val head = ByteArray(20)
                if (input.read(head) < 20) return@use -1
                if (head[0] != 0x7F.toByte() || head[1] != 'E'.code.toByte() ||
                    head[2] != 'L'.code.toByte() || head[3] != 'F'.code.toByte()
                ) {
                    return@use -1
                }
                val little = head[5] == 1.toByte()
                val lo = head[18].toInt() and 0xFF
                val hi = head[19].toInt() and 0xFF
                if (little) lo or (hi shl 8) else (lo shl 8) or hi
            }
        }.getOrDefault(-1)

        if (machine == -1) return "${file.name} 不是有效的 ELF（打包时被压缩或改写过？）"
        val actual = when (machine) {
            0xB7 -> "arm64"
            0x3E -> "x86_64"
            0x28 -> "arm"
            0x03 -> "x86"
            else -> "0x${machine.toString(16)}"
        }
        return if (expected != null && actual != expected) {
            "${file.name} 是 $actual 架构，本机是 $expected —— 安装包里打错了 ABI"
        } else {
            null
        }
    }

    private fun describeFailure(
        plan: LaunchPlan,
        result: Shell.Result,
        previous: LaunchPlan?,
    ): String {
        val raw = result.stderr.trim().ifBlank { result.stdout.trim() }
        val delta = plan.addedOver(previous)
        val tail = when {
            raw.isNotBlank() -> raw.take(600)
            result.timedOut -> "超时（${ProbeTimeoutSeconds}s）无输出，proot 卡住了"
            else -> "退出码 ${result.exitCode}，无输出"
        }
        /*
         * 诊断档（p0）没有"上一档"，不能说成"加开某物后失败"。
         * 另外把日志留 600 字而不是 240：截断在 `vpid 1` 正好把
         * `terminated with signal 11` 切掉，等于把唯一的线索丢了。
         */
        return if (previous == null) "裸跑失败：$tail" else "加开「$delta」后失败：$tail"
    }

    /**
     * 把请求的工作目录接到命令前面。
     *
     * shell 固定在 `/root` 启动（见 [exec] 里 `-w` 的说明），这里负责回到
     * 用户要求的目录。`cd` 失败时给一句明确提示并留在 `/root` —— 比让整条
     * 命令在一个不存在的目录里跑要好。
     */
    private fun withWorkingDirectory(cwd: String, command: String): String {
        if (cwd == "/root") return command
        val quoted = cwd.replace("'", "'\\''")
        return "cd '$quoted' 2>/dev/null || { " +
            "echo 'dsha: 工作目录不可用，已在 /root 执行' >&2; cd /root; }\n" +
            command
    }

    /**
     * 真正起 proot 的地方。
     *
     * 参数与 Operit / termux / proot-distro 对齐；绑定矩阵见 [LaunchPlan]
     * 里那几个 `bind*` 字段，缺哪一项就降一档，不赌"一次全绑能过"。
     */
    private suspend fun exec(
        bundle: ProotBundle,
        plan: LaunchPlan,
        command: String,
        cwd: String?,
        timeoutSeconds: Long,
    ): Shell.Result {
        val proot = bundle.binary()
            ?: return fail("缺少 proot 可执行文件（${bundle.binaryName} 没打进安装包）")
        if (!isInstalled()) return fail("Ubuntu 环境还没装。先让用户到「设置 → 运行环境」装一次。")

        val root = rootfs()
        // 老环境可能没有这条配置：apt update 会因 setresuid 直接失败
        writeAptSandbox(root)
        /*
         * 把 Android 的 gid 补进 guest 的 /etc/group（照 Operit 的 fix_permissions）。
         *
         * 不补的话，`id`、`ls -l`、以及 git 的 "dubious ownership" 检查都会先吐
         * 一句 `cannot find name for group ID 10123` —— 只是告警，但每条命令都
         * 顶着它，看起来像环境坏了。
         */
        writeAndroidGroups(root)
        val tmp = File(baseDir(), "tmp").apply { mkdirs() }
        /*
         * link2symlink 的代理目录。
         *
         * **放在 rootfs 内部**，与 proot-distro 的做法一致 —— 代理文件必须与
         * 被替换的文件在同一个文件系统上，而且 proot 会在它下面按 guest 路径
         * 逐级建目录；跨到另一个挂载点时，那些 mkdir 会静默失败，最后表现为
         * `ln: failed to create hard link`，而真正炸的是 dpkg 解包。
         */
        val links = File(root, ".dsha-links").apply { mkdirs() }

        /*
         * `cwd` 两种写法：以 `/` 开头就当成 guest 里的绝对路径（终端用），
         * 否则当成工作区相对路径（工具用）。分两种而不是统一，是因为
         * 模型写 `src/main.py` 时想表达的是"工作区里"，而终端里敲 `cd /etc`
         * 想表达的是"系统的 /etc" —— 这两件事不能合并。
         */
        val requestedCwd = when {
            cwd == null -> "/root"
            cwd.startsWith("/") -> cwd
            else -> "/workspace/" + cwd.trim('/')
        }
        /*
         * 工作区这次到底绑不绑 —— cwd 与 `-b` 必须用同一份判断，否则会出现
         * "以为绑上了、其实没绑"的目录。
         */
        val workspace = if (plan.bindWorkspace) {
            Workspace.rootOrNull()?.takeIf { it.isDirectory && it.canRead() }
        } else {
            null
        }
        /*
         * 工作区没绑上时不能 `cd /workspace`：rootfs 里有 configure() 建的
         * 同名空目录，`cd` 会"成功"进到一个什么都没有的地方，模型随后读不到
         * 任何文件，还以为是文件丢了。改为落在 `/root` 并明确说一句。
         */
        val workspaceRequested = requestedCwd == "/workspace" ||
            requestedCwd.startsWith("/workspace/")
        val effectiveCwd = if (workspaceRequested && workspace == null) "/root" else requestedCwd
        val cwdNotice = if (workspaceRequested && workspace == null) {
            "echo 'dsha: 工作区未挂载（没有读取权限或路径不可用），命令在 /root 执行' >&2\n"
        } else {
            ""
        }

        val args = mutableListOf<String>()
        args += proot.absolutePath
        /*
         * **不传 `--kill-on-exit`**（照 Operit）。
         *
         * 它会在 proot 退出时把 guest 里所有进程杀掉 —— 也就是说
         * `nohup python3 app.py &`、`service nginx start`、`tmux`、`sshd`
         * 这些"起一个后台就一直跑"的命令，父进程一退全没。用户看到的是
         * "这条命令跑不起来"，其实进程起过、被杀掉了。
         *
         * 早期用它是因为它能让早期失败变得"可见"（255 静默）；现在诊断靠
         * p0 那档的 `-v 2`，不需要拿运行期行为换可观测性。
         */
        if (plan.link2Symlink) args += "--link2symlink"
        if (plan.verbose > 0) args += listOf("-v", plan.verbose.toString())
        if (plan.rootId) args += "-0"
        args += listOf("-r", root.absolutePath)
        /*
         * 系统目录按档位逐项绑。
         *
         * p0 一档**什么都不绑**，就是为了把「proot 本身在这台设备上能不能跑」
         * 与「某个 bind 把它弄死了」这两件事分开 —— 旧版全绑着试，八档全挂时
         * 分不清是内核禁 ptrace 还是 `/storage/emulated/0` 不可读。
         */
        if (plan.bindDev) args += listOf("-b", "/dev")
        if (plan.bindProc) args += listOf("-b", "/proc")
        if (plan.bindTmp) args += listOf("-b", "${tmp.absolutePath}:/tmp")
        if (plan.bindWorkspace) {
            if (workspace != null) {
                args += listOf("-b", "${workspace.absolutePath}:/workspace")
            } else {
                /*
                 * ⚠️ 工作区不可读时**不要绑**。
                 *
                 * 用户把工作区换成"内部存储里的某个文件夹"之后，如果没有
                 * 「所有文件访问」权限，这个 bind 会让 proot 直接退出 ——
                 * 报错却是 `proot info: vpid 1: terminated with signal`，
                 * 看起来像 proot 坏了。少绑一个目录，命令还能跑；
                 * 绑了，连 `echo hi` 都跑不了。
                 */
                com.simplechat.app.util.AppLog.w(
                    "UbuntuEnv",
                    "workspace 不可读，跳过 bind：${Workspace.rootOrNull()?.absolutePath}",
                )
            }
        }
        if (plan.bindSdcard) {
            // 外部存储是可选的：没有「所有文件访问」权限时这个绑定不会成功，proot 会忽略
            File("/storage/emulated/0").takeIf { it.isDirectory && it.canRead() }?.let {
                args += listOf("-b", "/storage/emulated/0:/sdcard")
            }
        }
        /*
         * 以下四项照 Operit 的 `PRootMountMapping.buildRuntimeBindMounts` 补。
         *
         * 之前只绑 `/dev` 与 `/proc`，理由是"`/sys` 对 apt 没帮助" ——
         * 对 apt 确实没帮助，但**对用户敲的命令有**：
         *
         * - `lscpu`、`df -h`、`systemd-detect-virt` 读 `/sys`
         * - bash 的进程替换 `<(cmd)`、`/dev/fd/63`、`tee`、`xargs -o` 读 `/dev/fd`
         * - Python 的 multiprocessing、Chromium 的共享内存读 `/dev/shm`
         * - 脚本、ADB、不少工具的临时目录落在 `/data/local/tmp`
         *
         * 缺任何一项，表现都是"大部分命令能用，个别命令莫名用不了"。
         */
        if (plan.bindSys) {
            File("/sys").takeIf { it.isDirectory && it.canRead() }?.let {
                args += listOf("-b", "/sys")
            }
        }
        if (plan.bindDevFd) {
            File("/dev/pts").takeIf { it.isDirectory }?.let {
                args += listOf("-b", "/dev/pts")
            }
            if (File("/proc/self/fd").exists()) {
                /*
                 * 只绑目录，**不绑 `/dev/std{in,out,err}`**。
                 *
                 * 后三条的源是 `/proc/self/fd/0..2`，而 app 起的 proot 三根
                 * 标准流都是 **pipe**：`/proc/self/fd/1` 是指向 `pipe:[1234]`
                 * 的符号链接，`realpath()` 必然失败。proot 每次启动都会打三行
                 * `can't sanitize binding "...": No such file or directory` ——
                 * 白噪音，而且把真正的错误埋了。容器里 `/dev/std*` 由 rootfs
                 * 自带的符号链接提供，绑目录之后自然能解析。
                 */
                args += listOf("-b", "/proc/self/fd:/dev/fd")
            }
        }
        if (plan.bindShm) {
            /*
             * 宿主没有 `/dev/shm` 时挂到 App 的 tmp：容器里必须有个可写的
             * 共享内存目录，否则 `multiprocessing` 一开就报
             * "OSError: [Errno 38] Function not implemented"。
             */
            args += listOf("-b", "${tmp.absolutePath}:/dev/shm")
        }
        if (plan.bindLocalTmp) {
            File("/data/local/tmp").takeIf { it.isDirectory && it.canRead() }?.let {
                args += listOf("-b", "/data/local/tmp")
            }
        }
        /*
         * 宿主 `/proc/stat` 之类读不到时，把 rootfs 里的假数据绑过去。
         *
         * 只在读不到时才绑 —— 能读就用真的，假数据里 CPU 时间是静止的，
         * `top` 会显示 0% 占用。
         */
        UbuntuSysdata.ensure(root)
        UbuntuSysdata.needed(root).forEach { (fake, host) ->
            args += listOf("-b", "$fake:$host")
        }
        /*
         * `-w` **不能**直接用请求目录。
         *
         * 请求目录可能是 bind 进来的 `/workspace`，也可能是 rootfs 里一个刚被
         * 删掉的路径。proot 的 `-w` 失败时不会报错，shell 带着一个无效的 cwd
         * 启动，dash 找不到 `PWD` 就调 `getcwd`，于是每条命令都先吐一句
         * `getcwd() failed: Function not implemented`（正是用户 apt 时报的那行）。
         *
         * 固定落在 `/root`（rootfs 里真实存在），再由脚本 `cd` 到目标：
         * 目录不在时是一句可读的提示，不会把整个 shell 弄坏。
         */
        val shellCwd = "/root"
        args += listOf("-w", shellCwd)
        args += listOf("/usr/bin/env", "-i")
        args += "HOME=/root"
        /*
         * 显式给 PWD：dash 看到它就不再调 getcwd。值必须与 `-w` 一致，
         * 否则 `cd ..` 与相对路径会按错误的字符串推算。
         */
        args += "PWD=$shellCwd"
        args += "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        args += "TERM=xterm-256color"
        args += "TMPDIR=/tmp"
        args += "LANG=C.UTF-8"
        args += "LC_ALL=C.UTF-8"
        args += "SHELL=${plan.shell}"
        args += "DEBIAN_FRONTEND=noninteractive"
        /*
         * 日常档用 `-lc`（login shell）：`/etc/profile` 里那套 PATH、locale、
         * 以及发行版自带的初始化脚本才会跑。`-c` 是非登录 shell，`/usr/sbin`
         * 这类目录常常不在 PATH 里 —— 用户敲 `ifconfig` 得到 "command not found"，
         * 而 `ls /usr/sbin/ifconfig` 明明在。
         */
        val shellFlag = if (plan.loginShell) "-lc" else "-c"
        args += listOf(plan.shell, shellFlag, cwdNotice + withWorkingDirectory(effectiveCwd, command))

        val process = try {
            ProcessBuilder(args)
                .apply {
                    /*
                     * 宿主侧环境**清空再重建**。
                     *
                     * 不清的话会原样继承 App 进程的变量 —— 包括某些 ROM 塞进来的
                     * `LD_PRELOAD` / `LD_LIBRARY_PATH`，那些会让 proot 加载到
                     * 安卓的库，然后在 `execve` 阶段莫名其妙地 SIGSEGV。
                     * 只留一个可用的 PATH，够 proot 找 sh 了。
                     */
                    environment().clear()
                    environment()["PATH"] = "/system/bin"
                    environment()["PROOT_TMP_DIR"] = tmp.absolutePath
                    // 硬链接降级后的代理文件落点，必须可写且带链接目录
                    if (plan.link2Symlink) environment()["PROOT_L2S_DIR"] = links.absolutePath
                    environment()["TMPDIR"] = tmp.absolutePath
                    // Android 的 seccomp 过滤器经常挡掉 proot 的加速路径。关掉它换稳定性：
                    // 慢一点，但不会出现"某些命令莫名 EPERM"
                    if (plan.noSeccomp) environment()["PROOT_NO_SECCOMP"] = "1"
                    /*
                     * **必须显式给 loader 的路径。**
                     *
                     * 不给的话 proot 会把内嵌的 loader 写进 PROOT_TMP_DIR 再 exec 它
                     * （见 proot-5.3.0/src/execve/enter.c: extract_loader）。而 Android 10
                     * 起，应用**不能执行自己私有目录里的文件** —— execve 直接 EACCES，
                     * 报出来的却是 "execve(/usr/bin/env): Permission denied"，看着像
                     * rootfs 坏了。给了路径之后 proot 直接用它，不再落地（enter.c:566
                     * 是 `getenv("PROOT_LOADER") ?: extract_loader(...)`）。
                     *
                     * loader 与 proot 一起放在 nativeLibraryDir，那儿是可执行的；
                     * 而且必须取**同一套**（见 [ProotBundle]），版本错配同样是 255 静默。
                     */
                    bundle.loader()?.let { environment()["PROOT_LOADER"] = it.absolutePath }
                    /*
                     * Android 的 /proc/self/mountinfo 里没有 proot 想看的那些挂载点，
                     * 解析它只会白跑一遍（Operit 的构建专门加了这个开关来跳过）。
                     * 老的静态构建不认这个变量，给了也无害。
                     */
                    environment()["PROOT_NO_MOUNTINFO"] = "1"
                    environment()["HOME"] = root.absolutePath
                }
                .redirectErrorStream(false)
                .start()
        } catch (error: Exception) {
            return fail("无法启动 proot：${error.message}")
        }

        val outReader = StreamReader(process.inputStream.bufferedReader(), 24_000)
        val errReader = StreamReader(process.errorStream.bufferedReader(), 24_000)
        val outThread = Thread(outReader, "ubuntu-out").apply { isDaemon = true; start() }
        val errThread = Thread(errReader, "ubuntu-err").apply { isDaemon = true; start() }

        // 可取消：停止 AI 时 proot 里的命令也要一起结束（见 `awaitProcess`）
        val finished = awaitProcess(process, timeoutSeconds)
        outThread.join(800)
        errThread.join(800)

        return Shell.Result(
            stdout = outReader.text(),
            stderr = errReader.text(),
            exitCode = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1,
            timedOut = !finished,
            truncated = outReader.truncated || errReader.truncated,
        )
    }

    private fun fail(message: String) = Shell.Result(
        stdout = "",
        stderr = message,
        exitCode = -1,
        timedOut = false,
        truncated = false,
    )

    /** 计数输入流：只为把"解到哪儿了"换算成百分比。 */
    private class CountingStream(source: InputStream) : java.io.FilterInputStream(source) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) count += it }
    }

    /** 与 [Shell] 里那个等价，只是这边的进程是 proot 起的。 */
    private class StreamReader(private val source: java.io.BufferedReader, private val limit: Int) : Runnable {
        private val buffer = StringBuilder()
        @Volatile var truncated = false

        override fun run() {
            runCatching {
                val chunk = CharArray(4096)
                while (true) {
                    val read = source.read(chunk)
                    if (read <= 0) break
                    if (buffer.length < limit) {
                        val room = limit - buffer.length
                        buffer.append(chunk, 0, minOf(read, room))
                        if (read > room) truncated = true
                    } else {
                        truncated = true
                    }
                }
            }
        }

        fun text(): String = buffer.toString()
    }
}
