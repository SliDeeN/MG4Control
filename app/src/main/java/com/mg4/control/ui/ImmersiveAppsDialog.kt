package com.mg4.control.ui

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.mg4.control.R
import com.mg4.control.model.ImmersiveMode
import com.mg4.control.service.ImmersiveApps
import com.mg4.control.service.OverlayKit
import com.mg4.control.ui.AppDialog.dp

/**
 * Choix des applis en plein écran (issue #248, variante B choisie sur maquette le 2026-11-15) :
 * une ligne par appli, avec Normal / Bandeau / Barre / Tout. Une appli que MG4Dash cache déjà
 * entièrement le reste quoi qu'on choisisse : c'est dit sous son nom.
 */
internal object ImmersiveAppsDialog {

    private const val LARGEUR_DP = 1040f
    private const val SEGMENTS_DP = 500f

    /** Rang du segment → mode (`null` = Normal). */
    private val MODES = listOf(null, ImmersiveMode.STATUS, ImmersiveMode.NAVIGATION, ImmersiveMode.FULL)

    fun show(ctx: Context, apres: () -> Unit) {
        val applis = ImmersiveApps.candidates(ctx)
        val choix = ImmersiveApps.selection(ctx).toMutableMap()
        val parDash = ImmersiveApps.mg4DashFull(ctx)
        val pm = ctx.packageManager
        lateinit var dialog: AlertDialog

        val vue = LayoutInflater.from(ctx).inflate(R.layout.dialog_choice, null)
        vue.findViewById<ImageView>(R.id.choice_icon).apply {
            setImageResource(R.drawable.ic_cat_adjust)
            imageTintList = android.content.res.ColorStateList.valueOf(ctx.getColor(R.color.dash_accent))
        }
        vue.findViewById<TextView>(R.id.choice_title).text = ctx.getString(R.string.immersive_title)
        val sousTitre = vue.findViewById<TextView>(R.id.choice_subtitle)
        fun majSousTitre() {
            sousTitre.text = ctx.getString(R.string.immersive_dialog_count, choix.size)
        }
        majSousTitre()

        val rangees = vue.findViewById<LinearLayout>(R.id.choice_rows)
        val racine = rangees.parent.parent as LinearLayout
        val defilement = rangees.parent as View

        // Ce que veulent dire les segments, puis la recherche, entre l'en-tête et la liste.
        val aide = TextView(ctx).apply {
            text = ctx.getString(R.string.immersive_hint)
            setTextColor(ctx.getColor(R.color.text_secondary))
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = dp(ctx, 12f) }
        }
        racine.addView(aide, racine.indexOfChild(defilement))

        val lignes = applis.map { (paquet, nom) ->
            val ligne = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(ctx, 76f)
                setPadding(0, dp(ctx, 4f), 0, dp(ctx, 4f))
            }
            ligne.addView(ImageView(ctx).apply {
                setImageDrawable(runCatching { pm.getApplicationIcon(paquet) }.getOrNull())
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 48f), dp(ctx, 48f)).also { it.marginEnd = dp(ctx, 14f) }
            })
            val textes = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textes.addView(TextView(ctx).apply {
                text = nom
                setTextColor(ctx.getColor(R.color.text_primary))
                textSize = 19f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (paquet in parDash) textes.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.immersive_by_mg4dash)
                setTextColor(ctx.getColor(R.color.dash_warn))
                textSize = 15f
            })
            ligne.addView(textes)
            val segments = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundResource(R.drawable.bg_dialog_segments)
                val p = dp(ctx, 4f)
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, SEGMENTS_DP), dp(ctx, 60f)).also { it.marginStart = dp(ctx, 12f) }
            }
            ligne.addView(segments)
            AppDialog.segments(segments,
                listOf(R.string.immersive_mode_normal, R.string.immersive_mode_status, R.string.immersive_mode_nav,
                    R.string.immersive_mode_full).map { ctx.getString(it) },
                MODES.indexOf(choix[paquet]).coerceAtLeast(0)) { rang ->
                val mode = MODES[rang]
                if (mode == null) choix.remove(paquet) else choix[paquet] = mode
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
            racine.addView(champ, racine.indexOfChild(aide))
        }

        // Pied : Annuler à gauche (case « retour » de la mise en page), Enregistrer à droite.
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
                ImmersiveApps.setSelection(ctx, choix)
                dialog.dismiss()
                apres()
            }
        }
        dialog = AppDialog.afficher(ctx, vue, LARGEUR_DP)
    }
}
