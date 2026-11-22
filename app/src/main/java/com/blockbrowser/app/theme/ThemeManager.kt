package com.blockbrowser.app.theme

import android.app.Activity
import android.content.Context
import com.blockbrowser.app.R

/**
 * Simple theme ("Design") switcher. Each theme maps to a style in themes.xml.
 * Switching requires an Activity recreate() to apply (standard Android pattern
 * for runtime theme changes).
 */
enum class AppTheme(val styleRes: Int, val displayName: String) {
    LIGHT(R.style.Theme_Gravity, "Hell"),
    DARK(R.style.Theme_Gravity_Dark, "Dunkel"),
    MIDNIGHT_BLUE(R.style.Theme_Gravity_MidnightBlue, "Mitternachtsblau"),
    FOREST(R.style.Theme_Gravity_Forest, "Wald"),
    HIGH_CONTRAST(R.style.Theme_Gravity_HighContrast, "Hoher Kontrast");

    companion object {
        fun fromName(name: String?): AppTheme =
            values().firstOrNull { it.name == name } ?: LIGHT
    }
}

object ThemeManager {
    private const val PREFS = "gravity_browser_prefs"
    private const val KEY_THEME = "selected_theme"

    fun getSavedTheme(context: Context): AppTheme {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AppTheme.fromName(prefs.getString(KEY_THEME, AppTheme.LIGHT.name))
    }

    fun applyTheme(activity: Activity) {
        activity.setTheme(getSavedTheme(activity).styleRes)
    }

    /** Persist the choice and recreate the activity so the new style takes effect. */
    fun selectTheme(activity: Activity, theme: AppTheme) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THEME, theme.name).apply()
        activity.recreate()
    }
}
