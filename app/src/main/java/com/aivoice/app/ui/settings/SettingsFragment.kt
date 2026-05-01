package com.aivoice.app.ui.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.aivoice.app.R
import com.aivoice.app.api.ApiClient
import com.aivoice.app.databinding.FragmentSettingsBinding
import com.aivoice.app.util.IconManager
import kotlin.math.min

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val iconColors = listOf("#9C27B0", "#2196F3", "#E91E63", "#4CAF50", "#FF9800")
    private var selectedColorIndex = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSettings()
        setupIconPicker()

        binding.btnSaveSettings.setOnClickListener {
            saveSettings()
            Toast.makeText(requireContext(), "✅ 设置已保存", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupIconPicker() {
        val container = binding.iconPickerContainer
        val iconSize = dpToPx(48)
        val margin = dpToPx(8)
        
        iconColors.forEachIndexed { index, color ->
            val iconView = ImageView(requireContext()).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = iconSize
                    height = iconSize
                    setMargins(margin, margin, margin, margin)
                    columnSpec = GridLayout.spec(index)
                    rowSpec = GridLayout.spec(0)
                }
                setImageResource(when(index) {
                    0 -> R.drawable.ic_launcher_purple
                    1 -> R.drawable.ic_launcher_blue
                    2 -> R.drawable.ic_launcher_pink
                    3 -> R.drawable.ic_launcher_green
                    else -> R.drawable.ic_launcher_orange
                })
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(8).toFloat()
                    setStroke(3, android.graphics.Color.parseColor(
                        if (index == selectedColorIndex) "#333333" else "#EEEEEE"
                    ))
                }
                setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
                setOnClickListener { selectIcon(index) }
            }
            container.addView(iconView)
        }
    }

    private fun selectIcon(index: Int) {
        selectedColorIndex = index
        IconManager.setIcon(requireContext(), iconColors[index])
        updateIconSelection()
        Toast.makeText(requireContext(), "图标颜色已切换！", Toast.LENGTH_SHORT).show()
    }

    private fun updateIconSelection() {
        val container = binding.iconPickerContainer
        for (i in 0 until container.childCount) {
            val iconView = container.getChildAt(i)
            iconView.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(8).toFloat()
                setStroke(3, android.graphics.Color.parseColor(
                    if (i == selectedColorIndex) "#333333" else "#EEEEEE"
                ))
            }
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

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
