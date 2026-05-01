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
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
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
        binding.btnSave.setOnClickListener { saveSettings() }
        setupIconSelector()
    }

    private fun setupIconSelector() {
        val currentKey = IconManager.getCurrentIconKey(requireContext())
        updateCurrentIconPreview(currentKey)

        val grid = binding.gridIcons
        grid.removeAllViews()

        val iconDrawables = mapOf(
            "purple" to R.drawable.ic_launcher_purple,
            "blue" to R.drawable.ic_launcher_blue,
            "pink" to R.drawable.ic_launcher_pink,
            "green" to R.drawable.ic_launcher_green,
            "orange" to R.drawable.ic_launcher_orange
        )

        for (theme in IconManager.ICON_THEMES) {
            val resId = iconDrawables[theme.key] ?: continue

            // Container with icon + label
            val container = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val size = resources.displayMetrics.widthPixels / 6
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                }
                setPadding(8, 8, 8, 8)
            }

            val imgView = ImageView(requireContext()).apply {
                setImageResource(resId)
                layoutParams = LinearLayout.LayoutParams(120, 120)
                scaleType = ImageView.ScaleType.FIT_CENTER
                // 当前选中的加边框
                if (theme.key == currentKey) {
                    background = createHighlightBorder()
                }
            }

            val labelView = TextView(requireContext()).apply {
                text = theme.name.substringAfter(" ")  // 去掉 emoji，保留中文
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            }

            container.addView(imgView)
            container.addView(labelView)

            container.setOnClickListener {
                if (theme.key == currentKey) return@setOnClickListener
                IconManager.applyIcon(requireContext(), theme.key)
                Toast.makeText(requireContext(), "图标已切换为${theme.name}，桌面即将更新", Toast.LENGTH_SHORT).show()
                // 延迟刷新 UI，给系统一点时间
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isAdded) {
                        setupIconSelector() // 刷新选中状态
                    }
                }, 500)
            }

            grid.addView(container)
        }
    }

    private fun createHighlightBorder(): GradientDrawable {
        return GradientDrawable().apply {
            setStroke(4, 0xFF6C63FF.toInt())
            cornerRadius = 16f
            setColor(0x1A6C63FF.toInt()) // 浅紫背景
        }
    }

    private fun updateCurrentIconPreview(currentKey: String) {
        val iconDrawables = mapOf(
            "purple" to R.drawable.ic_launcher_purple,
            "blue" to R.drawable.ic_launcher_blue,
            "pink" to R.drawable.ic_launcher_pink,
            "green" to R.drawable.ic_launcher_green,
            "orange" to R.drawable.ic_launcher_orange
        )
        binding.imgCurrentIcon.setImageResource(iconDrawables[currentKey] ?: R.drawable.ic_launcher_purple)
        val theme = IconManager.ICON_THEMES.find { it.key == currentKey }
        binding.tvCurrentIconName.text = "当前图标：${theme?.name ?: "紫色默认"}"
    }

    private fun loadSettings() {
        val prefs = requireContext().getSharedPreferences("api_settings", Context.MODE_PRIVATE)
        binding.editApiKey.setText(prefs.getString("api_key", ""))
        binding.editBaseUrl.setText(prefs.getString("base_url", ApiClient.DEFAULT_BASE_URL))
        binding.editModel.setText(prefs.getString("model_name", ApiClient.DEFAULT_MODEL))
    }

    private fun saveSettings() {
        val prefs = requireContext().getSharedPreferences("api_settings", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("api_key", binding.editApiKey.text.toString())
            putString("base_url", binding.editBaseUrl.text.toString())
            putString("model_name", binding.editModel.text.toString())
            apply()
        }
        Toast.makeText(requireContext(), "设置已保存", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
