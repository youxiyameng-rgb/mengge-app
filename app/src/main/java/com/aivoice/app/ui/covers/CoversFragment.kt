package com.aivoice.app.ui.covers

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

        // 默认提示词
        binding.etSongDesc.setText("a pop song with vocals")

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

        val songDesc = binding.etSongDesc.text.toString().trim()
        if (songDesc.isEmpty()) {
            Toast.makeText(requireContext(), "请填写歌曲风格描述", Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.btnGenerate.isEnabled = false
        binding.layoutPlayback.visibility = View.GONE
        binding.tvStatus.text = "⏳ 翻唱处理中...\n💡 海螺音乐是国内服务器，速度快且稳定\n💡 处理需要1-5分钟，请耐心等待"

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    ApiClient.generateCover(minimaxKey, songFilePath!!, songDesc)
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
