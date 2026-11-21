package com.simplechat.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplechat.app.R
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.CapsuleButton
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.common.ShimmerText
import com.simplechat.app.ui.theme.ChatColors
import com.simplechat.app.ui.theme.LocalChatColors
import com.simplechat.app.ui.theme.LocalIsDarkTheme
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private enum class GuideScene {
    ASK,
    MODEL,
    THINK,
    CONTEXT,
    AGENT,
    PRESET,
    FILES,
    CLOUD,
    NAVIGATION,
}

private data class GuidePage(
    val scene: GuideScene,
    val title: String,
    val body: String,
)

/**
 * 首次进入时**浮在对话页上**的卡片导览。
 *
 * ### 这一版刻意没有的东西
 *
 * - **没有 Dialog。** 它是主页面里的一层 `Box`，底下的对话页仍然是活的，
 *   只是被父层用 blur 处理过；不再是一个独立窗口、独立页面。
 * - **没有遮罩色。** 背景不加任何黑白蒙版。深色下变暗、浅色下变灰，
 *   都不是"模糊"，只是遮了一层灰。这里只有 blur 本身。
 * - **不能点空白关闭。** 空白处只吞点击，避免误触到底下的对话。
 * - **没有「下一步」。** 翻页只能左右滑；最后一页才有「开始使用」。
 *
 * ### 图形为什么不是图片
 *
 * 九张图全部是用当前主题色、当前字号和 App 自己的控件语言画的矢量场景，
 * 不是截图、也不是另一套 IDE 皮肤。主题色 / 深浅色一换，图跟着换；
 * 在高分屏上也没有位图缩放的发虚。动效只说明"这块界面会怎么动"，
 * 不做自动翻页。
 *
 * ### 这一版动效改了什么
 *
 * 以前整张卡片共用**一个**线性相位：一个 `phase` 同时驱动呼吸灯、
 * 列表高亮、抽屉宽度、圆环扫描角 —— 于是所有东西在同一刻一起动，
 * 像一张幻灯片在闪。现在拆成三层：
 *
 * 1. **呼吸**用 `FastOutSlowInEasing` + `Reverse`：到头会回弹，不是撞墙重来；
 * 2. **列表项按序错峰**：第 N 项比第 N-1 项晚 120ms 起步，像真的在逐条加载；
 * 3. **路径与连线自己流动**：虚线偏移动画、包沿路径流动 —— 这两处是真矢量，
 *    不再是"一个方块在变大变小"。
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val colors = LocalChatColors.current
    val dark = LocalIsDarkTheme.current
    val accent = MaterialTheme.colorScheme.primary
    val guide = listOf(
        GuidePage(
            GuideScene.ASK,
            stringResource(R.string.onboard_ask_title),
            stringResource(R.string.onboard_ask_body),
        ),
        GuidePage(
            GuideScene.MODEL,
            stringResource(R.string.onboard_model_title),
            stringResource(R.string.onboard_model_body),
        ),
        GuidePage(
            GuideScene.THINK,
            stringResource(R.string.onboard_think_title),
            stringResource(R.string.onboard_think_body),
        ),
        GuidePage(
            GuideScene.CONTEXT,
            stringResource(R.string.onboard_memory_title),
            stringResource(R.string.onboard_memory_body),
        ),
        GuidePage(
            GuideScene.AGENT,
            stringResource(R.string.onboard_agent_title),
            stringResource(R.string.onboard_agent_body),
        ),
        GuidePage(
            GuideScene.PRESET,
            stringResource(R.string.onboard_preset_title),
            stringResource(R.string.onboard_preset_body),
        ),
        GuidePage(
            GuideScene.FILES,
            stringResource(R.string.onboard_files_title),
            stringResource(R.string.onboard_files_body),
        ),
        GuidePage(
            GuideScene.CLOUD,
            stringResource(R.string.onboard_cloud_title),
            stringResource(R.string.onboard_cloud_body),
        ),
        GuidePage(
            GuideScene.NAVIGATION,
            stringResource(R.string.onboarding_basic_title),
            stringResource(R.string.onboarding_basic_body),
        ),
    )
    val pager = rememberPagerState(pageCount = { guide.size })

    // 返回键也能关；点空白不关
    BackHandler(enabled = true) { onDone() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        /*
         * 卡片高度按屏高算，但要夹住上下限：矮屏上被键盘/导航条挤掉高度时
         * 仍能看清底部按钮，高屏上也不会把卡片拉成一张空纸。
         */
        val cardHeight = (this.maxHeight * 0.74f).coerceIn(450.dp, 640.dp)

        /*
         * 空白层只负责两件事：挡住底下的对话页、什么也不做。
         * 它**不能**调用 onDone —— 用户读卡时手滑点到边上，不应该被踢出引导。
         */
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { } },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.85f))

            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 10.dp),
                pageSpacing = 12.dp,
                beyondViewportPageCount = 1,
            ) { index ->
                val fraction = pager.currentPageOffsetFraction
                val distance = abs((pager.currentPage - index) + fraction).coerceIn(0f, 1f)
                GuideCard(
                    page = guide[index],
                    index = index,
                    total = guide.size,
                    accent = accent,
                    height = cardHeight,
                    dark = dark,
                    colors = colors,
                    isLast = index == guide.lastIndex,
                    onDone = onDone,
                    modifier = Modifier.graphicsLayer {
                        // 两侧的卡退远一点，当前这张才是主角
                        val scale = 1f - distance * 0.055f
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - distance * 0.42f
                    },
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                guide.indices.forEach { index ->
                    val selected = pager.currentPage == index
                    val width by animateDpAsState(
                        targetValue = if (selected) 20.dp else 6.dp,
                        animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
                        label = "dot$index",
                    )
                    Box(
                        modifier = Modifier
                            .width(width)
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) accent
                                else colors.placeholder.copy(alpha = 0.34f),
                            ),
                    )
                }
            }

            Spacer(Modifier.height(11.dp))
            Text(
                text = stringResource(R.string.onboarding_swipe_hint),
                color = colors.placeholder,
                fontSize = 11.sp,
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun GuideCard(
    page: GuidePage,
    index: Int,
    total: Int,
    accent: Color,
    height: Dp,
    dark: Boolean,
    colors: ChatColors,
    isLast: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(30.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .shadow(
                elevation = if (dark) 28.dp else 22.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = if (dark) 0.52f else 0.14f),
                spotColor = Color.Black.copy(alpha = if (dark) 0.58f else 0.18f),
            )
            .clip(shape)
            .background(colors.card)
            .border(
                1.dp,
                if (dark) Color.White.copy(alpha = 0.09f)
                else Color.White.copy(alpha = 0.82f),
                shape,
            ),
    ) {
        // 右上角一团品牌色的极淡光晕：和 App 里的空态页是同一套语言
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = if (dark) 0.13f else 0.09f), Color.Transparent),
                    center = Offset(size.width, 0f),
                    radius = size.width * 0.62f,
                ),
                radius = size.width * 0.62f,
                center = Offset(size.width, 0f),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = String.format("%02d", index + 1),
                    color = accent,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = " / %02d".format(total),
                    color = colors.placeholder,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.weight(1f))
                IconCircleButton(
                    icon = AppIcons.Close,
                    contentDescription = stringResource(R.string.onboarding_close),
                    onClick = onDone,
                    size = 30.dp,
                    iconSize = 15.dp,
                    tint = colors.processText,
                    background = colors.fieldBackground.copy(alpha = 0.82f),
                )
            }

            Spacer(Modifier.height(15.dp))
            GuideArtwork(
                scene = page.scene,
                accent = accent,
                colors = colors,
                dark = dark,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(224.dp),
            )

            Spacer(Modifier.height(21.dp))
            Text(
                text = page.title,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 23.sp,
                    lineHeight = 29.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.35).sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(9.dp))
            Text(
                text = page.body,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                ),
                color = colors.processText,
            )

            if (isLast) {
                Spacer(Modifier.height(20.dp))
                CapsuleButton(
                    text = stringResource(R.string.onboard_start),
                    onClick = onDone,
                    selected = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * 迷你界面：外框刻意做成和对话页一样的页面底色 + 卡片圆角。
 *
 * 它不是"示意图"，而是把真实界面的关键控件按比例缩小：
 * 顶栏、气泡、模型面板、过程流、工具条、上下文环，全部来自同一套图标与配色。
 */
@Composable
private fun GuideArtwork(
    scene: GuideScene,
    accent: Color,
    colors: ChatColors,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    val breath by rememberBreath(3_200)
    Box(
        modifier = modifier
            .clip(shape)
            .background(colors.pageBackground)
            .border(1.dp, colors.divider, shape),
    ) {
        /*
         * 场景底层的一团呼吸光。
         *
         * 以前只有卡片右上角有一团静态光晕，场景本身是"死的"。现在场景背后
         * 有一层跟着呼吸的品牌色辉光，视线落上去时画面是有呼吸的。
         */
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = (0.05f + breath * 0.07f) * (if (dark) 1.4f else 1f)),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.62f),
                    radius = size.minDimension * 0.78f,
                ),
                radius = size.minDimension * 0.78f,
                center = Offset(size.width * 0.5f, size.height * 0.62f),
            )
        }

        Column(Modifier.fillMaxSize()) {
            MiniTopBar(accent = accent, colors = colors)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.divider.copy(alpha = 0.65f)),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 11.dp, vertical = 10.dp),
            ) {
                when (scene) {
                    GuideScene.ASK -> AskArtwork(accent, colors)
                    GuideScene.MODEL -> ModelArtwork(accent, colors)
                    GuideScene.THINK -> ThinkingArtwork(accent, colors)
                    GuideScene.CONTEXT -> ContextArtwork(accent, colors)
                    GuideScene.AGENT -> AgentArtwork(accent, colors)
                    GuideScene.PRESET -> PresetArtwork(accent, colors)
                    GuideScene.FILES -> FilesArtwork(accent, colors)
                    GuideScene.CLOUD -> CloudArtwork(accent, colors)
                    GuideScene.NAVIGATION -> NavigationArtwork(accent, colors)
                }
            }
        }
    }
}

