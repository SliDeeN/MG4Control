package com.mg4.control.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.mg4.control.R
import com.mg4.control.model.CurveBars
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Graphique du détail d'un trajet (2026-11-05, maquette B) : une barre par tronçon (largeur
 * choisie selon la longueur du trajet, [com.mg4.control.model.TripCurve.bars]),
 * colorée selon la consommation (sobre / normal / gourmand, récupération sous zéro), et deux
 * bandes facultatives sous les barres — vitesse et altitude — alignées sur les mêmes kilomètres,
 * chacune avec sa propre échelle (jamais deux échelles sur un même graphique).
 *
 * L'échelle de consommation est fixe ([LO]..[HI]) pour comparer les trajets d'un coup d'œil ; elle
 * ne s'élargit que si un tronçon en sort.
 */
class TripCurveView(context: Context) : View(context) {

    var curve: CurveBars? = null
        set(v) { field = v; requestLayout(); invalidate() }
    var showSpeed = false
        set(v) { field = v; requestLayout(); invalidate() }
    var showAltitude = false
        set(v) { field = v; requestLayout(); invalidate() }

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private val mainH = 120 * dp
    private val stripH = 40 * dp
    private val gap = 12 * dp
    private val axisH = 18 * dp
    private val right = 6 * dp
    private val top = 6 * dp

