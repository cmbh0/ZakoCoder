package com.simplechat.app.ui.preview

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.simplechat.app.R
import com.simplechat.app.agent.PreviewServer
import com.simplechat.app.agent.PreviewSource
import com.simplechat.app.data.Res
import com.simplechat.app.ui.common.AppIcons
import com.simplechat.app.ui.common.IconCircleButton
import com.simplechat.app.ui.theme.LocalChatColors

/**
 * HTML 预览页。
 *
 * 三种东西同时出现时才叫"能用来调网页"：
 * - 一个真正的 http 源（不是 `file://`）—— 相对路径、fetch、模块脚本全部按网页规则走
 * - 一个能返回上一页的 WebView —— 页面之间互相跳转时不至于一点就退出预览
 * - 一个刷新键 —— AI 刚改完文件，用户按一下就看到新样子，不用退出去重进
 *
 * 服务本身见 [PreviewServer]：只监听 127.0.0.1，页面内容来自当前工作区。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPreviewScreen(
    source: PreviewSource,
    entry: String,
    title: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalChatColors.current

    val server = remember(source) { PreviewServer.open(source) }
    var address by remember(source, entry) { mutableStateOf(server.urlFor(entry)) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var failed by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableStateOf(0) }

    BackHandler(enabled = true) {
        if (canGoBack) webView?.goBack() else onClose()
    }

    /*
     * 刷新：只对**同一地址**重新 load。
     * AndroidView 的 update 每次重组都会跑，在那里写 loadUrl 会因为"加载完重组"
     * 再触发一次加载，变成死循环 —— 所以放在只认 token 的 effect 里。
     */
    LaunchedEffect(reloadToken) {
        if (reloadToken == 0) return@LaunchedEffect
        val target = server.urlFor(entry)
        address = target
        webView?.loadUrl(target)
    }

    DisposableEffect(Unit) {
        onDispose {
            // 页面销毁才停服务：预览通常连着看几个文件，来回开关服务会闪空白
            PreviewServer.stop()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.pageBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconCircleButton(
                icon = AppIcons.ArrowLeft,
                contentDescription = stringResource(R.string.action_back),
                onClick = onClose,
                size = 36.dp,
                iconSize = 19.dp,
                tint = colors.processText,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.trimStart('/').ifEmpty { "/" },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.placeholder,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconCircleButton(
                icon = AppIcons.Refresh,
                contentDescription = stringResource(R.string.web_preview_refresh),
                onClick = { reloadToken++ },
                size = 36.dp,
                iconSize = 18.dp,
                tint = colors.processText,
            )
            IconCircleButton(
                icon = AppIcons.ExternalLink,
                contentDescription = stringResource(R.string.web_preview_external),
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)))
                    }
                },
                size = 36.dp,
                iconSize = 18.dp,
                tint = colors.processText,
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setBackgroundColor(AndroidColor.WHITE)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            // 页面的资源全走 http，不需要也不该有文件系统访问权
                            allowFileAccess = false
                            allowContentAccess = false
                            mediaPlaybackRequiresUserGesture = false
                            // 页面里引用的 http 资源（CDN、本地 API）不该被拦
                            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            textZoom = 100
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val url = request?.url ?: return false
                                val local = url.host == "127.0.0.1" && url.port == server.port
                                if (local) return false
                                // 外链交给系统浏览器：预览窗里点进一个新闻页是最糟的体验
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                                return true
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                failed = null
                                canGoBack = view?.canGoBack() == true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                progress = 1f
                                canGoBack = view?.canGoBack() == true
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: android.webkit.WebResourceError?,
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true) {
                                    failed = error?.description?.toString()
                                }
                            }
                        }
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress / 100f
                            }
                        }
                        loadUrl(address)
                        webView = this
                    }
                },
                // 刷新交给上面的 effect：这里保持 WebView 实例不变（滚动位置不丢）
                update = { },
            )

            if (progress < 0.99f && failed == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(22.dp),
                    strokeWidth = 2.dp,
                )
            }

            failed?.let { message ->
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = Res.get(R.string.web_preview_failed, message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.processText,
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.web_preview_local_only),
            style = MaterialTheme.typography.labelSmall,
            color = colors.placeholder,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}
