package com.mg4.control.util

import android.text.Layout
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
import java.util.Collections
import java.util.WeakHashMap

/**
 * Libellé trop long sur un bouton (règle du 2026-11-03) : deux lignes s'il y a la place, sinon une
 * seule ; au-delà, « … ». Décidé à chaque mise en page d'après la hauteur RÉELLE du bouton et la
 * hauteur d'une ligne, donc selon la langue (le texte) et la taille du texte (Réglages ›
 * Interface) : en « Très grand », un bouton de hauteur fixe peut ne plus loger que une ligne.
 *
 * Un bouton à hauteur libre (`wrap_content`) grandit : il a toujours droit à ses deux lignes.
 * Les boutons dont le texte rétrécit déjà tout seul (autosize) sont laissés tels quels.
 *
 * [suivre] équipe tous les `MaterialButton` d'une fenêtre, ceux ajoutés plus tard compris ;
 * [appliquer] équipe un bouton dessiné autrement (une tuile `TextView`).
 */
object ButtonLines {

    const val MAX_LIGNES = 2

    private val equipes: MutableSet<TextView> = Collections.newSetFromMap(WeakHashMap())

    /** Tous les `MaterialButton` sous [racine], maintenant et à chaque mise en page. */
    fun suivre(racine: View) {
        equiper(racine)
        racine.viewTreeObserver.addOnGlobalLayoutListener { equiper(racine) }
    }

    private fun equiper(v: View) {
        if (v is MaterialButton) appliquer(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) equiper(v.getChildAt(i))
    }

    // Layout.BREAK_STRATEGY_BALANCED vaut LineBreaker.BREAK_STRATEGY_BALANCED, qui demande l'API 29.
    @android.annotation.SuppressLint("WrongConstant")
    fun appliquer(t: TextView) {
        if (!equipes.add(t)) return
        if (TextViewCompat.getAutoSizeTextType(t) != TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE) return
        t.ellipsize = TextUtils.TruncateAt.END
        t.breakStrategy = Layout.BREAK_STRATEGY_BALANCED
        if (t.layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT) t.maxLines = MAX_LIGNES
        t.addOnLayoutChangeListener { _, _, haut, _, bas, _, ancienHaut, _, ancienBas ->
            if (bas - haut != ancienBas - ancienHaut) regler(t)
        }
        if (t.height > 0) regler(t)
    }

    private fun regler(t: TextView) {
        if (t.height <= 0) return
        val libre = t.layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT
        val fm = t.paint.fontMetricsInt
        val marges = if (t.includeFontPadding) (fm.ascent - fm.top) + (fm.bottom - fm.descent) else 0
        val n = lignes(t.height - t.totalPaddingTop - t.totalPaddingBottom, t.lineHeight, marges, libre)
        // Après la passe de mise en page en cours : en redemander une de l'intérieur est refusé.
        if (t.maxLines != n) t.post { t.maxLines = n }
    }

    /**
     * Lignes qu'un bouton peut montrer : [MAX_LIGNES] s'il grandit avec son texte ([libre]),
     * sinon autant qu'en tiennent [utile] px (hauteur sans les marges intérieures ni l'icône), à
     * [ligne] px la ligne plus [marges] px d'interligne de police — au moins une.
     */
    fun lignes(utile: Int, ligne: Int, marges: Int, libre: Boolean): Int {
        if (libre) return MAX_LIGNES
        if (ligne <= 0) return 1
        return ((utile - marges) / ligne).coerceIn(1, MAX_LIGNES)
    }
}