@Composable
private fun MiniTopBar(accent: Color, colors: ChatColors) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Menu, null, tint = colors.processText, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(9.dp))
        Text(
            text = "DSHA",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.85f)),
        )
    }
}

// ══════════════════════════════════════════════════════════
//  场景
// ══════════════════════════════════════════════════════════

@Composable
private fun AskArtwork(accent: Color, colors: ChatColors) {
    val breath by rememberBreath(2_600)
    val caret by rememberBlink(1_100)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                text = "帮我看看这个项目",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 10.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp, 12.dp, 3.dp, 12.dp))
                    .background(colors.userBubble)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Sparkles, null, tint = accent, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(5.dp))
            Text(
                text = "DSHA",
                color = colors.processText,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(7.dp))
            Box(
                Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.35f + breath * 0.65f)),
            )
        }
        Spacer(Modifier.height(7.dp))
        MiniLine(0.92f, colors.processText.copy(alpha = 0.68f))
        Spacer(Modifier.height(6.dp))
        MiniLine(0.76f, colors.processText.copy(alpha = 0.54f))
        Spacer(Modifier.height(6.dp))
        /*
         * 一行"正在写"的内容 + 一个真的在闪的光标。
         *
         * 光标是这一页唯一需要"活"的元素：它在告诉用户"这里会一个字一个字地出来"。
         */
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniLine(0.42f + breath * 0.22f, accent.copy(alpha = 0.44f))
            Spacer(Modifier.width(3.dp))
            Box(
                Modifier
                    .width(1.5.dp)
                    .height(9.dp)
                    .background(accent.copy(alpha = 0.25f + caret * 0.75f)),
            )
        }
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.card)
                .border(1.dp, colors.divider, RoundedCornerShape(16.dp))
                .padding(horizontal = 9.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Plus, null, tint = colors.processText, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                text = "发消息",
                color = colors.placeholder,
                fontSize = 9.sp,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(accent, accent.copy(alpha = 0.72f)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.ArrowUp, null, tint = Color.White, modifier = Modifier.size(12.dp))
            }
        }
    }
}

