package com.aivoice.app.ui.covers

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.aivoice.app.api.ApiClient
import com.aivoice.app.databinding.FragmentCoversBinding
import kotlinx.coroutines.launch

class CoversFragment : Fragment() {
    private var _binding: FragmentCoversBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCoversBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnGenerate.setOnClickListener { generateCover() }
    }

    private fun generateCover() {
        val songName = binding.editSong.text.toString().trim()
        val lyrics = binding.editLyrics.text.toString().trim()
        if (songName.isEmpty() || lyrics.isEmpty()) { Toast.makeText(requireContext(), "请填写歌曲名和歌词", Toast.LENGTH_SHORT).show(); return }
        val prefs = requireContext().getSharedPreferences("api_settings", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        val baseUrl = prefs.getString("base_url", "") ?: ""
        if (apiKey.isEmpty() || baseUrl.isEmpty()) { Toast.makeText(requireContext(), "请先在设置中配置 API", Toast.LENGTH_SHORT).show(); return }
        binding.progressBar.visibility = View.VISIBLE; binding.btnGenerate.isEnabled = false
        lifecycleScope.launch {
            try {
                val result = ApiClient.generateCover(baseUrl, apiKey, songName, lyrics)
                binding.progressBar.visibility = View.GONE; binding.btnGenerate.isEnabled = true
                binding.tvStatus.text = if (result != null) "✅ 翻唱建议:\n$result" else "❌ 生成失败"
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE; binding.btnGenerate.isEnabled = true
                binding.tvStatus.text = "❌ 错误: ${e.message}"
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
