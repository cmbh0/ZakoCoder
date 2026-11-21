package com.simplechat.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplechat.app.App
import com.simplechat.app.R
import com.simplechat.app.ui.common.CapsuleButton
import com.simplechat.app.ui.theme.LocalChatColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 人设编辑页。
 *
 * 为什么不是设置页里的一个对话框：人设是**一段文字**，值得几百字；
 * 对话框那种两行高的输入框会让人本能地写一句话就收手，而这个字段
 * 恰恰是"写得越具体，回答越像给你的"。
 *
 * 版式只有三块：说明 → 一块写作区 → 保存。没有字数上限、没有模板按钮 ——
 * 模板会把所有人的自述压成同一个腔调。
 */
@Composable
fun PersonaPage() {
    val colors = LocalChatColors.current
    val context = LocalContext.current
    val store = (context.applicationContext as App).container.settingsStore
    val scope = rememberCoroutineScope()

    var text by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        text = store.flow.first().userPersona
    }

    // 与定时任务页同理：外层已经是滚动容器，这里只排内容。
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SettingsHint(
                text = stringResource(R.string.persona_page_hint),
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(SettingsDimens.CardCorner))
                    .background(colors.card)
                    .padding(horizontal = SettingsDimens.RowPadH, vertical = 14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.persona_editor_title),
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                        color = colors.placeholder,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.persona_char_count, text.length),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = colors.placeholder,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Box {
                    if (text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.persona_placeholder),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 14.sp,
                                lineHeight = 22.sp,
                            ),
                            color = colors.placeholder,
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            saved = false
                        },
                        textStyle = TextStyle(
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 22.sp,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp),
                    )
                }
            }

            SettingsHint(
                text = if (saved) {
                    stringResource(R.string.persona_saved)
                } else {
                    stringResource(R.string.settings_persona_hint)
                },
                modifier = Modifier.padding(top = 8.dp),
                tone = if (saved) SettingsTone.Accent else SettingsTone.Neutral,
            )
        }

        /*
         * 「保存」是这一页唯一的主要动作：整行宽、主题色实底。
         *
         * 此前它是右下角一颗描边的小胶囊，和输入栏里的选项胶囊一个长相 ——
         * 写完几百字的人设，要在页面最底下找一颗不起眼的小按钮。
         */
        SettingsButton(
            text = stringResource(R.string.action_save),
            onClick = {
                scope.launch {
                    store.setUserPersona(text)
                    saved = true
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 28.dp),
        )
    }
}
