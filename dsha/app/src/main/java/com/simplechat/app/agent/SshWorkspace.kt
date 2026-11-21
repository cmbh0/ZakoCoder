package com.simplechat.app.agent

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.SftpProgressMonitor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 用 SSH 直连一台 Linux 当作云端工作区。
 *
 * 与 [RemoteExec]（HTTP 服务）是同一件事的两种实现：**执行走 exec 通道、
 * 文件走 SFTP**。因此工具层一行都不用改 —— 模型看到的能力、参数、回执完全一致。
 *
 * 为什么值得有这条路：HTTP 那套需要在服务器上跑一个常驻服务（还得解决公网
 * 暴露、鉴权、端口）。而很多人手上本来就有一台 VPS，SSH 端口是现成的，
 * 凭据也是现成的。少一个部署环节，就少一整类"部署失败"。
 *
 * 连接按 [CloudEnv.id] 复用（见 [RemoteWorkspaces]）：SSH 建连要几百毫秒，
 * 一轮对话里工具会被调用十几次，每次重建等于每步都卡一下。
 */
class SshWorkspace(override val env: CloudEnv) : RemoteWorkspace {

    private companion object {
        /** 推送到远端的辅助脚本版本。改了脚本内容就 +1，下次连接会重推。 */
        const val HELPER_VERSION = 1
        const val HELPER_DIR = ".dsha"
        const val HELPER_FILE = "helper.sh"

        /** 单次读文件的上限。再大就不该整个读进来 —— 用 offset/limit 分段。 */
        const val MAX_BYTES = 8L * 1024 * 1024
    }

    private val lock = Any()
    private var session: Session? = null
    private var baseDir: String? = null
    private var rootUser = false
    private var helperReady = false

    // ── 连接 ────────────────────────────────────────────────

    /**
     * 拿一条活着的会话。断了就重连 —— 手机切网络、服务器重启都会让旧会话失效，
     * 而工具调用不该因此整轮失败。
     */
    private fun connect(): Session = synchronized(lock) {
        session?.let { live ->
            if (live.isConnected) return live
            runCatching { live.disconnect() }
        }
        val host = env.host.trim()
        if (host.isEmpty()) throw IOException("这个 SSH 环境没有填主机地址")
        val jsch = JSch()
        if (env.auth == CloudEnv.AUTH_KEY) {
            val key = env.secret.trim().replace("\r\n", "\n")
            if (key.isEmpty()) throw IOException("SSH 密钥是空的")
            val pass = env.passphrase.takeIf { it.isNotBlank() }?.toByteArray(Charsets.UTF_8)
            // JSch 要求私钥以换行结尾；用户从文件里复制过来常常少了最后一个 \n
            jsch.addIdentity("dsha-${env.id}", (key + "\n").toByteArray(Charsets.UTF_8), null, pass)
        }
        val fresh = jsch.getSession(
            env.user.ifBlank { "root" },
            host,
            env.port.takeIf { it in 1..65535 } ?: 22,
        )
        if (env.auth != CloudEnv.AUTH_KEY) fresh.setPassword(env.secret)

        /*
         * 主机密钥不做预置校验。
         *
         * 手机上没有一个可信的 known_hosts 可用，而"连不上"比"可能被中间人"
         * 更常见也更致命（换服务器、重装系统都会换指纹）。折中：连接后把指纹
         * 记在环境上，用户在环境卡片里能看到它 —— 想核对的人有东西可核对，
         * 不想核对的人不会被挡在门外。
         */
        fresh.setConfig("StrictHostKeyChecking", "no")
        fresh.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
        fresh.serverAliveInterval = 30_000
        fresh.serverAliveCountMax = 3
        fresh.connect(20_000)
        session = fresh
        baseDir = null
        helperReady = false
        return fresh
    }

