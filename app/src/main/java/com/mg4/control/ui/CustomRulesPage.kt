package com.mg4.control.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.mg4.control.R
import com.mg4.control.automation.CustomRuleStore
import com.mg4.control.automation.CustomRules
import com.mg4.control.model.CustomRule
import com.mg4.control.model.CustomRuleTemplates
import com.mg4.control.model.RuleLastRun
import com.mg4.control.model.RuleText
import java.text.DateFormat
import java.util.Date

/**
 * Page « Mes automatisations » de l'onglet Automatisation (issue #201) : la liste des
 * automatisations — interrupteur, résumé Quand / Si / Alors, dernière exécution, Modifier, Tester,
 * Supprimer — et la création, vide ou depuis un modèle. L'édition se fait dans
 * [CustomRuleEditFragment] (étape 2, 2026-11-02).
 */
class CustomRulesPage {

    private var vue: View? = null
    private var fragment: Fragment? = null

    fun bind(fragment: Fragment, root: View) {
        this.fragment = fragment
        vue = root
        root.findViewById<View>(R.id.btn_custom_rule_new).setOnClickListener { nouvelle(fragment.requireContext()) }
        // Une exécution vient de finir : sa ligne « Dernière exécution » change.
        CustomRules.surChangement = { dessiner() }
        dessiner()
    }

    fun detacher() {
        CustomRules.surChangement = null
        vue = null
        fragment = null
    }

    /** Retour sur l'onglet : profils et téléphones ont pu être renommés entre-temps. */
    fun refresh() = dessiner()

    private fun dessiner() {
        val root = vue ?: return
        val ctx = root.context
        val regles = CustomRuleStore.all(ctx)
        root.findViewById<TextView>(R.id.custom_rules_count).text =
            ctx.getString(R.string.rule_count_fmt, regles.size, CustomRuleStore.MAX_RULES)
        root.findViewById<View>(R.id.custom_rules_empty).visibility = if (regles.isEmpty()) View.VISIBLE else View.GONE
        val liste = root.findViewById<LinearLayout>(R.id.custom_rules_list)
        liste.removeAllViews()
        val texte = RuleText(AndroidRuleWords(ctx))
        val gonfleur = LayoutInflater.from(ctx)
        for (r in regles) {
            val carte = gonfleur.inflate(R.layout.item_custom_rule, liste, false)
            carte.findViewById<TextView>(R.id.rule_name).text = r.name
            carte.findViewById<TextView>(R.id.rule_summary).text = resume(ctx, texte, r)
            carte.findViewById<TextView>(R.id.rule_last_run).text = derniere(ctx, CustomRules.lastRun(ctx, r.id))
            carte.findViewById<Switch>(R.id.rule_switch).apply {
                isChecked = r.enabled
                setOnCheckedChangeListener { _, on ->
                    CustomRuleStore.setEnabled(ctx, r.id, on)
                    CustomRules.refresh(ctx)
                }
            }
            carte.setOnClickListener { ouvrir(CustomRuleEditFragment.ARG_RULE_ID to r.id) }
            carte.findViewById<View>(R.id.rule_edit).setOnClickListener { ouvrir(CustomRuleEditFragment.ARG_RULE_ID to r.id) }
            carte.findViewById<View>(R.id.rule_test).setOnClickListener {
                CustomRules.test(ctx, r.id)
                Toast.makeText(ctx, ctx.getString(R.string.rule_test_started, r.name), Toast.LENGTH_SHORT).show()
            }
            carte.findViewById<View>(R.id.rule_delete).setOnClickListener { supprimer(ctx, r) }
            liste.addView(carte)
        }
    }

    private fun ouvrir(arg: Pair<String, String>) {
        fragment?.findNavController()?.navigate(R.id.customRuleEditFragment, bundleOf(arg))
    }

    private fun resume(ctx: Context, t: RuleText, r: CustomRule): String =
        ctx.getString(R.string.rule_summary_fmt, t.triggers(r), t.conditions(r), t.actions(r))

    private fun derniere(ctx: Context, run: RuleLastRun?): String {
        if (run == null) return ctx.getString(R.string.rule_never_run)
        val quand = run.atMs?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
            ?: ctx.getString(R.string.rule_run_time_unknown)
        return ctx.getString(R.string.rule_last_run_fmt, quand, run.done, run.failed)
    }

    private fun supprimer(ctx: Context, r: CustomRule) {
        AppDialog.confirmer(ctx,
            titre = ctx.getString(R.string.rule_delete_confirm_fmt, r.name),
            ton = AppDialog.Ton.DANGER,
            oui = ctx.getString(R.string.rule_delete),
            non = ctx.getString(R.string.profile_cancel),
            onOui = {
                CustomRuleStore.delete(ctx, r.id)
                CustomRules.refresh(ctx)
                dessiner()
            })
    }

    // ── Création : vide ou depuis un modèle ──────────────────────────────────

    private class Modele(val t: CustomRuleTemplates.Template, val nom: Int, val message: Int)

    private val modeles = listOf(
        Modele(CustomRuleTemplates.Template.STARTUP_MESSAGE, R.string.rule_tpl_startup, R.string.rule_tpl_startup_msg),
        Modele(CustomRuleTemplates.Template.WINTER_MORNING, R.string.rule_tpl_morning, R.string.rule_tpl_morning_msg),
        Modele(CustomRuleTemplates.Template.HOT_DAY, R.string.rule_tpl_hot, R.string.rule_tpl_hot_msg),
        Modele(CustomRuleTemplates.Template.WINDOWS_ON_LEAVING, R.string.rule_tpl_windows, R.string.rule_tpl_windows),
    )

    /** La nouvelle automatisation s'ouvre dans l'éditeur ; elle n'existe qu'une fois enregistrée. */
    private fun nouvelle(ctx: Context) {
        val n = CustomRuleStore.all(ctx).size
        if (n >= CustomRuleStore.MAX_RULES) {
            Toast.makeText(ctx, ctx.getString(R.string.rule_max_reached, CustomRuleStore.MAX_RULES), Toast.LENGTH_LONG).show()
            return
        }
        AppDialog.choisir(ctx,
            titre = ctx.getString(R.string.rule_templates_title),
            sousTitre = ctx.getString(R.string.rule_templates_subtitle),
            choix = listOf(AppDialog.Choix(ctx.getString(R.string.rule_tpl_blank))) + modeles.map { AppDialog.Choix(ctx.getString(it.nom)) },
            annuler = ctx.getString(R.string.profile_cancel),
        ) { rang ->
            val regle = if (rang == 0) CustomRule(name = ctx.getString(R.string.rule_name_default_fmt, n + 1))
            else modeles[rang - 1].let { m -> CustomRuleTemplates.build(m.t, ctx.getString(m.nom), ctx.getString(m.message)) }
            ouvrir(CustomRuleEditFragment.ARG_NEW_RULE to CustomRuleStore.encode(listOf(regle)).removeSurrounding("[", "]"))
        }
    }
}
