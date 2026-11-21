package com.simplechat.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.simplechat.app.R
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.iconForPath
import com.simplechat.app.ui.theme.LocalChatColors
import java.io.File

/**
 * 消息气泡末尾的**产物条**：这一轮 AI 做出来交给用户的文件。
 *
 * 规则照需求来：
 * - 一个都没有 → 整块不渲染（调用方负责判断）
 * - 最多直接摆 [VisibleLimit] 个；多出来的收进末尾那颗「…」
 * - 点任意一个 → 交给系统用其他应用打开（见 `ArtifactOpener`）
 *
 * 为什么放在气泡**下面**而不是塞进正文：产物是"顺手交付的东西"，不该打断
 * 阅读；用户看完回答，抬眼就能拿到文件。
 */
@Composable
fun ArtifactBar(
    paths: List<String>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (paths.isEmpty()) return
    val colors = LocalChatColors.current
    var showAll by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        paths.take(VisibleLimit).forEach { path ->
            ArtifactChip(path = path, onClick = { onOpen(path) }, modifier = Modifier.weight(1f, fill = false))
        }
        if (paths.size > VisibleLimit) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.fieldBackground)
                    .clickable { showAll = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = AppIcons.More,
                    contentDescription = stringResource(R.string.artifacts_more, paths.size),
                    tint = colors.processText,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }

    if (showAll) {
        Dialog(onDismissRequest = { showAll = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(18.dp),
            ) {
                Text(
                    text = stringResource(R.string.artifacts_title, paths.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.size(12.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    paths.forEach { path ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    showAll = false
                                    onOpen(path)
                                }
                                .padding(vertical = 8.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = iconForPath(path),
                                contentDescription = null,
                                tint = colors.processText,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = File(path).name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtifactChip(path: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalChatColors.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.fieldBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = iconForPath(path),
            contentDescription = null,
            tint = colors.processText,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = File(path).name,
            style = MaterialTheme.typography.labelSmall,
            color = colors.processText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 直接摆出来的产物个数；多出来的收进「…」。 */
private const val VisibleLimit = 3
