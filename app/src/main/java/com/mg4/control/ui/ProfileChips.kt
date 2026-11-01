package com.mg4.control.ui

import android.content.res.ColorStateList
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.widget.LinearLayout
import android.widget.Space
import com.google.android.material.button.MaterialButton
import com.mg4.control.R
import com.mg4.control.model.DrivingProfile

/**
 * Puces de profils (2026-10-24) : « Réglage de : » de l'écran conducteur, « Boutons de : » des
 * boutons flottants, la copie vers d'autres profils. Jusqu'à dix profils en rangées de cinq, sans
 * liste déroulante (sa demande) ; des places vides gardent la même largeur d'une rangée à l'autre.
 */
object ProfileChips {

    private const val PAR_RANGEE = 5

    /**
     * Pose une puce par profil dans [grille] (vidée d'abord). [texte] : ce qu'affiche la puce ;
     * [surAppui] : l'identifiant du profil touché. Retourne les puces, dans l'ordre des profils.
     */
    fun construire(
        grille: LinearLayout,
        profils: List<DrivingProfile>,
        texte: (DrivingProfile) -> CharSequence,
        surAppui: (String) -> Unit,
    ): List<Pair<String, MaterialButton>> {
        val ctx = grille.context
        grille.removeAllViews()
        val dp = ctx.resources.displayMetrics.density
        val ecart = (4 * dp).toInt()
        return profils.chunked(PAR_RANGEE).flatMapIndexed { rang, groupe ->
            val rangee = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            grille.addView(rangee, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (rang > 0) topMargin = ecart })
            val puces = groupe.map { p ->
                val btn = MaterialButton(ctx).apply {
                    text = texte(p)
                    isAllCaps = false
                    textSize = 16f
                    maxLines = 2
                    minHeight = (52 * dp).toInt()
                    cornerRadius = (8 * dp).toInt()
                    strokeWidth = dp.toInt().coerceAtLeast(1)
                    setOnClickListener { surAppui(p.id) }
                }
                rangee.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = ecart })
                p.id to btn
            }
            repeat(PAR_RANGEE - groupe.size) {
                rangee.addView(Space(ctx), LinearLayout.LayoutParams(0, 0, 1f).apply { marginEnd = ecart })
            }
            puces
        }
    }

    /** Le nom du profil, et [suite] plus petit dessous (« en cours », nombre de bulles…). */
    fun texte(nom: String, suite: String?): CharSequence {
        if (suite.isNullOrEmpty()) return nom
        return SpannableString("$nom\n$suite").apply {
            setSpan(RelativeSizeSpan(0.75f), nom.length + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Puce choisie ou non, aux couleurs des autres boutons de choix. */
    fun peindre(btn: MaterialButton, choisie: Boolean) {
        val ctx = btn.context
        btn.backgroundTintList = ColorStateList.valueOf(ctx.getColor(if (choisie) R.color.dash_accent_dim else R.color.dash_btn))
        btn.setTextColor(ctx.getColor(if (choisie) R.color.dash_accent else R.color.text_secondary))
        btn.strokeColor = ColorStateList.valueOf(ctx.getColor(if (choisie) R.color.dash_accent else R.color.dash_border))
    }
}
