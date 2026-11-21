package com.simplechat.app.agent

import java.io.File

/**
 * 伪造的 `/proc` 数据 —— 照搬 Operit 的 `setup_fake_sysdata.sh`。
 *
 * ### 为什么需要这个
 *
 * Android 的容器里没有真 procfs：proot 只能把宿主的 `/proc` 绑进去，而宿主
 * `/proc` 上有一批条目对普通 App 是**不给读**的（`/proc/stat` 的某些字段、
 * `/proc/sys/kernel/cap_last_cap` 等，各家 ROM 限制不同）。读不到时：
 *
 * ```
 * top: failed to read /proc/stat
 * free: cannot open /proc/meminfo
 * apt/dpkg 的 postinst 脚本里 `nproc` 拿到 0，configure 直接退出
 * ```
 *
 * 表现就是"大部分命令能用，个别命令莫名失败"。
 *
 * 做法与 Operit / proot-distro 一致：在 rootfs 的 `proc/` 下放一份**静态**
 * 假数据（文件名以 `.` 开头，不会被当成真的 /proc 条目），启动时只在宿主
 * 对应文件**读不到**的情况下才 `-b` 过去。宿主能读就用真的 —— 假数据里的
 * CPU 时间永远是那一组，`top` 会显示 0% 占用，只能算"能跑"而不是"准确"。
 *
 * 数据取自 Operit 的脚本（其值来自一台 8 核 8 GiB 的 Linux 6.2 虚拟机），
 * 只保留 `free` / `top` / `vmstat` / `nproc` / inotify 真正会读的字段。
 */
internal object UbuntuSysdata {

    /** rootfs 里的相对路径 → 内容。文件已存在就不覆盖（保持幂等）。 */
    private val Files: Map<String, String> = mapOf(
        "proc/.loadavg" to "0.12 0.07 0.02 2/165 765\n",
        "proc/.stat" to """
            cpu  1957 0 2877 93280 262 342 254 87 0 0
            cpu0 31 0 226 12027 82 10 4 9 0 0
            cpu1 45 0 664 11144 21 263 233 12 0 0
            cpu2 494 0 537 11283 27 10 3 8 0 0
            cpu3 359 0 234 11723 24 26 5 7 0 0
            cpu4 295 0 268 11772 10 12 2 12 0 0
            cpu5 270 0 251 11833 15 3 1 10 0 0
            cpu6 430 0 520 11386 30 8 1 12 0 0
            cpu7 30 0 172 12108 50 8 1 13 0 0
            intr 127541 38 290 0 0 0 0 4 0 1 0 0 25329 258 0 5777 277 0 0 0
            ctxt 140223
            btime 1680020856
            processes 772
            procs_running 2
            procs_blocked 0
            softirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677
        """.trimIndent() + "\n",
        "proc/.uptime" to "124.08 932.80\n",
        "proc/.version" to "Linux version 6.1.0-dsha (proot@dsha) (gcc 13.3.0) #1 SMP\n",
        "proc/.vmstat" to """
            nr_free_pages 1743136
            nr_zone_inactive_anon 179281
            nr_zone_active_anon 7183
            nr_zone_inactive_file 22858
            nr_zone_active_file 51328
            nr_zone_unevictable 642
            nr_slab_reclaimable 8091
            nr_slab_unreclaimable 2203
            numa_hit 1259626
            numa_miss 0
            numa_foreign 0
            numa_interleave 720
            numa_local 1259626
            numa_other 0
            pgpgin 83231
            pgpgout 19023
            pswpin 0
            pswpout 0
            pgalloc_dma 0
            pgalloc_normal 1528332
            pgfree 1602484
            pgactivate 28311
            pgdeactivate 18221
            pgfault 421923
            pgmajfault 1902
            pgrefill 21403
            pgscan_kswapd 0
            pgsteal_kswapd 0
            thp_fault_alloc 13
            thp_fault_fallback 0
            thp_collapse_alloc 4
            compact_stall 0
            compact_fail 0
            compact_success 0
            ksm_swpin_copy 0
        """.trimIndent() + "\n",
        "proc/.sysctl_entry_cap_last_cap" to "40\n",
        "proc/.sysctl_inotify_max_user_watches" to "4096\n",
    )

    /**
     * 宿主上的这些路径**读不到**时，就用 rootfs 里对应的假文件绑过去。
     *
     * 顺序有意义：先 `/proc`，再它下面的子路径。
     */
    val Bindings: List<Pair<String, String>> = listOf(
        "/proc/stat" to "proc/.stat",
        "/proc/loadavg" to "proc/.loadavg",
        "/proc/uptime" to "proc/.uptime",
        "/proc/version" to "proc/.version",
        "/proc/vmstat" to "proc/.vmstat",
        "/proc/sys/kernel/cap_last_cap" to "proc/.sysctl_entry_cap_last_cap",
        "/proc/sys/fs/inotify/max_user_watches" to "proc/.sysctl_inotify_max_user_watches",
    )

    /** 补齐所有假文件（幂等）。装完 rootfs 调一次，之后每次启动再兜一次。 */
    fun ensure(rootfs: File) {
        runCatching {
            File(rootfs, "proc").mkdirs()
            Files.forEach { (relative, content) ->
                val file = File(rootfs, relative)
                if (file.isFile) return@forEach
                file.parentFile?.mkdirs()
                file.writeText(content)
            }
        }
    }

    /**
     * 需要绑的假文件：宿主路径读不到、且 rootfs 里备好了。
     *
     * 宿主能读到就不绑 —— 真数据比假数据好。
     */
    fun needed(rootfs: File): List<Pair<String, String>> = Bindings.mapNotNull { (host, relative) ->
        val fake = File(rootfs, relative)
        if (!fake.isFile) return@mapNotNull null
        if (runCatching { File(host).canRead() }.getOrDefault(false)) return@mapNotNull null
        fake.absolutePath to host
    }
}
