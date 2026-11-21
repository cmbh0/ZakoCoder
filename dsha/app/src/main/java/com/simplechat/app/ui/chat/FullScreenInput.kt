package com.simplechat.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.simplechat.app.data.Res
import com.simplechat.app.R
import com.simplechat.app.ui.common.CapsuleButton
import com.simplechat.app.ui.theme.LocalChatColors

/**
 * 全屏输入。
 *
 * ### 为什么需要它
 *
 * 输入栏最高 160dp（约 6 行）。写到第 7 行开始，光标就在一个**只能看见
 * 最后几行**的小窗里来回滚 —— 改一句开头的话要先把整段滚上去，改完再滚回来。
 * 长提示词、长代码片段基本没法在输入栏里改。
 *
 * 所以文本一旦超出可视区，右上角就长出一颗"放大"按钮；点开是这张铺满全屏
 * 的编辑器：字更大、行更松、没有高度上限。它**就是同一个 `TextFieldValue`**，
 * 不是副本 —— 在这里改的每一个字，关掉时已经在输入栏里了，不存在
 * "要不要保存"的问题。
 *
 * ### 为什么不用新页面
 *
 * 全屏输入是一个**临时姿态**，不是导航目的地：用户按返回就该回到对话，
 * 而不是回到一个"编辑页"。`Dialog` 的返回键天然是关闭，且不会把
 * 对话页的滚动位置、输入法状态重新走一遍。
 */
@Composable
internal fun FullScreenInputDialog(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalChatColors.current
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    /*
     * 自动聚焦 + 拉起输入法。
     *
     * 只 `requestFocus()` 不够：`Dialog` 是独立窗口，焦点落进去之后
     * 系统输入法未必跟着切过来。等一帧让窗口真正上屏再 `show()`，
     * 否则 show 会被旧窗口吃掉 —— 表现是"点开全屏，键盘没了"。
     */
    LaunchedEffect(Unit) {
        runCatching {
            focusRequester.requestFocus()
            withFrameNanos { }
            keyboard?.show()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.pageBackground)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            // ── 顶栏 ─────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = Res.get(R.string.input_fullscreen_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.weight(1f))
                CapsuleButton(
                    text = Res.get(R.string.input_fullscreen_done),
                    onClick = onDismiss,
                    selected = true,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .background(colors.divider.copy(alpha = 0.5f))
                    .padding(bottom = 1.dp),
            )

            // ── 编辑区 ───────────────────────────────────
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 20.dp, vertical = 14.dp)
                    .focusRequester(focusRequester),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onBackground,
                    lineHeight = 28.sp,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Box {
                        if (value.text.isEmpty()) {
                            Text(
                                text = Res.get(R.string.input_hint),
                                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp),
                                color = colors.placeholder,
                            )
                        }
                        inner()
                    }
                },
            )

            // ── 底部计数 ─────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = Res.get(R.string.input_fullscreen_count, value.text.length),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.placeholder,
                )
            }
        }
    }
}
