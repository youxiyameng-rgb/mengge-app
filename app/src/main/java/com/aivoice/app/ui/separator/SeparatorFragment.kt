package com.aivoice.app.ui.separator

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.aivoice.app.api.ApiClient
import com.aivoice.app.databinding.FragmentSeparatorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SeparatorFragment : Fragment() {
    private var _binding: FragmentSeparatorBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSeparatorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnUpload.setOnClickListener { Toast.makeText(requireContext(), "请选择音频文件", Toast.LENGTH_SHORT).show() }
        binding.btnSeparate.setOnClickListener { separateTrack() }
    }

    private fun separateTrack() {
        val prefs = requireContext().getSharedPreferences("api_settings", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        val baseUrl = prefs.getString("base_url", ApiClient.DEFAULT_BASE_URL) ?: ApiClient.DEFAULT_BASE_URL
        val model = prefs.getString("model_name", "Qwen/Qwen3-8B") ?: "Qwen/Qwen3-8B"
        if (apiKey.isEmpty()) { Toast.makeText(requireContext(), "请先在设置中配置 API Key", Toast.LENGTH_SHORT).show(); return }
        binding.progressBar.visibility = View.VISIBLE; binding.btnSeparate.isEnabled = false
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    ApiClient.separateTracks(baseUrl, apiKey, model)
                }
                binding.progressBar.visibility = View.GONE; binding.btnSeparate.isEnabled = true
                binding.tvStatus.text = if (result != null) "✅ 分离建议:\n$result" else "❌ 分离失败"
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE; binding.btnSeparate.isEnabled = true
                binding.tvStatus.text = "❌ 错误: ${e.message}"
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