    /**
     * 第一次连上后确定三件事：工作目录、是不是 root、有没有 sudo。
     *
     * 顺带把辅助脚本推上去。这是"把客户端推到服务器"的那一步 ——
     * 只是一份几行的 sh，不装任何东西，删掉也不影响功能（会退回内联命令）。
     */
    private fun prepare(live: Session) = synchronized(lock) {
        if (baseDir != null) return
        // 用 `pwd` 而不是 SFTP 的 home()：用户可能在 .bashrc 里 cd 到项目目录，
        // 那才是他心里的"登录进来在哪"
        val home = execRaw(live, "pwd", 20).stdout.trim().ifBlank { "/root" }
        rootUser = execRaw(live, "id -u").stdout.trim() == "0"
        val preferred = env.workdir.trim().trimEnd('/')
        val dir = when {
            preferred.isEmpty() -> home
            preferred.startsWith("/") -> preferred
            else -> home.trimEnd('/') + "/" + preferred
        }
        baseDir = if (dirExists(live, dir)) dir else home
        pushHelper(live)
    }

    private fun base(): String = baseDir ?: run {
        val live = connect()
        prepare(live)
        baseDir ?: "/"
    }

    override fun close() {
        val live = synchronized(lock) { session.also { session = null; baseDir = null } }
        runCatching { live?.disconnect() }
    }

    /** 远端主机密钥指纹（MD5，形如 `aa:bb:…`）。连接失败时为 null。 */
    fun fingerprint(): String? = runCatching {
        val live = connect()
        // SHA-256 over the raw host-key blob。与 OpenSSH 的 SHA256 指纹同一份数据，
        // 只是这里用十六进制显示（手机上对比一长串 base64 更容易看错）。
        // HostKey.getKey() 给的是 base64 的密钥块，先还原成字节再算摘要
        val blob = java.util.Base64.getDecoder().decode(live.hostKey.key)
        java.security.MessageDigest.getInstance("SHA-256").digest(blob)
            .joinToString(":") { byte -> "%02x".format(byte) }
    }.getOrNull()

    // ── 低层：exec / sftp ───────────────────────────────────

    private fun execRaw(live: Session, command: String, timeoutSec: Int = 60): RemoteExecResult {
        val channel = live.openChannel("exec") as ChannelExec
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        try {
            channel.setCommand(command)
            channel.setInputStream(null)
            channel.setErrStream(err)
            channel.setOutputStream(out)
            channel.connect(15_000)
            val deadline = System.currentTimeMillis() + timeoutSec * 1000L
            while (!channel.isClosed && System.currentTimeMillis() < deadline) {
                Thread.sleep(40)
            }
            val timedOut = !channel.isClosed
            if (timedOut) runCatching { channel.disconnect() }
            return RemoteExecResult(
                stdout = out.toString("UTF-8"),
                stderr = if (timedOut) {
                    err.toString("UTF-8") + "\n（超过 ${timeoutSec}s 被客户端中断）"
                } else {
                    err.toString("UTF-8")
                },
                code = if (timedOut) 124 else channel.exitStatus,
                cwd = baseDir.orEmpty(),
                elevated = rootUser,
            )
        } finally {
            runCatching { channel.disconnect() }
        }
    }

    private fun <T> sftpOf(live: Session, block: (ChannelSftp) -> T): T {
        val channel = live.openChannel("sftp") as ChannelSftp
        channel.connect(15_000)
        try {
            return block(channel)
        } finally {
            runCatching { channel.disconnect() }
        }
    }

    private fun <T> withSftp(block: (ChannelSftp) -> T): T {
        val live = connect()
        prepare(live)
        return sftpOf(live, block)
    }

    // ── 路径 ────────────────────────────────────────────────

