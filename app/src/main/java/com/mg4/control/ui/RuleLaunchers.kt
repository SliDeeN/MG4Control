package com.mg4.control.ui

import android.content.Context
import com.mg4.control.R
import com.mg4.control.accessibility.AdvancedShortcuts
import com.mg4.control.model.CustomRule
import com.mg4.control.model.RuleClipboard
import com.mg4.control.profile.ProfileManager
import com.mg4.control.shortcut.ShortcutAction
import com.mg4.control.shortcut.PressType
import com.mg4.control.util.FloatingButtonSettings
import com.mg4.control.util.FloatingProfiles

/**
 * Ce qui lance une automatisation « à la demande » (déclencheur « Bouton, bulle ou automatisation »,
 * 2026-11-12) : les boutons du volant, les bulles flottantes et les autres automatisations qui
 * l'ont pour cible. Le lien se règle de leur côté ; l'éditeur le montre sous le déclencheur.
 */
object RuleLaunchers {

    /** Fichier des cibles des raccourcis (classiques et avancés), comme MG4ControlService. */
    private const val PREFS_CIBLES = "mg4_shortcuts"
    private const val ETOILE_GAUCHE = 17
    private const val ETOILE_DROITE = 286

    /** Les libellés de ce qui lance [ruleId], dans l'ordre volant, bulles, automatisations. */
    fun of(ctx: Context, ruleId: String, regles: List<CustomRule>): List<String> {
        val cibles = ctx.getSharedPreferences(PREFS_CIBLES, Context.MODE_PRIVATE)
        val profils = runCatching { ProfileManager(ctx).getAll() }.getOrDefault(emptyList())
        fun nomProfil(id: String?) = id?.let { i -> profils.firstOrNull { it.id == i }?.name }
        val out = mutableListOf<String>()

        // Volant, capture avancée : chaque attribution (touche, appui, profil éventuel).
        val avancee = AdvancedShortcuts.isEnabled(ctx)
        val avancees = if (avancee) AdvancedShortcuts.all(ctx) else emptyList()
        avancees.filter { it.action == ShortcutAction.RUN_RULE }.forEach { m ->
            val slot = AdvancedShortcuts.slotKey(m.keyCode, m.press, m.profileId)
            if (cibles.getString("shortcut_${slot}_rule_id", null) == ruleId) {
                out += avecProfil(ctx, touche(ctx, m.keyCode, m.press), nomProfil(m.profileId))
            }
        }
        // Volant, boutons ★ classiques : sauf une touche passée à la capture avancée.
        for ((code, base) in listOf(ETOILE_GAUCHE to "btn1", ETOILE_DROITE to "btn2")) {
            if (avancees.any { it.keyCode == code }) continue
            for (press in listOf(PressType.SINGLE, PressType.LONG)) {
                val slot = "${base}_${press.key}"
                val action = ShortcutAction.fromId(cibles.getInt("shortcut_$slot", 0))
                if (action == ShortcutAction.RUN_RULE && cibles.getString("shortcut_${slot}_rule_id", null) == ruleId) {
                    out += touche(ctx, code, press)
                }
            }
        }

        // Bulles flottantes : celles de chaque profil, ou les communes sans profil.
        val jeux = if (profils.isEmpty()) listOf(null to FloatingButtonSettings.legacyButtons(ctx))
            else profils.map { it.name to FloatingProfiles.buttonsOf(it) }
        jeux.forEach { (profil, bulles) ->
            bulles.filter { it.action == ShortcutAction.RUN_RULE.name && it.customId == ruleId }.forEach { b ->
                val titre = b.customTitle?.takeIf { it.isNotBlank() } ?: b.actionLabel.ifBlank { "?" }
                out += avecProfil(ctx, ctx.getString(R.string.rule_manual_bubble_fmt, titre), profil.takeIf { profils.size > 1 })
            }
        }

        // Autres automatisations : une action « La lancer », même dans un bloc si / sinon.
        regles.filter { r -> r.id != ruleId && r.actions.any { RuleClipboard.runsRule(it, ruleId) } }.forEach { r ->
            out += ctx.getString(R.string.rule_manual_rule_fmt, r.name)
        }
        return out.distinct()
    }

    private fun avecProfil(ctx: Context, texte: String, profil: String?): String =
        profil?.let { ctx.getString(R.string.rule_manual_profile_fmt, texte, it) } ?: texte

    private fun touche(ctx: Context, code: Int, press: PressType): String =
        ctx.getString(R.string.rule_manual_wheel_fmt, WheelKeys.nom(ctx, code), ctx.getString(when (press) {
            PressType.SINGLE -> R.string.shortcuts_press_single
            PressType.LONG -> R.string.shortcuts_press_long
            PressType.DOUBLE -> R.string.wheel_press_double
        }).lowercase())
}

/** Nom d'une touche du volant : celui donné par l'utilisateur, sinon le nom connu, sinon son code. */
object WheelKeys {

    fun nom(ctx: Context, code: Int): String =
        ctx.getSharedPreferences(AdvancedShortcuts.PREFS, Context.MODE_PRIVATE).getString("wheel_key_name_$code", null)
            ?.takeIf { it.isNotBlank() } ?: origine(ctx, code)

    fun origine(ctx: Context, code: Int): String = when (code) {
        17 -> ctx.getString(R.string.wheel_key_star_left)
        286, 18 -> ctx.getString(R.string.wheel_key_star_right)
        297 -> ctx.getString(R.string.wheel_key_joy_up)
        298 -> ctx.getString(R.string.wheel_key_joy_down)
        299 -> ctx.getString(R.string.wheel_key_joy_left)
        300 -> ctx.getString(R.string.wheel_key_joy_right)
        301 -> ctx.getString(R.string.wheel_key_joy_center)
        else -> ctx.getString(R.string.adv_sc_key_unknown, code)
    }
}
