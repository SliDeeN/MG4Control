package com.mg4.control.ui

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mg4.control.R
import com.mg4.control.automation.CustomRuleStore
import com.mg4.control.automation.CustomRules
import com.mg4.control.automation.RuleRefLookup
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.RefKind
import com.mg4.control.model.RuleRefs
import com.mg4.control.service.OverlayKit

/**
 * Fenêtre de fin d'import (maquette du 2026-11-14) : ce qui a été relié tout seul (liens vers les
 * automatisations du fichier, objets reliés par leur nom), puis ce qui reste à réassocier, chacun
 * avec son choix sur place. Un choix réécrit ce lien dans l'automatisation importée.
 */
object RuleImportReport {

    private const val TAG = "MG4_RULES"
    private const val LISTE_MAX_DP = 380f

    fun afficher(ctx: Context, importees: Int, liens: List<RuleRefs.Link>, apres: () -> Unit) {
        val vue = LayoutInflater.from(ctx).inflate(R.layout.dialog_rule_import, null)
        vue.findViewById<TextView>(R.id.rule_import_title).text = ctx.getString(R.string.rule_import_done, importees).trimEnd('.')
        val liste = vue.findViewById<LinearLayout>(R.id.rule_import_list)
        val fermer = vue.findViewById<TextView>(R.id.rule_import_close)

        val duFichier = liens.filter { it.status == RuleRefs.Status.FROM_FILE }
        val parNom = liens.filter { it.status == RuleRefs.Status.BY_NAME }
        val manquants = liens.filter { it.status == RuleRefs.Status.MISSING }
        var restants = manquants.size
        lateinit var titreManquants: TextView

        fun peindreFermer() {
            fermer.text = ctx.getString(if (restants > 0) R.string.rule_import_later else R.string.rule_import_finished)
        }

        if (duFichier.isNotEmpty() || parNom.isNotEmpty()) {
            liste.addView(section(ctx, ctx.getString(R.string.rule_import_auto)))
            if (duFichier.isNotEmpty()) liste.addView(rangee(ctx, RefKind.RULE, true,
                ctx.resources.getQuantityString(R.plurals.rule_import_from_file, duFichier.size, duFichier.size), null))
            parNom.forEach { l ->
                liste.addView(rangee(ctx, l.ref.kind, true, ctx.getString(R.string.rule_import_by_name_fmt, l.name ?: "?"),
                    ctx.getString(R.string.rule_import_in_fmt, l.ruleName)))
            }
        }
        if (manquants.isNotEmpty()) {
            titreManquants = section(ctx, ctx.getString(R.string.rule_import_todo_fmt, restants))
            liste.addView(titreManquants)
            manquants.forEach { l ->
                val nom = l.name ?: ctx.getString(R.string.rule_import_unnamed)
                lateinit var r: LinearLayout
                r = rangee(ctx, l.ref.kind, false, ctx.getString(R.string.rule_import_missing_fmt, nom),
                    ctx.getString(R.string.rule_import_in_fmt, l.ruleName), ctx.getString(choisir(l.ref.kind))) {
                    remplacer(ctx, l) { choisi ->
                        restants--
                        titreManquants.text = ctx.getString(R.string.rule_import_todo_fmt, restants)
                        val nouvelle = rangee(ctx, l.ref.kind, true, ctx.getString(R.string.rule_import_linked_fmt, choisi),
                            ctx.getString(R.string.rule_import_in_fmt, l.ruleName))
                        liste.addView(nouvelle, liste.indexOfChild(r))
                        liste.removeView(r)
                        peindreFermer()
                    }
                }
                liste.addView(r)
            }
        }
        peindreFermer()

        val dialog = AppDialog.afficher(ctx, vue, 900f)
        dialog.setOnDismissListener { apres() }
        fermer.setOnClickListener { dialog.dismiss() }
        // Longue liste : elle défile, la fenêtre reste dans l'écran.
        vue.findViewById<ScrollView>(R.id.rule_import_scroll).post {
            val max = AppDialog.dp(ctx, LISTE_MAX_DP)
            val s = vue.findViewById<ScrollView>(R.id.rule_import_scroll)
            if (s.height > max) s.layoutParams = s.layoutParams.apply { height = max }
        }
    }