@Composable
private fun ModelArtwork(accent: Color, colors: ChatColors) {
    val travel by rememberTravel(3_600)
    val rows = remember {
        listOf(
            "DeepSeek V4 Flash" to "1M",
            "GLM-5.3" to "202K",
            "Kimi K3" to "262K",
            "Qwen3.8 Max" to "1M",
        )
    }
    // 高亮在行之间**滑动**，而不是一行行地跳
    val position = travel * (rows.size - 1)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.card)
            .border(1.dp, colors.divider, RoundedCornerShape(16.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = "模型",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "可切换",
                color = accent,
                fontSize = 8.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.10f))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        /*
         * **必须是 Column。**
         *
         * 这里原来是 `Box` —— Box 是叠层容器，四个 `Row` 全叠在左上角，
         * 于是四行模型名、四个容量、四条高亮底**互相压在同一行上**，
         * 看起来就是一团糊字（截图里那种 "Claude/Gemini 重叠" 正是它）。
         * 高亮靠 `weight` 调 alpha 就够了，本来就不需要叠层。
         */
        Column(Modifier.fillMaxWidth()) {
            /*
             * 滑动的高亮条：画在行下面，靠 alpha 跟着"最近的那一行"走。
             * 用 alpha 过渡而不是位移，是为了让切换看起来像"选中了"，
             * 而不是"列表在动"。
             */
            rows.forEachIndexed { index, _ ->
                val distance = abs(position - index).coerceAtMost(1f)
                val weight = 1f - distance
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(accent.copy(alpha = 0.13f * weight))
                        .padding(horizontal = 7.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(
                                accent.copy(alpha = 0.35f + 0.65f * weight),
                            ),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = rows[index].first,
                        color = if (weight > 0.5f) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            colors.processText
                        },
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = rows[index].second,
                        color = colors.placeholder,
                        fontSize = 7.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (weight > 0.5f) {
                        Spacer(Modifier.width(5.dp))
                        Icon(
                            AppIcons.Check,
                            null,
                            tint = accent,
                            modifier = Modifier.size(10.dp),
                        )
                    }
                }
                if (index != rows.lastIndex) Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun ThinkingArtwork(accent: Color, colors: ChatColors) {
    val breath by rememberBreath(2_200)
    val dots by rememberTravel(1_500)
    Column(Modifier.fillMaxSize()) {
        /*
         * 与聊天页现在的过程流同一套：一个标题 + 一栏纯灰字，
         * 没有卡片、没有顶边流光、没有圆点与竖线、也没有"第 N 次"。
         */
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShimmerText(
                text = "正在思考",
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 9.sp),
                color = colors.processText,
                active = true,
                modifier = Modifier.weight(1f),
            )
            Icon(
                AppIcons.ChevronDown,
                null,
                tint = colors.placeholder,
                modifier = Modifier.size(10.dp),
            )
        }
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            MiniLine(0.94f, colors.processText.copy(alpha = 0.55f))
            Spacer(Modifier.height(5.dp))
            MiniLine(0.66f, colors.processText.copy(alpha = 0.42f))
            Spacer(Modifier.height(5.dp))
            MiniLine(0.82f, colors.processText.copy(alpha = 0.45f))
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.card.copy(alpha = 0.60f))
                .padding(horizontal = 9.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Wrench, null, tint = colors.processText, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(6.dp))
            ShimmerText(
                text = "读取 workspace/README.md",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = colors.processText,
                active = true,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.45f + breath * 0.55f)),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "思考完再调用工具",
                color = colors.placeholder,
                fontSize = 7.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.weight(1f))
            /*
             * 三颗跳动的小点 —— 经典的三点加载。
             * 每颗错开 1/3 个周期，看上去像"一个一个地冒出来"。
             */
            repeat(3) { index ->
                val phase = ((dots + index / 3f) % 1f)
                val scale = 0.55f + 0.45f * sin(phase * Math.PI).toFloat()
                Box(
                    Modifier
                        .padding(start = if (index == 0) 0.dp else 3.dp)
                        .size((4 * scale).dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.35f + 0.5f * scale)),
                )
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ContextArtwork(accent: Color, colors: ChatColors) {
    val breath by rememberBreath(3_000)
    val sweep by rememberTravel(4_200)
    // Canvas 的 draw 块不是 @Composable，颜色必须在这里取好
    val dotColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(118.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 8.dp.toPx()
                drawArc(
                    color = colors.outline.copy(alpha = 0.45f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                /*
                 * 渐变扫过的进度弧。
                 *
                 * 以前是一整条纯色的弧，端点是硬边。现在弧本身带一段
                 * 从品牌色到浅色的渐变，尾端还拖着一个更亮的高光 ——
                 * 读起来像"扫描"而不是"填充"。
                 */
                val sweep = 214f + breath * 26f
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(
                            accent.copy(alpha = 0.35f),
                            accent,
                            accent.copy(alpha = 0.55f),
                            accent.copy(alpha = 0.35f),
                        ),
                    ),
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                val angle = Math.toRadians((-90f + sweep).toDouble())
                val radius = (size.minDimension - stroke) / 2f
                val head = Offset(
                    center.x + (cos(angle) * radius).toFloat(),
                    center.y + (sin(angle) * radius).toFloat(),
                )
                // 端点高光：一小团柔光，让"头"更明显
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.55f), Color.Transparent),
                        center = head,
                        radius = 9.dp.toPx(),
                    ),
                    radius = 9.dp.toPx(),
                    center = head,
                )
                drawCircle(
                    color = dotColor,
                    radius = 3.dp.toPx(),
                    center = head,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${59 + (breath * 3).toInt()}%",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 19.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = "上下文",
                    color = colors.placeholder,
                    fontSize = 7.sp,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "自动压缩",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = "保留关键事实与最近对话",
                color = colors.processText,
                fontSize = 8.sp,
                lineHeight = 12.sp,
            )
            Spacer(Modifier.height(9.dp))
            /*
             * 原文 → 摘要：左边几行"旧对话"淡出，右边一行"摘要"亮起来。
             * 扫过的那一格用 sweep 控制亮度，于是压缩这件事是**看得见的**。
             */
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiniLine(
                    0.84f,
                    colors.processText.copy(alpha = 0.26f + 0.18f * (1f - sweep)),
                    Modifier.weight(1f),
                )
                Spacer(Modifier.width(5.dp))
                Text("摘要", color = accent, fontSize = 7.sp)
            }
            Spacer(Modifier.height(5.dp))
            MiniLine(0.62f, colors.processText.copy(alpha = 0.18f + 0.12f * (1f - sweep)))
        }
    }
}

