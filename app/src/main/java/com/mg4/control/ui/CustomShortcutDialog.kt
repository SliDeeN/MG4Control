package com.mg4.control.ui

import android.content.Context
import android.content.Intent
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.mg4.control.BuildConfig
import com.mg4.control.R
import com.mg4.control.model.CustomKind
import com.mg4.control.model.CustomShortcut
import com.mg4.control.model.CustomShortcuts
import com.mg4.control.service.CustomShortcutRunner
import com.mg4.control.util.CustomShortcutSettings

/**
 * Raccourcis perso (issue #172, 2026-10-25) : la fenêtre de création / modification, et le choix
 * d'un raccourci perso pour un bouton (du volant, avancé ou flottant) — avec « Créer… » au bout de
 * la liste, qui ouvre la création et attribue aussitôt le raccourci créé.
 *
 * « Fonction d'une appli » réunit deux sources ([CustomShortcutRunner.fonctions]) : les raccourcis
 * que les applis publient pour les launchers, et les fonctions déclarées pour MG4Control.
 */
object CustomShortcutDialog {

    private const val LARGEUR_DP = 860f

    /** Les types proposés ; la fonction couvre les raccourcis d'applis et les fonctions déclarées. */
    private fun types(): List<CustomKind> = listOfNotNull(
        CustomKind.APP, CustomKind.FUNCTION, CustomKind.BROADCAST, CustomKind.LINK,
        CustomKind.WEBHOOK.takeIf { !BuildConfig.OFFLINE },
    )

    private fun affiche(kind: CustomKind?): CustomKind? = if (kind == CustomKind.APP_SHORTCUT) CustomKind.FUNCTION else kind

    fun libelleType(ctx: Context, kind: CustomKind?): String = ctx.getString(when (affiche(kind)) {
        CustomKind.APP -> R.string.custom_kind_app
        CustomKind.BROADCAST -> R.string.custom_kind_broadcast
        CustomKind.LINK -> R.string.custom_kind_link
        CustomKind.WEBHOOK -> R.string.custom_kind_webhook
        else -> R.string.custom_kind_function
    })

    /** Une ligne de résumé : le type, puis la cible (sans le jeton d'un webhook). */
    fun resume(ctx: Context, c: CustomShortcut): String {
        val cible = when (CustomShortcuts.kindOf(c)) {
            CustomKind.BROADCAST -> listOfNotNull(c.action, c.targetLabel ?: c.packageName).joinToString(" → ")
            CustomKind.LINK -> c.uri.orEmpty()
            CustomKind.WEBHOOK -> "${CustomShortcuts.methodOf(c)} ${CustomShortcuts.maskUrl(c.url)}"
            else -> c.targetLabel ?: c.packageName.orEmpty()
        }
        return listOf(libelleType(ctx, CustomShortcuts.kindOf(c)), cible).filter { it.isNotBlank() }.joinToString(" · ")
    }

    /**
     * Choisir le raccourci perso d'un bouton. [onAnnule] : rien de choisi, la création comprise
     * (le bouton peut alors revenir à « aucune action »).
     */
    fun choisir(ctx: Context, dejaChoisi: String?, onAnnule: () -> Unit = {}, onChoix: (CustomShortcut) -> Unit) {
        val liste = CustomShortcutSettings.all(ctx)
        val fond = ctx.getColor(R.color.dash_accent_dim)
        val texte = ctx.getColor(R.color.dash_accent)
        val choix = liste.map { AppDialog.Choix(it.name, pastille = libelleType(ctx, CustomShortcuts.kindOf(it)), fondPastille = fond, textePastille = texte) } +
            listOfNotNull(AppDialog.Choix(ctx.getString(R.string.custom_create)).takeIf { liste.size < CustomShortcuts.MAX })
        AppDialog.choisir(ctx,
            titre = ctx.getString(R.string.custom_pick_title),
            choix = choix,
            annuler = ctx.getString(R.string.profile_cancel),
            choisi = liste.indexOfFirst { it.id == dejaChoisi },
            onAnnule = onAnnule,
        ) { rang ->
            if (rang < liste.size) onChoix(liste[rang]) else ouvrir(ctx, null, onAnnule, onChoix)
        }
    }

