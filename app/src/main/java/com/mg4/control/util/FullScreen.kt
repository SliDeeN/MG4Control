package com.mg4.control.util

import android.content.Context
import android.view.View
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Plein écran : barre d'état et barre de navigation masquées (méthode de DiAuto-MG4, `SystemUI.kt`).
 *
 * Validé en voiture le 2026-10-23 avec l'écran de suivi de la pré-climatisation, qui l'utilise
 * toujours. Pour l'application, c'est le réglage « Plein écran » (Réglages › Interface), désactivé
 * par défaut. Un glissement depuis le bord fait réapparaître les barres un instant ; une activité le
 * réapplique quand elle reprend le focus (une fenêtre de dialogue peut les avoir fait revenir).
 */
object FullScreen {

    /** Même fichier que les autres réglages. */
    private const val PREFS = "mg4_settings"
    const val KEY = "app_fullscreen"

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    /** Masque ([on]) ou rend les barres système de [window] : drapeaux d'origine (Android 9) et contrôleur d'encarts. */
    fun apply(window: Window, on: Boolean) {
        val controleur = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            controleur.hide(WindowInsetsCompat.Type.systemBars())
            controleur.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            controleur.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
