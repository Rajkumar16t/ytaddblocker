package com.example.ytplayer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private const val START_URL = "https://m.youtube.com"
private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Mobile Safari/537.36"

// Hosts that stay inside the WebView; everything else opens in the system browser.
private val INTERNAL_HOSTS = listOf("youtube.com", "google.com", "gstatic.com", "googlevideo.com", "ytimg.com")

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeWebScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var customView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    fun exitFullscreen() {
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
    }

    // Back: close fullscreen -> WebView history -> exit app
    BackHandler {
        when {
            customView != null -> exitFullscreen()
            webView?.canGoBack() == true -> webView?.goBack()
            else -> onExit()
        }
    }

    // Hide system bars while a video is fullscreen
    LaunchedEffect(customView) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (customView != null) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Pause/resume WebView with the Activity lifecycle (not with rotation: configChanges handles that)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, webView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> webView?.onPause()
                Lifecycle.Event.ON_RESUME -> webView?.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            webView?.apply { stopLoading(); destroy() }
            webView = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(if (customView == null) Modifier.systemBarsPadding() else Modifier)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    setBackgroundColor(android.graphics.Color.BLACK)

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        userAgentString = MOBILE_UA
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        cacheMode = WebSettings.LOAD_DEFAULT
                        allowFileAccess = false
                        allowContentAccess = false
                    }

                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(this@apply, true)
                    }

                    webViewClient = object : WebViewClient() {

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            if (!request.isForMainFrame && AdBlocker.isBlocked(request.url.toString())) {
                                return AdBlocker.emptyResponse()
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            val host = request.url.host.orEmpty()
                            if (INTERNAL_HOSTS.any { host == it || host.endsWith(".$it") }) return false
                            runCatching {
                                view.context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(request.url.toString()))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                            return true
                        }

                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            super.onPageFinished(view, url)
                            view.evaluateJavascript(AdBlocker.injectionScript, null)
                        }

                        // YouTube is a single-page app: re-inject on in-app navigations too (guarded, so cheap)
                        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                            super.doUpdateVisitedHistory(view, url, isReload)
                            view.evaluateJavascript(AdBlocker.injectionScript, null)
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                            if (customView != null) {
                                callback.onCustomViewHidden()
                                return
                            }
                            customView = view
                            customViewCallback = callback
                        }

                        override fun onHideCustomView() {
                            exitFullscreen()
                        }
                    }

                    loadUrl(START_URL)
                    webView = this
                }
            }
        )

        // Fullscreen video surface supplied by the WebView
        customView?.let { v ->
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                factory = { v }
            )
        }
    }
}
