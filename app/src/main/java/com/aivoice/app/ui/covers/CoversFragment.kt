package com.aivoice.app.ui.covers

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.aivoice.app.api.ApiClient
import com.aivoice.app.databinding.FragmentCoversBinding
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

    private val stylePresets = listOf(
        "🎤 选择翻唱风格...",
        "🎵 流行女声版 - 温柔甜美女声，流行编曲，轻快节奏",
        "🎸 摇滚版 - 激烈电吉他，强力鼓点，嘶吼男声",
        "🎷 爵士版 - 萨克斯主奏，柔和女声，爵士钢琴和弦",
        "🎹 钢琴抒情版 - 钢琴伴奏，深情男声，简约编曲",
        "🎻 古风版 - 古筝琵琶，悠扬女声，中国风编曲",
        "🎧 电子EDM版 - 电子合成器，128BPM，动感节拍",
        "🌊 Lo-fi版 - 黑胶噪声，慵懒节拍，温暖中频",
        "🎻 民谣吉他版 - 指弹吉他，温暖男声，亲密录音室",
        "🤘 说唱版 - 说唱flow，808贝斯，trap节拍",
        "✨ 自定义 (在下方输入)")
    private val stylePrompts = listOf(
        "",
        "Pop version, sweet female vocal, catchy melody, light upbeat arrangement",
        "Hard rock, electric guitar riffs, powerful drums, intense male vocal",
        "Jazz arrangement, saxophone lead, smooth female vocal, mellow piano chords",
        "Piano ballad, emotional male vocal, minimal arrangement, intimate feel",
        "Traditional Chinese style, guzheng and pipa, ethereal female vocal",
        "EDM remix, 128 BPM, synth bass, atmospheric pads, driving beat",
        "Lo-fi hip hop version, vinyl crackle, chill beat, warm midrange",
        "Acoustic folk, fingerpicked guitar, warm male vocal, intimate studio feel",
        "Rap version, trap beat, 808 bass, flow delivery",
        "")

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

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, stylePresets)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerStyle.adapter = adapter
        binding.spinnerStyle.setSelection(1)
        binding.spinnerStyle.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                binding.etCustomPrompt.visibility = if (position == stylePresets.size - 1) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })

        binding.btnSelectSong.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
            }
            songPicker.launch(intent)
        }

        binding.btnGenerate.setOnClickListener { generateCover() }
        binding.btnPlay.setOnClickListener { playAudio() }
        binding.layoutPlayback.visibility = View.GONE
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

        val spinnerPos = binding.spinnerStyle.selectedItemPosition
        val stylePrompt = if (spinnerPos == stylePresets.size - 1) {
            binding.etCustomPrompt.text.toString().trim()
        } else {
            stylePrompts[spinnerPos]
        }

        if (stylePrompt.isEmpty()) {
            Toast.makeText(requireContext(), "请选择或输入翻唱风格", Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.btnGenerate.isEnabled = false
        binding.layoutPlayback.visibility = View.GONE
        binding.tvStatus.text = "⏳ 翻唱处理中...\n💡 海螺音乐是国内服务器，速度快且稳定\n💡 大文件处理需要1-5分钟，请耐心等待"

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    ApiClient.generateCover(minimaxKey, songFilePath!!, stylePrompt)
                }
                binding.progressBar.visibility = View.GONE
                binding.btnGenerate.isEnabled = true
                if (result != null) {
                    currentFilePath = result
                    binding.tvStatus.text = "✅ 翻唱完成！"
                    binding.layoutPlayback.visibility = View.VISIBLE
                    playAudio()
                } else {
                    binding.tvStatus.text = "❌ 翻唱失败：返回结果为空"
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.btnGenerate.isEnabled = true
                binding.tvStatus.text = "❌ ${e.message}\n\n💡 如果是API配置问题，请检查设置页的MiniMax API Key"
            }
        }
    }

    private fun copyUriToFile(uri: android.net.Uri, prefix: String): String? {
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

    private fun playAudio() {
        val path = currentFilePath ?: return
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                start()
                setOnCompletionListener { binding.btnPlay.text = "▶️ 播放" }
            }
            binding.btnPlay.text = "⏸ 暂停"
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroyView()
        _binding = null
    }
}