    private fun abs(path: String): String {
        val raw = path.trim().removePrefix("file://")
        val root = base()
        if (raw.isEmpty() || raw == ".") return root
        val joined = if (raw.startsWith("/")) raw else root.trimEnd('/') + "/" + raw
        val parts = ArrayDeque<String>()
        joined.split('/').forEach { seg ->
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(seg)
            }
        }
        return "/" + parts.joinToString("/")
    }

    /** 一条命令里的单引号转义。路径里带空格、括号、`$` 都靠它。 */
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun mkdirs(channel: ChannelSftp, dir: String) {
        if (dir.isEmpty() || dir == "/") return
        val parts = dir.trim('/').split('/')
        var current = ""
        parts.forEach { part ->
            current += "/" + part
            runCatching { channel.mkdir(current) }
        }
    }

    private fun readAll(path: String): String = withSftp { channel ->
        val target = abs(path)
        val attrs = runCatching { channel.lstat(target) }.getOrNull()
            ?: throw IOException("文件不存在：$path")
        if (attrs.size > MAX_BYTES) {
            throw IOException("文件有 ${attrs.size / 1024 / 1024} MB，太大了。用 read_file 的 offset/limit 分段读。")
        }
        channel.get(target).use { input -> input.readBytes().toString(Charsets.UTF_8) }
    }

    private fun dirExists(live: Session, dir: String): Boolean = runCatching {
        sftpOf(live) { channel ->
            val attrs = channel.lstat(dir)
            attrs.isDir
        }
    }.getOrDefault(false)

    // ── 辅助脚本 ────────────────────────────────────────────

    /**
     * 把一份极小的 sh 推到 `~/.dsha/helper.sh`。
     *
     * 它只做一件事：用**同一种格式**报出这台机器的信息。不同发行版的
     * `/etc/os-release`、`python3 -V` 输出格式都不一样，客户端在那边拼
     * 十几次判断，不如把这几行放在机器上跑。
     *
     * 推不上去（只读 home、无 SFTP 权限）就算了 —— [sysInfo] 有内联兜底。
     */
    private fun pushHelper(live: Session) {
        if (helperReady) return
        helperReady = true
        val dir = base() + "/" + HELPER_DIR
        val target = "$dir/$HELPER_FILE"
        runCatching {
            sftpOf(live) { channel ->
                mkdirs(channel, dir)
                val probe = runCatching { channel.lstat(target) }.getOrNull()
                val stamp = "# dsha-helper v$HELPER_VERSION"
                val upToDate = probe != null && probe.size > 0 && runCatching {
                    channel.get(target).use { input ->
                        input.bufferedReader().readLine().orEmpty() == stamp
                    }
                }.getOrDefault(false)
                if (!upToDate) {
                    val bytes = (stamp + "\n" + HELPER_SCRIPT).toByteArray(Charsets.UTF_8)
                    channel.put(ByteArrayInputStream(bytes), target, ChannelSftp.OVERWRITE)
                }
            }
        }
    }

    // ── 执行 ────────────────────────────────────────────────

    override suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val live = connect()
            prepare(live)
            execRaw(live, "echo dsha-ok", 15).stdout.contains("dsha-ok")
        }.getOrDefault(false)
    }

    override suspend fun exec(
        cmd: String,
        cwd: String?,
        timeoutSec: Int,
        sudo: Boolean,
    ): RemoteExecResult = withContext(Dispatchers.IO) {
        val live = connect()
        prepare(live)
        val dir = cwd?.let { abs(it) } ?: base()
        val body = "cd ${quote(dir)} && $cmd"
        val full = if (sudo && !rootUser) "sudo -n sh -c ${quote(body)}" else body
        val result = execRaw(live, full, timeoutSec.coerceIn(1, 1800))
        if (sudo && !rootUser && result.code != 0 && result.stderr.contains("password is required")) {
            result.copy(
                stderr = result.stderr + "\n（这台机器没有免密 sudo。请在服务器上给该用户配 NOPASSWD，" +
                    "或换一个有 sudo 权限的账号）",
            )
        } else {
            result.copy(elevated = result.code == 0 && (rootUser || sudo))
        }
    }

    override suspend fun sysInfo(): RemoteSystem? = withContext(Dispatchers.IO) {
        val result = runCatching {
            val live = connect()
            prepare(live)
            val helper = base() + "/" + HELPER_DIR + "/" + HELPER_FILE
            val viaHelper = execRaw(live, "sh ${quote(helper)} info 2>/dev/null", 40)
            if (viaHelper.stdout.contains("DSHA_OS=")) viaHelper else execRaw(live, INLINE_INFO, 40)
        }.getOrNull() ?: return@withContext null

        val values = result.stdout.lineSequence()
            .mapNotNull { line ->
                val at = line.indexOf('=')
                if (at <= 0) null else line.substring(0, at).trim() to line.substring(at + 1).trim()
            }
            .toList()
        if (values.isEmpty()) return@withContext null

        fun one(key: String): String = values.firstOrNull { it.first == key }?.second.orEmpty()
        val tools = values.filter { it.first == "DSHA_TOOL" && it.second.isNotBlank() }
            .associate { it.second to it.second }
        val sudoRaw = one("DSHA_SUDO")
        RemoteSystem(
            root = one("DSHA_UID") == "0",
            user = one("DSHA_USER"),
            sudo = sudoRaw == "yes",
            sudoReason = when (sudoRaw) {
                "none" -> "这台机器没装 sudo"
                "no" -> "sudo 需要密码，非交互环境用不了"
                else -> ""
            },
            distro = one("DSHA_OS"),
            kernel = one("DSHA_KERNEL"),
            python = one("DSHA_PY"),
            tools = tools,
            // SSH 实现在客户端把这几个能力都实现了，服务端不需要自报
            capabilities = setOf("grep", "glob", "patch", "tree", "install", "read_page"),
        )
    }

    // ── 文件 ────────────────────────────────────────────────

    override suspend fun list(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        withSftp { channel ->
            val dir = abs(path)
            @Suppress("UNCHECKED_CAST")
            val entries = channel.ls(dir) as java.util.Vector<ChannelSftp.LsEntry>
            entries.mapNotNull { entry ->
                if (entry.filename == "." || entry.filename == "..") return@mapNotNull null
                RemoteEntry(
                    name = entry.filename,
                    isDir = entry.attrs.isDir,
                    size = entry.attrs.size,
                    mtime = entry.attrs.mTime * 1000L,
                )
            }
        }
    }

    override suspend fun read(path: String, maxChars: Int): String = withContext(Dispatchers.IO) {
        readAll(path).take(maxChars)
    }

    override suspend fun readPage(path: String, offset: Int, limit: Int): RemotePage =
        withContext(Dispatchers.IO) {
            val text = readAll(path)
            /*
             * 末尾的换行是"第 3 行结束"，不是"第 4 行开始"。
             * 直接 `lines()` 会多出一个空行，read_file 的回执就会写成
             * "共 4 行"，而编辑器里只看到 3 行 —— 模型据此算 offset 会错位。
             */
            val raw = text.split("\n")
            val lines = if (raw.size > 1 && raw.last().isEmpty()) raw.dropLast(1) else raw
            val total = if (text.isEmpty()) 0 else lines.size
            val start = (offset - 1).coerceIn(0, total)
            val end = (start + limit).coerceIn(start, total)
            RemotePage(
                content = lines.subList(start, end).joinToString("\n"),
                startLine = start + 1,
                endLine = end,
                totalLines = total,
            )
        }

    override suspend fun write(path: String, content: String, append: Boolean): List<DiffLine> =
        withContext(Dispatchers.IO) {
            withSftp { channel ->
                val target = abs(path)
                val before = runCatching {
                    channel.get(target).use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
                mkdirs(channel, target.substringBeforeLast('/', ""))
                val after = if (append && before != null) before + content else content
                channel.put(ByteArrayInputStream(after.toByteArray(Charsets.UTF_8)), target, ChannelSftp.OVERWRITE)
                Diffs.compute(before.orEmpty(), after)
            }
        }

    override suspend fun edit(path: String, old: String, new: String): List<DiffLine> =
        withContext(Dispatchers.IO) {
            withSftp { channel ->
                val target = abs(path)
                val before = runCatching {
                    channel.get(target).use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull() ?: throw IOException("文件不存在：$path")
                val count = occurrences(before, old)
                if (count == 0) {
                    throw IOException("原文没有找到。请先用 read_file 看清当前内容（缩进、空行都要一致），再给出 old。")
                }
                if (count > 1) {
                    throw IOException("原文在这份文件里出现了 $count 次，无法确定改哪一处。请带上前后几行让它唯一。")
                }
                val after = before.replace(old, new)
                channel.put(ByteArrayInputStream(after.toByteArray(Charsets.UTF_8)), target, ChannelSftp.OVERWRITE)
                Diffs.compute(before, after)
            }
        }

    override suspend fun patch(path: String, hunks: List<RemoteHunk>): List<DiffLine> =
        withContext(Dispatchers.IO) {
            withSftp { channel ->
                val target = abs(path)
                val before = runCatching {
                    channel.get(target).use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull() ?: throw IOException("文件不存在：$path")
                var working = before
                hunks.forEachIndexed { index, hunk ->
                    val count = occurrences(working, hunk.old)
                    if (count == 0) {
                        throw IOException(
                            "第 ${index + 1} 处改动找不到原文，整批未写入。请重新读一遍文件再改。",
                        )
                    }
                    if (count > 1 && !hunk.all) {
                        throw IOException(
                            "第 ${index + 1} 处改动的原文出现了 $count 次。补上上下文，或把 all 设为 true。",
                        )
                    }
                    working = if (hunk.all) working.replace(hunk.old, hunk.new) else working.replaceFirst(hunk.old, hunk.new)
                }
                channel.put(ByteArrayInputStream(working.toByteArray(Charsets.UTF_8)), target, ChannelSftp.OVERWRITE)
                Diffs.compute(before, working)
            }
        }

    private fun occurrences(text: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = text.indexOf(needle)
        while (index >= 0) {
            count++
            index = text.indexOf(needle, index + needle.length)
        }
        return count
    }

    override suspend fun grep(
        pattern: String,
        path: String,
        glob: String?,
        regex: Boolean,
        caseSensitive: Boolean,
        max: Int,
        context: Int,
    ): RemoteGrepResult = withContext(Dispatchers.IO) {
        val live = connect()
        prepare(live)
        val dir = abs(path)
        val flags = buildString {
            append("-rn")
            if (!regex) append(" -F")
            if (!caseSensitive) append(" -i")
            if (context > 0) append(" -C ").append(context.coerceIn(0, 5))
        }
        val include = glob?.takeIf { it.isNotBlank() }?.let { " --include=${quote(it)}" }.orEmpty()
        val countCmd = "find . -type f 2>/dev/null | wc -l"
        val command = "cd ${quote(dir)} && " +
            "grep $flags --binary-files=without-match -I$include -e ${quote(pattern)} . 2>/dev/null; " +
            "echo __DSHA_EOF__; $countCmd"
        val raw = execRaw(live, command, 120)
        val text = raw.stdout.substringBefore("__DSHA_EOF__")
        val scanned = raw.stdout.substringAfter("__DSHA_EOF__").trim().toIntOrNull() ?: 0

        val matches = mutableListOf<RemoteGrepMatch>()
        val counts = LinkedHashMap<String, Int>()
        text.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val match = Regex("^(.+?):(\\d+):(.*)$").find(line)
            if (match != null) {
                val file = match.groupValues[1].removePrefix("./")
                val number = match.groupValues[2].toIntOrNull() ?: return@forEach
                matches += RemoteGrepMatch(file, number, match.groupValues[3])
                counts[file] = (counts[file] ?: 0) + 1
                return@forEach
            }
            // -C 的上下文行：file-line-text
            val ctx = Regex("^(.+?)-(\\d+)-(.*)$").find(line) ?: return@forEach
            val file = ctx.groupValues[1].removePrefix("./")
            val number = ctx.groupValues[2].toIntOrNull() ?: return@forEach
            matches += RemoteGrepMatch(file, number, ctx.groupValues[3])
        }
        RemoteGrepResult(
            matches = matches.take(max.coerceIn(1, 2000)),
            files = counts.entries.sortedByDescending { it.value }.map { it.key to it.value },
            scanned = scanned,
            truncated = matches.size > max,
        )
    }

    override suspend fun glob(pattern: String, path: String, max: Int): List<RemoteEntry> =
        withContext(Dispatchers.IO) {
            val live = connect()
            prepare(live)
            val dir = abs(path)
            val regex = globToRegex(pattern)
            val limit = max.coerceIn(1, 2000)
            /*
             * 先试 GNU 的 -printf（能一起拿到大小，省 N 次 stat）。
             * 老 busybox 没有它，退回纯路径列表 —— 大小留空比显示一个假的 0 好。
             */
            val gnu = "cd ${quote(dir)} && find . -type f -printf '%s\\t%p\\n' 2>/dev/null | head -n $limit"
            val plain = "cd ${quote(dir)} && find . -type f 2>/dev/null | head -n $limit"
            val first = execRaw(live, gnu, 90)
            // 注意末尾那个空行：`lineSequence()` 会把它也给你，忘了过滤就会永远判定 GNU 失败
            val lines = first.stdout.lineSequence().filter { it.isNotBlank() }.toList()
            val useGnu = lines.isNotEmpty() && lines.all { it.contains('\t') }
            val output = if (useGnu) first.stdout else execRaw(live, plain, 90).stdout

            output.lineSequence().mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val size = if (useGnu) line.substringBefore('\t').toLongOrNull() ?: -1L else -1L
                val name = (if (useGnu) line.substringAfter('\t') else line).removePrefix("./")
                if (name.isEmpty()) return@mapNotNull null
                if (!Regex(regex).matches(name)) return@mapNotNull null
                RemoteEntry(name = name, isDir = false, size = size, mtime = 0L)
            }.take(limit).toList()
        }

    override suspend fun tree(path: String, depth: Int): String = withContext(Dispatchers.IO) {
        val live = connect()
        prepare(live)
        val dir = abs(path)
        val command = "cd ${quote(dir)} && find . -maxdepth ${depth.coerceIn(1, 6)} 2>/dev/null | " +
            "head -n 300 | while IFS= read -r p; do if [ -d \"\$p\" ]; then echo \"\$p/\"; else echo \"\$p\"; fi; done"
        execRaw(live, command, 60).stdout.trim()
    }

    override suspend fun install(kind: String, packages: List<String>, mirror: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            val live = connect()
            prepare(live)
            val list = packages.joinToString(" ") { quote(it) }
            val sudo = if (rootUser) "" else "sudo -n "
            val command = when (kind) {
                "apt" -> "export DEBIAN_FRONTEND=noninteractive; " +
                    "${sudo}apt-get update -qq && ${sudo}apt-get install -y --no-install-recommends $list"
                "pip" -> "python3 -m pip install --upgrade $list || " +
                    "python3 -m pip install --break-system-packages --upgrade $list"
                "npm" -> "npm install -g $list"
                else -> return@withContext false to "kind 只能是 apt / pip / npm"
            }
            val result = execRaw(live, command, 900)
            val note = if (kind == "apt" && mirror != "none") {
                "\n（SSH 模式不改软件源：那是服务器自己的配置，动了会影响别的服务）"
            } else {
                ""
            }
            val ok = result.code == 0
            val log = buildString {
                if (result.stdout.isNotBlank()) append(result.stdout.trimEnd())
                if (result.stderr.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(result.stderr.trimEnd())
                }
                append(note)
            }
            ok to log
        }

    override suspend fun upload(
        dir: String,
        name: String,
        bytes: ByteArray,
        onProgress: ((Float) -> Unit)?,
    ): Long = withContext(Dispatchers.IO) {
        withSftp { channel ->
            val target = abs(if (dir.isBlank()) name else "$dir/$name")
            mkdirs(channel, target.substringBeforeLast('/', ""))
            channel.put(
                ByteArrayInputStream(bytes),
                target,
                ProgressMonitor(onProgress, bytes.size.toLong()),
                ChannelSftp.OVERWRITE,
            )
            bytes.size.toLong()
        }
    }

    override suspend fun uploadFile(
        dir: String,
        name: String,
        file: File,
        onProgress: ((Float) -> Unit)?,
    ): Long = withContext(Dispatchers.IO) {
        withSftp { channel ->
            val target = abs(if (dir.isBlank()) name else "$dir/$name")
            mkdirs(channel, target.substringBeforeLast('/', ""))
            channel.put(
                file.absolutePath,
                target,
                ProgressMonitor(onProgress, file.length()),
                ChannelSftp.OVERWRITE,
            )
            file.length()
        }
    }

    override suspend fun download(path: String): ByteArray = withContext(Dispatchers.IO) {
        withSftp { channel ->
            channel.get(abs(path)).use { it.readBytes() }
        }
    }

    override suspend fun remove(path: String): Unit = withContext(Dispatchers.IO) {
        withSftp { channel ->
            val target = abs(path)
            try {
                channel.rm(target)
            } catch (error: SftpException) {
                // 空目录用 rmdir；非空目录不在这里递归删 —— 那太容易误伤
                channel.rmdir(target)
            }
        }
    }

    override suspend fun mkdir(path: String): Unit = withContext(Dispatchers.IO) {
        withSftp { channel -> mkdirs(channel, abs(path)) }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { withSftp { channel -> channel.lstat(abs(path)); true } }.getOrDefault(false)
    }

    private class ProgressMonitor(
        private val callback: ((Float) -> Unit)?,
        private val total: Long,
    ) : SftpProgressMonitor {
        private var sent = 0L

        override fun init(op: Int, src: String?, dest: String?, max: Long) = Unit

        override fun count(count: Long): Boolean {
            sent = count
            callback?.invoke(if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else 0f)
            return true
        }

        override fun end() {
            callback?.invoke(1f)
        }
    }
}

