package com.aivoice.app.ui.covers

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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
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
    private var progressRunnable: Runnable? = null

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.etSongDesc.setText(stylePresets[0].second)
        setupStyleChips()
        setupModelChips()

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
        binding.seekBarPlayback.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.seekTo(progress)
                    updateTimeDisplay(progress)
                }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
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
                    showPlaybackControls()
                    showLyrics(result.lyrics)
                    startAutoPlay()
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

    private fun showPlaybackControls() {
        binding.layoutPlayback.visibility = View.VISIBLE
        binding.btnPlay.text = "⏸ 暂停"
        binding.btnPlay.isEnabled = true
        binding.btnStop.isEnabled = true
        binding.btnDownload.isEnabled = true
    }

    private fun showLyrics(lyrics: String?) {
        if (!lyrics.isNullOrEmpty()) {
            binding.layoutLyrics.visibility = View.VISIBLE
            // 取前两行歌词显示
            val lines = lyrics.split("\n").filter { it.isNotBlank() }
            binding.tvLyricsLine1.text = if (lines.isNotEmpty()) "🎵 ${lines[0].trim()}" else ""
            binding.tvLyricsLine2.text = if (lines.size > 1) "🎵 ${lines[1].trim()}" else ""
        } else {
            // API不返回歌词就不显示
            binding.layoutLyrics.visibility = View.GONE
        }
    }

    private fun startAutoPlay() {
        val path = currentFilePath ?: return
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                start()

                // 设置进度条
                binding.seekBarPlayback.max = duration
                binding.tvTimeTotal.text = formatTime(duration)
                binding.tvTimeCurrent.text = "0:00"
                binding.btnPlay.text = "⏸ 暂停"

                setOnCompletionListener {
                    binding.btnPlay.text = "▶️ 播放"
                    stopProgressUpdate()
                    binding.seekBarPlayback.progress = binding.seekBarPlayback.max
                    binding.tvTimeCurrent.text = formatTime(duration)
                }
            }
            startProgressUpdate()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun togglePlayPause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            binding.btnPlay.text = "▶️ 播放"
            stopProgressUpdate()
        } else {
            mp.start()
            binding.btnPlay.text = "⏸ 暂停"
            startProgressUpdate()
        }
    }

    private fun stopPlayback() {
        mediaPlayer?.apply {
            stop()
            prepare()
            seekTo(0)
        }
        binding.btnPlay.text = "▶️ 播放"
        binding.seekBarPlayback.progress = 0
        binding.tvTimeCurrent.text = "0:00"
        stopProgressUpdate()
    }

    private fun startProgressUpdate() {
        stopProgressUpdate()
        progressRunnable = object : Runnable {
            override fun run() {
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        binding.seekBarPlayback.progress = it.currentPosition
                        updateTimeDisplay(it.currentPosition)
                        handler.postDelayed(this, 500)
                    }
                }
            }
        }
        handler.post(progressRunnable!!)
    }

    private fun stopProgressUpdate() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    private fun updateTimeDisplay(currentMs: Int) {
        binding.tvTimeCurrent.text = formatTime(currentMs)
    }

    private fun formatTime(ms: Int): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "$min:${if (sec < 10) "0" else ""}$sec"
    }

    private fun downloadCover() {
        val path = currentFilePath ?: return
        try {
            val srcFile = File(path)
            // 复制到公共下载目录
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

    override fun onDestroyView() {
        stopProgressUpdate()
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroyView()
        _binding = null
    }
}
