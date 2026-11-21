package com.simplechat.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.simplechat.app.R
import com.simplechat.app.data.Res
import com.simplechat.app.permission.AppPermission
import com.simplechat.app.permission.AppPermissions
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.theme.LocalChatColors

/**
 * 权限管理。
 *
 * 一屏看全 **这个 App 到底要什么、现在给了几项**。每一项都写清"不给会怎样" ——
 * 用户拒权限不是在跟开发者作对，他只是不知道代价。
 *
 * 状态每次回到前台重算（`ON_RESUME`）：用户是去系统设置里点的，回来必须立刻
 * 看到真实结果，而不是进来那一刻的缓存。
 *
 * 版式与设置里其它页是同一套（顶栏、16dp 边距、卡片、行首图标、状态标签），
 * 都来自 `SettingsKit.kt`。此前这一页自己写了一条顶栏（标题左对齐、箭头带灰底）、
 * 20dp 边距、12dp 圆角，每行右边还有一颗和整行重复的灰色圆按钮。
 */
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val colors = LocalChatColors.current
    val context = LocalContext.current
    val permissions = remember { AppPermissions(context) }
    var states by remember { mutableStateOf(permissions.snapshot()) }
    var failure by remember { mutableStateOf<String?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) states = permissions.snapshot()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.settingsBackground),
    ) {
        SettingsTopBar(
            title = Res.get(R.string.permissions_title),
            onBack = onBack,
            // 右上角是"给了几项 / 一共几项"：进来第一眼就知道还差多少
            trailing = Res.get(
                R.string.permissions_granted_count,
                states.count { it.granted == true },
                states.size,
            ),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SettingsDimens.ScreenPadding),
        ) {
            SettingsHint(
                text = Res.get(R.string.permissions_intro),
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
            )

            permissionRows().forEachIndexed { index, spec ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                val state = states.firstOrNull { it.permission == spec.permission }
                SettingsCard {
                    PermissionRow(
                        spec = spec,
                        granted = state?.granted,
                        onClick = {
                            failure = null
                            if (!permissions.open(spec.permission)) {
                                failure = Res.get(R.string.permissions_open_failed)
                            }
                        },
                    )
                }
            }

            failure?.let {
                Spacer(Modifier.height(12.dp))
                SettingsBanner(text = it, tone = SettingsTone.Danger)
            }

            SettingsHint(
                text = Res.get(R.string.permissions_footer),
                modifier = Modifier.padding(top = 16.dp),
            )

            Spacer(Modifier.height(32.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

/** 一项权限的静态描述。文案集中在这儿，界面只负责画。 */
private data class PermissionSpec(
    val permission: AppPermission,
    val title: Int,
    val body: Int,
    val impact: Int,
    /** 行首图标：五项权限五枚不同的图标，扫一眼就能认出是哪一项。 */
    val icon: ImageVector,
)

private fun permissionRows(): List<PermissionSpec> = listOf(
    PermissionSpec(
        AppPermission.NOTIFICATIONS,
        R.string.permission_notifications,
        R.string.permission_notifications_body,
        R.string.permission_notifications_impact,
        AppIcons.Bell,
    ),
    PermissionSpec(
        AppPermission.ALL_FILES,
        R.string.permission_files,
        R.string.permission_files_body,
        R.string.permission_files_impact,
        AppIcons.Folder,
    ),
    PermissionSpec(
        AppPermission.OVERLAY,
        R.string.permission_overlay,
        R.string.permission_overlay_body,
        R.string.permission_overlay_impact,
        AppIcons.Layers,
    ),
    PermissionSpec(
        AppPermission.ACCESSIBILITY,
        R.string.permission_accessibility,
        R.string.permission_accessibility_body,
        R.string.permission_accessibility_impact,
        AppIcons.Eye,
    ),
    PermissionSpec(
        AppPermission.BATTERY,
        R.string.permission_battery,
        R.string.permission_battery_body,
        R.string.permission_battery_impact,
        AppIcons.Bolt,
    ),
)

/**
 * 一项权限：图标 + 名称 + 状态标签，下面两行分别是"用来干什么"和"不给会怎样"。
 *
 * 整行可点（去系统设置里对应的那一页），行尾只是一根箭头 —— 不再另放一颗按钮，
 * 两个点下去结果一样的东西挨在一起，只会让人犹豫该点哪个。
 *
 * 状态用标签而不是彩色文字：「已授予」是主题色，「未授予」是中性灰 ——
 * 没给权限不是出错，不该用红色吓人。
 */
@Composable
private fun PermissionRow(spec: PermissionSpec, granted: Boolean?, onClick: () -> Unit) {
    val colors = LocalChatColors.current
    val statusText = when (granted) {
        true -> Res.get(R.string.permissions_granted)
        false -> Res.get(R.string.permissions_denied)
        null -> Res.get(R.string.permissions_unknown)
    }
    val detailStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 17.sp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = Res.get(R.string.permissions_open), onClick = onClick)
            .padding(horizontal = SettingsDimens.RowPadH, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(SettingsDimens.IconColumn),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = spec.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(SettingsDimens.Icon),
            )
        }
        Spacer(Modifier.width(SettingsDimens.IconGap))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Res.get(spec.title),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    // fill = false：标签紧跟在名称后面，而不是被顶到最右
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                SettingsTag(
                    text = statusText,
                    tone = if (granted == true) SettingsTone.Accent else SettingsTone.Neutral,
                )
            }
            Text(
                text = Res.get(spec.body),
                style = detailStyle,
                color = colors.placeholder,
                modifier = Modifier.padding(top = 3.dp),
            )
            Text(
                text = Res.get(spec.impact),
                style = detailStyle,
                color = colors.placeholder,
            )
        }
        Spacer(Modifier.width(8.dp))
        SettingsChevron()
    }
}