    /** Créer ([existant] `null`) ou modifier ; [onEnregistre] reçoit le raccourci enregistré. */
    fun ouvrir(ctx: Context, existant: CustomShortcut?, onAnnule: () -> Unit = {}, onEnregistre: (CustomShortcut) -> Unit) {
        val vue = LayoutInflater.from(ctx).inflate(R.layout.dialog_custom_shortcut, null)
        val dialog = AppDialog.afficher(ctx, vue, LARGEUR_DP)
        var enregistre = false
        dialog.setOnDismissListener { if (!enregistre) onAnnule() }

        var etat = existant ?: CustomShortcut(id = CustomShortcutSettings.newId(), name = "", kind = CustomKind.FUNCTION.key)
        var methode = CustomShortcuts.methodOf(etat)
        val types = types()

        val nom = vue.findViewById<EditText>(R.id.cs_name).apply {
            filters = arrayOf(InputFilter.LengthFilter(CustomShortcuts.NAME_MAX))
            setText(etat.name)
        }
        val action = vue.findViewById<EditText>(R.id.cs_action).apply { setText(etat.action.orEmpty()) }
        val extras = vue.findViewById<EditText>(R.id.cs_extras).apply { setText(etat.extras.orEmpty()) }
        val lien = vue.findViewById<EditText>(R.id.cs_link).apply { setText(etat.uri.orEmpty()) }
        val url = vue.findViewById<EditText>(R.id.cs_url).apply { setText(etat.url.orEmpty()) }
        val corps = vue.findViewById<EditText>(R.id.cs_body).apply { setText(etat.body.orEmpty()) }
        val message = vue.findViewById<TextView>(R.id.cs_message)
        val sections = mapOf(
            CustomKind.APP to vue.findViewById<View>(R.id.cs_section_app),
            CustomKind.FUNCTION to vue.findViewById(R.id.cs_section_function),
            CustomKind.BROADCAST to vue.findViewById(R.id.cs_section_broadcast),
            CustomKind.LINK to vue.findViewById(R.id.cs_section_link),
            CustomKind.WEBHOOK to vue.findViewById(R.id.cs_section_webhook),
        )
        val choisir = ctx.getString(R.string.custom_choose)

        fun dire(texte: String?, ok: Boolean = false) {
            message.text = texte
            message.visibility = if (texte.isNullOrEmpty()) View.GONE else View.VISIBLE
            message.setTextColor(ctx.getColor(if (ok) R.color.dash_eco else R.color.dash_warn))
        }
        fun nommerSiVide(n: String) {
            if (nom.text.isNullOrBlank()) nom.setText(n.take(CustomShortcuts.NAME_MAX))
        }
        fun montrer() {
            val k = affiche(CustomShortcuts.kindOf(etat))
            sections.forEach { (kind, v) -> v.visibility = if (kind == k) View.VISIBLE else View.GONE }
            vue.findViewById<TextView>(R.id.cs_app).text =
                (if (CustomShortcuts.kindOf(etat) == CustomKind.APP) etat.targetLabel else null) ?: choisir
            vue.findViewById<TextView>(R.id.cs_function).text =
                (if (k == CustomKind.FUNCTION && !etat.packageName.isNullOrBlank()) etat.targetLabel else null) ?: choisir
            vue.findViewById<TextView>(R.id.cs_target).text =
                (if (CustomShortcuts.kindOf(etat) == CustomKind.BROADCAST) etat.targetLabel ?: etat.packageName else null) ?: choisir
        }
        fun lire(): CustomShortcut = etat.copy(
            name = nom.text.toString().trim(),
            action = if (CustomShortcuts.kindOf(etat) == CustomKind.BROADCAST) action.text.toString().trim() else etat.action,
            extras = extras.text.toString().takeIf { it.isNotBlank() },
            uri = lien.text.toString().trim().takeIf { it.isNotEmpty() },
            url = url.text.toString().trim().takeIf { it.isNotEmpty() },
            body = corps.text.toString().takeIf { it.isNotBlank() },
            method = methode,
        )

        AppDialog.segments(vue.findViewById(R.id.cs_kinds), types.map { libelleType(ctx, it) },
            types.indexOf(affiche(CustomShortcuts.kindOf(etat))).coerceAtLeast(0)) { rang ->
            // Changer de type garde ce qui a été saisi : revenir en arrière le retrouve.
            val nouveau = types[rang]
            val garde = affiche(CustomShortcuts.kindOf(etat)) == CustomKind.FUNCTION && nouveau == CustomKind.FUNCTION
            if (!garde) etat = etat.copy(kind = nouveau.key,
                // Une appli choisie pour un type ne vaut pas forcément pour l'autre.
                packageName = null, className = null, shortcutId = null, targetLabel = null)
            dire(null)
            montrer()
        }
        AppDialog.segments(vue.findViewById<LinearLayout>(R.id.cs_method), listOf("GET", "POST"),
            if (methode == "POST") 1 else 0) { rang -> methode = if (rang == 1) "POST" else "GET" }

        vue.findViewById<View>(R.id.cs_app).setOnClickListener {
            choisirAppli(ctx, lanceur = true, dejaChoisie = etat.packageName) { paquet, appli ->
                etat = etat.copy(kind = CustomKind.APP.key, packageName = paquet, targetLabel = appli)
                nommerSiVide(appli)
                montrer()
            }
        }
        vue.findViewById<View>(R.id.cs_target).setOnClickListener {
            choisirAppli(ctx, lanceur = false, dejaChoisie = etat.packageName) { paquet, appli ->
                etat = etat.copy(packageName = paquet, targetLabel = appli)
                montrer()
            }
        }
        vue.findViewById<View>(R.id.cs_function).setOnClickListener {
            val offres = CustomShortcutRunner.fonctions(ctx)
            if (offres.isEmpty()) {
                dire(ctx.getString(R.string.custom_function_none))
                return@setOnClickListener
            }
            val fond = ctx.getColor(R.color.dash_accent_dim)
            val texte = ctx.getColor(R.color.dash_accent)
            AppDialog.choisir(ctx,
                titre = ctx.getString(R.string.custom_pick_function),
                choix = offres.map { o ->
                    AppDialog.Choix("${o.appLabel} · ${o.label}", icone = o.icone,
                        pastille = ctx.getString(if (o.kind == CustomKind.FUNCTION) R.string.custom_tag_declared else R.string.custom_tag_shortcut),
                        fondPastille = fond, textePastille = texte)
                },
                annuler = ctx.getString(R.string.profile_cancel),
                choisi = offres.indexOfFirst { it.packageName == etat.packageName && (it.shortcutId == etat.shortcutId && it.action == etat.action) },
            ) { rang ->
                val o = offres[rang]
                etat = etat.copy(kind = o.kind.key, packageName = o.packageName, className = o.className,
                    shortcutId = o.shortcutId, action = o.action, targetLabel = "${o.appLabel} · ${o.label}")
                nommerSiVide(o.label)
                dire(null)
                montrer()
            }
        }
        vue.findViewById<View>(R.id.cs_test).setOnClickListener {
            dire(null)
            CustomShortcutRunner.executer(ctx, lire()) { r -> dire(r.texte, ok = r.ok) }
        }
        vue.findViewById<View>(R.id.cs_cancel).setOnClickListener { dialog.dismiss() }
        vue.findViewById<View>(R.id.cs_save).setOnClickListener {
            val c = lire()
            val p = CustomShortcuts.problem(c) { CustomShortcutRunner.estAppliUtilisateur(ctx, it) }
            when {
                p != null -> dire(CustomShortcutRunner.texteProbleme(ctx, p))
                !CustomShortcutSettings.save(ctx, c) -> dire(ctx.getString(R.string.custom_full))
                else -> {
                    enregistre = true
                    dialog.dismiss()
                    onEnregistre(c)
                }
            }
        }
        montrer()
    }

