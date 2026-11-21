package com.simplechat.app.ui.theme

import androidx.compose.ui.graphics.Color

// ── 品牌色 ────────────────────────────────────────────────
/** DeepSeek 蓝，浅色模式主色。 */
val BrandBlue = Color(0xFF4D6BFE)

/** 深色模式下略微提亮的品牌蓝，保证对比度。 */
val BrandBlueDark = Color(0xFF6B84FF)

// ── 主题色候选（设置页「个性化 → 主题色」）──────────────────
/*
 * 每个色相两档：浅色一档、深色一档。
 *
 * 深色那一档**不是**把浅色随手调亮 —— 同一个色相在近黑底上要够亮才顶得住
 * 对比度（原来的 BrandBlue → BrandBlueDark 就是这么来的，这里沿用同一套处理）。
 *
 * 蓝色两个档刻意就取原来的品牌色：**默认观感一个像素都不变**。
 */
val AccentGreenLight = Color(0xFF12A150)
val AccentGreenDark = Color(0xFF3DBF74)
val AccentRedLight = Color(0xFFB01E28)
val AccentRedDark = Color(0xFFE05C63)
val AccentPinkLight = Color(0xFFE35183)
val AccentPinkDark = Color(0xFFFF77A9)
val AccentPurpleLight = Color(0xFF7C4DFF)
val AccentPurpleDark = Color(0xFF9E7BFF)
val AccentBrownLight = Color(0xFF9A6B4F)
val AccentBrownDark = Color(0xFFB98A6B)
val AccentGrayLight = Color(0xFF6B7280)
val AccentGrayDark = Color(0xFF9AA1AC)

// ── 用户气泡 ──────────────────────────────────────────────
val UserBubbleLight = Color(0xFFE9EFFF)
val UserBubbleDark = Color(0xFF2A3556)

// ── 过程面板（思考区）────────────────────────────────────
val ProcessTextLight = Color(0xFF84888F)
val ProcessTextDark = Color(0xFF9BA1AC)
val ProcessBarLight = Color(0xFFDCDEE2)
val ProcessBarDark = Color(0xFF3A3D42)

// ── 文本 ─────────────────────────────────────────────────
val TextPrimaryLight = Color(0xFF121214)
val TextPrimaryDark = Color(0xFFE8E8EA)
val TextSecondaryLight = Color(0xFF8C9199)
val TextSecondaryDark = Color(0xFF8B9099)
val PlaceholderLight = Color(0xFFA7ACB5)
val PlaceholderDark = Color(0xFF6B7079)

// ── 表面 ─────────────────────────────────────────────────
val PageBgLight = Color(0xFFFFFFFF)
val PageBgDark = Color(0xFF0F0F10)
/**
 * 设置页底色 = 浅灰。
 *
 * 官方客户端就是"浅灰底 + 白色圆角卡片"：分组之间靠底色断开，卡片内部
 * 不再画分隔线。纯白底是上一版的判断 —— 那时为了"干净"把底色刷白、
 * 靠 1dp 细线分区，结果是整页发平、每行都贴在一起，看不出哪儿是一组。
 */
val SettingsBgLight = Color(0xFFF4F5F7)
val SettingsBgDark = Color(0xFF0F0F10)
val CardLight = Color(0xFFFFFFFF)
val CardDark = Color(0xFF1A1A1C)
val OutlineLight = Color(0xFFE7E9ED)
val OutlineDark = Color(0xFF2E2F33)
/**
 * 侧边栏底色。
 *
 * 抄官方 chat-demo 的 `--dsw-specific-sidebar-fill`：浅色 249,250,251，
 * 深色 27,27,28。规律是**浅色比页面暗一档、深色比页面亮一档**，
 * 差得很小 —— 层次感来自"几乎看不出的一点点不同"，不是一块明显的板。
 */
val SidebarLight = Color(0xFFF9FAFB)
val SidebarDark = Color(0xFF1B1B1C)


val DividerLight = Color(0xFFF0F1F4)
val DividerDark = Color(0xFF26272A)

// ── 浮层（长按菜单等）────────────────────────────────────
/**
 * 菜单浮层比卡片再"抬高"一档：
 * 深色下必须比 CardDark 亮，否则菜单和背景糊在一起（见参考图）。
 */
val MenuBgLight = Color(0xFFFFFFFF)
val MenuBgDark = Color(0xFF2B2C30)

// ── 功能色 ───────────────────────────────────────────────
val DangerRed = Color(0xFFF5222D)
val DangerRedDark = Color(0xFFFF5A5F)
val CapsuleSelectedLight = Color(0xFFE8EDFF)
val CapsuleSelectedDark = Color(0xFF243056)

// ── 状态色 ───────────────────────────────────────────────
/** 上下文接近阈值时的提醒色。不用红色 —— 那是"出错了"，这里是"注意一下"。 */
val WarningAmber = Color(0xFFD98A1F)
val WarningAmberDark = Color(0xFFE8A33D)
val FieldBgLight = Color(0xFFF4F5F8)
val FieldBgDark = Color(0xFF232427)

// ── Markdown（对齐官方 Web 端的配色 token）────────────────
//
// 官方 `markdown.css` 用了一组独立的格子里：代码块 `/neutral-bluish-50`、
// 行内代码 `-100`、引用竖线 `label-caption(400)`、表格横线 `border-l2`。
// 我们的 `fieldBackground` 是给输入框/胶囊用的，比官方的代码块底色深一档 ——
// 直接拿它当代码块底色，整段代码会显得"发灰、压页面"。所以单独一组。
val MarkdownCodeBgLight = Color(0xFFFAFBFC)
val MarkdownCodeBgDark = Color(0xFF1B1B1C)
val MarkdownInlineCodeBgLight = Color(0xFFEDF0F4)
val MarkdownInlineCodeBgDark = Color(0xFF2A2B2F)
val MarkdownQuoteBarLight = Color(0xFFADB2B8)
val MarkdownQuoteBarDark = Color(0xFF81858C)
val MarkdownRuleLight = Color(0x1A000000)
val MarkdownRuleDark = Color(0x1FFFFFFF)