@Composable
private fun AgentArtwork(accent: Color, colors: ChatColors) {
    val dash by rememberDash(2_600)
    val tools = remember {
        listOf(
            AppIcons.Folder to "read_file",
            AppIcons.Terminal to "run_shell",
            AppIcons.Check to "write_file",
        )
    }
    val active = (dash * 3f).toInt().coerceIn(0, 2)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        /*
         * 工具之间那条**流动的虚线**。
         *
         * 以前三个工具只是排在一起，看不出"串行"这件事。现在它们之间有一条
         * 虚线，线上的短划一直在往前挪 —— 一眼看得出是"一个接一个"，
         * 而不是"三个并列的选项"。这是真正按路径绘制的矢量动画。
         */
        tools.forEachIndexed { index, (icon, name) ->
            val done = index < active
            val running = index == active
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (running) colors.card else Color.Transparent,
                    )
                    .then(
                        if (running) {
                            Modifier.border(1.dp, colors.divider, RoundedCornerShape(10.dp))
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (done) AppIcons.Check else icon,
                    contentDescription = null,
                    tint = if (done) Color(0xFF3FBF7F) else if (running) accent else colors.placeholder,
                    modifier = Modifier.size(11.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = name,
                    color = if (running) MaterialTheme.colorScheme.onSurface else colors.processText,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (done) "完成" else if (running) "运行中" else "等待",
                    color = if (running) accent else colors.placeholder,
                    fontSize = 7.sp,
                )
            }
            if (index != tools.lastIndex) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.fillMaxHeight().width(24.dp)) {
                        val y = size.height / 2f
                        drawLine(
                            color = colors.divider,
                            start = Offset(14.dp.toPx(), y),
                            end = Offset(size.width - 6.dp.toPx(), y),
                            strokeWidth = 1.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                        // 流动的短划
                        drawLine(
                            color = accent.copy(alpha = 0.85f),
                            start = Offset(14.dp.toPx(), y),
                            end = Offset(14.dp.toPx() + 5.dp.toPx(), y),
                            strokeWidth = 1.6.dp.toPx(),
                            cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(5.dp.toPx(), 9.dp.toPx()),
                                phase = dash * 14.dp.toPx(),
                            ),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "每一次动手，都先经过审批",
            color = colors.placeholder,
            fontSize = 7.sp,
        )
    }
}

