package com.mg4.control

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.mg4.hardware.AppLogger
import com.mg4.control.debug.CrashLogger
import com.mg4.control.util.LocaleHelper
import com.mg4.control.util.ThemeHelper

class MG4App : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(base))
    }

    override fun onCreate() {
        super.onCreate()

        // ── Crash handler global ──────────────────────────────────────────────
        // Intercepte toute exception non gérée, écrit la stack trace + les logs
        // AppLogger dans filesDir/crash_log.txt, puis laisse le handler par défaut
        // terminer le processus normalement (Android affiche son propre écran de crash).
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLogger.e("CRASH", "Uncaught exception on thread '${thread.name}': $throwable")
            try {
                CrashLogger.write(applicationContext, thread, throwable)
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // ── Migration + initialisation du thème ───────────────────────────────
        val prefs = getSharedPreferences("mg4_settings", Context.MODE_PRIVATE)

        if (!prefs.contains(ThemeHelper.PREF_THEME_MODE)) {
            // Migration depuis l'ancien booléen "dark_theme" (version < 2.x)
            // Sur nouvelle installation : "auto" (tous les firmwares le supportent)
            val defaultMode = when {
                prefs.contains("dark_theme") ->
                    if (prefs.getBoolean("dark_theme", true)) "dark" else "light"
                else -> "auto"
            }
            prefs.edit().putString(ThemeHelper.PREF_THEME_MODE, defaultMode).apply()
        }

        AppCompatDelegate.setDefaultNightMode(ThemeHelper.resolveNightMode(this))
    }
}
