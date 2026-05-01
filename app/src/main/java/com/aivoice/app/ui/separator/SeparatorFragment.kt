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
import kotlinx.coroutines.launch

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
        val baseUrl = prefs.getString("base_url", "") ?: ""
        if (apiKey.isEmpty() || baseUrl.isEmpty()) { Toast.makeText(requireContext(), "请先在设置中配置 API", Toast.LENGTH_SHORT).show(); return }
        binding.progressBar.visibility = View.VISIBLE; binding.btnSeparate.isEnabled = false
        lifecycleScope.launch {
            try {
                val result = ApiClient.separateTracks(baseUrl, apiKey)
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
