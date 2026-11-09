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
import com.mg4.control.model.RuleHistory
import com.mg4.control.model.RuleHistoryEntry
import com.mg4.control.model.RuleLastRun
import com.mg4.control.model.RuleSkip
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
        val compteurs = CustomRuleStore.counts(ctx)
        for (r in regles) {
            val carte = gonfleur.inflate(R.layout.item_custom_rule, liste, false)
            val active = moteur && r.enabled
            listOf(R.id.rule_name, R.id.rule_runs, R.id.rule_skips, R.id.rule_summary, R.id.rule_last_run, R.id.rule_edit, R.id.rule_test, R.id.rule_delete)
                .forEach { carte.findViewById<View>(it).alpha = if (active) 1f else ALPHA_COUPEE }
            // Compteurs (variante 3) : simples pastilles, sans action au toucher.
            val n = compteurs[r.id]
            carte.findViewById<TextView>(R.id.rule_runs).apply {
                val v = n?.runs ?: 0
                visibility = if (v > 0) View.VISIBLE else View.GONE
                text = ctx.resources.getQuantityString(R.plurals.rule_runs_count, v, v)
            }
            carte.findViewById<TextView>(R.id.rule_skips).apply {
                val v = n?.skips ?: 0
                visibility = if (v > 0) View.VISIBLE else View.GONE
                text = ctx.resources.getQuantityString(R.plurals.rule_skips_count, v, v)
            }
            if (!moteur) carte.findViewById<View>(R.id.rule_switch).alpha = ALPHA_COUPEE
            carte.findViewById<TextView>(R.id.rule_name).text = r.name
            carte.findViewById<TextView>(R.id.rule_summary).text = resume(ctx, texte, r)
            carte.findViewById<TextView>(R.id.rule_last_run).text =
                derniere(ctx, CustomRules.lastRun(ctx, r.id), CustomRules.lastSkip(ctx, r.id))
            carte.findViewById<Switch>(R.id.rule_switch).apply {
                // Toutes les cartes portent le même identifiant : au retour de l'éditeur, Android
                // rendait à CHAQUE interrupteur l'état sauvegardé du dernier, et l'écoute
                // l'enregistrait (2026-11-07). L'état vient du stockage, jamais de la vue.
                isSaveEnabled = false
                isChecked = r.enabled
                setOnCheckedChangeListener { _, on ->
                    if (on == CustomRuleStore.all(ctx).firstOrNull { it.id == r.id }?.enabled) return@setOnCheckedChangeListener
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

    /**
     * Historique (2026-11-08) : exécutions (▶) et déclenchements refusés (⊘, avec la raison et le
     * nombre de refus identiques regroupés), filtre Toutes / Exécutées / Ignorées.
     */
    private fun historique(ctx: Context) {
        val vue = LayoutInflater.from(ctx).inflate(R.layout.dialog_rule_value, null)
        vue.findViewById<View>(R.id.rule_value_cancel).visibility = View.GONE
        vue.findViewById<View>(R.id.rule_value_back).visibility = View.GONE
        vue.findViewById<TextView>(R.id.rule_value_title).text = ctx.getString(R.string.rule_tool_history)
        val sousTitre = vue.findViewById<TextView>(R.id.rule_value_subtitle)
        val corps = vue.findViewById<LinearLayout>(R.id.rule_value_body)
        val toutes = CustomRuleStore.history(ctx)
        val filtres = RuleHistory.Filter.values()
        val seg = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_dialog_segments)
            val p = AppDialog.dp(ctx, 4f)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, AppDialog.dp(ctx, 60f))
        }
        corps.addView(seg)
        val liste = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        // Hauteur fixe : vingt entrées dépasseraient l'écran de la voiture.
        corps.addView(android.widget.ScrollView(ctx).apply {
            addView(liste)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, AppDialog.dp(ctx, 420f)).apply {
                topMargin = AppDialog.dp(ctx, 10f)
            }
        })
        fun dessiner(f: RuleHistory.Filter) {
            val l = RuleHistory.filter(toutes, f)
            sousTitre.text = ctx.getString(R.string.rule_hist_count_fmt, l.size, CustomRuleStore.HISTORY_MAX)
            liste.removeAllViews()
            if (l.isEmpty()) {
                liste.addView(TextView(ctx).apply {
                    text = ctx.getString(if (toutes.isEmpty()) R.string.rule_never_run else R.string.rule_hist_empty_filter)
                    textSize = 17f
                    setTextColor(ctx.getColor(R.color.text_secondary))
                    setPadding(0, AppDialog.dp(ctx, 12f), 0, 0)
                })
                return
            }
            l.forEach { liste.addView(entree(ctx, it)) }
        }
        AppDialog.segments(seg, listOf(ctx.getString(R.string.rule_hist_filter_all), ctx.getString(R.string.rule_hist_filter_runs),
            ctx.getString(R.string.rule_hist_filter_skipped)), 0) { dessiner(filtres[it]) }
        dessiner(RuleHistory.Filter.ALL)
        lateinit var dialog: android.app.AlertDialog
        vue.findViewById<TextView>(R.id.rule_value_ok).apply {
            text = ctx.getString(R.string.nav_close)
            setOnClickListener { dialog.dismiss() }
        }
        dialog = AppDialog.afficher(ctx, vue, 1000f)
    }

    /** Une entrée de l'historique : ▶ exécution, ⊘ déclenchement refusé (grisé, raison en orange). */
    private fun entree(ctx: Context, e: RuleHistoryEntry): View {
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val s = e.skip
        val quand = (s?.atMs ?: e.run.atMs)?.let { format.format(Date(it)) } ?: ctx.getString(R.string.rule_run_time_unknown)
        val titre = android.text.SpannableStringBuilder()
        val vert = ctx.getColor(R.color.dash_eco)
        val orange = ctx.getColor(R.color.dash_warn)
        fun couleur(texte: String, c: Int) {
            val d = titre.length
            titre.append(texte)
            titre.setSpan(android.text.style.ForegroundColorSpan(c), d, titre.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val details = mutableListOf<String>()
        if (s == null) {
            couleur("▶  ", vert)
            titre.append("$quand — ${e.name} : ")
            couleur("${e.run.done} ✓", vert)
            titre.append(" · ")
            couleur("${e.run.failed} ✗", if (e.run.failed > 0) ctx.getColor(R.color.dash_danger) else ctx.getColor(R.color.text_secondary))
            e.run.by?.let { details += ctx.getString(R.string.rule_hist_by_fmt, it) }
            details += e.run.journal.orEmpty().take(6)
        } else {
            couleur("⊘  ", orange)
            titre.append("$quand — ${e.name}")
            if (s.count > 1) titre.append("  ×${s.count}")
            s.by?.let { details += ctx.getString(R.string.rule_hist_by_fmt, it) }
            details += ctx.getString(R.string.rule_hist_skipped_fmt, raison(ctx, s, longue = true))
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, AppDialog.dp(ctx, 8f), 0, AppDialog.dp(ctx, 8f))
            addView(TextView(ctx).apply {
                text = titre
                textSize = 18f
                setTextColor(ctx.getColor(if (s == null) R.color.text_primary else R.color.text_secondary))
            })
            details.forEachIndexed { i, d ->
                addView(TextView(ctx).apply {
                    text = d
                    textSize = 15f
                    // La raison d'un refus en orange ; le reste en texte secondaire.
                    setTextColor(if (s != null && i == details.lastIndex) orange else ctx.getColor(R.color.text_secondary))
                    setPadding(AppDialog.dp(ctx, 28f), 0, 0, 0)
                })
            }
        }
    }

    /** Raison d'un refus : courte pour la carte, longue (condition en cause, valeur lue) pour l'historique. */
    private fun raison(ctx: Context, s: RuleSkip, longue: Boolean): String {
        val cond = s.detail?.let { d -> s.value?.let { ctx.getString(R.string.rule_skip_read_fmt, d, it) } ?: d }
        return when (s.reason) {
            RuleSkip.Reason.CONDITIONS -> if (longue && cond != null) ctx.getString(R.string.rule_skip_conditions_fmt, cond)
                else ctx.getString(R.string.rule_skip_short_conditions)
            RuleSkip.Reason.UNKNOWN -> if (longue && cond != null) ctx.getString(R.string.rule_skip_unknown_fmt, cond)
                else ctx.getString(R.string.rule_skip_short_unknown)
            RuleSkip.Reason.NOT_READY -> ctx.getString(if (longue) R.string.rule_skip_not_ready else R.string.rule_skip_short_not_ready)
            RuleSkip.Reason.BUSY -> ctx.getString(if (longue) R.string.rule_skip_busy else R.string.rule_skip_short_busy)
            RuleSkip.Reason.COOLDOWN -> if (longue) ctx.getString(R.string.rule_skip_cooldown_fmt, s.minutes ?: 0)
                else ctx.getString(R.string.rule_skip_short_cooldown)
            RuleSkip.Reason.GARAGE -> ctx.getString(R.string.settings_garage_mode)
            null -> ctx.getString(R.string.rule_unknown)
        }
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


    /**
     * Un compte ABRP vient d'être ajouté (2026-11-09, suggestion d'un utilisateur) : l'envoi ne part
     * pas tout seul, il faut une automatisation. Sauf si une automatisation envoie déjà vers ABRP,
     * on propose de la préparer — au démarrage de l'écran, vers ce compte — et l'éditeur s'ouvre
     * dessus ; elle n'existe qu'une fois enregistrée. « Plus tard » rouvre la liste des comptes.
     */
    private fun proposerAutomatisationAbrp(ctx: Context, compte: com.mg4.control.model.AbrpAccount) {
        if (CustomRuleTemplates.sendsToAbrp(CustomRuleStore.all(ctx))) return comptesAbrp(ctx)
        AppDialog.confirmer(ctx,
            titre = ctx.getString(R.string.abrp_rule_prompt_title, compte.name),
            message = ctx.getString(R.string.abrp_rule_prompt_msg),
            icone = R.drawable.ic_dialog_info,
            oui = ctx.getString(R.string.abrp_rule_prompt_create),
            non = ctx.getString(R.string.abrp_rule_prompt_later),
            onOui = {
                if (CustomRuleStore.all(ctx).size >= CustomRuleStore.MAX_RULES) {
                    Toast.makeText(ctx, ctx.getString(R.string.rule_max_reached, CustomRuleStore.MAX_RULES), Toast.LENGTH_LONG).show()
                } else {
                    val regle = CustomRuleTemplates.abrpOnScreenStart(ctx.getString(R.string.rule_tpl_abrp_account_fmt, compte.name), compte.id)
                    ouvrir(CustomRuleEditFragment.ARG_NEW_RULE to CustomRuleStore.encode(listOf(regle)).removeSurrounding("[", "]"))
                }
            },
            onNon = { comptesAbrp(ctx) },
        )
    }

    /** Comptes ABRP : la liste, l'import du fichier de comptes, le fichier modèle (mêmes fenêtres que l'export). */
    private fun comptesAbrp(ctx: Context) {
        AbrpDialogs.comptes(ctx,
            importer = {
                importDialog = ImportDialog(ctx, com.mg4.control.model.AbrpConfigFile.FILE_NAME, parcourirFichier) { texte, ou ->
                    AbrpDialogs.importer(ctx, texte, ou, nouveaux = { l -> proposerAutomatisationAbrp(ctx, l.first()) }) { comptesAbrp(ctx) }
                }.also { it.ouvrir() }
            },
            nouveau = { a -> proposerAutomatisationAbrp(ctx, a) },
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

    /**
     * Bas de la carte : la dernière exécution, et dessous en orange le dernier déclenchement refusé
     * s'il est plus récent (variante A, 2026-11-08).
     */
    private fun derniere(ctx: Context, run: RuleLastRun?, skip: RuleSkip?): CharSequence {
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        // « 1 action réussie » : le nombre d'actions de CETTE exécution, pas un compteur de lancements.
        val ligne = if (run == null) ctx.getString(R.string.rule_never_run) else ctx.getString(R.string.rule_last_run_actions_fmt,
            run.atMs?.let { format.format(Date(it)) } ?: ctx.getString(R.string.rule_run_time_unknown),
            ctx.resources.getQuantityString(R.plurals.rule_actions_ok, run.done, run.done), run.failed)
        if (skip == null || !RuleHistory.skipIsNewer(run, skip)) return ligne
        val refus = ctx.getString(R.string.rule_last_skip_fmt,
            skip.atMs?.let { format.format(Date(it)) } ?: ctx.getString(R.string.rule_run_time_unknown), raison(ctx, skip, longue = false))
        return android.text.SpannableStringBuilder(ligne).append("\n").apply {
            val d = length
            append(refus)
            setSpan(android.text.style.ForegroundColorSpan(ctx.getColor(R.color.dash_warn)), d, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
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
