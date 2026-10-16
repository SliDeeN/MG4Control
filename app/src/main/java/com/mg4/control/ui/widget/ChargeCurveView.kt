package com.mg4.control.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.mg4.control.R
import com.mg4.control.model.ChargePoint

/**
 * Courbe du pop-up de charge (maquette A, 2026-10-16) : la puissance (aire bleue, axe de gauche
 * en kW) et le niveau de batterie (ligne verte, axe de droite en %) depuis le DÉBUT de la charge,
 * la limite en pointillé, et — tant que la voiture donne un temps restant — un pointillé du niveau
 * actuel jusqu'à la limite à l'heure de fin estimée. Ce n'est pas une prévision de la courbe : la
 * droite que dessine le temps restant de la voiture.
 */
class ChargeCurveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Ce que la vue dessine ; les libellés arrivent écrits, dans la langue du pop-up. */
    data class Donnees(
        val points: List<ChargePoint>,
        /** Durée écoulée depuis le début de la charge. */
        val ecouleMs: Long,
        /** Temps restant donné par la voiture, `null` s'il est inconnu (ou charge finie). */
        val restantMs: Long?,
        val socActuel: Float?,
        val limitePct: Int?,
        val finie: Boolean,
        val libelleCible: String?,
        /** Sous l'axe : à gauche, au milieu, à droite. */
        val heureDebut: String,
        val heureMilieu: String?,
        val heureFin: String?,
    )

    var donnees: Donnees? = null
        set(v) { field = v; invalidate() }

    private val cAccent = context.getColor(R.color.dash_accent)
    private val cVert = context.getColor(R.color.dash_eco)
    private val cGrille = context.getColor(R.color.dash_border)
    private val cTexte2 = context.getColor(R.color.text_secondary)
    private val cTexte = context.getColor(R.color.text_primary)

    private val trait = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val plein = Paint(Paint.ANTI_ALIAS_FLAG)
    private val texte = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chemin = Path()

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = donnees ?: return
        texte.textSize = sp(12f)
        val gauche = dp(34f)
        val droite = width - dp(30f)
        val haut = dp(8f)
        val bas = height - dp(22f)
        val l = droite - gauche
        val h = bas - haut
        if (l <= 0f || h <= 0f) return

        val totalMs = (d.ecouleMs + (d.restantMs ?: 0L)).coerceAtLeast(60_000L)
        val kwMax = echelleKw(d.points.maxOfOrNull { it.kw ?: 0f } ?: 0f)
        fun x(t: Long) = gauche + l * (t.toFloat() / totalMs).coerceIn(0f, 1f)
        fun yKw(k: Float) = bas - h * (k / kwMax).coerceIn(0f, 1f)
        fun ySoc(s: Float) = bas - h * (s / 100f).coerceIn(0f, 1f)
        val xMaintenant = x(d.ecouleMs)

        // Après « maintenant » : un fond à peine plus clair, le temps qu'il reste.
        if (!d.finie && d.restantMs != null) {
            plein.color = Color.argb(8, 255, 255, 255)
            canvas.drawRect(xMaintenant, haut, droite, bas, plein)
        }
        // Grille et axes.
        trait.pathEffect = null
        trait.strokeWidth = dp(1f)
        trait.color = cGrille
        for (f in 0..4) canvas.drawLine(gauche, haut + h * f / 4f, droite, haut + h * f / 4f, trait)
        texte.color = cTexte2
        texte.textAlign = Paint.Align.RIGHT
        listOf(0f, kwMax / 2f, kwMax).forEach { k ->
            canvas.drawText(entier(k), gauche - dp(6f), yKw(k) + sp(4f), texte)
        }
        texte.textAlign = Paint.Align.LEFT
        listOf(0f, 50f, 100f).forEach { s -> canvas.drawText(entier(s), droite + dp(6f), ySoc(s) + sp(4f), texte) }
        canvas.drawText(d.heureDebut, gauche, height - dp(4f), texte)
        d.heureMilieu?.let {
            texte.textAlign = Paint.Align.CENTER
            canvas.drawText(it, gauche + l / 2f, height - dp(4f), texte)
        }
        d.heureFin?.let {
            texte.textAlign = Paint.Align.RIGHT
            canvas.drawText(it, droite, height - dp(4f), texte)
        }

        // Limite : pointillé vert et son libellé, à droite.
        d.limitePct?.let { lim ->
            trait.color = cVert
            trait.alpha = 170
            trait.strokeWidth = dp(1.5f)
            trait.pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)
            canvas.drawLine(gauche, ySoc(lim.toFloat()), droite, ySoc(lim.toFloat()), trait)
            trait.alpha = 255
            d.libelleCible?.let {
                texte.color = cVert
                texte.textAlign = Paint.Align.RIGHT
                canvas.drawText(it, droite - dp(4f), ySoc(lim.toFloat()) - dp(6f), texte)
            }
        }
        trait.pathEffect = null

        val pts = d.points
        if (pts.isNotEmpty()) {
            // Puissance : l'aire, puis son trait.
            chemin.reset()
            chemin.moveTo(x(pts.first().tMs), bas)
            pts.forEach { p -> chemin.lineTo(x(p.tMs), yKw(p.kw ?: 0f)) }
            chemin.lineTo(x(pts.last().tMs), bas)
            chemin.close()
            plein.color = Color.argb(52, Color.red(cAccent), Color.green(cAccent), Color.blue(cAccent))
            canvas.drawPath(chemin, plein)
            chemin.reset()
            pts.forEachIndexed { i, p -> if (i == 0) chemin.moveTo(x(p.tMs), yKw(p.kw ?: 0f)) else chemin.lineTo(x(p.tMs), yKw(p.kw ?: 0f)) }
            trait.color = cAccent
            trait.strokeWidth = dp(2.5f)
            canvas.drawPath(chemin, trait)
            // Batterie.
            chemin.reset()
            var premier = true
            pts.forEach { p ->
                val s = p.soc ?: return@forEach
                if (premier) chemin.moveTo(x(p.tMs), ySoc(s)) else chemin.lineTo(x(p.tMs), ySoc(s))
                premier = false
            }
            trait.color = cVert
            canvas.drawPath(chemin, trait)
        }

        // Estimation : du niveau actuel à la limite, à l'heure de fin.
        val soc = d.socActuel
        val lim = d.limitePct
        if (!d.finie && d.restantMs != null && soc != null && lim != null && lim > soc) {
            trait.color = cVert
            trait.alpha = 140
            trait.strokeWidth = dp(2f)
            trait.pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(5f)), 0f)
            canvas.drawLine(xMaintenant, ySoc(soc), droite, ySoc(lim.toFloat()), trait)
            trait.pathEffect = null
            trait.alpha = 255
        }
        // Maintenant : un trait vertical et les deux points du moment.
        if (!d.finie) {
            trait.color = cTexte2
            trait.strokeWidth = dp(1f)
            trait.pathEffect = DashPathEffect(floatArrayOf(dp(2f), dp(4f)), 0f)
            canvas.drawLine(xMaintenant, haut, xMaintenant, bas, trait)
            trait.pathEffect = null
            pts.lastOrNull()?.kw?.let {
                plein.color = cTexte
                canvas.drawCircle(xMaintenant, yKw(it), dp(4f), plein)
            }
            soc?.let {
                plein.color = cVert
                canvas.drawCircle(xMaintenant, ySoc(it), dp(4f), plein)
            }
        }
    }

    private fun entier(v: Float) = Math.round(v).toString()

    private companion object {
        val ECHELLES = floatArrayOf(10f, 20f, 50f, 100f, 150f, 200f, 250f, 300f, 400f)

        /** Haut de l'axe des kW : le premier palier au-dessus du maximum, avec un peu d'air. */
        fun echelleKw(max: Float): Float = ECHELLES.firstOrNull { it >= max * 1.08f } ?: ECHELLES.last()
    }
}
