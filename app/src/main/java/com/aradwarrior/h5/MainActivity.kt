package com.aradwarrior.h5

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var gmVisible = false

    // 隐藏手势检测：右上角连击 5 次
    private var tapCount = 0
    private var lastTapTime = 0L
    private val maxTaps = 5
    private val tapTimeout = 1000L // 1 秒内完成

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏 + 常亮 + 沉浸式
        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        val layout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        setContentView(layout)

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(GameManagerInterface(), "GameManager")
            // 加载远程游戏页面
            loadUrl("https://youxiyameng-rgb.github.io/arad-warrior-h5/")
        }

        layout.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_DOWN) {
            val x = event.x.toInt()
            val y = event.y.toInt()
            val screenWidth = window.decorView.width
            val screenHeight = window.decorView.height

            // 检测右上角 100x100 区域
            if (x > screenWidth - 100 && y < 100) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastTapTime < tapTimeout) {
                    tapCount++
                    if (tapCount >= maxTaps) {
                        tapCount = 0
                        gmVisible = !gmVisible
                        if (gmVisible) {
                            showGameManager()
                        } else {
                            hideGameManager()
                        }
                    }
                } else {
                    tapCount = 1
                }
                lastTapTime = currentTime
            }
        }
        return super.onTouchEvent(event)
    }

    private fun showGameManager() {
        webView.loadUrl("javascript:(function() { " +
            "var div = document.getElementById('gm-panel');" +
            "if (!div) {" +
            "  div = document.createElement('div');" +
            "  div.id = 'gm-panel';" +
            "  div.style.cssText = 'position:fixed;top:10px;left:10px;width:200px;height:300px;background:#333;color:#fff;padding:10px;border-radius:5px;z-index:9999;';" +
            "  div.innerHTML = '<h3>GameManager</h3><p>GM Panel</p>';" +
            "  document.body.appendChild(div);" +
            "}" +
            "div.style.display = 'block';" +
            "})()")
    }

    private fun hideGameManager() {
        webView.loadUrl("javascript:(function() { " +
            "var div = document.getElementById('gm-panel');" +
            "if (div) div.style.display = 'none';" +
            "})()")
    }

    inner class GameManagerInterface {
        @JavascriptInterface
        fun showToast(message: String) {
            webView.post {
                android.widget.Toast.makeText(this@MainActivity, message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