/**
 * 推到远端的辅助脚本。
 *
 * 只输出稳定的 `KEY=VALUE` 行：客户端不必为每个发行版写一套解析。
 * 它不改变系统任何状态，删掉也能工作（客户端有内联兜底）。
 */
private val HELPER_SCRIPT = """
dsha_os() {
  if [ -r /etc/os-release ]; then
    . /etc/os-release
    printf '%s' "${'$'}{PRETTY_NAME:-${'$'}{NAME:-Linux}}"
  else
    uname -s
  fi
}
dsha_info() {
  echo "DSHA_OS=$(dsha_os)"
  echo "DSHA_KERNEL=$(uname -r 2>/dev/null)"
  echo "DSHA_USER=$(id -un 2>/dev/null)"
  echo "DSHA_UID=$(id -u 2>/dev/null)"
  if command -v sudo >/dev/null 2>&1; then
    if sudo -n true >/dev/null 2>&1; then echo "DSHA_SUDO=yes"; else echo "DSHA_SUDO=no"; fi
  else
    echo "DSHA_SUDO=none"
  fi
  if command -v python3 >/dev/null 2>&1; then
    echo "DSHA_PY=$(python3 -V 2>&1 | head -n 1)"
  fi
  for t in git curl wget node npm rg unzip tar gcc make docker; do
    if command -v "${'$'}t" >/dev/null 2>&1; then echo "DSHA_TOOL=${'$'}t"; fi
  done
}
case "$1" in
  info) dsha_info ;;
  *) dsha_info ;;
esac
"""