    /** Choisir le remplaçant d'un lien manquant ; [ok] reçoit son nom une fois enregistré. */
    private fun remplacer(ctx: Context, l: RuleRefs.Link, ok: (String) -> Unit) {
        val items = RuleRefLookup(ctx).items(l.ref.kind).filter { l.ref.kind != RefKind.RULE || it.first != l.ruleId }
        AppDialog.choisir(ctx,
            titre = ctx.getString(choisir(l.ref.kind)),
            choix = items.map { AppDialog.Choix(it.second) },
            annuler = ctx.getString(R.string.profile_cancel),
        ) { rang ->
            val (id, nom) = items[rang]
            val regle = CustomRuleStore.all(ctx).firstOrNull { it.id == l.ruleId } ?: return@choisir
            CustomRuleStore.upsert(ctx, RuleRefs.rewrite(regle) { if (it == l.ref) id else null })
            CustomRules.refresh(ctx)
            AppLogger.i(TAG, "import : « ${l.ruleName} » — ${l.ref.kind} « ${l.name ?: l.ref.id} » relié à « $nom »")
            ok(nom)
        }
    }

    private fun choisir(k: RefKind) = when (k) {
        RefKind.RULE -> R.string.rule_import_choose_rule
        RefKind.PLACE -> R.string.rule_import_choose_place
        RefKind.PROFILE -> R.string.rule_import_choose_profile
        RefKind.SOUND -> R.string.rule_import_choose_sound
        RefKind.SHORTCUT -> R.string.rule_import_choose_shortcut
    }

    fun sorte(k: RefKind) = when (k) {
        RefKind.RULE -> R.string.rule_ref_rule
        RefKind.PLACE -> R.string.rule_ref_place
        RefKind.PROFILE -> R.string.rule_ref_profile
        RefKind.SOUND -> R.string.rule_ref_sound
        RefKind.SHORTCUT -> R.string.rule_ref_shortcut
    }

    private fun section(ctx: Context, texte: String) = TextView(ctx).apply {
        text = texte
        isAllCaps = true
        letterSpacing = 0.08f
        textSize = 13f
        setTextColor(ctx.getColor(R.color.dash_text_lo))
        setPadding(0, AppDialog.dp(ctx, 14f), 0, AppDialog.dp(ctx, 4f))
    }

    private fun rangee(ctx: Context, kind: RefKind, relie: Boolean, ligne1: String, ligne2: String?,
                       bouton: String? = null, onBouton: (() -> Unit)? = null): LinearLayout {
        val px = { v: Float -> AppDialog.dp(ctx, v) }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(56f)
            setPadding(0, px(6f), 0, px(6f))
            addView(TextView(ctx).apply {
                text = ctx.getString(sorte(kind))
                textSize = 13f
                maxLines = 1
                setPadding(px(10f), px(3f), px(10f), px(3f))
                setTextColor(ctx.getColor(if (relie) R.color.dash_eco else R.color.dash_warn))
                OverlayKit.fond(this, ctx.getColor(if (relie) R.color.dash_eco_dim else R.color.dash_warn_dim), rayon = px(8f).toFloat())
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply { text = ligne1; textSize = 16f; setTextColor(ctx.getColor(R.color.text_primary)) })
                if (ligne2 != null) addView(TextView(ctx).apply { text = ligne2; textSize = 13f; setTextColor(ctx.getColor(R.color.text_secondary)) })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(12f); marginEnd = px(12f) })
            if (bouton != null && onBouton != null) addView(TextView(ctx).apply {
                text = bouton
                textSize = 15f
                maxLines = 1
                gravity = Gravity.CENTER
                setTextColor(ctx.getColor(R.color.text_primary))
                setBackgroundResource(R.drawable.bg_dialog_tile)
                setPadding(px(16f), 0, px(16f), 0)
                setOnClickListener { onBouton() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(48f)))
        }
    }
}
