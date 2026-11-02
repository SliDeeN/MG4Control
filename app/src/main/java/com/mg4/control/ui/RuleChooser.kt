package com.mg4.control.ui

import android.content.Context
import android.widget.Toast
import com.mg4.control.R
import com.mg4.control.automation.CustomRuleStore
import com.mg4.control.model.CustomRule
import com.mg4.control.model.RuleTrigger

/**
 * Choisir une automatisation de « Mes automatisations » : la cible d'un bouton du volant ou d'une
 * bulle « Lancer une automatisation », ou d'une action qui en active / lance une autre. Celles qui
 * se lancent à la main (déclencheur « Bouton, bulle ou automatisation ») viennent en tête.
 */
internal object RuleChooser {

    fun choisir(ctx: Context, deja: String?, sauf: String? = null, onAnnule: () -> Unit = {}, onChoix: (CustomRule) -> Unit) {
        val regles = CustomRuleStore.all(ctx).filter { it.id != sauf }
            .sortedByDescending { r -> r.triggers.any { it.type == RuleTrigger.Type.MANUAL } }
        if (regles.isEmpty()) {
            Toast.makeText(ctx, R.string.rule_none_to_pick, Toast.LENGTH_LONG).show()
            onAnnule()
            return
        }
        AppDialog.choisir(ctx,
            titre = ctx.getString(R.string.rule_choose_rule),
            sousTitre = ctx.getString(R.string.rule_choose_rule_hint),
            choix = regles.map { AppDialog.Choix(it.name) },
            annuler = ctx.getString(R.string.profile_cancel),
            choisi = regles.indexOfFirst { it.id == deja },
            onAnnule = onAnnule,
        ) { rang -> onChoix(regles[rang]) }
    }

    fun nom(ctx: Context, id: String?): String? = id?.let { i -> CustomRuleStore.all(ctx).firstOrNull { it.id == i }?.name }
}
