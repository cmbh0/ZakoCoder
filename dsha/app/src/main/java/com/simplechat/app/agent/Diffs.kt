package com.simplechat.app.agent

import kotlinx.serialization.Serializable

/**
 * diff 的一行。
 *
 * 只保留三种，不做"修改"这一种 —— 统一 diff 的语义里本来就没有"修改"：
 * 一行被改，等价于删掉旧行、加上新行。多一种状态只会让人多猜一次。
 */
@Serializable
data class DiffLine(val kind: Kind, val text: String) {

    @Serializable
    enum class Kind {
        /** 未变化。只作为上下文出现，且会被裁掉大半。 */
        SAME,

        /** 新增。 */
        ADD,

        /** 删除。 */
        REMOVE,
    }
}

/**
 * 行级 diff。
 *
 * ### 为什么不用 LCS
 *
 * 标准 LCS 是 O(n·m)：一个 3000 行的文件做编辑，最坏是九百万次比较，
 * 在手机上会直接卡住主线程。而工具改文件有一个很强的性质 ——
 * **改动往往是局部的**，剥掉公共前缀和后缀之后，中间那一段通常只有几十行。
 *
 * 所以这里走"剥前后缀 + 中间全删全增"：
 * ```
 * 相同前缀 ┐
 *          ├─ 中间：旧全删、新全增
 * 相同后缀 ┘
 * ```
 * 结果不如 LCS 精细（中间若有两处不相邻的改动，中间未变的部分也会被标成改动），
 * 但对"看看它到底动了什么"这个用途完全够，且线性时间、几毫秒出结果。
 *
 * 真正的 LCS 留给以后需要"精确合并"的场景 —— 那是另一件事。
 */
object Diffs {

    /** 中间段超过这个行数就裁掉，只留头尾各若干行。 */
    private const val MaxMiddle = 240

    /** 上下文行：中间段前后各保留几行未变内容，方便定位。 */
    private const val ContextLines = 3

    /**
     * 算一份 diff。[before] 为空视为新建文件（不会产出删除行）。
     *
     * 返回空列表表示"没有变化" —— 调用方据此决定界面上要不要显示 diff 区块。
     */
    fun compute(before: String, after: String): List<DiffLine> {
        if (before == after) return emptyList()

        val oldLines = before.lines()
        val newLines = after.lines()

        // 空文件：全新增（不要因为 `"".lines()` 得到 `[""]` 而多出一行空行）
        if (before.isEmpty()) {
            return newLines.filterIndexed { index, _ -> !(newLines.size == 1 && newLines[0].isEmpty()) }
                .map { DiffLine(DiffLine.Kind.ADD, it) }
        }

        var prefix = 0
        val maxPrefix = minOf(oldLines.size, newLines.size)
        while (prefix < maxPrefix && oldLines[prefix] == newLines[prefix]) prefix++

        var suffix = 0
        val maxSuffix = minOf(oldLines.size - prefix, newLines.size - prefix)
        while (suffix < maxSuffix &&
            oldLines[oldLines.size - 1 - suffix] == newLines[newLines.size - 1 - suffix]
        ) {
            suffix++
        }

        val out = ArrayList<DiffLine>()

        // 前文上下文：只留最后几行，多了没意义
        val headFrom = (prefix - ContextLines).coerceAtLeast(0)
        for (i in headFrom until prefix) out += DiffLine(DiffLine.Kind.SAME, oldLines[i])

        val removed = oldLines.subList(prefix, oldLines.size - suffix)
        val added = newLines.subList(prefix, newLines.size - suffix)

        fun emit(lines: List<String>, kind: DiffLine.Kind) {
            if (lines.size <= MaxMiddle) {
                lines.forEach { out += DiffLine(kind, it) }
                return
            }
            val head = MaxMiddle / 2
            val tail = MaxMiddle - head
            lines.take(head).forEach { out += DiffLine(kind, it) }
            out += DiffLine(DiffLine.Kind.SAME, "… 省略 ${lines.size - MaxMiddle} 行 …")
            lines.takeLast(tail).forEach { out += DiffLine(kind, it) }
        }

        emit(removed, DiffLine.Kind.REMOVE)
        emit(added, DiffLine.Kind.ADD)

        // 后文上下文
        val tailTo = (suffix).coerceAtMost(ContextLines)
        for (i in 0 until tailTo) {
            out += DiffLine(DiffLine.Kind.SAME, newLines[newLines.size - suffix + i])
        }
        return out
    }

    /** 统计口径：`+12 −3`。空 diff 返回 null，界面据此不显示那一行。 */
    fun summary(lines: List<DiffLine>): Pair<Int, Int>? {
        if (lines.isEmpty()) return null
        val add = lines.count { it.kind == DiffLine.Kind.ADD }
        val remove = lines.count { it.kind == DiffLine.Kind.REMOVE }
        return if (add == 0 && remove == 0) null else add to remove
    }
}
