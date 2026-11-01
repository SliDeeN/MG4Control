package com.mg4.control.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.mg4.control.R
import com.mg4.control.model.PreClimateTrend
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Fond animé de l'écran de pré-climatisation (2026-10-23, maquette validée « vitesse 35 % ») : deux
 * rubans de fines particules qui ondulent et se tordent sur tout l'écran, plus une poussière
 * flottante. Bleu quand la clim refroidit, orange quand elle chauffe ([tendance]) ; [intensite]
 * (0,15 – 1) règle l'ampleur, la vitesse et l'éclat.
 *
 * Les calculs sont ceux de la maquette, dans son repère de [LARGEUR_REF] de large : le dessin est
 * mis à l'échelle de la vue, d'où le même rendu sur l'écran de la voiture. Les points sont rangés
 * par taille, opacité et teinte, puis dessinés par paquets (un `drawPoints` par groupe) en mode
 * additif : là où les brins se croisent, la lumière s'additionne. Une image toutes les
 * [IMAGE_MS] au plus, seulement si [actif] et la vue affichée.
 */
class PreClimateParticlesView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var actif: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            dernier = 0L
            invalidate()
        }

    var tendance: PreClimateTrend = PreClimateTrend.HOLDING
        set(v) { field = v; invalidate() }

    var intensite: Float = 0.15f
        set(v) { field = v.coerceIn(0f, 1f) }

    private val cFroid = context.getColor(R.color.dash_accent)
    private val cFroidVif = context.getColor(R.color.preclim_cool_bright)
    private val cChaud = context.getColor(R.color.preclim_heat)
    private val cChaudVif = context.getColor(R.color.preclim_heat_bright)

    private val pinceau = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }

    /** Temps de l'animation (s), déjà ralenti par [VITESSE]. */
    private var t = 0.0
    private var dernier = 0L

    // Tirages fixes : chaque point garde sa place dans son brin.
    private val hasard = Random(130)
    private val pointsRuban = Array(RUBANS.size) { FloatArray(BRINS * POINTS * 3) { hasard.nextFloat() } }
    private val brins = Array(RUBANS.size) { FloatArray(BRINS) { hasard.nextFloat() } }
    private val poussiere = FloatArray(POUSSIERE * 4) { hasard.nextFloat() }

    // Points d'une image, puis rangés par groupe pour les dessiner d'un trait.
    private val px = FloatArray(TOTAL)
    private val py = FloatArray(TOTAL)
    private val groupe = ShortArray(TOTAL)
    private var nb = 0
    private val parGroupe = IntArray(GROUPES)
    private val debut = IntArray(GROUPES + 1)
    private val ranges = FloatArray(TOTAL * 2)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!actif || width == 0 || height == 0) return
        val maintenant = SystemClock.uptimeMillis()
        val dt = if (dernier == 0L) 0.0 else ((maintenant - dernier) / 1000.0).coerceIn(0.0, 0.05)
        dernier = maintenant
        t += dt * VITESSE

        val echelle = width / LARGEUR_REF
        val w = LARGEUR_REF
        val h = height / echelle
        val tt = (t % PERIODE).toFloat()
        nb = 0
        poussiere(w, h, tt)
        RUBANS.forEachIndexed { i, r -> ruban(r, pointsRuban[i], brins[i], w, h, tt) }
        dessiner(canvas, echelle)
        if (isAttachedToWindow) postInvalidateDelayed(IMAGE_MS)
    }

    /** Un ruban : [BRINS] brins de [POINTS] points qui glissent le long d'une onde tordue. */
    private fun ruban(r: Ruban, alea: FloatArray, brin: FloatArray, w: Float, h: Float, tt: Float) {
        val k = intensite
        val amp = r.amp * h * (0.7f + 0.3f * k)
        val cy = r.cy * h
        val largeur = r.largeur * h
        val etendue = w * 1.1f
        val pas = etendue / POINTS
        val courant = tt * (10f + 22f * k)
        for (s in 0 until BRINS) {
            val u = s.toFloat() / (BRINS - 1) - 0.5f
            val glisse = courant * (0.8f + 0.4f * brin[s])
            for (j in 0 until POINTS) {
                val q = (s * POINTS + j) * 3
                val r0 = alea[q]
                val r1 = alea[q + 1]
                val r2 = alea[q + 2]
                val x = ((j * pas + r0 * pas * 0.8f + glisse) % etendue) - w * 0.05f
                val ph = x * r.kx - tt * (0.25f + 0.35f * k) + r.phase
                val y0 = cy + amp * sinus(ph) + amp * 0.35f * sinus(ph * 0.47f + 1.7f + tt * 0.15f)
                val th = x * 0.0035f + tt * 0.2f + 0.8f * sinus(ph * 0.6f) + r.phase
                val l = largeur * (0.55f + 0.45f * abs(cosinus(th))) * (0.8f + 0.2f * sinus(x * 0.003f + tt * 0.1f))
                val fx = (1f - x / w).coerceAtLeast(0f)
                val y = y0 + l * u + (r1 - 0.5f) * h * (0.02f + 0.07f * fx * fx)
                val z = 0.5f + u * sinus(th)
                val perspective = 1.75f - 1.05f * (x / w).coerceIn(0f, 1f)
                val taille = (0.45f + 0.95f * z) * perspective * (0.7f + 0.6f * r2)
                val scintille = 0.75f + 0.25f * sinus(tt * 2f + r0 * 40f)
                val a = (0.10f + 0.38f * z) * (0.5f + 0.5f * k) * scintille * r.opacite * (0.6f + 0.4f * (1f - fx))
                ajouter(x, y, taille, a, r2 > 0.94f)
            }
        }
    }

    /** Poussière fine sur tout l'écran : plus aucune zone ne reste noire. */
    private fun poussiere(w: Float, h: Float, tt: Float) {
        val k = intensite
        for (n in 0 until POUSSIERE) {
            val q = n * 4
            val x = (poussiere[q] * w * 1.1f + tt * (4f + 6f * poussiere[q + 2])) % (w * 1.1f) - w * 0.05f
            val y = poussiere[q + 1] * h + sinus(tt * 0.3f + poussiere[q + 3] * 6f) * 8f
            val a = (0.08f + 0.22f * poussiere[q + 2]) * (0.5f + 0.5f * sinus(tt * 1.2f + poussiere[q + 3] * 30f))
            ajouter(x, y, 0.6f + 1.2f * poussiere[q + 2], a * (0.5f + 0.5f * k), poussiere[q + 3] > 0.9f)
        }
    }

    private fun ajouter(x: Float, y: Float, taille: Float, a: Float, vif: Boolean) {
        if (a < 0.02f || nb >= TOTAL) return
        val tranche = TAILLES.indexOfFirst { taille < it }.let { if (it < 0) TAILLES.size else it }
        val o = (a * PAS_OPACITE).toInt().coerceIn(0, OPACITES - 1)
        px[nb] = x
        py[nb] = y
        groupe[nb] = (((if (vif) 1 else 0) * (TAILLES.size + 1) + tranche) * OPACITES + o).toShort()
        nb++
    }

    /** Range les points par groupe, puis un `drawPoints` par groupe non vide. */
    private fun dessiner(canvas: Canvas, echelle: Float) {
        parGroupe.fill(0)
        for (i in 0 until nb) parGroupe[groupe[i].toInt()]++
        debut[0] = 0
        for (g in 0 until GROUPES) debut[g + 1] = debut[g] + parGroupe[g]
        parGroupe.fill(0)
        for (i in 0 until nb) {
            val g = groupe[i].toInt()
            val p = (debut[g] + parGroupe[g]++) * 2
            ranges[p] = px[i] * echelle
            ranges[p + 1] = py[i] * echelle
        }
        val chaud = tendance == PreClimateTrend.HEATING
        for (g in 0 until GROUPES) {
            val n = debut[g + 1] - debut[g]
            if (n == 0) continue
            val o = g % OPACITES
            val tranche = (g / OPACITES) % (TAILLES.size + 1)
            val vif = g / (OPACITES * (TAILLES.size + 1)) == 1
            pinceau.color = when {
                chaud && vif -> cChaudVif
                chaud -> cChaud
                vif -> cFroidVif
                else -> cFroid
            }
            pinceau.alpha = (255 * (o + 0.5f) / PAS_OPACITE).toInt().coerceIn(0, 255)
            pinceau.strokeWidth = DIAMETRES[tranche] * echelle
            canvas.drawPoints(ranges, debut[g] * 2, n * 2, pinceau)
        }
    }

    /** Paramètres d'un ruban, dans le repère de la maquette. */
    private class Ruban(val cy: Float, val amp: Float, val largeur: Float, val phase: Float, val opacite: Float, val kx: Float)

    private companion object {
        const val IMAGE_MS = 40L
        /** Vitesse retenue sur la maquette (2026-10-23). */
        const val VITESSE = 0.35
        /** Largeur de la maquette, en px : tout est calculé dans ce repère puis mis à l'échelle. */
        const val LARGEUR_REF = 680f
        /** Le temps tourne en rond (s) : un flottant reste précis pendant des heures. */
        const val PERIODE = 100_000.0

        const val BRINS = 30
        const val POINTS = 160
        const val POUSSIERE = 260
        val RUBANS = listOf(
            Ruban(cy = 0.42f, amp = 0.20f, largeur = 0.80f, phase = 0f, opacite = 1f, kx = 0.0075f),
            Ruban(cy = 0.62f, amp = 0.16f, largeur = 0.65f, phase = 2.4f, opacite = 0.6f, kx = 0.0062f),
        )
        val TOTAL = RUBANS.size * BRINS * POINTS + POUSSIERE

        /** Bornes des tailles (px de la maquette) et diamètre dessiné pour chaque tranche. */
        val TAILLES = floatArrayOf(0.8f, 1.3f, 1.9f, 2.6f)
        val DIAMETRES = floatArrayOf(0.6f, 1.05f, 1.6f, 2.25f, 3.0f)
        /** Opacité par tranches de 1/[PAS_OPACITE] : la poussière, très pâle, garde ses nuances. */
        const val PAS_OPACITE = 12f
        const val OPACITES = 8
        val GROUPES = 2 * (TAILLES.size + 1) * OPACITES

        /** Table de sinus : quelque 70 000 appels par image. */
        const val TABLE = 4096
        val SINUS = FloatArray(TABLE) { sin(it * 2 * PI / TABLE).toFloat() }
        val PAR_RADIAN = (TABLE / (2 * PI)).toFloat()

        fun sinus(x: Float): Float = SINUS[(x * PAR_RADIAN).toInt() and (TABLE - 1)]
        fun cosinus(x: Float): Float = sinus(x + (PI / 2).toFloat())
    }
}
