package com.simplechat.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplechat.app.App
import com.simplechat.app.R
import com.simplechat.app.data.Res
import com.simplechat.app.ui.common.CapsuleButton
import com.simplechat.app.ui.common.ProfileAvatar
import com.simplechat.app.ui.settings.SettingsTopBar
import com.simplechat.app.ui.theme.LocalChatColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.simplechat.app.ui.settings.SettingsTextAction
import com.simplechat.app.ui.settings.SettingsButton
import com.simplechat.app.ui.settings.SettingsDimens
import com.simplechat.app.ui.settings.SettingsHint

/**
 * 账号管理：头像 + 名字。
 *
 * 头像**选完立刻落库**，名字点「保存」才落 —— 前者是一次性的选择，
 * 后者可能打到一半改主意。两者都不上传：这个 App 没有账号体系。
 */
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
) {
    val colors = LocalChatColors.current
    val context = LocalContext.current
    val store = (context.applicationContext as App).container.settingsStore
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var avatar by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!loaded) {
            val current = store.flow.first()
            name = current.userName
            avatar = current.userAvatar
            loaded = true
        }
    }

    val pickImage = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        /*
         * 立刻复制进私有目录。
         *
         * 直接存 content:// 行不通：读权限只在本次会话有效，重启后头像就空了。
         */
        val target = java.io.File(context.filesDir, "profile_avatar")
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            true
        }.getOrDefault(false)
        if (ok) {
            avatar = target.absolutePath
            scope.launch { store.setAvatar(target.absolutePath) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.settingsBackground)
            .navigationBarsPadding(),
    ) {
        SettingsTopBar(Res.get(R.string.settings_account_manage), onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))

            ProfileAvatar(
                uri = avatar,
                name = name,
                size = 80.dp,
            )
            Spacer(Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsTextAction(
                    text = Res.get(R.string.profile_change_avatar),
                    onClick = {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    primary = true,
                )
                if (avatar.isNotBlank()) {
                    SettingsTextAction(
                        text = Res.get(R.string.profile_remove_avatar),
                        onClick = {
                            avatar = ""
                            scope.launch { store.setAvatar("") }
                        },
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // 昵称：一张卡、一行标签、一个输入区。不用 OutlinedTextField ——
            // 它的描边和浮动标签在设置页这套语言里是另一个系统。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(SettingsDimens.CardCorner))
                    .background(colors.card)
                    .padding(horizontal = SettingsDimens.RowPadH, vertical = 14.dp),
            ) {
                Text(
                    text = Res.get(R.string.profile_name),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                    color = colors.placeholder,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 24.dp),
                ) {
                    if (name.isEmpty()) {
                        Text(
                            text = Res.get(R.string.profile_name_hint),
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                            color = colors.placeholder,
                        )
                    }
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it.take(24) },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            SettingsHint(
                text = Res.get(R.string.profile_hint),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }

        // 这一页唯一的主要动作：整行宽、主题色实底（与「人设」页的保存同一种按钮）
        SettingsButton(
            text = Res.get(R.string.action_save),
            onClick = {
                scope.launch {
                    store.setProfile(name, avatar)
                    onBack()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsDimens.ScreenPadding, vertical = 12.dp),
        )
    }
}
