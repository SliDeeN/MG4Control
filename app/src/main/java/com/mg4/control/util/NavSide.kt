package com.mg4.control.util

import android.content.Context
import com.mg4.control.hardware.DriverSide

/**
 * Côté du menu (2026-11-02, Réglages › Interface) : le menu principal et les sous-onglets du côté
 * du conducteur. Auto suit le côté du volant lu dans la configuration de la voiture
 * ([DriverSide]) ; illisible, le menu reste à gauche.
 *
 * Le côté détecté est gardé dans les préférences : l'écran se pose au démarrage sans attendre la
 * connexion à la voiture, puis [MainActivity][com.mg4.control.MainActivity] relit et se recrée si
 * le côté a changé (première ouverture sur un volant à droite).
 */
object NavSide {

    enum class Mode(val key: String) { AUTO("auto"), LEFT("left"), RIGHT("right") }

    private const val PREFS = "mg4_settings"
    private const val KEY_MODE = "nav_side"
    private const val KEY_DETECTED = "nav_side_detected"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(ctx: Context): Mode {
        val k = prefs(ctx).getString(KEY_MODE, null)
        return Mode.entries.firstOrNull { it.key == k } ?: Mode.AUTO
    }

    fun setMode(ctx: Context, m: Mode) = prefs(ctx).edit().putString(KEY_MODE, m.key).apply()

    /** Le côté du volant détecté à la dernière lecture, `null` si jamais lu. */
    fun detected(ctx: Context): DriverSide.Side? =
        prefs(ctx).getString(KEY_DETECTED, null)?.let { k -> DriverSide.Side.entries.firstOrNull { it.name == k } }

    /** Garde [side] ; vrai s'il a changé. */
    fun saveDetected(ctx: Context, side: DriverSide.Side?): Boolean {
        if (side == null || side == detected(ctx)) return false
        prefs(ctx).edit().putString(KEY_DETECTED, side.name).apply()
        return true
    }

    /** Vrai si le menu va à droite. */
    fun right(ctx: Context): Boolean = when (mode(ctx)) {
        Mode.RIGHT -> true
        Mode.LEFT -> false
        Mode.AUTO -> detected(ctx) == DriverSide.Side.RIGHT
    }
}