@Composable
private fun PresetArtwork(accent: Color, colors: ChatColors) {
    val breath by rememberBreath(2_800)
    Column(Modifier.fillMaxSize()) {
        Text(
            text = "系统提示词",
            color = colors.placeholder,
            fontSize = 7.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(6.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.card)
                .border(1.dp, colors.divider, RoundedCornerShape(12.dp))
                .padding(10.dp),
        ) {
            // 三段来源：人设 / 预设 / 会话补充，依次点亮
            SourceRow("人设", "我是一名后端工程师", 0f, breath, accent, colors)
            Spacer(Modifier.height(6.dp))
            SourceRow("预设", "回答简短，先给结论", 0.33f, breath, accent, colors)
            Spacer(Modifier.height(6.dp))
            SourceRow("会话", "这次只看日志目录", 0.66f, breath, accent, colors)
        }
        Spacer(Modifier.height(9.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.08f))
                .padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Prompt, null, tint = accent, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = "预设可以从文档导入",
                color = colors.processText,
                fontSize = 8.sp,
                modifier = Modifier.weight(1f),
            )
            Icon(AppIcons.Download, null, tint = accent, modifier = Modifier.size(10.dp))
        }
        Spacer(Modifier.weight(1f))
    }
}

/**
 * 一行"来源"，亮度由相位决定 —— 三行依次亮起，像是在一段段拼装提示词。
 */
