package com.mg4.control.util

import com.mg4.control.model.BubbleColors
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mg4.control.model.FloatingButton
import com.mg4.control.model.FloatingButtons
import java.util.UUID

/**
 * Boutons flottants (2026-10-23) : interrupteur d'affichage (activé, la liste est vide au départ),
 * masquage en marche arrière (activé : la caméra de recul ne doit jamais être cachée) et les
 * boutons eux-mêmes, en JSON.
 *
 * Depuis le 2026-10-24, l'interrupteur et les boutons appartiennent à chaque profil
 * ([FloatingProfiles]) : les fonctions ci-dessous lisent et écrivent ceux du profil dont les bulles
 * sont à l'écran. Les clés communes ne valent plus que tant qu'il n'existe aucun profil. Le
 * masquage en marche arrière et la zone clim restent communs.
 */
object FloatingButtonSettings {

    /** Même fichier que les autres réglages. */
    private const val PREFS = "mg4_settings"
    private const val KEY_ENABLED = "floating_enabled"
    private const val KEY_COLOR = "floating_color"
    private const val KEY_HIDE_REVERSE = "floating_hide_reverse"
    private const val KEY_BUTTONS = "floating_buttons"
    /**
     * Zone clim pendant Android Auto / CarPlay (2026-10-24). DÉSACTIVÉE par défaut depuis le
     * 2026-10-30 (sa demande) : seul un « ON » mis par l'utilisateur l'active.
     */
    private const val KEY_HVAC_ZONE = "floating_hvac_zone"

    private val gson = Gson()
    private val type = object : TypeToken<List<FloatingButton>>() {}.type

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = FloatingProfiles.isEnabled(ctx) ?: legacyEnabled(ctx)
    fun setEnabled(ctx: Context, on: Boolean) {
        if (!FloatingProfiles.setEnabled(ctx, on)) prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /**
     * Couleur générale des bulles (2026-11-04), pour tous les profils ; chaque bulle peut avoir la
     * sienne ([FloatingButton.color]). Sans réglage : le bleu de la refonte.
     */
    fun defaultColor(ctx: Context): Int =
        if (prefs(ctx).contains(KEY_COLOR)) prefs(ctx).getInt(KEY_COLOR, BubbleColors.DEFAULT) else BubbleColors.DEFAULT

    /** [c] = null : retour au bleu de la refonte. */
    fun setDefaultColor(ctx: Context, c: Int?) {
        prefs(ctx).edit().apply { if (c == null) remove(KEY_COLOR) else putInt(KEY_COLOR, c) }.apply()
    }

    /** La couleur d'une bulle : la sienne, sinon la générale. */
    fun colorOf(ctx: Context, b: FloatingButton): Int = b.color ?: defaultColor(ctx)

    fun hideInReverse(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_HIDE_REVERSE, true)
    fun setHideInReverse(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_HIDE_REVERSE, on).apply()

    fun isHvacZoneEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_HVAC_ZONE, false)
    fun setHvacZoneEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(KEY_HVAC_ZONE, on).apply()

    fun buttons(ctx: Context): List<FloatingButton> = FloatingProfiles.buttons(ctx) ?: legacyButtons(ctx)

    fun save(ctx: Context, list: List<FloatingButton>) {
        if (!FloatingProfiles.save(ctx, list))
            prefs(ctx).edit().putString(KEY_BUTTONS, gson.toJson(list.take(FloatingButtons.MAX))).apply()
    }

    /** L'interrupteur commun d'avant les profils (ou sans aucun profil). */
    fun legacyEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, true)

    /** Les bulles communes d'avant les profils (ou sans aucun profil). */
    fun legacyButtons(ctx: Context): List<FloatingButton> = try {
        prefs(ctx).getString(KEY_BUTTONS, null)?.let { gson.fromJson<List<FloatingButton>>(it, type) }
            ?.filterNotNull()?.take(FloatingButtons.MAX) ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /** Nouveau bouton pour [action], posé sous les précédents ; `null` si la liste est pleine. */
    fun add(ctx: Context, action: String): FloatingButton? {
        val list = buttons(ctx)
        if (list.size >= FloatingButtons.MAX) return null
        val (x, y) = FloatingButtons.defaultPosition(list.size)
        val b = FloatingButton(id = UUID.randomUUID().toString(), action = action, x = x, y = y)
        save(ctx, list + b)
        return b
    }

    fun update(ctx: Context, b: FloatingButton) = save(ctx, buttons(ctx).map { if (it.id == b.id) b else it })

    fun remove(ctx: Context, id: String) = save(ctx, buttons(ctx).filter { it.id != id })

    /**
     * Toutes les bulles reprennent leur place d'origine, en colonne sur la droite ; celles de la
     * barre du haut, la première place libre de la barre, choisie à la pose.
     */
    fun resetPositions(ctx: Context) = save(ctx, buttons(ctx).mapIndexed { i, b ->
        if (FloatingButtons.inTopBar(b)) return@mapIndexed b.copy(x = FloatingButtons.TOP_AUTO)
        val (x, y) = FloatingButtons.defaultPosition(i)
        b.copy(x = x, y = y)
    })
}
