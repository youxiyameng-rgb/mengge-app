package com.aivoice.app.ui.clone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.aivoice.app.api.ApiClient
import com.aivoice.app.databinding.FragmentCloneBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class CloneFragment : Fragment() {
    private var _binding: FragmentCloneBinding? = null
    private val binding get() = _binding!!
    private var mediaPlayer: MediaPlayer? = null
    private var currentFilePath: String? = null
    private var referenceAudioPath: String? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val fileName = getFileName(uri)
                binding.tvRefAudio.text = "📎 $fileName"
                // Copy to internal storage
                referenceAudioPath = copyFileToInternal(uri)
                Toast.makeText(requireContext(), "参考音频已选择", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCloneBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnUpload.setOnClickListener { pickAudioFile() }
        binding.btnClone.setOnClickListener { cloneVoice() }
        binding.btnPlay.setOnClickListener { playAudio() }
        binding.btnShare.setOnClickListener { shareAudio() }
        binding.layoutPlayback.visibility = View.GONE
    }

    private fun pickAudioFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        filePicker.launch(intent)
    }

    private fun getFileName(uri: android.net.Uri): String {
        var name = "未知文件"
        requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun copyFileToInternal(uri: android.net.Uri): String? {
        return try {
            val dir = File(requireContext().filesDir, "ref_audio")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "ref_${System.currentTimeMillis()}.wav")
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun cloneVoice() {
        val text = binding.editText.text.toString().trim()
        if (text.isEmpty()) { Toast.makeText(requireContext(), "请输入要合成的文本", Toast.LENGTH_SHORT).show(); return }
        val prefs = requireContext().getSharedPreferences("api_settings", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        val baseUrl = prefs.getString("base_url", ApiClient.DEFAULT_BASE_URL) ?: ApiClient.DEFAULT_BASE_URL
        val model = prefs.getString("model_name", ApiClient.DEFAULT_MODEL) ?: ApiClient.DEFAULT_MODEL
        if (apiKey.isEmpty()) { Toast.makeText(requireContext(), "请先在设置中配置 API Key", Toast.LENGTH_SHORT).show(); return }

        binding.progressBar.visibility = View.VISIBLE; binding.btnClone.isEnabled = false
        binding.layoutPlayback.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    ApiClient.cloneVoice(baseUrl, apiKey, text, referenceAudioPath, model)
                }
                binding.progressBar.visibility = View.GONE; binding.btnClone.isEnabled = true
                if (result != null) {
                    currentFilePath = result
                    binding.tvStatus.text = "✅ 克隆合成成功！"
                    binding.layoutPlayback.visibility = View.VISIBLE
                    playAudio()
                } else {
                    binding.tvStatus.text = "❌ 克隆失败，请检查 API 配置"
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE; binding.btnClone.isEnabled = true
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
                setOnCompletionListener { binding.btnPlay.text = "▶️ 播放" }
            }
            binding.btnPlay.text = "⏸ 暂停"
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "播放失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareAudio() {
        val path = currentFilePath ?: return
        try {
            val file = File(path)
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
