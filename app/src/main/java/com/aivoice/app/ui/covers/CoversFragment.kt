package com.aivoice.app.ui.covers

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.aivoice.app.api.ApiClient
import com.aivoice.app.databinding.FragmentCoversBinding
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CoversFragment : Fragment() {
    private var _binding: FragmentCoversBinding? = null
    private val binding get() = _binding!!

    private var mediaPlayer: MediaPlayer? = null
    private var currentFilePath: String? = null
    private var songFilePath: String? = null
    private var selectedModel = "music-cover-free"

    private val handler = Handler(Looper.getMainLooper())
    private var isSeekBarTracking = false

    // 进度更新 Runnable
    private val progressRunnable = object : Runnable {
        override fun run() {
            val mp = mediaPlayer ?: return
            if (mp.isPlaying && !isSeekBarTracking) {
                binding.seekBarPlayback.progress = mp.currentPosition
                binding.tvTimeCurrent.text = formatTime(mp.currentPosition)
            }
            handler.postDelayed(this, 300)
        }
    }

    private val stylePresets = listOf(
        "🎤 温柔女声" to "温柔甜美的女声翻唱，抒情流行风格，细腻情感",
        "🎸 摇滚男声" to "摇滚风格翻唱，沙哑力量感男声，电吉他鼓点伴奏",
        "🎹 轻柔钢琴" to "钢琴伴奏为主的轻柔翻唱，简约空灵编曲",
        "🎷 爵士风情" to "爵士风格翻唱，即兴感，萨克斯和低音贝斯",
        "🎻 古风国潮" to "中国风翻唱，古筝和笛子元素，古风韵味",
        "🎧 电子舞曲" to "电子舞曲风格翻唱，合成器音色，节奏感强",
        "🪕 民谣弹唱" to "民谣吉他弹唱风格，清新自然，原声感",
        "🎻 交响史诗" to "交响乐编曲翻唱，气势磅礴，管弦乐配器"
    )

    private val songPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val path = copyUriToFile(uri, "song_input")
                if (path != null) {
                    songFilePath = path
                    binding.tvSongFile.text = "✅ 已选择: ${File(path).name}"
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCoversBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.etSongDesc.setText(stylePresets[0].second)
        setupStyleChips()
        setupModelChips()
        setupEngineTabs()

        binding.btnSelectSong.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
            }
            songPicker.launch(intent)
        }

        binding.btnGenerate.setOnClickListener { generateCover() }
        binding.btnPlay.setOnClickListener { togglePlayPause() }
        binding.btnStop.setOnClickListener { stopPlayback() }
        binding.btnDownload.setOnClickListener { downloadCover() }

        binding.seekBarPlayback.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvTimeCurrent.text = formatTime(progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {
                isSeekBarTracking = true
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                try {
                    mediaPlayer?.seekTo(sb.progress)
                } catch (_: Exception) {}
                isSeekBarTracking = false
            }
        })

        binding.layoutPlayback.visibility = View.GONE
        binding.layoutLyrics.visibility = View.GONE
    }

    private fun setupModelChips() {
        binding.chipFreeModel.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedModel = "music-cover-free"
                binding.chipPaidModel.isChecked = false
                binding.tvModelHint.text = "💡 免费模型，风格控制较弱但完全免费"
            }
        }
        binding.chipPaidModel.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                selectedModel = "music-cover"
                binding.chipFreeModel.isChecked = false
                binding.tvModelHint.text = "💡 付费模型，风格控制力强，需要充值 Token Plan"
            }
        }
        binding.chipFreeModel.isChecked = true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupEngineTabs() {
        binding.chipEngineMinimax.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipEngineMemotune.isChecked = false
                binding.layoutMinimax.visibility = View.VISIBLE
                binding.layoutMemotune.visibility = View.GONE
            }
        }
        binding.chipEngineMemotune.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.chipEngineMinimax.isChecked = false
                binding.layoutMinimax.visibility = View.GONE
                binding.layoutMemotune.visibility = View.VISIBLE
                initMemoTuneWebView()
            }
        }
        // 默认选中 MiniMax
        binding.chipEngineMinimax.isChecked = true
    }

    private var memoTuneLoaded = false

    @SuppressLint("SetJavaScriptEnabled")
    private fun initMemoTuneWebView() {
        if (memoTuneLoaded) return
        memoTuneLoaded = true

        binding.webviewMemotune.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false  // 允许音频自动播放
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            userAgentString = userAgentString.replace("; wv", "") // 避免被认为是 WebView
        }

        binding.webviewMemotune.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false // 在 WebView 内加载
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 注入JS：确保Web Audio API的AudioContext正常工作
                view?.evaluateJavascript("""
                    (function() {
                        document.querySelectorAll('audio,video').forEach(function(el) {
                            el.setAttribute('preload','auto');
                        });
                        document.addEventListener('click', function() {
                            if (window.AudioContext || window.webkitAudioContext) {
                                var ctx = new (window.AudioContext || window.webkitAudioContext)();
                                if (ctx.state === 'suspended') { ctx.resume(); }
                            }
                        }, { once: true });
                    })();
                """, null)
            }
        }

        binding.webviewMemotune.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                // 自动授予音频播放权限
                request?.grant(request.resources)
            }
        }

        binding.webviewMemotune.loadUrl("https://memotune.com/zh-CN/ai-voice-cover")
    }

    private fun setupStyleChips() {
        for ((index, pair) in stylePresets.withIndex()) {
            val (label, prompt) = pair
            val chip = Chip(requireContext()).apply {
                text = label
                isCheckable = true
                isChecked = index == 0
                setOnClickListener {
                    binding.etSongDesc.setText(prompt)
                    for (i in 0 until binding.chipGroupStyles.childCount) {
                        (binding.chipGroupStyles.getChildAt(i) as? Chip)?.isChecked = (i == index)
                    }
                }
            }
            binding.chipGroupStyles.addView(chip)
        }
    }

    // ===== 翻唱 =====

    private fun generateCover() {
        val prefs = requireContext().getSharedPreferences("api_settings", Context.MODE_PRIVATE)
        val minimaxKey = prefs.getString("minimax_api_key", "") ?: ""

        if (minimaxKey.isEmpty()) {
            Toast.makeText(requireContext(), "请先在设置中配置 MiniMax API Key", Toast.LENGTH_LONG).show()
            return
        }
        if (songFilePath == null) {
            Toast.makeText(requireContext(), "请选择原唱歌曲文件", Toast.LENGTH_SHORT).show()
            return
        }
        val songDesc = binding.etSongDesc.text.toString().trim()
        if (songDesc.length < 10) {
            Toast.makeText(requireContext(), "风格描述至少10个字", Toast.LENGTH_SHORT).show()
            return
        }

        // 先停止之前的播放
        releaseMediaPlayer()

        val modelLabel = if (selectedModel == "music-cover-free") "免费版" else "付费版"
        binding.progressBar.visibility = View.VISIBLE
        binding.btnGenerate.isEnabled = false
        binding.layoutPlayback.visibility = View.GONE
        binding.layoutLyrics.visibility = View.GONE
        binding.tvStatus.text = "⏳ 翻唱处理中 ($modelLabel)...\n📝 风格: $songDesc"

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    ApiClient.generateCover(minimaxKey, songFilePath!!, songDesc, selectedModel)
                }
                binding.progressBar.visibility = View.GONE
                binding.btnGenerate.isEnabled = true
                if (result != null) {
                    currentFilePath = result.audioPath
                    binding.tvStatus.text = "✅ 翻唱完成！($modelLabel)"
                    showPlaybackControls(result.audioPath)
                    showLyrics(result.lyrics)
                } else {
                    binding.tvStatus.text = "❌ 翻唱失败：返回结果为空"
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.btnGenerate.isEnabled = true
                val hint = if (e.message?.contains("1008") == true) {
                    "\n💡 付费模型需要充值 Token Plan"
                } else ""
                binding.tvStatus.text = "❌ ${e.message}$hint"
            }
        }
    }

    // ===== 歌词 =====

    private fun showLyrics(lyrics: String?) {
        if (!lyrics.isNullOrEmpty()) {
            binding.layoutLyrics.visibility = View.VISIBLE
            val lines = lyrics.split("\n").filter { it.isNotBlank() }
            binding.tvLyricsLine1.text = if (lines.isNotEmpty()) "🎵 ${lines[0].trim()}" else ""
            binding.tvLyricsLine2.text = if (lines.size > 1) "🎵 ${lines[1].trim()}" else ""
        } else {
            binding.layoutLyrics.visibility = View.GONE
        }
    }

    // ===== 播放控制 =====

    private fun showPlaybackControls(path: String) {
        // 只显示控件，不播放
        binding.layoutPlayback.visibility = View.VISIBLE
        binding.seekBarPlayback.progress = 0
        binding.tvTimeCurrent.text = "0:00"

        // 先预加载获取时长，但不播放
        try {
            val mp = MediaPlayer()
            mp.setDataSource(path)
            mp.prepare()
            binding.seekBarPlayback.max = mp.duration
            binding.tvTimeTotal.text = formatTime(mp.duration)
            mp.release()
        } catch (_: Exception) {
            binding.tvTimeTotal.text = "0:00"
        }

        binding.btnPlay.text = "▶️ 播放"
    }

    /** 创建并开始播放 */
    private fun startPlayback() {
        val path = currentFilePath ?: return
        try {
            releaseMediaPlayer()

            mediaPlayer = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                start()

                // 设置进度条范围
                binding.seekBarPlayback.max = duration
                binding.tvTimeTotal.text = formatTime(duration)
                binding.tvTimeCurrent.text = "0:00"
                binding.btnPlay.text = "⏸ 暂停"

                setOnCompletionListener {
                    // 播放完成 → 按钮变为"重播"
                    binding.btnPlay.text = "🔄 重播"
                    handler.removeCallbacks(progressRunnable)
                    binding.seekBarPlayback.progress = binding.seekBarPlayback.max
                    binding.tvTimeCurrent.text = formatTime(duration)
                }
            }

            // 启动进度更新
            handler.post(progressRunnable)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 暂停/继续/重播 切换 */
    private fun togglePlayPause() {
        val mp = mediaPlayer

        // mediaPlayer 不存在或已播放完成 → 从头播放
        if (mp == null || !mp.isPlaying && !isPaused()) {
            startPlayback()
            return
        }

        try {
            if (mp.isPlaying) {
                // 正在播放 → 暂停
                mp.pause()
                binding.btnPlay.text = "▶️ 继续"
                handler.removeCallbacks(progressRunnable)
            } else {
                // 已暂停 → 继续
                mp.start()
                binding.btnPlay.text = "⏸ 暂停"
                handler.post(progressRunnable)
            }
        } catch (e: Exception) {
            // 出错了就重新播放
            startPlayback()
        }
    }

    /** 判断是否处于暂停状态（非播放、非播放完成） */
    private fun isPaused(): Boolean {
        val mp = mediaPlayer ?: return false
        return try {
            // 如果能获取到 duration 且 position < duration，说明是暂停
            mp.currentPosition < mp.duration && mp.currentPosition > 0
        } catch (_: Exception) {
            false
        }
    }

    /** 停止播放 */
    private fun stopPlayback() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
            }
            releaseMediaPlayer()
        } catch (_: Exception) {}

        binding.btnPlay.text = "▶️ 播放"
        binding.seekBarPlayback.progress = 0
        binding.tvTimeCurrent.text = "0:00"
        handler.removeCallbacks(progressRunnable)
    }

    /** 释放 MediaPlayer */
    private fun releaseMediaPlayer() {
        handler.removeCallbacks(progressRunnable)
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    // ===== 下载 =====

    private fun downloadCover() {
        val path = currentFilePath ?: return
        try {
            val srcFile = File(path)
            val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            val destFile = File(downloadDir, "AI翻唱_${System.currentTimeMillis()}.mp3")
            srcFile.copyTo(destFile, overwrite = true)

            // 通知媒体库扫描
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = Uri.fromFile(destFile)
            requireContext().sendBroadcast(mediaScanIntent)

            Toast.makeText(requireContext(),
                "✅ 已保存到 Download/${destFile.name}",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "❌ 保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ===== 工具 =====

    private fun copyUriToFile(uri: Uri, prefix: String): String? {
        return try {
            val input = requireContext().contentResolver.openInputStream(uri) ?: return null
            val file = File(requireContext().cacheDir, "${prefix}_${System.currentTimeMillis()}.wav")
            file.outputStream().use { output -> input.copyTo(output) }
            input.close()
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "$min:${if (sec < 10) "0" else ""}$sec"
    }

    override fun onDestroyView() {
        releaseMediaPlayer()
        if (memoTuneLoaded) {
            try {
                binding.webviewMemotune.stopLoading()
                binding.webviewMemotune.destroy()
            } catch (_: Exception) {}
        }
        super.onDestroyView()
        _binding = null
    }
}
