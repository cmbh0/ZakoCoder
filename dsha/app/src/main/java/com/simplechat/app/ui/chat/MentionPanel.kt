package com.simplechat.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.simplechat.app.R
import com.simplechat.app.data.Res
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.AppShadow
import com.simplechat.app.ui.theme.LocalChatColors

/**
 * `@` 弹出的能力包列表。
 *
 * 放在输入框**正上方**、与输入栏同宽：它回答的是"我刚打的那个 @ 能接什么"，
 * 位置必须紧贴光标所在的区域，弹到屏幕中间就成了另一个东西。
 *
 * 每一项只给三个信息：类型、"叫什么"、"干什么的"。再多就没人看了。
 */
@Composable
fun MentionPanel(
    items: List<MentionItem>,
    onPick: (MentionItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChatColors.current
    val shape = RoundedCornerShape(14.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = AppShadow.PanelElevation,
                shape = shape,
                clip = false,
                ambientColor = AppShadow.PanelAmbient,
                spotColor = AppShadow.PanelSpot,
            )
            .clip(shape)
            .background(colors.card)
            .border(1.dp, colors.outline, shape)
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = Res.get(R.string.mention_title),
            style = MaterialTheme.typography.labelSmall,
            color = colors.placeholder,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        )
        items.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(item) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = when (item.kind) {
                        MentionKind.SKILL -> AppIcons.Sparkles
                        MentionKind.MCP -> AppIcons.Globe
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(17.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.subtitle.isNotBlank()) {
                        Text(
                            text = item.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.placeholder,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = when (item.kind) {
                        MentionKind.SKILL -> Res.get(R.string.mention_kind_skill)
                        MentionKind.MCP -> Res.get(R.string.mention_kind_mcp)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.placeholder,
                )
            }
        }
    }
}
