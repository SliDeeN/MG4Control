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
import android.os.Environment
import com.mg4.control.automation.RulePlaces
import com.mg4.control.automation.RuleVariables
import java.io.File
import java.text.DateFormat
import java.util.UUID
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
        outils(root)
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

    // ── Outils (étape 3) ─────────────────────────────────────────────────────

    private fun outils(root: View) {
        val ctx = root.context
        val box = root.findViewById<LinearLayout>(R.id.custom_rules_tools)
        box.removeAllViews()
        fun dp(v: Float) = AppDialog.dp(ctx, v)
        box.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.rule_engine_switch)
            setTextColor(ctx.getColor(R.color.text_secondary))
            textSize = 19f
        })
        box.addView(com.mg4.control.ui.widget.SegmentSwitch(ctx).apply {
            isChecked = CustomRuleStore.isEngineEnabled(ctx)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.marginStart = dp(12f) }
            setOnCheckedChangeListener { _, on ->
                CustomRuleStore.setEngineEnabled(ctx, on)
                CustomRules.refresh(ctx)
            }
        })
        box.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        listOf<Pair<Int, () -> Unit>>(
            R.string.rule_tool_history to { historique(ctx) },
            R.string.rule_tool_variables to { variables(ctx) },
            R.string.rule_tool_places to { lieux(ctx) },
            R.string.rule_tool_export to { exporter(ctx) },
            R.string.rule_tool_import to { importer(ctx) },
        ).forEach { (libelle, action) ->
            box.addView(TextView(ctx).apply {
                text = ctx.getString(libelle)
                textSize = 19f
                setTextColor(ctx.getColor(R.color.text_primary))
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.bg_dialog_tile)
                setPadding(dp(18f), 0, dp(18f), 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(56f)).also { it.marginStart = dp(10f) }
                setOnClickListener { action() }
            })
        }
    }

    private fun historique(ctx: Context) {
        val h = CustomRuleStore.history(ctx)
        val texte = if (h.isEmpty()) ctx.getString(R.string.rule_never_run) else h.joinToString("\n\n") { e ->
            val quand = e.run.atMs?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
                ?: ctx.getString(R.string.rule_run_time_unknown)
            "$quand — ${e.name} : ${e.run.done} ✓ · ${e.run.failed} ✗" +
                e.run.journal.orEmpty().take(6).joinToString("") { "\n   $it" }
        }
        AppDialog.confirmer(ctx, titre = ctx.getString(R.string.rule_tool_history), message = texte,
            icone = R.drawable.ic_dialog_list, oui = ctx.getString(R.string.nav_close))
    }

    private fun variables(ctx: Context) {
        val v = RuleVariables.all(ctx).toSortedMap()
        if (v.isEmpty()) {
            AppDialog.confirmer(ctx, titre = ctx.getString(R.string.rule_tool_variables), message = ctx.getString(R.string.rule_vars_empty),
                icone = R.drawable.ic_dialog_info, oui = ctx.getString(R.string.nav_close))
            return
        }
        AppDialog.choisir(ctx, titre = ctx.getString(R.string.rule_tool_variables), sousTitre = ctx.getString(R.string.rule_vars_hint),
            choix = v.map { (k, x) -> AppDialog.Choix("$k = $x") }, annuler = ctx.getString(R.string.nav_close)) { rang ->
            val nom = v.keys.elementAt(rang)
            AppDialog.confirmer(ctx, titre = ctx.getString(R.string.rule_delete_confirm_fmt, nom), ton = AppDialog.Ton.DANGER,
                oui = ctx.getString(R.string.rule_delete), non = ctx.getString(R.string.profile_cancel),
                onOui = { RuleVariables.delete(ctx, nom) })
        }
    }

    private fun lieux(ctx: Context) {
        val l = RulePlaces.all(ctx)
        if (l.isEmpty()) {
            AppDialog.confirmer(ctx, titre = ctx.getString(R.string.rule_tool_places), message = ctx.getString(R.string.rule_places_empty),
                icone = R.drawable.ic_dialog_info, oui = ctx.getString(R.string.nav_close))
            return
        }
        AppDialog.choisir(ctx, titre = ctx.getString(R.string.rule_tool_places), sousTitre = ctx.getString(R.string.rule_places_hint),
            choix = l.map { AppDialog.Choix("${it.name} · ${it.radius} m") }, annuler = ctx.getString(R.string.nav_close)) { rang ->
            val p = l[rang]
            AppDialog.confirmer(ctx, titre = ctx.getString(R.string.rule_delete_confirm_fmt, p.name), ton = AppDialog.Ton.DANGER,
                oui = ctx.getString(R.string.rule_delete), non = ctx.getString(R.string.profile_cancel),
                onOui = { RulePlaces.save(ctx, l.filterNot { it.id == p.id }) })
        }
    }

    /** Le fichier d'échange, dans « Téléchargements » : là où l'utilisateur peut le retrouver. */
    private fun fichier(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "mg4control-automatisations.json")

    private fun exporter(ctx: Context) {
        val regles = CustomRuleStore.all(ctx)
        val ok = runCatching {
            val f = fichier()
            f.parentFile?.mkdirs()
            f.writeText(CustomRuleStore.encode(regles))
        }
        Toast.makeText(ctx, if (ok.isSuccess) ctx.getString(R.string.rule_export_done, regles.size, fichier().path)
            else ctx.getString(R.string.rule_export_failed), Toast.LENGTH_LONG).show()
    }

    private fun importer(ctx: Context) {
        val lues = runCatching { CustomRuleStore.decode(fichier().readText()) }.getOrNull().orEmpty()
        if (lues.isEmpty()) {
            Toast.makeText(ctx, ctx.getString(R.string.rule_import_none, fichier().path), Toast.LENGTH_LONG).show()
            return
        }
        AppDialog.confirmer(ctx, titre = ctx.getString(R.string.rule_import_title, lues.size), message = ctx.getString(R.string.rule_import_msg),
            oui = ctx.getString(R.string.rule_tool_import), non = ctx.getString(R.string.profile_cancel), onOui = {
                // Nouveaux identifiants, et coupées : un import n'écrase rien et ne part pas tout seul.
                var n = 0
                lues.forEach { r -> if (CustomRuleStore.add(ctx, r.copy(id = UUID.randomUUID().toString(), enabled = false))) n++ }
                CustomRules.refresh(ctx)
                dessiner()
                Toast.makeText(ctx, ctx.getString(R.string.rule_import_done, n), Toast.LENGTH_LONG).show()
            })
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
        Modele(CustomRuleTemplates.Template.STEERING_HEAT_TIMER, R.string.rule_tpl_steering_timer, R.string.rule_tpl_steering_timer_msg),
        Modele(CustomRuleTemplates.Template.SEAT_HEAT_TIMER, R.string.rule_tpl_seat_timer, R.string.rule_tpl_seat_timer_msg),
        Modele(CustomRuleTemplates.Template.BRIGHTNESS_SUN, R.string.rule_tpl_brightness_sun, R.string.rule_tpl_brightness_sun),
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
