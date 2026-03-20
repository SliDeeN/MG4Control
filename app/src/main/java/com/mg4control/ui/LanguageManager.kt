package com.mg4control.ui

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import java.util.Locale

object LanguageManager {

    private const val PREFS_NAME = "mg4_settings"
    private const val KEY_LANGUAGE = "language"
    const val LANG_FR = "fr"
    const val LANG_EN = "en"
    const val LANG_DEFAULT = LANG_FR

    fun getSavedLanguage(context: Context): String {
        return prefs(context).getString(KEY_LANGUAGE, null) ?: LANG_DEFAULT
    }

    fun saveLanguage(context: Context, lang: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, lang).apply()
    }

    /**
     * Applique la locale au contexte — à appeler dans attachBaseContext()
     * et après chaque changement de langue.
     */
    fun applyLanguage(context: Context, lang: String): Context {
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    fun isFirstLaunch(context: Context): Boolean {
        return !prefs(context).contains(KEY_LANGUAGE)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