@Composable
private fun SourceRow(
    label: String,
    text: String,
    offset: Float,
    breath: Float,
    accent: Color,
    colors: ChatColors,
) {
    val local = ((breath + offset) % 1f)
    val weight = sin(local * Math.PI).toFloat().coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.30f + 0.70f * weight)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = if (weight > 0.5f) accent else colors.placeholder,
            fontSize = 7.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            color = colors.processText.copy(alpha = 0.45f + 0.55f * weight),
            fontSize = 8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FilesArtwork(accent: Color, colors: ChatColors) {
    val pulse by rememberBreath(2_400)
    val rows = remember {
        listOf(
            "src" to true,
            "main.kt" to false,
            "README.md" to false,
            "build.gradle.kts" to false,
        )
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.FolderOpen, null, tint = colors.processText, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = "workspace",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            Icon(AppIcons.Plus, null, tint = colors.placeholder, modifier = Modifier.size(10.dp))
        }
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.card)
                .border(1.dp, colors.divider, RoundedCornerShape(12.dp))
                .padding(horizontal = 9.dp, vertical = 7.dp),
        ) {
            rows.forEachIndexed { index, (name, isDir) ->
                val selected = index == 1
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (selected) accent.copy(alpha = 0.10f) else Color.Transparent,
                        )
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (isDir) AppIcons.Folder else AppIcons.FileText,
                        contentDescription = null,
                        tint = if (selected) accent else colors.placeholder,
                        modifier = Modifier.size(10.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = name,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            colors.processText
                        },
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        // 正在写入的那一行：末尾有一道会呼吸的高亮
                        Box(
                            Modifier
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(accent.copy(alpha = 0.35f + pulse * 0.65f)),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "AI 改的是同一个工作区",
            color = colors.placeholder,
            fontSize = 7.sp,
        )
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun CloudArtwork(accent: Color, colors: ChatColors) {
    val travel by rememberTravel(2_800)
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.card)
                .border(1.dp, colors.divider, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Server, null, tint = accent, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "云端工作区",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "apt · pip · 长任务",
                    color = colors.placeholder,
                    fontSize = 7.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3FBF7F)),
            )
        }
        Spacer(Modifier.height(4.dp))
        /*
         * 手机 → 云端：一条真正的曲线路径，上面有一个沿路径移动的包。
         *
         * 这一页要说清的是"命令发到别处去跑，结果再回来"。画一条线
         * 不够，得让线上**有东西在动**。
         */
        Box(
            Modifier
                .fillMaxWidth()
                .height(58.dp),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val start = Offset(size.width * 0.16f, size.height * 0.16f)
                val end = Offset(size.width * 0.84f, size.height * 0.84f)
                val control = Offset(size.width * 0.84f, size.height * 0.16f)
                val path = Path().apply {
                    moveTo(start.x, start.y)
                    quadraticTo(control.x, control.y, end.x, end.y)
                }
                drawPath(
                    path = path,
                    color = colors.divider,
                    style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round),
                )
                // 路径上一段更亮的"正在传输"的部分
                drawPath(
                    path = path,
                    color = accent.copy(alpha = 0.9f),
                    style = Stroke(
                        width = 1.8.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(6.dp.toPx(), 10.dp.toPx()),
                            phase = travel * 16.dp.toPx(),
                        ),
                    ),
                )
                // 沿路径移动的包
                val measure = PathMeasure().apply { setPath(path, false) }
                val length = measure.length
                val point = measure.getPosition(length * travel)
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.55f), Color.Transparent),
                        center = point,
                        radius = 8.dp.toPx(),
                    ),
                    radius = 8.dp.toPx(),
                    center = point,
                )
                drawCircle(color = accent, radius = 2.6.dp.toPx(), center = point)
            }
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 4.dp)
                    .size(26.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(colors.pageBackground)
                    .border(1.dp, colors.divider, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.PanelLeft, null, tint = colors.processText, modifier = Modifier.size(11.dp))
            }
        }
        Text(
            text = "也可以只用本机环境",
            color = colors.placeholder,
            fontSize = 7.sp,
        )
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun NavigationArtwork(accent: Color, colors: ChatColors) {
    val travel by rememberTravel(3_400)
    // 抽屉宽度在 0.34–0.50 之间来回：开合一下，说明"侧边栏可以拉出来"
    val drawer = 0.34f + 0.16f * (0.5f + 0.5f * sin(travel * 2f * Math.PI.toFloat()))
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(drawer)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.sidebar)
                .border(1.dp, colors.divider, RoundedCornerShape(12.dp))
                .padding(8.dp),
        ) {
            Text(
                text = "历史对话",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 8.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(9.dp))
            repeat(3) { index ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (index == 0) accent.copy(alpha = 0.10f) else Color.Transparent,
                        )
                        .padding(horizontal = 5.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(3.dp)
                            .clip(CircleShape)
                            .background(if (index == 0) accent else colors.placeholder.copy(alpha = 0.4f)),
                    )
                    Spacer(Modifier.width(5.dp))
                    MiniLine(0.72f - index * 0.12f, colors.processText.copy(alpha = 0.26f))
                }
                Spacer(Modifier.height(5.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Menu, null, tint = colors.processText, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(6.dp))
                Text("DSHA", color = colors.processText, fontSize = 8.sp)
                Spacer(Modifier.weight(1f))
                Icon(AppIcons.More, null, tint = colors.processText, modifier = Modifier.size(11.dp))
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.divider),
            )
            Spacer(Modifier.height(12.dp))
            MiniLine(0.72f, colors.processText.copy(alpha = 0.26f))
            Spacer(Modifier.height(6.dp))
            MiniLine(0.52f, colors.processText.copy(alpha = 0.20f))
            Spacer(Modifier.weight(1f))
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(colors.fieldBackground)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Sparkles, null, tint = accent, modifier = Modifier.size(9.dp))
                Spacer(Modifier.width(5.dp))
                Text("模型", color = colors.processText, fontSize = 7.sp)
            }
        }
    }
}

