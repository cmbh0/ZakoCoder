package com.simplechat.app.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 图标集。
 *
 * 绝大多数图标取自 **Lucide**（https://lucide.dev，ISC License）的官方 SVG 几何，
 * 在构建期转写为 Compose `ImageVector` 源码 —— 因此**没有任何运行时依赖**，
 * 也不受 `material-icons-extended` 的体积与构建耗时问题影响
 * （Google 已明确不推荐该库）。
 *
 * 三个自定义图标例外：[Menu]（官方 chat-demo 的一长一短双横条）、[Bubble]（品牌标识）、
 * [Stop]（实心圆角方块，停止生成）。
 *
 * 统一约定：24×24 viewport，黑色描边/填充，由 `Icon(tint = ...)` 着色。
 * 描边宽度 2dp、圆头圆角，与 Lucide 原始设计一致。
 */
object AppIcons {

    private fun vector(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()

    /** 实心路径。 */
    private fun ImageVector.Builder.solid(block: PathBuilder.() -> Unit) {
        path(fill = SolidColor(Color.Black), pathBuilder = block)
    }

    /**
     * 描边路径（Lucide 风格）。
     *
     * 线宽 **1.8**，不是 Lucide 原版的 2。
     *
     * 从 DeepSeek 2.8.0 的截图量的：1080px 宽、density 2.75 的机器上，
     * 它菜单图标的线宽是 4~5px ≈ 1.7dp；顶栏那两条横条也是同一档。我们的 2.0
     * 在 18~22dp 这个尺寸上整体偏"胖"，一屏图标看下来就显得笨。1.8 是
     * 保持可读性又不发糊的下限。
     */
    private fun ImageVector.Builder.stroke(
        width: Float = 1.9f,
        block: PathBuilder.() -> Unit,
    ) {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }

    // ══════════════════════════════════════════════════════
    //  自定义图标
    // ══════════════════════════════════════════════════════

    /**
     * 抽屉按钮。几何来自官方 chat-demo 的 `IconMenuOutline20` ——
     * 一条长横 + 一条短横的实心条，见 [DsIcons.Menu]。
     */
    val Menu: ImageVector get() = DsIcons.Menu

    /**
     * 时钟：定时任务。
     *
     * 与其余手绘图标同一口径（24 viewport、1.9 线宽、圆头），
     * 因为 DeepSeek 的安装包里没有这个图形 —— 不能硬套一个"看着像"的。
     */
    val Clock: ImageVector by lazy {
        vector("Clock") {
            stroke(width = 1.9f) {
                moveTo(21f, 12f)
                arcToRelative(9f, 9f, 0f, true, true, -18f, 0f)
                arcToRelative(9f, 9f, 0f, true, true, 18f, 0f)
                close()
                moveTo(12f, 7.2f)
                verticalLineTo(12f)
                lineTo(15.4f, 14.2f)
            }
        }
    }

    /** 品牌标识：极简对话气泡。 */
    val Bubble: ImageVector by lazy {
        vector("Bubble") {
            solid {
                moveTo(8f, 3f)
                horizontalLineTo(16f)
                arcToRelative(5f, 5f, 0f, false, true, 5f, 5f)
                verticalLineTo(13f)
                arcToRelative(5f, 5f, 0f, false, true, -5f, 5f)
                horizontalLineTo(12f)
                lineTo(7f, 22f)
                verticalLineTo(18f)
                horizontalLineTo(8f)
                arcToRelative(5f, 5f, 0f, false, true, -5f, -5f)
                verticalLineTo(8f)
                arcToRelative(5f, 5f, 0f, false, true, 5f, -5f)
                close()
            }
        }
    }

    /**
     * 停止生成：实心圆角方块。
     *
     * 圆角取 3.5 / 边长 12 ≈ 29% —— 先前是 1.5 / 10 = 15%，
     * 在 15dp 下几乎就是个直角方块，和周围一圈圆角/胶囊对不上。
     * 边长也从 10 加到 12：这个图标本来就该比箭头略大，才压得住实心底。
     */
    val Stop: ImageVector by lazy {
        vector("Stop") {
            solid {
                moveTo(9.5f, 6f)
                horizontalLineTo(14.5f)
                arcToRelative(3.5f, 3.5f, 0f, false, true, 3.5f, 3.5f)
                verticalLineTo(14.5f)
                arcToRelative(3.5f, 3.5f, 0f, false, true, -3.5f, 3.5f)
                horizontalLineTo(9.5f)
                arcToRelative(3.5f, 3.5f, 0f, false, true, -3.5f, -3.5f)
                verticalLineTo(9.5f)
                arcToRelative(3.5f, 3.5f, 0f, false, true, 3.5f, -3.5f)
                close()
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  导航与顶栏
    // ══════════════════════════════════════════════════════

    /** 历史对话（Lucide: panel-left） */
    val PanelLeft: ImageVector by lazy {
        vector("PanelLeft") {
            stroke {
                moveTo(9f, 3f)
                verticalLineToRelative(18f)
                moveTo(5f, 3f)
                horizontalLineTo(19f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                verticalLineTo(19f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                close()
            }
        }
    }

    /** 圆圈加号（Lucide: circle-plus）。用于「新建对话」与输入区的附件入口。 */
    val PlusCircle: ImageVector get() = DsIcons.NewChat

    /**
     * 盾牌（Lucide: shield）—— 审批档位的入口。
     *
     * 为什么不是齿轮 / 对勾：这个入口管的是"这次允许 AI 动多狠"，
     * 语义是**防护**，不是设置。盾牌在一屏幕图标里也是最容易被认出来的那个。
     */
    val Shield: ImageVector by lazy {
        vector("Shield") {
            stroke(width = 1.8f) {
                moveTo(12f, 3f)
                lineTo(4.5f, 5.6f)
                verticalLineTo(11.2f)
                curveTo(4.5f, 16.1f, 7.7f, 19.7f, 12f, 21.2f)
                curveTo(16.3f, 19.7f, 19.5f, 16.1f, 19.5f, 11.2f)
                verticalLineTo(5.6f)
                close()
            }
        }
    }

    /**
     * 纯加号，**不带外圈**。
     *
     * 输入栏那颗 ⊕ 的外圈由 `border` 画在 32dp 触控格上 —— 图标再自带一圈，
     * 就成了两个同心圆，而且内圈会比外圈粗，比例全乱。
     *
     * ⚠️ 这里用的是**自绘的 1.5 描边加号**，不是 [DsIcons.PlusDs]。
     * 官方那枚是 20 viewport 上的实心矩形（笔画 2/20 = 10%），放到 18dp 上
     * 线宽 1.8dp；而外圈只有 1dp —— 里面那根比外圈还粗，看起来"糊成一坨"。
     * 用户反馈的就是这个。描边 1.5 在 18dp 上约 1.1dp，与外圈同档。
     */
    val Plus: ImageVector by lazy {
        vector("PlusThin") {
            stroke(width = 1.5f) {
                moveTo(12f, 5f)
                verticalLineTo(19f)
                moveTo(5f, 12f)
                horizontalLineTo(19f)
            }
        }
    }

    /** 静音（Lucide: bell-off） */
    val BellOff: ImageVector by lazy {
        vector("BellOff") {
            stroke {
                moveTo(10.268f, 21f)
                arcToRelative(2f, 2f, 0f, false, false, 3.464f, 0f)
                moveTo(17f, 17f)
                horizontalLineTo(4f)
                arcToRelative(1f, 1f, 0f, false, true, -0.74f, -1.673f)
                curveTo(4.59f, 13.956f, 6f, 12.499f, 6f, 8f)
                arcToRelative(6f, 6f, 0f, false, true, 0.258f, -1.742f)
                moveTo(2f, 2f)
                lineToRelative(20f, 20f)
                moveTo(8.668f, 3.01f)
                arcTo(6f, 6f, 0f, false, true, 18f, 8f)
                curveToRelative(0f, 2.687f, 0.77f, 4.653f, 1.707f, 6.05f)
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  通用
    // ══════════════════════════════════════════════════════

    /** 关闭（Lucide: x） */
    val Close: ImageVector by lazy {
        vector("Close") {
            stroke {
                moveTo(18f, 6f)
                lineTo(6f, 18f)
                moveTo(6f, 6f)
                lineToRelative(12f, 12f)
            }
        }
    }

    /** 对勾（Lucide: check） */
    val Check: ImageVector get() = DsIcons.CheckDs

    /** 搜索（Lucide: search） */
    val Search: ImageVector get() = DsIcons.SearchDs

    /** 更多（Lucide: ellipsis） */
    val More: ImageVector get() = DsIcons.More

    /** 设置（Lucide: settings） */
    val Settings: ImageVector by lazy {
        vector("Settings") {
            stroke {
                moveTo(9.671f, 4.136f)
                arcToRelative(2.34f, 2.34f, 0f, false, true, 4.659f, 0f)
                arcToRelative(2.34f, 2.34f, 0f, false, false, 3.319f, 1.915f)
                arcToRelative(2.34f, 2.34f, 0f, false, true, 2.33f, 4.033f)
                arcToRelative(2.34f, 2.34f, 0f, false, false, 0f, 3.831f)
                arcToRelative(2.34f, 2.34f, 0f, false, true, -2.33f, 4.033f)
                arcToRelative(2.34f, 2.34f, 0f, false, false, -3.319f, 1.915f)
                arcToRelative(2.34f, 2.34f, 0f, false, true, -4.659f, 0f)
                arcToRelative(2.34f, 2.34f, 0f, false, false, -3.32f, -1.915f)
                arcToRelative(2.34f, 2.34f, 0f, false, true, -2.33f, -4.033f)
                arcToRelative(2.34f, 2.34f, 0f, false, false, 0f, -3.831f)
                arcTo(2.34f, 2.34f, 0f, false, true, 6.35f, 6.051f)
                arcToRelative(2.34f, 2.34f, 0f, false, false, 3.319f, -1.915f)
                moveTo(9f, 12f)
                arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
                close()
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  展开 / 折叠 / 导航方向
    // ══════════════════════════════════════════════════════

    /** 展开（Lucide: chevron-down） */
    val ChevronDown: ImageVector get() = DsIcons.ChevronDownDs

    /** 折叠（Lucide: chevron-up） */
    val ChevronUp: ImageVector by lazy {
        vector("ChevronUp") {
            stroke {
                moveTo(18f, 15f)
                lineToRelative(-6f, -6f)
                lineToRelative(-6f, 6f)
            }
        }
    }

    /** 上一个（Lucide: chevron-left） */
    val ChevronLeft: ImageVector get() = DsIcons.ChevronLeftDs

    /** 下一个（Lucide: chevron-right） */
    val ChevronRight: ImageVector get() = DsIcons.ChevronRightDs

    /** 发送（Lucide: arrow-up） */
    val ArrowUp: ImageVector get() = DsIcons.SendArrow

    /** 回到底部（Lucide: arrow-down） */
    val ArrowDown: ImageVector by lazy {
        vector("ArrowDown") {
            stroke {
                moveTo(12f, 5f)
                verticalLineToRelative(14f)
                moveTo(19f, 12f)
                lineToRelative(-7f, 7f)
                lineToRelative(-7f, -7f)
            }
        }
    }

    /** 排序（Lucide: arrow-up-down） */
    val Sort: ImageVector by lazy {
        vector("Sort") {
            stroke {
                moveTo(21f, 16f)
                lineToRelative(-4f, 4f)
                lineToRelative(-4f, -4f)
                moveTo(17f, 20f)
                verticalLineTo(4f)
                moveTo(3f, 8f)
                lineToRelative(4f, -4f)
                lineToRelative(4f, 4f)
                moveTo(7f, 4f)
                verticalLineToRelative(16f)
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  消息操作
    // ══════════════════════════════════════════════════════

    /** 复制（Lucide: copy） */
    val Copy: ImageVector by lazy {
        vector("Copy") {
            stroke {
                moveTo(4f, 16f)
                curveToRelative(-1.1f, 0f, -2f, -0.9f, -2f, -2f)
                verticalLineTo(4f)
                curveToRelative(0f, -1.1f, 0.9f, -2f, 2f, -2f)
                horizontalLineToRelative(10f)
                curveToRelative(1.1f, 0f, 2f, 0.9f, 2f, 2f)
                moveTo(10f, 8f)
                horizontalLineTo(20f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                verticalLineTo(20f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(10f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(10f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                close()
            }
        }
    }

    /** 重新生成（Lucide: refresh-cw） */
    val Refresh: ImageVector get() = DsIcons.RefreshDs

    /**
     * 回滚到此处（Lucide: rotate-ccw）。
     *
     * 逆时针一圈 + 左上角一个"回头"的拐角 —— 与 Refresh（顺时针）方向相反，
     * 一眼能分出"再来一次"和"退回去"。
     */
    val RotateCcw: ImageVector by lazy {
        vector("RotateCcw") {
            stroke {
                moveTo(3f, 12f)
                arcToRelative(9f, 9f, 0f, true, false, 9f, -9f)
                arcToRelative(9.75f, 9.75f, 0f, false, false, -6.74f, 2.74f)
                lineTo(3f, 8f)
                moveTo(3f, 3f)
                verticalLineToRelative(5f)
                horizontalLineToRelative(5f)
            }
        }
    }

    /** 编辑（Lucide: pencil） */
    val Edit: ImageVector by lazy {
        vector("Edit") {
            stroke {
                moveTo(21.174f, 6.812f)
                arcToRelative(1f, 1f, 0f, false, false, -3.986f, -3.987f)
                lineTo(3.842f, 16.174f)
                arcToRelative(2f, 2f, 0f, false, false, -0.5f, 0.83f)
                lineToRelative(-1.321f, 4.352f)
                arcToRelative(0.5f, 0.5f, 0f, false, false, 0.623f, 0.622f)
                lineToRelative(4.353f, -1.32f)
                arcToRelative(2f, 2f, 0f, false, false, 0.83f, -0.497f)
                close()
                moveTo(15f, 5f)
                lineToRelative(4f, 4f)
            }
        }
    }

    /** 删除（Lucide: trash） */
    val Trash: ImageVector get() = DsIcons.TrashDs

    /** 置顶（Lucide: pin） */
    val Pin: ImageVector by lazy {
        vector("Pin") {
            stroke {
                moveTo(12f, 17f)
                verticalLineToRelative(5f)
                moveTo(9f, 10.76f)
                arcToRelative(2f, 2f, 0f, false, true, -1.11f, 1.79f)
                lineToRelative(-1.78f, 0.9f)
                arcTo(2f, 2f, 0f, false, false, 5f, 15.24f)
                verticalLineTo(16f)
                arcToRelative(1f, 1f, 0f, false, false, 1f, 1f)
                horizontalLineToRelative(12f)
                arcToRelative(1f, 1f, 0f, false, false, 1f, -1f)
                verticalLineToRelative(-0.76f)
                arcToRelative(2f, 2f, 0f, false, false, -1.11f, -1.79f)
                lineToRelative(-1.78f, -0.9f)
                arcTo(2f, 2f, 0f, false, true, 15f, 10.76f)
                verticalLineTo(7f)
                arcToRelative(1f, 1f, 0f, false, true, 1f, -1f)
                arcToRelative(2f, 2f, 0f, false, false, 0f, -4f)
                horizontalLineTo(8f)
                arcToRelative(2f, 2f, 0f, false, false, 0f, 4f)
                arcToRelative(1f, 1f, 0f, false, true, 1f, 1f)
                close()
            }
        }
    }

    /** 取消置顶（Lucide: pin-off） */
    val PinOff: ImageVector by lazy {
        vector("PinOff") {
            stroke {
                moveTo(12f, 17f)
                verticalLineToRelative(5f)
                moveTo(15f, 9.34f)
                verticalLineTo(7f)
                arcToRelative(1f, 1f, 0f, false, true, 1f, -1f)
                arcToRelative(2f, 2f, 0f, false, false, 0f, -4f)
                horizontalLineTo(7.89f)
                moveTo(2f, 2f)
                lineToRelative(20f, 20f)
                moveTo(9f, 9f)
                verticalLineToRelative(1.76f)
                arcToRelative(2f, 2f, 0f, false, true, -1.11f, 1.79f)
                lineToRelative(-1.78f, 0.9f)
                arcTo(2f, 2f, 0f, false, false, 5f, 15.24f)
                verticalLineTo(16f)
                arcToRelative(1f, 1f, 0f, false, false, 1f, 1f)
                horizontalLineToRelative(11f)
            }
        }
    }

    /** 多选（Lucide: list-checks） */
    val ListChecks: ImageVector by lazy {
        vector("ListChecks") {
            stroke {
                moveTo(13f, 5f)
                horizontalLineToRelative(8f)
                moveTo(13f, 12f)
                horizontalLineToRelative(8f)
                moveTo(13f, 19f)
                horizontalLineToRelative(8f)
                moveTo(3f, 17f)
                lineToRelative(2f, 2f)
                lineToRelative(4f, -4f)
                moveTo(3f, 7f)
                lineToRelative(2f, 2f)
                lineToRelative(4f, -4f)
            }
        }
    }

    /** 不参与上下文（Lucide: eye-off） */
    val EyeOff: ImageVector by lazy {
        vector("EyeOff") {
            stroke {
                moveTo(10.733f, 5.076f)
                arcToRelative(10.744f, 10.744f, 0f, false, true, 11.205f, 6.575f)
                arcToRelative(1f, 1f, 0f, false, true, 0f, 0.696f)
                arcToRelative(10.747f, 10.747f, 0f, false, true, -1.444f, 2.49f)
                moveTo(14.084f, 14.158f)
                arcToRelative(3f, 3f, 0f, false, true, -4.242f, -4.242f)
                moveTo(17.479f, 17.499f)
                arcToRelative(10.75f, 10.75f, 0f, false, true, -15.417f, -5.151f)
                arcToRelative(1f, 1f, 0f, false, true, 0f, -0.696f)
                arcToRelative(10.75f, 10.75f, 0f, false, true, 4.446f, -5.143f)
                moveTo(2f, 2f)
                lineToRelative(20f, 20f)
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  输入区
    // ══════════════════════════════════════════════════════

    /** 深度思考（Lucide: sparkles） */
    val Sparkles: ImageVector get() = DsIcons.SparkleDs

    /**
     * 全屏输入（Lucide: maximize-2）。
     *
     * 四个角各一段斜角线，中间是空的 —— 与"外壳变大"的直觉一致。
     * 不用常见的双向箭头：那在读屏里是"缩放窗口"，而这里做的是
     * "把这行字铺到整屏去写"。
     */
    val Expand: ImageVector by lazy {
        vector("Expand") {
            stroke {
                moveTo(15f, 3f)
                horizontalLineTo(21f)
                verticalLineTo(9f)
                moveTo(9f, 21f)
                horizontalLineTo(3f)
                verticalLineTo(15f)
                moveTo(21f, 3f)
                lineTo(14f, 10f)
                moveTo(3f, 21f)
                lineTo(10f, 14f)
            }
        }
    }

    /** 添加图片（Lucide: image） */
    val Image: ImageVector by lazy {
        vector("Image") {
            stroke {
                moveTo(21f, 15f)
                lineToRelative(-3.086f, -3.086f)
                arcToRelative(2f, 2f, 0f, false, false, -2.828f, 0f)
                lineTo(6f, 21f)
                moveTo(7f, 9f)
                arcToRelative(2f, 2f, 0f, true, true, 4f, 0f)
                arcToRelative(2f, 2f, 0f, true, true, -4f, 0f)
                close()
                moveTo(5f, 3f)
                horizontalLineTo(19f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                verticalLineTo(19f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                close()
            }
        }
    }

    /** 拍照（Lucide: camera） */
    val Camera: ImageVector by lazy {
        vector("Camera") {
            stroke {
                // 机身 + 顶部取景凸起
                moveTo(14.5f, 4f)
                horizontalLineToRelative(-5f)
                lineTo(7f, 7f)
                horizontalLineTo(4f)
                arcToRelative(2f, 2f, 0f, false, false, -2f, 2f)
                verticalLineToRelative(9f)
                arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
                horizontalLineToRelative(16f)
                arcToRelative(2f, 2f, 0f, false, false, 2f, -2f)
                verticalLineTo(9f)
                arcToRelative(2f, 2f, 0f, false, false, -2f, -2f)
                horizontalLineToRelative(-3f)
                lineToRelative(-2.5f, -3f)
                close()
                // 镜头。整圆用两段半弧画 —— 与 Circle 那枚同一套写法
                moveTo(15f, 13f)
                arcToRelative(3f, 3f, 0f, true, true, -6f, 0f)
                arcToRelative(3f, 3f, 0f, true, true, 6f, 0f)
                close()
            }
        }
    }

    /** 纯文本文件附件（Lucide: file-text） */
    val FileText: ImageVector by lazy {
        vector("FileText") {
            stroke {
                // 折角纸张
                moveTo(15f, 2f)
                horizontalLineTo(6f)
                arcToRelative(2f, 2f, 0f, false, false, -2f, 2f)
                verticalLineToRelative(16f)
                arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
                horizontalLineToRelative(12f)
                arcToRelative(2f, 2f, 0f, false, false, 2f, -2f)
                verticalLineTo(7f)
                close()
                // 折角
                moveTo(14f, 2f)
                verticalLineToRelative(4f)
                arcToRelative(2f, 2f, 0f, false, false, 2f, 2f)
                horizontalLineToRelative(4f)
                // 三条文字线
                moveTo(10f, 9f)
                horizontalLineTo(8f)
                moveTo(16f, 13f)
                horizontalLineTo(8f)
                moveTo(16f, 17f)
                horizontalLineTo(8f)
            }
        }
    }

    /** 思考过程（Lucide: brain） */    val Brain: ImageVector by lazy {
        vector("Brain") {
            stroke {
                moveTo(12f, 18f)
                verticalLineTo(5f)
                moveTo(15f, 13f)
                arcToRelative(4.17f, 4.17f, 0f, false, true, -3f, -4f)
                arcToRelative(4.17f, 4.17f, 0f, false, true, -3f, 4f)
                moveTo(17.598f, 6.5f)
                arcTo(3f, 3f, 0f, true, false, 12f, 5f)
                arcToRelative(3f, 3f, 0f, true, false, -5.598f, 1.5f)
                moveTo(17.997f, 5.125f)
                arcToRelative(4f, 4f, 0f, false, true, 2.526f, 5.77f)
                moveTo(18f, 18f)
                arcToRelative(4f, 4f, 0f, false, false, 2f, -7.464f)
                moveTo(19.967f, 17.483f)
                arcTo(4f, 4f, 0f, true, true, 12f, 18f)
                arcToRelative(4f, 4f, 0f, true, true, -7.967f, -0.517f)
                moveTo(6f, 18f)
                arcToRelative(4f, 4f, 0f, false, true, -2f, -7.464f)
                moveTo(6.003f, 5.125f)
                arcToRelative(4f, 4f, 0f, false, false, -2.526f, 5.77f)
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  模式
    // ══════════════════════════════════════════════════════

    /** 创作模式（Lucide: feather） */
    val Feather: ImageVector by lazy {
        vector("Feather") {
            stroke {
                moveTo(14.086f, 18.412f)
                arcTo(2f, 2f, 0f, false, true, 12.67f, 19f)
                horizontalLineTo(5f)
                verticalLineToRelative(-7.672f)
                arcToRelative(2f, 2f, 0f, false, true, 0.586f, -1.414f)
                lineTo(11.75f, 3.75f)
                arcToRelative(6f, 6f, 0f, true, true, 8.49f, 8.49f)
                close()
                moveTo(16f, 8f)
                lineTo(2f, 22f)
                moveTo(17.488f, 15f)
                horizontalLineTo(9f)
            }
        }
    }

    /** 扮演模式（Lucide: users） */
    val Users: ImageVector by lazy {
        vector("Users") {
            stroke {
                moveTo(16f, 21f)
                verticalLineToRelative(-2f)
                arcToRelative(4f, 4f, 0f, false, false, -4f, -4f)
                horizontalLineTo(6f)
                arcToRelative(4f, 4f, 0f, false, false, -4f, 4f)
                verticalLineToRelative(2f)
                moveTo(16f, 3.128f)
                arcToRelative(4f, 4f, 0f, false, true, 0f, 7.744f)
                moveTo(22f, 21f)
                verticalLineToRelative(-2f)
                arcToRelative(4f, 4f, 0f, false, false, -3f, -3.87f)
                moveTo(5f, 7f)
                arcToRelative(4f, 4f, 0f, true, true, 8f, 0f)
                arcToRelative(4f, 4f, 0f, true, true, -8f, 0f)
                close()
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  选择控件
    // ══════════════════════════════════════════════════════

    /** 未选中（Lucide: circle） */
    val Circle: ImageVector by lazy {
        vector("Circle") {
            stroke {
                moveTo(2f, 12f)
                arcToRelative(10f, 10f, 0f, true, true, 20f, 0f)
                arcToRelative(10f, 10f, 0f, true, true, -20f, 0f)
                close()
            }
        }
    }

    /** 已选中（Lucide: circle-check） */
    val CircleCheck: ImageVector by lazy {
        vector("CircleCheck") {
            stroke {
                moveTo(16f, 9f)
                lineToRelative(-5.5f, 5.5f)
                lineTo(8f, 12f)
                moveTo(2f, 12f)
                arcToRelative(10f, 10f, 0f, true, true, 20f, 0f)
                arcToRelative(10f, 10f, 0f, true, true, -20f, 0f)
                close()
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  设置
    // ══════════════════════════════════════════════════════

    /**
     * 外观（Lucide: contrast）
     *
     * 与 Lucide 原图有一处不同：**右半边是填实的**。那边把内层半圆只画了轮廓
     * （r=6，还缩在圆内），21dp 上一个半圆轮廓读不出"一半深一半浅"。
     * 这里填成同半径（r=10，直边正好落在竖直直径上）—— 浅色下就是那个
     * 「半边黑、半边白」的观感。
     */
    val Contrast: ImageVector by lazy {
        vector("Contrast") {
            stroke {
                moveTo(2f, 12f)
                arcToRelative(10f, 10f, 0f, true, true, 20f, 0f)
                arcToRelative(10f, 10f, 0f, true, true, -20f, 0f)
                close()
            }
            solid {
                // 右半边：12 点顺时针走到 6 点，再闭合回 12 点
                moveTo(12f, 2f)
                arcToRelative(10f, 10f, 0f, false, true, 0f, 20f)
                close()
            }
        }
    }

    /** 浅色（Lucide: sun） */
    val Sun: ImageVector by lazy {
        vector("Sun") {
            stroke {
                moveTo(12f, 2f)
                verticalLineToRelative(2f)
                moveTo(12f, 20f)
                verticalLineToRelative(2f)
                moveTo(4.93f, 4.93f)
                lineToRelative(1.41f, 1.41f)
                moveTo(17.66f, 17.66f)
                lineToRelative(1.41f, 1.41f)
                moveTo(2f, 12f)
                horizontalLineToRelative(2f)
                moveTo(20f, 12f)
                horizontalLineToRelative(2f)
                moveTo(6.34f, 17.66f)
                lineToRelative(-1.41f, 1.41f)
                moveTo(19.07f, 4.93f)
                lineToRelative(-1.41f, 1.41f)
                moveTo(8f, 12f)
                arcToRelative(4f, 4f, 0f, true, true, 8f, 0f)
                arcToRelative(4f, 4f, 0f, true, true, -8f, 0f)
                close()
            }
        }
    }

    /** 深色（Lucide: moon） */
    val Moon: ImageVector by lazy {
        vector("Moon") {
            stroke {
                moveTo(20.985f, 12.486f)
                arcToRelative(9f, 9f, 0f, true, true, -9.473f, -9.472f)
                curveToRelative(0.405f, -0.022f, 0.617f, 0.46f, 0.402f, 0.803f)
                arcToRelative(6f, 6f, 0f, false, false, 8.268f, 8.268f)
                curveToRelative(0.344f, -0.215f, 0.825f, -0.004f, 0.803f, 0.401f)
            }
        }
    }

    /** 跟随系统（Lucide: monitor） */
    val Monitor: ImageVector by lazy {
        vector("Monitor") {
            stroke {
                moveTo(4f, 3f)
                horizontalLineTo(20f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                verticalLineTo(15f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(4f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                close()
                moveTo(8f, 21f)
                lineTo(16f, 21f)
                moveTo(12f, 17f)
                lineTo(12f, 21f)
            }
        }
    }

    /** 高级参数（Lucide: sliders-horizontal） */
    val Sliders: ImageVector get() = DsIcons.SlidersDs

    /** 导出（Lucide: download） */
    val Download: ImageVector by lazy {
        vector("Download") {
            stroke {
                moveTo(12f, 15f)
                verticalLineTo(3f)
                moveTo(21f, 15f)
                verticalLineToRelative(4f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineToRelative(-4f)
                moveTo(7f, 10f)
                lineToRelative(5f, 5f)
                lineToRelative(5f, -5f)
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  设置 · 一级入口（每个入口一个独立图标，不复用）
    // ══════════════════════════════════════════════════════

    /** 数据管理（Lucide: database） */
    val Database: ImageVector get() = DsIcons.DatabaseDs

    /** API 配置（Lucide: server） */
    val Server: ImageVector by lazy {
        vector("Server") {
            stroke {
                moveTo(4f, 2f)
                horizontalLineTo(20f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                verticalLineTo(8f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(4f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(4f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                close()
                moveTo(4f, 14f)
                horizontalLineTo(20f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                verticalLineToRelative(4f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(4f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(16f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                close()
                moveTo(6f, 6f)
                horizontalLineToRelative(0.01f)
                moveTo(6f, 18f)
                horizontalLineToRelative(0.01f)
            }
        }
    }

    /**
     * 主题色（Lucide: palette）
     *
     * 四个小孔用**描边**而不是填充：`r=0.5` 的填充圆在 21dp 图标上只有
     * 不到 1dp，实际看不见；描边后外径 1.5，才和其它线条一个量级。
     */
    val Palette: ImageVector by lazy {
        vector("Palette") {
            stroke {
                moveTo(12f, 2f)
                curveToRelative(-5.5f, 0f, -10f, 4.5f, -10f, 10f)
                reflectiveCurveToRelative(4.5f, 10f, 10f, 10f)
                curveToRelative(0.926f, 0f, 1.648f, -0.746f, 1.648f, -1.688f)
                curveToRelative(0f, -0.437f, -0.18f, -0.835f, -0.437f, -1.125f)
                curveToRelative(-0.29f, -0.289f, -0.438f, -0.652f, -0.438f, -1.125f)
                arcToRelative(1.64f, 1.64f, 0f, false, true, 1.668f, -1.668f)
                horizontalLineToRelative(1.996f)
                curveToRelative(3.051f, 0f, 5.555f, -2.503f, 5.555f, -5.554f)
                curveTo(21.965f, 6.012f, 17.461f, 2f, 12f, 2f)
                close()
                // 色盘上的四个小孔
                hueDot(13.5f, 6.5f)
                hueDot(17.5f, 10.5f)
                hueDot(8.5f, 7.5f)
                hueDot(6.5f, 12.5f)
            }
        }
    }

    private fun PathBuilder.hueDot(x: Float, y: Float) {
        moveTo(x - 0.5f, y)
        arcToRelative(0.5f, 0.5f, 0f, true, true, 1f, 0f)
        arcToRelative(0.5f, 0.5f, 0f, true, true, -1f, 0f)
        close()
    }

    /** 字体大小（Lucide: a-large-small） */
    /** 界面语言（Lucide: globe） */
    val Globe: ImageVector get() = DsIcons.GlobeDs

    val FontSize: ImageVector by lazy {
        vector("FontSize") {
            stroke {
                moveTo(15f, 16f)
                lineTo(17.536f, 8.672f)
                arcToRelative(1.02f, 1.02f, 0f, false, true, 1.928f, 0f)
                lineTo(22f, 16f)
                moveTo(15.697f, 14f)
                horizontalLineTo(21.303f)
                moveTo(2f, 16f)
                lineTo(6.039f, 6.31f)
                arcToRelative(0.5f, 0.5f, 0f, false, true, 0.923f, 0f)
                lineTo(11f, 16f)
                moveTo(3.304f, 13f)
                horizontalLineTo(9.696f)
            }
        }
    }

    /**
     * 自动折叠思考内容（Lucide: fold-vertical）
     *
     * 不用 `chevrons-down-up` —— 那个在 21dp 下就是个 X，和"关闭"混淆。
     * 这个带中间的虚线分隔，语义明确是"收起"。
     */
    val Collapse: ImageVector by lazy {
        vector("Collapse") {
            stroke {
                moveTo(12f, 22f)
                verticalLineToRelative(-6f)
                moveTo(12f, 8f)
                verticalLineTo(2f)
                moveTo(4f, 12f)
                horizontalLineTo(2f)
                moveTo(10f, 12f)
                horizontalLineTo(8f)
                moveTo(16f, 12f)
                horizontalLineTo(14f)
                moveTo(22f, 12f)
                horizontalLineTo(20f)
                moveTo(15f, 19f)
                lineTo(12f, 16f)
                lineTo(9f, 19f)
                moveTo(15f, 5f)
                lineTo(12f, 8f)
                lineTo(9f, 5f)
            }
        }
    }

    /**
     * 对话气泡（Lucide: message-square）。**方形**气泡，尾巴在左下。
     *
     * 两处语义共用同一个字形：对话页空态，以及设置页的「预设 / 提示词库」。
     * 字形既然相同就只定义一次 —— 分开写两份，改形状时必然漏掉一处。
     * [Prompt] 是它的语义别名。
     *
     * 与 [Bubble] 的区别：那个是**实心**的品牌标识，属于启动图与 App 图标；
     * 这里是描边的，跟界面其余图标是一套。
     */
    val MessageSquare: ImageVector by lazy {
        vector("MessageSquare") {
            stroke {
                moveTo(21f, 15f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(7f)
                lineTo(3f, 21f)
                verticalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                horizontalLineTo(19f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                close()
            }
        }
    }

    /**
     * 「从这里新建对话」（Lucide: message-square-plus）。
     *
     * 气泡里多一个加号 —— 语义正是"把这段对话另起一个"。 */
    val MessageSquarePlus: ImageVector by lazy {
        vector("MessageSquarePlus") {
            stroke {
                moveTo(21f, 15f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(7f)
                lineTo(3f, 21f)
                verticalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                horizontalLineTo(19f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                close()
                moveTo(12f, 7f)
                verticalLineTo(13f)
                moveTo(9f, 10f)
                horizontalLineTo(15f)
            }
        }
    }

    /**
     * 预设 / 提示词库 —— [MessageSquare] 的语义别名。
     *
     * 描边风格，与设置页其它一级入口（Palette / FontSize / Collapse / Info）
     * 是一套。实心的 [Bubble] 那个是品牌标识，混在描边图标里会显得很重。
     */
    val Prompt: ImageVector get() = MessageSquare

    /** 关于（Lucide: info） */
    val Info: ImageVector get() = DsIcons.InfoDs

    /** 导入（Lucide: upload） */
    val Upload: ImageVector by lazy {
        vector("Upload") {
            stroke {
                moveTo(21f, 15f)
                verticalLineTo(19f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(15f)
                moveTo(17f, 8f)
                lineTo(12f, 3f)
                lineTo(7f, 8f)
                moveTo(12f, 3f)
                verticalLineTo(15f)
            }
        }
    }

    /** 继续生成（Lucide: play） */
    val Play: ImageVector by lazy {
        vector("Play") {
            stroke {
                moveTo(7f, 4f)
                lineTo(19f, 12f)
                lineTo(7f, 20f)
                close()
            }
        }
    }

    /** 朗读（Lucide: volume-2） */
    val Volume: ImageVector by lazy {
        vector("Volume") {
            stroke {
                // 喇叭
                moveTo(11f, 5f)
                lineTo(6f, 9f)
                horizontalLineTo(2f)
                verticalLineTo(15f)
                horizontalLineTo(6f)
                lineTo(11f, 19f)
                close()
                // 两道声波
                moveTo(15.54f, 8.46f)
                arcToRelative(5f, 5f, 0f, false, true, 0f, 7.07f)
                moveTo(19.07f, 4.93f)
                arcToRelative(10f, 10f, 0f, false, true, 0f, 14.14f)
            }
        }
    }

    /** 语音输入（Lucide: mic） */
    val Mic: ImageVector by lazy {
        vector("Mic") {
            stroke {
                moveTo(12f, 2f)
                arcToRelative(3f, 3f, 0f, false, true, 3f, 3f)
                verticalLineTo(12f)
                arcToRelative(3f, 3f, 0f, false, true, -6f, 0f)
                verticalLineTo(5f)
                arcToRelative(3f, 3f, 0f, false, true, 3f, -3f)
                close()
                moveTo(19f, 10f)
                verticalLineTo(12f)
                arcToRelative(7f, 7f, 0f, false, true, -14f, 0f)
                verticalLineTo(10f)
                moveTo(12f, 19f)
                verticalLineTo(22f)
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  服务商标识（纯装饰）
    // ══════════════════════════════════════════════════════

    /*
     * 四个服务商各一个几何标识。**不是商标**，也不打算是 ——
     * 它们只承担一件事：让"我在用哪家"在列表里一眼可辨。
     * 所以全部用同一套 24×24 描边语言重画，与其余图标同源，
     * 不引入彩色 logo（那会在纯白界面上撕开一道口子）。
     */

    /** 标准 OpenAI：环形齿轮状，六个方向的短线。 */
    val ProviderOpenAI: ImageVector by lazy {
        vector("ProviderOpenAI") {
            stroke(width = 1.6f) {
                moveTo(12f, 3.2f)
                arcToRelative(8.8f, 8.8f, 0f, true, true, 0f, 17.6f)
                arcToRelative(8.8f, 8.8f, 0f, true, true, 0f, -17.6f)
                close()
                moveTo(12f, 7.4f)
                verticalLineTo(12f)
                moveTo(12f, 12f)
                lineTo(16f, 14.4f)
                moveTo(12f, 12f)
                lineTo(8f, 14.4f)
            }
        }
    }

    /** DeepSeek：一道浪，鲸背露出水面。 */
    val ProviderDeepSeek: ImageVector by lazy {
        vector("ProviderDeepSeek") {
            stroke(width = 1.6f) {
                moveTo(3f, 14.5f)
                curveTo(6f, 10.5f, 9f, 10.5f, 12f, 14.5f)
                curveTo(15f, 10.5f, 18f, 10.5f, 21f, 14.5f)
                moveTo(3f, 19f)
                curveTo(6f, 16f, 9f, 16f, 12f, 19f)
                curveTo(15f, 16f, 18f, 16f, 21f, 19f)
                moveTo(9f, 6.5f)
                lineTo(9f, 3.5f)
            }
        }
    }

    /** OpenCode Go：终端提示符 `>_`。 */
    val ProviderCode: ImageVector by lazy {
        vector("ProviderCode") {
            stroke(width = 1.8f) {
                moveTo(5f, 5f)
                horizontalLineTo(19f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
                verticalLineTo(17f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
                horizontalLineTo(5f)
                arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
                verticalLineTo(7f)
                arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
                close()
                moveTo(7.5f, 10.5f)
                lineTo(10.5f, 13f)
                lineTo(7.5f, 15.5f)
                moveTo(12.5f, 15.5f)
                horizontalLineTo(16.5f)
            }
        }
    }

    /**
     * 自定义端点 / 认不出来的服务商。
     *
     * 早先是三条带圆点的横线 —— 和「高级参数」的滑块图标几乎是同一个图形，
     * 设置首页上「API 配置」「预设」「上下文压缩参数」三行因此长得一模一样。
     * 现在与 [Api]（插头）是同一枚：认不出牌子的端点，本质就是"一个接口"。
     */
    val ProviderCustom: ImageVector get() = Api

    /** 文件夹（Lucide: folder） */
    val Folder: ImageVector get() = DsIcons.FolderDs

    // ══════════════════════════════════════════════════════
    //  浮层 / 文件管理器用的小件
    // ══════════════════════════════════════════════════════

    /** 警告三角（Lucide: triangle-alert）。审批档位「逐项确认」用。 */
    val Warning: ImageVector by lazy {
        vector("Warning") {
            stroke(width = 1.8f) {
                moveTo(10.3f, 3.9f)
                lineTo(1.8f, 18f)
                curveTo(1.0f, 19.4f, 2.0f, 21f, 3.5f, 21f)
                horizontalLineTo(20.5f)
                curveTo(22.0f, 21f, 23.0f, 19.4f, 22.2f, 18f)
                lineTo(13.7f, 3.9f)
                curveTo(12.9f, 2.5f, 11.1f, 2.5f, 10.3f, 3.9f)
                close()
                moveTo(12f, 9f)
                verticalLineTo(13f)
                moveTo(12f, 17f)
                verticalLineTo(17.01f)
            }
        }
    }

    /** 锁（Lucide: lock）。审批档位「需要授权」用。 */
    val Lock: ImageVector by lazy {
        vector("Lock") {
            stroke(width = 1.8f) {
                moveTo(5f, 11f)
                horizontalLineTo(19f)
                verticalLineTo(20f)
                horizontalLineTo(5f)
                close()
                moveTo(8f, 11f)
                verticalLineTo(7f)
                curveTo(8f, 4.8f, 9.8f, 3f, 12f, 3f)
                curveTo(14.2f, 3f, 16f, 4.8f, 16f, 7f)
                verticalLineTo(11f)
            }
        }
    }

    /** 闪电（Lucide: zap）。审批档位「全放行」用 —— 快，但代价是危险。 */
    val Bolt: ImageVector by lazy {
        vector("Bolt") {
            stroke(width = 1.8f) {
                moveTo(13f, 2f)
                lineTo(4f, 14f)
                horizontalLineTo(11f)
                lineTo(10f, 22f)
                lineTo(20f, 9f)
                horizontalLineTo(13f)
                close()
            }
        }
    }

    /** 压缩包（Lucide: archive）。 */
    val Archive: ImageVector by lazy {
        vector("Archive") {
            stroke(width = 1.8f) {
                moveTo(3f, 4f)
                horizontalLineTo(21f)
                verticalLineTo(8f)
                horizontalLineTo(3f)
                close()
                moveTo(5f, 8f)
                verticalLineTo(19f)
                curveTo(5f, 20.1f, 5.9f, 21f, 7f, 21f)
                horizontalLineTo(17f)
                curveTo(18.1f, 21f, 19f, 20.1f, 19f, 19f)
                verticalLineTo(8f)
                moveTo(10f, 12f)
                horizontalLineTo(14f)
            }
        }
    }

    /** 分享（Lucide: share）。 */
    val Share: ImageVector by lazy {
        vector("Share") {
            stroke(width = 1.8f) {
                moveTo(4f, 12f)
                verticalLineTo(19f)
                curveTo(4f, 20.1f, 4.9f, 21f, 6f, 21f)
                horizontalLineTo(18f)
                curveTo(19.1f, 21f, 20f, 20.1f, 20f, 19f)
                verticalLineTo(12f)
                moveTo(16f, 6f)
                lineTo(12f, 2f)
                lineTo(8f, 6f)
                moveTo(12f, 2f)
                verticalLineTo(15f)
            }
        }
    }

    /** 书签（Lucide: bookmark）。 */
    val Bookmark: ImageVector by lazy {
        vector("Bookmark") {
            stroke(width = 1.8f) {
                moveTo(19f, 21f)
                lineTo(12f, 17f)
                lineTo(5f, 21f)
                verticalLineTo(5f)
                curveTo(5f, 3.9f, 5.9f, 3f, 7f, 3f)
                horizontalLineTo(17f)
                curveTo(18.1f, 3f, 19f, 3.9f, 19f, 5f)
                close()
            }
        }
    }

    /** 文件夹加号（Lucide: folder-plus）。 */
    val FolderPlus: ImageVector by lazy {
        vector("FolderPlus") {
            stroke(width = 1.8f) {
                moveTo(20f, 20f)
                curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
                verticalLineTo(8f)
                curveTo(22f, 6.9f, 21.1f, 6f, 20f, 6f)
                horizontalLineTo(13.1f)
                curveTo(12.4f, 6f, 11.8f, 5.6f, 11.4f, 5.1f)
                lineTo(9.6f, 3.9f)
                curveTo(9.2f, 3.4f, 8.6f, 3f, 7.9f, 3f)
                horizontalLineTo(4f)
                curveTo(2.9f, 3f, 2f, 3.9f, 2f, 5f)
                verticalLineTo(18f)
                curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
                close()
                moveTo(12f, 10f)
                verticalLineTo(16f)
                moveTo(9f, 13f)
                horizontalLineTo(15f)
            }
        }
    }

    /** 文件夹移动（Lucide: folder-symlink 的简化）。 */
    val FolderMove: ImageVector by lazy {
        vector("FolderMove") {
            stroke(width = 1.8f) {
                moveTo(20f, 20f)
                curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
                verticalLineTo(8f)
                curveTo(22f, 6.9f, 21.1f, 6f, 20f, 6f)
                horizontalLineTo(13.1f)
                curveTo(12.4f, 6f, 11.8f, 5.6f, 11.4f, 5.1f)
                lineTo(9.6f, 3.9f)
                curveTo(9.2f, 3.4f, 8.6f, 3f, 7.9f, 3f)
                horizontalLineTo(4f)
                curveTo(2.9f, 3f, 2f, 3.9f, 2f, 5f)
                verticalLineTo(18f)
                curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
                close()
                moveTo(9f, 13f)
                horizontalLineTo(15f)
                moveTo(12f, 10f)
                lineTo(15f, 13f)
                lineTo(12f, 16f)
            }
        }
    }

    /** 用其他应用打开（Lucide: external-link）。 */
    val ExternalLink: ImageVector by lazy {
        vector("ExternalLink") {
            stroke(width = 1.8f) {
                moveTo(15f, 3f)
                horizontalLineTo(21f)
                verticalLineTo(9f)
                moveTo(10f, 14f)
                lineTo(21f, 3f)
                moveTo(18f, 13f)
                verticalLineTo(19f)
                curveTo(18f, 20.1f, 17.1f, 21f, 16f, 21f)
                horizontalLineTo(5f)
                curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f)
                verticalLineTo(8f)
                curveTo(3f, 6.9f, 3.9f, 6f, 5f, 6f)
                horizontalLineTo(11f)
            }
        }
    }

    /** 普通文件（Lucide: file）。 */
    val File: ImageVector by lazy {
        vector("File") {
            stroke(width = 1.8f) {
                moveTo(15f, 2f)
                horizontalLineTo(6f)
                curveTo(4.9f, 2f, 4f, 2.9f, 4f, 4f)
                verticalLineTo(20f)
                curveTo(4f, 21.1f, 4.9f, 22f, 6f, 22f)
                horizontalLineTo(18f)
                curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f)
                verticalLineTo(7f)
                close()
                moveTo(14f, 2f)
                verticalLineTo(6f)
                curveTo(14f, 7.1f, 14.9f, 8f, 16f, 8f)
                horizontalLineTo(20f)
            }
        }
    }

    /** 打开的文件夹（Lucide: folder-open）。 */
    val FolderOpen: ImageVector by lazy {
        vector("FolderOpen") {
            stroke(width = 1.8f) {
                moveTo(6f, 14f)
                lineTo(7.5f, 11f)
                curveTo(7.9f, 10.4f, 8.5f, 10f, 9.2f, 10f)
                horizontalLineTo(20f)
                curveTo(21.2f, 10f, 22f, 11.1f, 21.7f, 12.3f)
                lineTo(20.2f, 18.3f)
                curveTo(20f, 19.3f, 19.1f, 20f, 18.1f, 20f)
                horizontalLineTo(4f)
                curveTo(2.9f, 20f, 2f, 19.1f, 2f, 18f)
                verticalLineTo(5f)
                curveTo(2f, 3.9f, 2.9f, 3f, 4f, 3f)
                horizontalLineTo(7.9f)
                curveTo(8.6f, 3f, 9.2f, 3.4f, 9.6f, 3.9f)
                lineTo(10.4f, 5.1f)
                curveTo(10.8f, 5.6f, 11.4f, 6f, 12.1f, 6f)
                horizontalLineTo(18f)
                curveTo(19.1f, 6f, 20f, 6.9f, 20f, 8f)
                verticalLineTo(10f)
            }
        }
    }

    /** 左箭头（Lucide: arrow-left）。文件管理器后退用 —— 直角箭头，不是尖括号。 */
    val ArrowLeft: ImageVector by lazy {
        vector("ArrowLeft") {
            stroke(width = 1.8f) {
                moveTo(19f, 12f)
                horizontalLineTo(5f)
                moveTo(12f, 19f)
                lineTo(5f, 12f)
                lineTo(12f, 5f)
            }
        }
    }

    /** 右箭头（Lucide: arrow-right）。 */
    val ArrowRight: ImageVector by lazy {
        vector("ArrowRight") {
            stroke(width = 1.8f) {
                moveTo(5f, 12f)
                horizontalLineTo(19f)
                moveTo(12f, 5f)
                lineTo(19f, 12f)
                lineTo(12f, 19f)
            }
        }
    }

    /** 上箭头（Lucide: arrow-up）。上一级目录用。 */
    val ArrowUpDir: ImageVector by lazy {
        vector("ArrowUpDir") {
            stroke(width = 1.8f) {
                moveTo(12f, 19f)
                verticalLineTo(5f)
                moveTo(5f, 12f)
                lineTo(12f, 5f)
                lineTo(19f, 12f)
            }
        }
    }

    /**
     * 终端（Lucide: terminal）—— 一尖括号 + 一条下划线。
     *
     * 设置里原来用 [Prompt]（`>` 加竖线）当终端入口，那个形状更像"命令行提示符"，
     * 但放在一排图标里认不出来；这个是各家 IDE / 系统统一的终端符号。
     */
    val Terminal: ImageVector by lazy {
        vector("Terminal") {
            stroke(width = 1.8f) {
                moveTo(4f, 17f)
                lineTo(10f, 11f)
                lineTo(4f, 5f)
                moveTo(12f, 19f)
                horizontalLineTo(20f)
            }
        }
    }

    /** 扳手（Lucide: wrench）—— MCP 服务。它代表"给 AI 加装外部工具"。 */
    val Wrench: ImageVector by lazy {
        vector("Wrench") {
            stroke(width = 1.8f) {
                moveTo(14.7f, 6.3f)
                arcToRelative(1f, 1f, 0f, false, false, 0f, 1.4f)
                lineToRelative(1.6f, 1.6f)
                arcToRelative(1f, 1f, 0f, false, false, 1.4f, 0f)
                lineToRelative(3.77f, -3.77f)
                arcToRelative(6f, 6f, 0f, false, true, -7.94f, 7.94f)
                lineToRelative(-6.91f, 6.91f)
                arcToRelative(2.12f, 2.12f, 0f, false, true, -3f, -3f)
                lineToRelative(6.91f, -6.91f)
                arcToRelative(6f, 6f, 0f, false, true, 7.94f, -7.94f)
                lineToRelative(-3.76f, 3.76f)
                close()
            }
        }
    }

    /** 沙漏（Lucide: hourglass）—— 空闲计时 / 定时任务的"等待"语义。 */
    val Hourglass: ImageVector by lazy {
        vector("Hourglass") {
            stroke(width = 1.8f) {
                moveTo(5f, 22f)
                horizontalLineTo(19f)
                moveTo(5f, 2f)
                horizontalLineTo(19f)
                moveTo(17f, 22f)
                verticalLineTo(17.83f)
                arcToRelative(2f, 2f, 0f, false, false, -0.59f, -1.42f)
                lineTo(12f, 12f)
                lineTo(7.59f, 16.41f)
                arcTo(2f, 2f, 0f, false, false, 7f, 17.83f)
                verticalLineTo(22f)
                moveTo(7f, 2f)
                verticalLineTo(6.17f)
                arcToRelative(2f, 2f, 0f, false, false, 0.59f, 1.42f)
                lineTo(12f, 12f)
                lineToRelative(4.41f, -4.41f)
                arcTo(2f, 2f, 0f, false, false, 17f, 6.17f)
                verticalLineTo(2f)
            }
        }
    }

    /** 铃铛（Lucide: bell）—— 通知 / 提醒。 */
    val Bell: ImageVector by lazy {
        vector("Bell") {
            stroke(width = 1.8f) {
                moveTo(10.27f, 21f)
                arcToRelative(2f, 2f, 0f, false, false, 3.46f, 0f)
                moveTo(3.26f, 15.33f)
                arcTo(1f, 1f, 0f, false, false, 4f, 17f)
                horizontalLineTo(20f)
                arcToRelative(1f, 1f, 0f, false, false, 0.74f, -1.67f)
                curveTo(19.41f, 13.96f, 18f, 12.5f, 18f, 8f)
                arcTo(6f, 6f, 0f, false, false, 6f, 8f)
                curveToRelative(0f, 4.5f, -1.41f, 5.96f, -2.74f, 7.33f)
                close()
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  设置页补充图标（Lucide 原始几何，构建期转写）
    // ══════════════════════════════════════════════════════

    /**
     * 由 SVG path 字符串直接生成图标。
     *
     * 上面的图标是逐条 `moveTo / arcToRelative` 手写的；这一批改成**原样保留
     * Lucide 的 path 字符串**交给 [PathParser] —— 少一道人工转写，就少一处抄错坐标的机会。
     * 字符串在生成时已经把数字和圆弧标志位全部用空格分开（见 `tools/svgpath.py`），
     * 不依赖解析器对连写格式的兼容性。
     *
     * @param strokes 描边路径（线宽、圆头圆角与手写那批一致）
     * @param solids  需要**填实**的路径（Lucide 里的小圆点、以及自绘图标里的实心部件）
     */
    private fun parsed(
        name: String,
        strokes: List<String>,
        solids: List<String> = emptyList(),
        width: Float = 1.9f,
    ): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        strokes.forEach { data ->
            builder.addPath(
                pathData = PathParser().parsePathString(data).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        solids.forEach { data ->
            builder.addPath(
                pathData = PathParser().parsePathString(data).toNodes(),
                fill = SolidColor(Color.Black),
            )
        }
        return builder.build()
    }

    /** API 配置（Lucide: plug）。API 就是「接口」，插头是它最直白的那个比喻。 */
    val Api: ImageVector by lazy {
        parsed(
            "Api",
            strokes = listOf(
                "M12 22v-5",
                "M15 8V2",
                "M17 8a1 1 0 0 1 1 1v4a4 4 0 0 1 -4 4h-4a4 4 0 0 1 -4 -4V9a1 1 0 0 1 1 -1z",
                "M9 8V2",
            ),
        )
    }

    /** API Key（Lucide: key-round） */
    val Key: ImageVector by lazy {
        parsed(
            "Key",
            strokes = listOf(
                "M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h3a1 1 0 0 0 1 -1v-1a1 1 0 0 1 1 -1h1a1 1 0" +
                    " 0 0 1 -1v-1a1 1 0 0 1 1 -1h.172a2 2 0 0 0 1.414 -.586l.814 -.814a6.5 6.5 0 1 0 -4 -4z",
                "M16 7.5a.5 .5 0 1 0 1 0a.5 .5 0 1 0 -1 0Z",
            ),
            solids = listOf(
                "M16 7.5a.5 .5 0 1 0 1 0a.5 .5 0 1 0 -1 0Z",
            ),
        )
    }

    /** Base URL / 端点地址（Lucide: link-2） */
    val Link: ImageVector by lazy {
        parsed(
            "Link",
            strokes = listOf(
                "M9 17H7A5 5 0 0 1 7 7h2",
                "M15 7h2a5 5 0 1 1 0 10h-2",
                "M8 12L16 12",
            ),
        )
    }

    /** 云端环境（Lucide: cloud） */
    val Cloud: ImageVector by lazy {
        parsed(
            "Cloud",
            strokes = listOf(
                "M17.5 19H9a7 7 0 1 1 6.71 -9h1.79a4.5 4.5 0 1 1 0 9Z",
            ),
        )
    }

    /** 显示密钥 / 识图能力（Lucide: eye） */
    val Eye: ImageVector by lazy {
        parsed(
            "Eye",
            strokes = listOf(
                "M2.062 12.348a1 1 0 0 1 0 -.696a10.75 10.75 0 0 1 19.876 0a1 1 0 0 1 0 .696a10.75 10.75 0 0 " +
                    "1 -19.876 0",
                "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z",
            ),
        )
    }

    /** 多套配置（Lucide: layers） */
    val Layers: ImageVector by lazy {
        parsed(
            "Layers",
            strokes = listOf(
                "M12.83 2.18a2 2 0 0 0 -1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58 -3.9" +
                    "a1 1 0 0 0 0 -1.83z",
                "M2 12a1 1 0 0 0 .58 .91l8.6 3.91a2 2 0 0 0 1.65 0l8.58 -3.9A1 1 0 0 0 22 12",
                "M2 17a1 1 0 0 0 .58 .91l8.6 3.91a2 2 0 0 0 1.65 0l8.58 -3.9A1 1 0 0 0 22 17",
            ),
        )
    }

    /** 最大输出（Lucide: gauge） */
    val Gauge: ImageVector by lazy {
        parsed(
            "Gauge",
            strokes = listOf(
                "m12 14l4 -4",
                "M3.34 19a10 10 0 1 1 17.32 0",
            ),
        )
    }

    /** 协议通道（Lucide: route） */
    val Route: ImageVector by lazy {
        parsed(
            "Route",
            strokes = listOf(
                "M9 5h8.5a1 1 0 0 1 0 7h-11a1 1 0 0 0 0 7H15",
                "M15 19a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z",
                "M3 5a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z",
            ),
        )
    }

    /** 地区（Lucide: map-pin） */
    val MapPin: ImageVector by lazy {
        parsed(
            "MapPin",
            strokes = listOf(
                "M20 10c0 4.993 -5.539 10.193 -7.399 11.799a1 1 0 0 1 -1.202 0C9.539 20.193 4 14.993 4 10a8 8" +
                    " 0 0 1 16 0",
                "M9 10a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z",
            ),
        )
    }

    /** 粘贴（Lucide: clipboard-paste） */
    val Paste: ImageVector by lazy {
        parsed(
            "Paste",
            strokes = listOf(
                "M11 14h10",
                "M16 4h2a2 2 0 0 1 2 2v1.344",
                "m17 18l4 -4l-4 -4",
                "M8 4H6a2 2 0 0 0 -2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 1.793 -1.113",
                "M9 2h6a1 1 0 0 1 1 1v2a1 1 0 0 1 -1 1h-6a1 1 0 0 1 -1 -1v-2a1 1 0 0 1 1 -1Z",
            ),
        )
    }

    /** 预设 / 提示词库（Lucide: scroll-text） */
    val Scroll: ImageVector by lazy {
        parsed(
            "Scroll",
            strokes = listOf(
                "M15 12h-5",
                "M15 8h-5",
                "M19 17V5a2 2 0 0 0 -2 -2H4",
                "M8 21h12a2 2 0 0 0 2 -2v-1a1 1 0 0 0 -1 -1H11a1 1 0 0 0 -1 1v1a2 2 0 1 1 -4 0V5a2 2 0 1 0 -4" +
                    " 0v2a1 1 0 0 0 1 1h3",
            ),
        )
    }

    /** 技能（Lucide: puzzle） */
    val Puzzle: ImageVector by lazy {
        parsed(
            "Puzzle",
            strokes = listOf(
                "M15.39 4.39a1 1 0 0 0 1.68 -.474a2.5 2.5 0 1 1 3.014 3.015a1 1 0 0 0 -.474 1.68l1.683 1.682a" +
                    "2.414 2.414 0 0 1 0 3.414L19.61 15.39a1 1 0 0 1 -1.68 -.474a2.5 2.5 0 1 0 -3.014 3.015a1 1 0" +
                    " 0 1 .474 1.68l-1.683 1.682a2.414 2.414 0 0 1 -3.414 0L8.61 19.61a1 1 0 0 0 -1.68 .474a2.5 2" +
                    ".5 0 1 1 -3.014 -3.015a1 1 0 0 0 .474 -1.68l-1.683 -1.682a2.414 2.414 0 0 1 0 -3.414L4.39 8." +
                    "61a1 1 0 0 1 1.68 .474a2.5 2.5 0 1 0 3.014 -3.015a1 1 0 0 1 -.474 -1.68l1.683 -1.682a2.414 2" +
                    ".414 0 0 1 3.414 0z",
            ),
        )
    }

    /**
     * 通用模型标识：六边形里一颗四角星。**认不出厂商的模型**用它。
     *
     * 上一版的占位是模型名的首字母 —— 孤零零一个「G」「Q」挂在行首，既不像图标
     * 也不像文字，一排下来参差不齐；而且名字就写在它右边，首字母并没有带来新信息。
     * 现在统一成一枚"这是个模型"的符号：六边形取"模块 / 权重包"的意思，
     * 星是这套图标里 AI 的固定记号（见 [Sparkles]）。描边 + 一处实心，
     * 与旁边实心的厂商 logo 放在一起时分量刚好轻一档 —— 它本来就是"未识别"。
     */
    val ModelGeneric: ImageVector by lazy {
        parsed(
            "ModelGeneric",
            strokes = listOf(
                "M21 16V8a2 2 0 0 0 -1 -1.73l-7 -4a2 2 0 0 0 -2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73" +
                    "l7 4a2 2 0 0 0 2 0l7 -4A2 2 0 0 0 21 16z",
            ),
            solids = listOf(
                "M12 7.4c.38 2.5 2.1 4.22 4.6 4.6c-2.5 .38 -4.22 2.1 -4.6 4.6c-.38 -2.5 -2.1 -4.22 -4.6 -4.6" +
                    "c2.5 -.38 4.22 -2.1 4.6 -4.6z",
            ),
        )
    }
}