/** 辅助脚本推不上去时的等价命令（POSIX sh，不依赖任何非标准工具）。 */
private val INLINE_INFO = """
echo "DSHA_OS=$( ( . /etc/os-release 2>/dev/null; printf '%s' "${'$'}{PRETTY_NAME:-Linux}" ) )"
echo "DSHA_KERNEL=$(uname -r 2>/dev/null)"
echo "DSHA_USER=$(id -un 2>/dev/null)"
echo "DSHA_UID=$(id -u 2>/dev/null)"
if command -v sudo >/dev/null 2>&1; then if sudo -n true >/dev/null 2>&1; then echo "DSHA_SUDO=yes"; else echo "DSHA_SUDO=no"; fi; else echo "DSHA_SUDO=none"; fi
if command -v python3 >/dev/null 2>&1; then echo "DSHA_PY=$(python3 -V 2>&1 | head -n 1)"; fi
for t in git curl wget node npm rg unzip tar gcc make docker; do if command -v "${'$'}t" >/dev/null 2>&1; then echo "DSHA_TOOL=${'$'}t"; fi; done
"""

/**
 * 通配符 → 正则。只处理 `**`、`*`、`?` 和字符转义，
 * 其余字符一律当作字面量 —— 文件名里的 `.` `+` `(` 不该被当成语法。
 */
internal fun globToRegex(glob: String): String {
    val out = StringBuilder("^")
    var index = 0
    val pattern = glob.trim().removePrefix("./")
    while (index < pattern.length) {
        val ch = pattern[index]
        when {
            ch == '*' && index + 1 < pattern.length && pattern[index + 1] == '*' -> {
                // `**/` 匹配零层或多层目录：根目录下的 a.py 也能被 **/*.py 命中
                if (index + 2 < pattern.length && pattern[index + 2] == '/') {
                    out.append("(?:.*/)?")
                    index += 3
                    continue
                }
                out.append(".*")
                index += 2
                continue
            }
            ch == '*' -> out.append("[^/]*")
            ch == '?' -> out.append("[^/]")
            ch in ".+()[]{}\\^\$|" -> out.append('\\').append(ch)
            else -> out.append(ch)
        }
        index++
    }
    out.append('$')
    return out.toString()
}