    /**
     * Choisir une appli : celles du launcher ([lanceur]), ou celles que l'utilisateur a installées
     * — les seules qui peuvent recevoir une diffusion.
     */
    private fun choisirAppli(ctx: Context, lanceur: Boolean, dejaChoisie: String?, onChoix: (String, String) -> Unit) {
        val pm = ctx.packageManager
        val applis = (if (lanceur) {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        } else {
            pm.getInstalledApplications(0)
                .filter { CustomShortcutRunner.estAppliUtilisateur(ctx, it.packageName) }
                .map { it.packageName to pm.getApplicationLabel(it).toString() }
        }).distinctBy { it.first }.sortedBy { it.second.lowercase() }
        AppDialog.choisir(ctx,
            titre = ctx.getString(R.string.shortcuts_pick_app_title),
            sousTitre = ctx.getString(R.string.choice_count_apps, applis.size),
            choix = applis.map { (paquet, appli) ->
                AppDialog.Choix(appli, icone = runCatching { pm.getApplicationIcon(paquet) }.getOrNull())
            },
            annuler = ctx.getString(R.string.profile_cancel),
            choisi = applis.indexOfFirst { it.first == dejaChoisie },
        ) { rang -> onChoix(applis[rang].first, applis[rang].second) }
    }
}