    private val texte = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.dash_text_lo); textSize = 11 * sp
    }
    /**
     * Marge des étiquettes de gauche : la plus large possible (« 888 km/h », « 8888 m »), et non plus
     * 44 dp fixes — « 93 km/h » y était coupé en « 3 km/h » (essai du 2026-11-09).
     */
    private val left = maxOf(44 * dp, listOf("888 km/h", "8888 m", "-60").maxOf { texte.measureText(it) } + 10 * dp)
    private val grille = Paint().apply { color = context.getColor(R.color.dash_border); strokeWidth = 1 * dp }
    private val barre = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trait = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * dp; strokeJoin = Paint.Join.ROUND
    }
    private val aire = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    /** Maximum du trajet dans les bandes vitesse et altitude. */
    private val pointille = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1 * dp
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(4 * dp, 4 * dp), 0f)
    }

    private val cSobre = context.getColor(R.color.dash_eco)
    private val cNormal = context.getColor(R.color.dash_accent)
    private val cGourmand = context.getColor(R.color.dash_warn)
    private val cRegen = assombrir(cSobre)
    private val cVitesse = context.getColor(R.color.text_secondary)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = top + mainH + (if (showSpeed) gap + stripH else 0f) + (if (showAltitude) gap + stripH else 0f) + axisH
        setMeasuredDimension(w, h.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val c = curve ?: return
        val points = c.bars
        if (points.isEmpty() || c.totalKm <= 0f) return
        val largeur = width - left - right
        val total = c.totalKm
        fun x(km: Float) = left + largeur * km / total
        // Largeur d'une barre entière, à l'écran ; la dernière peut être plus courte.
        val pas = largeur * c.stepKm / total
        val valeurs = points.map { it.kwhPer100 }
        val hi = max(HI, ceil(valeurs.max() / 5f) * 5f).coerceAtMost(80f)
        val lo = min(LO, floor(valeurs.min() / 5f) * 5f).coerceAtLeast(-60f)
        val bas = top + mainH
        fun y(v: Float) = top + mainH * (hi - v.coerceIn(lo, hi)) / (hi - lo)

        // Lignes de l'échelle : les repères habituels, et le bas et le haut quand l'échelle s'élargit.
        (listOf(lo, 0f, 10f, 20f, 30f, hi).distinct().filter { it in lo..hi }).forEach { v ->
            val yy = y(v)
            grille.alpha = if (v == 0f) 255 else 110
            canvas.drawLine(left, yy, width - right, yy, grille)
            canvas.drawText(entier(v), left - 6 * dp - texte.measureText(entier(v)), yy + 4 * dp, texte)
        }
        grille.alpha = 255

        val zero = y(0f)
        val ecart = if (pas >= 4 * dp) 1.5f * dp else 0f
        val r = RectF()
        valeurs.forEachIndexed { i, v ->
            barre.color = when {
                v < 0f -> cRegen
                v < SOBRE -> cSobre
                v <= NORMAL -> cNormal
                else -> cGourmand
            }
            val x0 = x(i * c.stepKm) + ecart / 2
            val x1 = x(min((i + 1) * c.stepKm, total)) - ecart / 2
            val yy = y(v)
            r.set(x0, min(yy, zero), max(x1, x0 + 1f), max(max(yy, zero), min(yy, zero) + 1 * dp))
            canvas.drawRoundRect(r, 1.5f * dp, 1.5f * dp, barre)
        }

        var haut = bas
        // Variante A (2026-11-09) : chaque bande à l'échelle du trajet, son maximum en pointillé.
        if (showSpeed) {
            val v = points.map { it.speedKmh }
            val pic = v.filterNotNull().maxOrNull()?.let { ceil(it) }
            // Plancher : un trajet en ville ne doit pas étirer ses petites variations sur toute la bande.
            val mx = max(VITESSE_MIN, pic ?: 0f)
            haut = bande(canvas, haut, v, 0f, mx, cVitesse, "${entier(mx)} km/h", "0", c, ::x, pic)
        }
        if (showAltitude) {
            val a = points.map { it.altitudeM }
            val connus = a.filterNotNull()
            var mn = if (connus.isEmpty()) 0f else floor(connus.min())
            var mx = if (connus.isEmpty()) 100f else ceil(connus.max())
            // Écart minimal : quelques mètres de bruit GPS ne doivent pas ressembler à une côte.
            if (mx - mn < ALTITUDE_ECART_MIN) {
                val milieu = (mx + mn) / 2f
                mn = floor(milieu - ALTITUDE_ECART_MIN / 2f)
                mx = mn + ALTITUDE_ECART_MIN
            }
            val pic = connus.maxOrNull()?.let { ceil(it) }
            haut = bande(canvas, haut, a, mn, mx, C_ALTITUDE, "${entier(mx)} m", "${entier(mn)} m", c, ::x, pic)
        }

        // Kilomètres : un pas rond, six repères au plus.
        val graduation = listOf(1f, 2f, 5f, 10f, 20f, 50f, 100f, 200f).firstOrNull { total / it <= 6f } ?: 500f
        var km = 0f
        while (km <= total + 0.01f) {
            val etiquette = "${entier(km)} km"
            val xe = (x(km) - texte.measureText(etiquette) / 2)
                .coerceIn(left, width - right - texte.measureText(etiquette))
            canvas.drawText(etiquette, xe, haut + axisH - 4 * dp, texte)
            km += graduation
        }
    }

    /**
     * Une bande sous les barres : aire et trait, coupés là où la valeur manque. Rend son bas.
     * [pic] : le maximum du trajet, tracé en pointillé de la couleur de la bande.
     */
    private fun bande(canvas: Canvas, depuis: Float, v: List<Float?>, mn: Float, mx: Float, couleur: Int,
                      etiquetteHaut: String, etiquetteBas: String, c: CurveBars, x: (Float) -> Float,
                      pic: Float? = null): Float {
        // Chaque valeur au milieu de sa barre.
        fun milieu(i: Int) = x((i * c.stepKm + min((i + 1) * c.stepKm, c.totalKm)) / 2f)
        val debut = depuis + gap
        val bas = debut + stripH
        fun y(x: Float) = bas - stripH * ((x - mn) / (mx - mn)).coerceIn(0f, 1f)
        canvas.drawLine(left, bas, width - right, bas, grille)
        if (pic != null) {
            // En chemin, pas en drawLine : l'accélération graphique ignore les pointillés d'une ligne.
            pointille.color = couleur
            val yp = y(pic)
            canvas.drawPath(Path().apply { moveTo(left, yp); lineTo(width - right, yp) }, pointille)
        }
        canvas.drawText(etiquetteHaut, left - 6 * dp - texte.measureText(etiquetteHaut), debut + 10 * dp, texte)
        canvas.drawText(etiquetteBas, left - 6 * dp - texte.measureText(etiquetteBas), bas, texte)
        trait.color = couleur
        aire.color = couleur
        aire.alpha = 45
        // Un morceau par suite de valeurs connues.
        var i = 0
        while (i < v.size) {
            if (v[i] == null) { i++; continue }
            val ligne = Path()
            val surface = Path()
            surface.moveTo(milieu(i), bas)
            var j = i
            while (j < v.size && v[j] != null) {
                val xm = milieu(j)
                val yy = y(v[j]!!)
                if (j == i) ligne.moveTo(xm, yy) else ligne.lineTo(xm, yy)
                surface.lineTo(xm, yy)
                j++
            }
            surface.lineTo(milieu(j - 1), bas)
            surface.close()
            canvas.drawPath(surface, aire)
            canvas.drawPath(ligne, trait)
            i = j
        }
        return bas
    }

    private fun entier(v: Float) = Math.round(v).toString()

    private fun assombrir(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[2] *= 0.62f
        return Color.HSVToColor(hsv)
    }

    companion object {
        const val LO = -20f
        const val HI = 35f
        /** Seuils des couleurs, en kWh/100 km. */
        const val SOBRE = 14f
        const val NORMAL = 22f
        /** Haut minimal de la bande vitesse, en km/h. */
        const val VITESSE_MIN = 30f
        /** Écart minimal de la bande altitude, en mètres. */
        const val ALTITUDE_ECART_MIN = 10f
        /** Violet lisible sur les deux thèmes. */
        val C_ALTITUDE = Color.rgb(0x9B, 0x7F, 0xD4)
    }
}
