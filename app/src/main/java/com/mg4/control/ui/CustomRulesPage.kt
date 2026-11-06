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
import com.mg4.control.automation.RulePlaces
import com.mg4.control.automation.RuleVariables
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

    /** Sélecteurs d'Android, enregistrés par le fragment hôte (ils doivent l'être à sa création). */
    var parcourirDossier: () -> Unit = {}
    var parcourirFichier: () -> Unit = {}
    private var exportDialog: ExportDialog? = null
    private var importDialog: ImportDialog? = null

    /** Dossier choisi pour l'export. */
    fun surDossier(arbre: android.net.Uri?) { exportDialog?.surDossier(arbre) }

    /** Fichier choisi pour l'import. */
    fun surFichier(doc: android.net.Uri?) { importDialog?.surFichier(doc) }

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
        // Grisées : une automatisation coupée, et toutes quand l'interrupteur général est coupé.
        val moteur = CustomRuleStore.isEngineEnabled(ctx)
        for (r in regles) {
            val carte = gonfleur.inflate(R.layout.item_custom_rule, liste, false)
            val active = moteur && r.enabled
            listOf(R.id.rule_name, R.id.rule_summary, R.id.rule_last_run, R.id.rule_edit, R.id.rule_test, R.id.rule_delete)
                .forEach { carte.findViewById<View>(it).alpha = if (active) 1f else ALPHA_COUPEE }
            if (!moteur) carte.findViewById<View>(R.id.rule_switch).alpha = ALPHA_COUPEE
            carte.findViewById<TextView>(R.id.rule_name).text = r.name
            carte.findViewById<TextView>(R.id.rule_summary).text = resume(ctx, texte, r)
            carte.findViewById<TextView>(R.id.rule_last_run).text = derniere(ctx, CustomRules.lastRun(ctx, r.id))
            carte.findViewById<Switch>(R.id.rule_switch).apply {
                isChecked = r.enabled
                setOnCheckedChangeListener { _, on ->
                    CustomRuleStore.setEnabled(ctx, r.id, on)
                    CustomRules.refresh(ctx)
                    post { dessiner() }
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
                dessiner()
            }
        })
        box.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        buildList<Pair<Int, () -> Unit>> {
            add(R.string.rule_tool_history to { historique(ctx) })
            add(R.string.rule_tool_variables to { variables(ctx) })
            add(R.string.rule_tool_places to { lieux(ctx) })
            // Comptes ABRP (issue #188) : version en ligne seulement.
            if (com.mg4.control.automation.AbrpStore.available) add(R.string.abrp_tool to { comptesAbrp(ctx) })
            add(R.string.rule_tool_export to { exporter(ctx) })
            add(R.string.rule_tool_import to { importer(ctx) })
        }.forEach { (libelle, action) ->
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
            variable(ctx, nom, v[nom].orEmpty())
        }
    }

    /** Une variable : renommer (partout dans les automatisations), changer sa valeur, la supprimer. */
    private fun variable(ctx: Context, nom: String, valeur: String) {
        val gestes = listOf(R.string.rule_edit_rename, R.string.rule_var_edit_value, R.string.rule_delete)
        AppDialog.choisir(ctx, titre = "$nom = $valeur", choix = gestes.map { AppDialog.Choix(ctx.getString(it)) },
            annuler = ctx.getString(R.string.profile_cancel), icone = R.drawable.ic_dialog_edit) { rang ->
            when (gestes[rang]) {
                R.string.rule_edit_rename -> RuleValueDialogs.texte(ctx, ctx.getString(R.string.rule_edit_rename), nom) { n ->
                    if (com.mg4.control.model.RuleLogic.variableName(n) == nom) return@texte
                    if (!RuleVariables.rename(ctx, nom, n)) Toast.makeText(ctx, R.string.rule_var_rename_taken, Toast.LENGTH_LONG).show()
                    else variables(ctx)
                }
                R.string.rule_var_edit_value -> RuleValueDialogs.texte(ctx, ctx.getString(R.string.rule_var_edit_value), valeur) { n ->
                    RuleVariables.set(ctx, nom, n)
                    variables(ctx)
                }
                else -> AppDialog.confirmer(ctx, titre = ctx.getString(R.string.rule_delete_confirm_fmt, nom), ton = AppDialog.Ton.DANGER,
                    oui = ctx.getString(R.string.rule_delete), non = ctx.getString(R.string.profile_cancel),
                    onOui = { RuleVariables.delete(ctx, nom) })
            }
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
            lieu(ctx, l[rang])
        }
    }

    /** Un lieu : renommer, changer le rayon, le placer ici, le supprimer (les automatisations le suivent par son id). */
    private fun lieu(ctx: Context, p: com.mg4.control.model.RulePlace) {
        fun remplacer(n: com.mg4.control.model.RulePlace) {
            RulePlaces.save(ctx, RulePlaces.all(ctx).map { if (it.id == p.id) n else it })
            lieux(ctx)
        }
        val gestes = listOf(R.string.rule_edit_rename, R.string.rule_place_radius, R.string.rule_place_move_here, R.string.rule_delete)
        AppDialog.choisir(ctx, titre = "${p.name} · ${p.radius} m", choix = gestes.map { AppDialog.Choix(ctx.getString(it)) },
            annuler = ctx.getString(R.string.profile_cancel), icone = R.drawable.ic_dialog_edit) { rang ->
            when (gestes[rang]) {
                R.string.rule_edit_rename -> RuleValueDialogs.texte(ctx, ctx.getString(R.string.rule_place_name), p.name) { n ->
                    if (n.isNotBlank()) remplacer(p.copy(name = n.trim()))
                }
                R.string.rule_place_radius -> RuleValueDialogs.nombre(ctx, ctx.getString(R.string.rule_place_radius), null, p.radius, 50, 5_000,
                    texte = { "$it m" }, pas = { v, sens -> v + sens * (if (sens > 0 && v < 500 || sens < 0 && v <= 500) 50 else 250) }) { r ->
                    remplacer(p.copy(radius = r))
                }
                R.string.rule_place_move_here -> {
                    Toast.makeText(ctx, R.string.rule_place_locating, Toast.LENGTH_SHORT).show()
                    com.mg4.control.automation.AndroidControls.locate(ctx) { loc ->
                        if (loc == null) Toast.makeText(ctx, R.string.rule_place_no_fix, Toast.LENGTH_LONG).show()
                        else remplacer(p.copy(lat = loc.latitude, lng = loc.longitude))
                    }
                }
                else -> AppDialog.confirmer(ctx, titre = ctx.getString(R.string.rule_delete_confirm_fmt, p.name), ton = AppDialog.Ton.DANGER,
                    oui = ctx.getString(R.string.rule_delete), non = ctx.getString(R.string.profile_cancel),
                    onOui = { RulePlaces.save(ctx, RulePlaces.all(ctx).filterNot { it.id == p.id }) })
            }
        }
    }

    /**
     * Export (2026-11-05) : la même fenêtre que les Statistiques et le diagnostic — Téléchargements,
     * clé USB, autre dossier —, un fichier JSON dans « MG4Control ».
     */
    private fun exporter(ctx: Context) {
        val regles = CustomRuleStore.all(ctx)
        exportDialog = ExportDialog(ctx,
            titre = ctx.getString(R.string.rule_export_title),
            sousTitre = ctx.getString(R.string.rule_export_subtitle, regles.size),
            bouton = ctx.getString(R.string.rule_tool_export),
            titreFait = ctx.getString(R.string.rule_export_done_title),
            fichiers = { listOf(FICHIER to CustomRuleStore.encode(regles)) },
            parcourir = parcourirDossier,
        ).also { it.ouvrir() }
    }

    /** Import : depuis Téléchargements, la clé USB, ou n'importe quel fichier choisi. */
    private fun importer(ctx: Context) {
        importDialog = ImportDialog(ctx, FICHIER, parcourirFichier) { texte, ou -> importerTexte(ctx, texte, ou) }
            .also { it.ouvrir() }
    }

    private fun importerTexte(ctx: Context, texte: String?, ou: String) {
        val lues = texte?.let { runCatching { CustomRuleStore.decode(it) }.getOrNull() }.orEmpty()
        if (lues.isEmpty()) {
            AppDialog.confirmer(ctx, titre = ctx.getString(R.string.rule_tool_import),
                message = ctx.getString(R.string.rule_import_none, ou), ton = AppDialog.Ton.ALERTE, oui = ctx.getString(R.string.nav_close))
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


    /** Comptes ABRP : la liste, l'import du fichier de comptes, le fichier modèle (mêmes fenêtres que l'export). */
    private fun comptesAbrp(ctx: Context) {
        AbrpDialogs.comptes(ctx,
            importer = {
                importDialog = ImportDialog(ctx, com.mg4.control.model.AbrpConfigFile.FILE_NAME, parcourirFichier) { texte, ou ->
                    AbrpDialogs.importer(ctx, texte, ou) { comptesAbrp(ctx) }
                }.also { it.ouvrir() }
            },
            modele = {
                exportDialog = ExportDialog(ctx,
                    titre = ctx.getString(R.string.abrp_template_title),
                    sousTitre = ctx.getString(R.string.abrp_template_subtitle),
                    bouton = ctx.getString(R.string.abrp_template_go),
                    titreFait = ctx.getString(R.string.abrp_template_done),
                    fichiers = { listOf(com.mg4.control.model.AbrpConfigFile.FILE_NAME to AbrpDialogs.modele(ctx)) },
                    parcourir = parcourirDossier,
                ).also { it.ouvrir() }
            })
    }

    private companion object {
        /** Nom du fichier d'échange (le même qu'avant, pour retrouver les anciens exports). */
        const val FICHIER = "mg4control-automatisations.json"
        const val ALPHA_COUPEE = 0.45f
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

    /**
     * Modèle « ABRP selon le profil » : le n-ième profil avec le n-ième compte ABRP, à corriger
     * dans l'éditeur. Sans compte, rien n'est créé : il faut d'abord en ajouter un.
     */
    private fun abrpSelonProfil(ctx: Context): CustomRule? {
        val comptes = com.mg4.control.automation.AbrpStore.accounts(ctx)
        if (comptes.isEmpty()) {
            Toast.makeText(ctx, ctx.getString(R.string.abrp_no_accounts), Toast.LENGTH_LONG).show()
            return null
        }
        val profils = com.mg4.control.profile.ProfileManager(ctx).getAll()
        return CustomRuleTemplates.abrpByProfile(ctx.getString(R.string.rule_tpl_abrp_profile),
            profils.zip(comptes).map { (p, c) -> p.id to c.id }, comptes.first().id)
    }

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
            choix = listOf(AppDialog.Choix(ctx.getString(R.string.rule_tpl_blank))) + modeles.map { AppDialog.Choix(ctx.getString(it.nom)) } +
                (if (com.mg4.control.automation.AbrpStore.available) listOf(AppDialog.Choix(ctx.getString(R.string.rule_tpl_abrp_profile))) else emptyList()),
            annuler = ctx.getString(R.string.profile_cancel),
        ) { rang ->
            val regle = when {
                rang == 0 -> CustomRule(name = ctx.getString(R.string.rule_name_default_fmt, n + 1))
                rang <= modeles.size -> modeles[rang - 1].let { m -> CustomRuleTemplates.build(m.t, ctx.getString(m.nom), ctx.getString(m.message)) }
                else -> abrpSelonProfil(ctx) ?: return@choisir
            }
            ouvrir(CustomRuleEditFragment.ARG_NEW_RULE to CustomRuleStore.encode(listOf(regle)).removeSurrounding("[", "]"))
        }
    }
}
