package com.aivoice.app.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object IconManager {

    data class IconTheme(
        val name: String,
        val key: String,
        val drawableRes: String,     // resource name, e.g. "ic_launcher_purple"
        val aliasSuffix: String      // alias component suffix
    )

    val ICON_THEMES = listOf(
        IconTheme("🟣 紫色默认", "purple", "ic_launcher_purple", "Default"),
        IconTheme("🔵 蓝色清爽", "blue", "ic_launcher_blue", "Blue"),
        IconTheme("🩷 粉色少女", "pink", "ic_launcher_pink", "Pink"),
        IconTheme("🟢 绿色护眼", "green", "ic_launcher_green", "Green"),
        IconTheme("🟠 橙色活力", "orange", "ic_launcher_orange", "Orange"),
    )

    private const val PREFS_NAME = "icon_prefs"
    private const val KEY_CURRENT_ICON = "current_icon"

    fun getCurrentIconKey(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CURRENT_ICON, "purple") ?: "purple"
    }

    fun applyIcon(context: Context, themeKey: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentKey = prefs.getString(KEY_CURRENT_ICON, "purple") ?: "purple"
        if (currentKey == themeKey) return

        val pm = context.packageManager
        val pkg = context.packageName

        // Disable all aliases first
        for (theme in ICON_THEMES) {
            val aliasName = "$pkg.MainActivityAlias${theme.aliasSuffix}"
            val currentState = pm.getComponentEnabledSetting(
                ComponentName(pkg, aliasName)
            )
            if (currentState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setComponentEnabledSetting(
                    ComponentName(pkg, aliasName),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }

        // Enable the chosen alias (skip "Default" which is the main activity)
        if (themeKey != "purple") {
            val targetTheme = ICON_THEMES.find { it.key == themeKey }
            if (targetTheme != null) {
                val aliasName = "$pkg.MainActivityAlias${targetTheme.aliasSuffix}"
                pm.setComponentEnabledSetting(
                    ComponentName(pkg, aliasName),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }

        prefs.edit().putString(KEY_CURRENT_ICON, themeKey).apply()
    }
}