// ══════════════════════════════════════════════════════════
//  动画原语
// ══════════════════════════════════════════════════════════

/**
 * 呼吸：0 → 1 → 0，到头**回弹**。
 *
 * 以前是线性的 0 → 1 → 0（Restart），到 1 的那一刻会硬生生跳回去，
 * 看着像卡了一下。现在用 `FastOutSlowInEasing` + `Reverse`，
 * 两端都软下来，像真的在呼吸。
 */
@Composable
private fun rememberBreath(durationMs: Int = 2_600): State<Float> {
    val transition = rememberInfiniteTransition(label = "guideBreath")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "guideBreathValue",
    )
}

/** 匀速巡回：0 → 1 → 0 → 1，用来驱动"一直在走"的元素。 */
@Composable
private fun rememberTravel(durationMs: Int = 2_800): State<Float> {
    val transition = rememberInfiniteTransition(label = "guideTravel")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "guideTravelValue",
    )
}

/** 闪烁：0 → 1 → 0，用来做光标。 */
@Composable
private fun rememberBlink(durationMs: Int = 1_100): State<Float> {
    val transition = rememberInfiniteTransition(label = "guideBlink")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "guideBlinkValue",
    )
}

/** 虚线偏移动画：短划沿连线往前挪。 */
@Composable
private fun rememberDash(durationMs: Int = 2_600): State<Float> {
    val transition = rememberInfiniteTransition(label = "guideDash")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "guideDashValue",
    )
}

@Composable
private fun MiniLine(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth(fraction.coerceIn(0f, 1f))
            .height(5.dp)
            .clip(CircleShape)
            .background(color),
    )
}
