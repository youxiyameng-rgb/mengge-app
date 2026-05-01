package com.aivoice.app.ui.dubbing

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.aivoice.app.api.ApiClient
import com.aivoice.app.databinding.FragmentDubbingBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DubbingFragment : Fragment() {
    private var _binding: FragmentDubbingBinding? = null
    private val binding get() = _binding!!
    private var mediaPlayer: MediaPlayer? = null
    private var currentFilePath: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDubbingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 设置音色选择器
        val voiceNames = ApiClient.VOICE_PRESETS.map { it.name }
        val voiceAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, voiceNames)
        voiceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerVoice.adapter = voiceAdapter

        binding.btnGenerate.setOnClickListener { generateSpeech() }
        binding.btnPlay.setOnClickListener { playAudio() }
        binding.btnShare.setOnClickListener { shareAudio() }
        binding.layoutPlayback.visibility = View.GONE
    }

    private fun generateSpeech() {
        val text = binding.editText.text.toString().trim()
        if (text.isEmpty()) { Toast.makeText(requireContext(), "请输入文本", Toast.LENGTH_SHORT).show(); return }
        val prefs = requireContext().getSharedPreferences("api_settings", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        val baseUrl = prefs.getString("base_url", ApiClient.DEFAULT_BASE_URL) ?: ApiClient.DEFAULT_BASE_URL
        val model = prefs.getString("model_name", ApiClient.DEFAULT_MODEL) ?: ApiClient.DEFAULT_MODEL
        if (apiKey.isEmpty()) { Toast.makeText(requireContext(), "请先在设置中配置 API Key", Toast.LENGTH_SHORT).show(); return }

        val selectedVoice = binding.spinnerVoice.selectedItemPosition
        val voiceValue = ApiClient.VOICE_PRESETS[selectedVoice].voiceValue

        binding.progressBar.visibility = View.VISIBLE
        binding.btnGenerate.isEnabled = false
        binding.layoutPlayback.visibility = View.GONE
        binding.tvStatus.text = "⏳ 正在生成配音..."

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    ApiClient.synthesizeSpeech(baseUrl, apiKey, text, model, voiceValue)
                }
                binding.progressBar.visibility = View.GONE
                binding.btnGenerate.isEnabled = true
                if (result != null) {
                    currentFilePath = result
                    binding.tvStatus.text = "✅ 生成成功！"
                    binding.layoutPlayback.visibility = View.VISIBLE
                    playAudio()
                } else {
                    binding.tvStatus.text = "❌ 生成失败，请检查 API Key 是否正确"
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                binding.btnGenerate.isEnabled = true
                binding.tvStatus.text = "❌ 错误: ${e.message}"
            }
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
                setOnCompletionListener {
                    binding.btnPlay.text = "▶️ 播放"
                }
            }
            binding.btnPlay.text = "⏸ 暂停"
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareAudio() {
        val path = currentFilePath ?: return
        try {
            val file = java.io.File(path)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(), "${requireContext().packageName}.provider", file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "分享音频"))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroyView()
        _binding = null
    }
}
