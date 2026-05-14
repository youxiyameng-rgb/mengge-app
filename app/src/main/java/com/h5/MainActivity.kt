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
                allowFileAccess = true
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_NO_CACHE
            }

            // 注入 GM 接口
            addJavascriptInterface(GMInterface(this@MainActivity), "GM")

            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()

            // 监听触摸事件，检测隐藏手势
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    checkSecretTap(event.x, event.y, v.width, v.height)
                }
                false // 继续传递事件给 WebView
            }

            // 加载本地游戏
            loadUrl("file:///android_asset/game/index.html")
        }

        layout.addView(webView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
    }

    // 检测右上角秘密点击
    private fun checkSecretTap(x: Float, y: Float, width: Int, height: Int) {
        val tapZoneX = width * 0.9f // 右上角 10% 区域
        val tapZoneY = height * 0.1f

        if (x > tapZoneX && y < tapZoneY) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastTapTime < tapTimeout) {
                tapCount++
                if (tapCount >= maxTaps) {
                    toggleGMPanel()
                    tapCount = 0
                    lastTapTime = 0
                }
            } else {
                tapCount = 1
                lastTapTime = currentTime
            }
        } else {
            tapCount = 0
        }
    }

    private fun toggleGMPanel() {
        gmVisible = !gmVisible
        val action = if (gmVisible) "block" else "none"

        webView.evaluateJavascript("""
            (function() {
                var panel = document.getElementById('gm-panel');
                if (!panel) {
                    panel = document.createElement('div');
                    panel.id = 'gm-panel';
                    panel.style.cssText = 'position:fixed;top:60px;right:20px;width:220px;background:rgba(0,0,0,0.95);color:white;padding:15px;border-radius:8px;z-index:9999;font-size:12px;display:none;box-shadow:0 0 10px rgba(0,0,0,0.5);';
                    panel.innerHTML = \`
                        <h3 style="margin:0 0 10px 0;border-bottom:1px solid #555;padding-bottom:5px;font-size:14px;">🔥 GM 控制台</h3>
                        <div style="display:grid;grid-template-columns:1fr 1fr;gap:5px;">
                            <button onclick="GM.setLevel(999)" style="background:#d32f2f;color:white;border:none;padding:5px;border-radius:4px;font-size:11px;">等级 999</button>
                            <button onclick="GM.setGold(99999)" style="background:#f57c00;color:white;border:none;padding:5px;border-radius:4px;font-size:11px;">金币 +9w</button>
                            <button onclick="GM.addEquipment('史诗武器')" style="background:#7b1fa2;color:white;border:none;padding:5px;border-radius:4px;font-size:11px;">送史诗</button>
                            <button onclick="GM.fillInventory()" style="background:#00796b;color:white;border:none;padding:5px;border-radius:4px;font-size:11px;">背包满</button>
                        </div>
                        <div style="margin-top:10px;border-top:1px solid #555;padding-top:5px;">
                            <label style="font-size:11px;">⚔️ 爆率倍数:</label>
                            <select onchange="GM.setDropRate(this.value)" style="width:100%;background:#333;color:white;border:1px solid #555;padding:2px;border-radius:4px;font-size:11px;">
                                <option value="1">1x (正常)</option>
                                <option value="10">10x (高)</option>
                                <option value="100">100x (超高)</option>
                                <option value="1000">1000x (满屏)</option>
                            </select>
                        </div>
                        <button onclick="document.getElementById('gm-panel').style.display='none'" style="width:100%;margin-top:10px;background:#555;color:white;border:none;padding:5px;border-radius:4px;font-size:11px;">关闭</button>
                    \`;
                    document.body.appendChild(panel);
                }
                panel.style.display = '$action';
            })()
        """, null)
    }

    class GMInterface(private val activity: MainActivity) {
        @JavascriptInterface
        fun setLevel(level: Int) {
            activity.runOnUiThread {
                activity.webView.evaluateJavascript("cc.sys.localStorage.setItem('player_level', $level); alert('等级已设为 $level');", null)
            }
        }

        @JavascriptInterface
        fun setGold(gold: Int) {
            activity.runOnUiThread {
                activity.webView.evaluateJavascript("var current = parseInt(cc.sys.localStorage.getItem('player_gold')||'0'); cc.sys.localStorage.setItem('player_gold', current + $gold); alert('金币已添加');", null)
            }
        }

        @JavascriptInterface
        fun addEquipment(itemName: String) {
            activity.runOnUiThread {
                activity.webView.evaluateJavascript("""
                    (function() {
                        var inv = JSON.parse(cc.sys.localStorage.getItem('player_inventory') || '[]');
                        inv.push({name: '$itemName', type: 'weapon', rarity: 'epic'});
                        cc.sys.localStorage.setItem('player_inventory', JSON.stringify(inv));
                        alert('获得装备：$itemName');
                        if(window.gameUtil && window.gameUtil.refreshInventory) window.gameUtil.refreshInventory();
                    })()
                """, null)
            }
        }

        @JavascriptInterface
        fun fillInventory() {
            activity.runOnUiThread {
                activity.webView.evaluateJavascript("""
                    (function() {
                        var items = [];
                        for(var i=0; i<50; i++) items.push({name: '神装'+i, type: 'armor', rarity: 'legendary'});
                        cc.sys.localStorage.setItem('player_inventory', JSON.stringify(items));
                        alert('背包已满！全是神装！');
                    })()
                """, null)
            }
        }

        @JavascriptInterface
        fun setDropRate(multiplier: String) {
            activity.runOnUiThread {
                activity.webView.evaluateJavascript("""
                    (function() {
                        var rate = parseFloat('$multiplier');
                        if(window.config) window.config.dropRate = rate;
                        if(rate > 1) {
                            var oldRandom = Math.random;
                            Math.random = function() { return oldRandom() / rate; };
                        }
                        cc.sys.localStorage.setItem('gm_drop_rate', rate);
                        alert('爆率已调整为 ' + rate + 'x！去打野怪试试吧！');
                    })()
                """, null)
            }
        }
    }
}
