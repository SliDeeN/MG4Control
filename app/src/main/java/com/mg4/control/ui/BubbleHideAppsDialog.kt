package com.mg4.control.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mg4.control.R
import com.mg4.control.model.BubbleHideApps
import com.mg4.control.model.ProjectionKind
import com.mg4.control.service.OverlayKit
import com.mg4.control.ui.AppDialog.dp
import com.mg4.control.util.FloatingButtonSettings

/**
 * Choix des applications où les boutons flottants se cachent (issue #261, maquette validée le
 * 2026-11-19) : une case par application, Android Auto et CarPlay en tête, recherche au-delà de 6.
 */
internal object BubbleHideAppsDialog {

    private const val LARGEUR_DP = 900f

    /** Android Auto et CarPlay (AllGo) : installés mais pas « lançables », ajoutés à la main. */
    private val PROJECTIONS = ProjectionKind.entries.map { it.packageName to it.label }

    fun candidates(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val projections = PROJECTIONS.mapNotNull { (p, nom) ->
            runCatching { pm.getApplicationInfo(p, 0) }.getOrNull()?.let { p to nom }
        }
        val lancables = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        }.getOrDefault(emptyList())
        return BubbleHideApps.candidats(projections, lancables, ctx.packageName)
    }

    fun show(ctx: Context, apres: () -> Unit) {
        val applis = candidates(ctx)
        val choix = FloatingButtonSettings.hideApps(ctx).toMutableSet()
        val pm = ctx.packageManager
        lateinit var dialog: AlertDialog

        val vue = LayoutInflater.from(ctx).inflate(R.layout.dialog_choice, null)
        vue.findViewById<ImageView>(R.id.choice_icon).apply {
            setImageResource(R.drawable.ic_cat_adjust)
            imageTintList = android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.dash_accent))
        }
        vue.findViewById<TextView>(R.id.choice_title).text = ctx.getString(R.string.bubble_hide_apps_dialog_title)
        val sousTitre = vue.findViewById<TextView>(R.id.choice_subtitle)
        fun majSousTitre() {
            sousTitre.text = ctx.getString(R.string.bubble_hide_apps_dialog_count, choix.count { p -> applis.any { it.first == p } })
        }
        majSousTitre()

        val rangees = vue.findViewById<LinearLayout>(R.id.choice_rows)
        val racine = rangees.parent.parent as LinearLayout
        val defilement = rangees.parent as View

        val lignes = applis.map { (paquet, nom) ->
            val ligne = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(ctx, 72f)
                setPadding(0, dp(ctx, 4f), 0, dp(ctx, 4f))
            }
            val caseACocher = CheckBox(ctx).apply {
                isChecked = paquet in choix
                isClickable = false
                isFocusable = false
                buttonTintList = android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.dash_accent))
                scaleX = 1.3f
                scaleY = 1.3f
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .also { it.marginEnd = dp(ctx, 14f) }
            }
            ligne.addView(caseACocher)
            ligne.addView(ImageView(ctx).apply {
                setImageDrawable(runCatching { pm.getApplicationIcon(paquet) }.getOrNull())
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 48f), dp(ctx, 48f)).also { it.marginEnd = dp(ctx, 14f) }
            })
            ligne.addView(TextView(ctx).apply {
                text = nom
                setTextColor(ctx.getColor(R.color.text_primary))
                textSize = 19f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            ligne.setOnClickListener {
                if (!choix.remove(paquet)) choix += paquet
                caseACocher.isChecked = paquet in choix
                majSousTitre()
            }
            rangees.addView(ligne)
            Triple(ligne, nom, paquet)
        }

        val vide = TextView(ctx).apply {
            text = ctx.getString(R.string.immersive_empty)
            setTextColor(ctx.getColor(R.color.text_secondary))
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 24f), 0, dp(ctx, 24f))
            visibility = if (lignes.isEmpty()) View.VISIBLE else View.GONE
        }
        rangees.addView(vide)

        if (lignes.size > 6) {
            val champ = AppDialog.champRecherche(ctx, { dialog }) { texte ->
                val cle = texte.trim().lowercase()
                var vues = 0
                lignes.forEach { (ligne, nom, paquet) ->
                    val ok = cle.isEmpty() || nom.lowercase().contains(cle) || paquet.lowercase().contains(cle)
                    ligne.visibility = if (ok) View.VISIBLE else View.GONE
                    if (ok) vues++
                }
                vide.visibility = if (vues == 0) View.VISIBLE else View.GONE
            }
            racine.addView(champ, racine.indexOfChild(defilement))
        }

        vue.findViewById<TextView>(R.id.choice_back).apply {
            visibility = View.VISIBLE
            text = ctx.getString(R.string.profile_cancel)
            setOnClickListener { dialog.dismiss() }
        }
        vue.findViewById<TextView>(R.id.choice_cancel).apply {
            text = ctx.getString(R.string.profile_save)
            setTextColor(ctx.getColor(R.color.hvac_on_accent))
            OverlayKit.fond(this, ctx.getColor(R.color.dash_accent), rayon = dp(ctx, 12f).toFloat())
            setOnClickListener {
                // Une application désinstallée depuis disparaît de la liste enregistrée.
                FloatingButtonSettings.setHideApps(ctx, choix.filter { p -> applis.any { it.first == p } }.toSet())
                dialog.dismiss()
                apres()
            }
        }
        dialog = AppDialog.afficher(ctx, vue, LARGEUR_DP)
    }
}
