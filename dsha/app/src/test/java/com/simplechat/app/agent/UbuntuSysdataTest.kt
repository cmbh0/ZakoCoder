package com.simplechat.app.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 假 /proc 数据。
 *
 * 这套文件只有一个用途：宿主 `/proc/stat` 之类读不到时顶上，让 `top`、
 * `free`、`nproc`、`dpkg` 的 postinst 不至于莫名失败。测试守着两件事 ——
 * **该有的都有**、**重复调用不覆盖**（用户手工改过就按用户的来）。
 */
class UbuntuSysdataTest {

    private fun tempRoot(): File =
        File.createTempFile("dsha-sysdata", "").let {
            it.delete()
            it.mkdirs()
            it
        }

    @Test
    fun `生成全部假文件`() {
        val root = tempRoot()
        UbuntuSysdata.ensure(root)

        listOf(
            "proc/.stat",
            "proc/.loadavg",
            "proc/.uptime",
            "proc/.version",
            "proc/.vmstat",
            "proc/.sysctl_entry_cap_last_cap",
            "proc/.sysctl_inotify_max_user_watches",
        ).forEach { relative ->
            val file = File(root, relative)
            assertTrue("缺 $relative", file.isFile)
            assertTrue("$relative 是空的", file.length() > 0)
        }
        assertTrue(File(root, "proc/.stat").readText().startsWith("cpu "))
        root.deleteRecursively()
    }

    @Test
    fun `已存在就不覆盖`() {
        val root = tempRoot()
        val stat = File(root, "proc/.stat")
        stat.parentFile?.mkdirs()
        stat.writeText("cpu  1 2 3\n")

        UbuntuSysdata.ensure(root)

        assertEquals("cpu  1 2 3\n", stat.readText())
        root.deleteRecursively()
    }

    @Test
    fun `宿主可读时不需要绑定`() {
        val root = tempRoot()
        UbuntuSysdata.ensure(root)
        /*
         * 测试机（Linux）上 /proc/stat 一定可读，所以这里应当一个都不绑。
         * 真机上读不到时会返回 fake→host 的映射，那一条没法在 CI 里造。
         */
        assertTrue(UbuntuSysdata.needed(root).isEmpty())
        root.deleteRecursively()
    }
}
