package com.aivoice.app.ui.separator

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
import com.aivoice.app.databinding.FragmentSeparatorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SeparatorFragment : Fragment() {
    private var _binding: FragmentSeparatorBinding? = null
    private val binding get() = _binding!!
    private var mediaPlayer: MediaPlayer? = null
    private var currentFilePath: String? = null
    private var songFilePath: String? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val path = copyUriToFile(uri)
                if (path != null) {
                    songFilePath = path
                    binding.tvSongFile.text = "✅ 已选择: ${File(path).name}"
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSeparatorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        binding.btnSelectFile.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
            }
            filePicker.launch(intent)
        }
        binding.btnSeparate.setOnClickListener { separateTrack() }
        binding.btnPlay.setOnClickListener { playAudio() }
        binding.layoutPlayback.visibility = View.GONE
    }

    private fun separateTrack() {
        val prefs = requireContext().getSharedPreferences("api_settings", Context.MODE_PRIVATE)
        val replicateToken = prefs.getString("replicate_token", "") ?: ""

        if (replicateToken.isEmpty()) {
            Toast.makeText(requireContext(), "请先在设置中配置 Replicate Token", Toast.LENGTH_SHORT).show()
            return
        }
        if (songFilePath == null) {
            Toast.makeText(requireContext(), "请先选择音频文件", Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBar.visibility = View.VISIBLE
        binding.btnSeparate.isEnabled = false
        binding.layoutPlayback.visibility = View.GONE
        binding.tvStatus.text = "⏳ 正在分离伴奏... 上传音频 + AI 处理，请耐心等待"

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    ApiClient.separateTracks(replicateToken, songFilePath!!)
                }
                binding.progressBar.visibility = View.GONE
                binding.btnSeparate.isEnabled = true
                if (result != null) {
                    currentFilePath = result
                    binding.tvStatus.text = "✅ 伴奏分离完成！（已提取人声）"
                    binding.layoutPlayback.visibility = View.VISIBLE
                    playAudio()
                } else {
                    binding.tvStatus.text = "❌ 分离失败，请检查 Replicate Token"
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.btnSeparate.isEnabled = true
                binding.tvStatus.text = "❌ 错误: ${e.message}"
            }
        }
    }

    private fun copyUriToFile(uri: android.net.Uri): String? {
        return try {
            val input = requireContext().contentResolver.openInputStream(uri) ?: return null
            val file = File(requireContext().cacheDir, "separate_${System.currentTimeMillis()}.wav")
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
