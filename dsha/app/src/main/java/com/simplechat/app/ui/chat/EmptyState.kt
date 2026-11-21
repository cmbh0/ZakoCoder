package com.simplechat.app.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.simplechat.app.R

/**
 * 空态：品牌图标 + 一句问候。
 *
 * 图标用的是应用自己的图标，不是线性占位符 —— 打开 App 第一屏就是"这是谁"。
 * 与 DeepSeek 首页同一套构图：正中一个方形 Logo，下面是粗体的一句话，
 * 不写功能说明、不放示例问题（那些都在输入框上方抢注意力）。
 */
@Composable
fun EmptyState(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_brand),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp)),
        )

        Spacer(Modifier.size(20.dp))

        Text(
            text = stringResource(R.string.empty_greeting),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
    }
}
