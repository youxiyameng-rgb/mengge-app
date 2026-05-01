package com.aivoice.app.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.aivoice.app.R
import com.aivoice.app.api.ApiClient
import com.aivoice.app.databinding.FragmentSettingsBinding
import com.aivoice.app.util.IconManager

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSettings()

        binding.btnSave.setOnClickListener {
            saveSettings()
            Toast.makeText(requireContext(), "✅ 设置已保存", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadSettings() {
        val prefs = requireContext().getSharedPreferences("api_settings", Context.MODE_PRIVATE)
        binding.editApiKey.setText(prefs.getString("api_key", ""))
        binding.editBaseUrl.setText(prefs.getString("base_url", ApiClient.DEFAULT_BASE_URL))
        binding.editModel.setText(prefs.getString("model_name", ApiClient.DEFAULT_MODEL))
        binding.editReplicateToken.setText(prefs.getString("replicate_token", ""))
    }

    private fun saveSettings() {
        val prefs = requireContext().getSharedPreferences("api_settings", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("api_key", binding.editApiKey.text.toString().trim())
            putString("base_url", binding.editBaseUrl.text.toString().trim().ifEmpty { ApiClient.DEFAULT_BASE_URL })
            putString("model_name", binding.editModel.text.toString().trim().ifEmpty { ApiClient.DEFAULT_MODEL })
            putString("replicate_token", binding.editReplicateToken.text.toString().trim())
            apply()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
