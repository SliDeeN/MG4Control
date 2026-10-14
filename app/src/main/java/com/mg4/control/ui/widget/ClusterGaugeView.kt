package com.mg4.control.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.mg4.control.R
import com.mg4.control.model.ClusterGauge
import com.mg4.control.model.ClusterModule
import com.mg4.control.model.DriveMode
import com.mg4.control.model.GaugeLevel
import com.mg4.control.model.GaugeState
import com.mg4.control.model.GaugeUnit
import com.mg4.control.service.OverlayKit
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Module de la zone centrale du combiné : la jauge de consommation (A) ou de puissance (B).
 *
 * Dessiné sur un carré de [ClusterGauge.SIDE_PX] unités mis à l'échelle de la vue : c'est le
 * carré de l'appli du combiné, en px. Toujours sombre — le combiné l'est, quel que soit le thème
 * de l'application —, et sur le fond même de cette appli ([FOND]) : le module se fond dans le
 * combiné au lieu d'y faire une boîte. Textes et chiffres dans la langue du contexte reçu.
 *
 * L'anneau glisse d'une valeur à la suivante : les relevés arrivent chaque seconde, une jauge qui
 * sauterait de l'une à l'autre se lirait mal du coin de l'œil.
 */
class ClusterGaugeView(context: Context) : View(context) {

    /** Ce que montre le module. Puissance lissée, positive en décharge ; `null` = illisible. */
    data class Contenu(
        val module: ClusterModule,
        val kw: Float?,
        val kmh: Float?,
        val mode: DriveMode?,
        val moyenneWhKm: Float?,
        val recupWh: Float?,
        val batterie: Float?,
    )

    private var contenu: Contenu? = null
    private var etat: GaugeState? = null
    private var partAffichee = 0f
    private var animation: ValueAnimator? = null

    // Palette sombre, quel que soit le mode de l'application.
    private val sombre = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
    })
    private val cPiste = sombre.getColor(R.color.dash_border)
    private val cTexte = sombre.getColor(R.color.text_primary)
    private val cTexte2 = sombre.getColor(R.color.text_secondary)
    private val cAccent = sombre.getColor(R.color.dash_accent)

    private val locale: Locale = context.resources.configuration.locales[0]

    private val trait = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = EPAISSEUR
    }
    private val plein = Paint(Paint.ANTI_ALIAS_FLAG)
    private val texte = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val moyen = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val cadre = RectF()

    fun montrer(c: Contenu) {
        contenu = c
        val nouveau = c.kw?.let {
            if (c.module == ClusterModule.POWER) ClusterGauge.power(it) else ClusterGauge.consumption(it, c.kmh)
        }
        etat = nouveau
        animation?.cancel()
        animation = ValueAnimator.ofFloat(partAffichee, nouveau?.fraction ?: 0f).apply {
            duration = ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                partAffichee = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animation?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Tout le carré, avant même le premier relevé : rien de la page de l'appli SAIC, dessous,
        // ne doit transparaître.
        canvas.drawColor(FOND)
        val c = contenu ?: return
        val echelle = minOf(width, height) / ClusterGauge.SIDE_PX.toFloat()
        canvas.save()
        canvas.scale(echelle, echelle)

        c.mode?.let { pastille(canvas, it) }

        if (c.module == ClusterModule.POWER) puissance(canvas, c) else consommation(canvas, c)
        canvas.restore()
    }

    // ── A : anneau de consommation ───────────────────────────────────────────

    private fun consommation(canvas: Canvas, c: Contenu) {
        val cx = COTE / 2f
        val cy = 116f
        arc(canvas, cx, cy, 135f, 270f, cPiste)
        val e = etat
        if (e != null) arc(canvas, cx, cy, 135f, 270f * partAffichee, couleur(e.level))

        val (chiffre, unite) = when (e?.unit) {
            null -> "–" to ""
            GaugeUnit.WH_PER_KM -> e.value.roundToInt().toString() to "Wh/km"
            GaugeUnit.KW_REGEN -> "↓" + ClusterGauge.formatKw(e.value, locale) to context.getString(R.string.cluster_regen_kw)
            GaugeUnit.KW_STOPPED -> ClusterGauge.formatKw(e.value, locale) to context.getString(R.string.cluster_stopped_kw)
            GaugeUnit.KW -> ClusterGauge.formatKw(e.value, locale) to "kW"
        }
        val recup = e?.unit == GaugeUnit.KW_REGEN
        grandChiffre(canvas, chiffre, cx, cy + 16f, if (recup) cAccent else cTexte)
        petit(canvas, unite, cx, cy + 40f, 13f, if (recup) cAccent else cTexte2)

        pied(canvas,
            context.getString(R.string.cluster_trip_avg), whKm(c.moyenneWhKm), cTexte,
            context.getString(R.string.cluster_regen), c.recupWh?.let { "+${it.roundToInt()} Wh" } ?: "–", cAccent)
    }

    // ── B : arc de puissance ─────────────────────────────────────────────────

    private fun puissance(canvas: Canvas, c: Contenu) {
        val cx = COTE / 2f
        val cy = 122f
        arc(canvas, cx, cy, 150f, 240f, cPiste)
        val e = etat
        // Zéro en haut ; la récupération remplit vers la gauche (balayage négatif).
        if (e != null) arc(canvas, cx, cy, 270f, 120f * partAffichee, couleur(e.level))
        trait.color = cTexte2
        trait.strokeWidth = 2f
        canvas.drawLine(cx, cy - RAYON - 8f, cx, cy - RAYON + 8f, trait)
        trait.strokeWidth = EPAISSEUR

        val gauche = point(cx, cy, 150f)
        val droite = point(cx, cy, 30f)
        petit(canvas, context.getString(R.string.cluster_regen), gauche.first + 4f, gauche.second + 24f, 11f, cAccent)
        petit(canvas, "kW", droite.first - 4f, droite.second + 24f, 11f, cTexte2)

        val recup = e != null && e.value < 0f
        val chiffre = e?.let { (if (recup) "−" else "") + ClusterGauge.formatKw(it.value, locale) } ?: "–"
        grandChiffre(canvas, chiffre, cx, cy + 14f, if (recup) cAccent else cTexte)
        val sous = when {
            e == null -> ""
            recup -> context.getString(R.string.cluster_regen_word)
            else -> c.kw?.let { ClusterGauge.whPerKm(it, c.kmh) }?.let { whKm(it) }
                ?: context.getString(R.string.cluster_stopped_word)
        }
        petit(canvas, sous, cx, cy + 38f, 13f, cTexte2)

        pied(canvas,
            context.getString(R.string.cluster_battery),
            c.batterie?.let { context.getString(R.string.autobri_percent, it.roundToInt()) } ?: "–", cTexte,
            context.getString(R.string.cluster_trip_avg), whKm(c.moyenneWhKm), cTexte)
    }

    // ── Pièces communes ──────────────────────────────────────────────────────

    private fun arc(canvas: Canvas, cx: Float, cy: Float, debut: Float, balayage: Float, couleur: Int) {
        if (kotlin.math.abs(balayage) < 0.5f) return
        trait.color = couleur
        cadre.set(cx - RAYON, cy - RAYON, cx + RAYON, cy + RAYON)
        canvas.drawArc(cadre, debut, balayage, false, trait)
    }

    private fun point(cx: Float, cy: Float, angle: Float): Pair<Float, Float> {
        val a = Math.toRadians(angle.toDouble())
        return (cx + RAYON * cos(a)).toFloat() to (cy + RAYON * sin(a)).toFloat()
    }

    private fun grandChiffre(canvas: Canvas, s: String, x: Float, y: Float, couleur: Int) {
        texte.typeface = moyen
        texte.textSize = if (s.length > 3) 46f else 54f
        texte.color = couleur
        texte.letterSpacing = 0f
        canvas.drawText(s, x, y, texte)
    }

    private fun petit(canvas: Canvas, s: String, x: Float, y: Float, taille: Float, couleur: Int) {
        if (s.isEmpty()) return
        texte.typeface = Typeface.DEFAULT
        texte.textSize = taille
        texte.color = couleur
        texte.letterSpacing = 0.04f
        canvas.drawText(s, x, y, texte)
    }

    /** Deux colonnes sous la jauge, séparées d'un filet : intitulé en petit, valeur dessous. */
    private fun pied(canvas: Canvas, l1: String, v1: String, c1: Int, l2: String, v2: String, c2: Int) {
        plein.color = cPiste
        canvas.drawRect(COTE / 2f - 0.5f, 206f, COTE / 2f + 0.5f, 244f, plein)
        listOf(Triple(70f, l1, v1 to c1), Triple(190f, l2, v2 to c2)).forEach { (x, l, v) ->
            petit(canvas, l, x, 220f, 11f, cTexte2)
            texte.typeface = moyen
            texte.textSize = 16f
            texte.color = v.second
            texte.letterSpacing = 0f
            canvas.drawText(v.first, x, 241f, texte)
        }
    }

    /** Pastille du mode de conduite, en haut à gauche : la teinte du mode, sur un fond atténué. */
    private fun pastille(canvas: Canvas, mode: DriveMode) {
        val teinte = OverlayKit.pastilleMode(sombre, mode).first
        val nom = mode.label.uppercase(Locale.ROOT)
        texte.typeface = moyen
        texte.textSize = 11f
        texte.letterSpacing = 0.09f
        val largeur = texte.measureText(nom) + 30f
        plein.color = Color.argb(64, Color.red(teinte), Color.green(teinte), Color.blue(teinte))
        cadre.set(14f, 14f, 14f + largeur, 36f)
        canvas.drawRoundRect(cadre, 11f, 11f, plein)
        plein.color = teinte
        canvas.drawCircle(26f, 25f, 3.5f, plein)
        texte.color = teinte
        texte.textAlign = Paint.Align.LEFT
        canvas.drawText(nom, 35f, 29.5f, texte)
        texte.textAlign = Paint.Align.CENTER
    }

    private fun whKm(v: Float?): String = v?.let { "${it.roundToInt()} Wh/km" } ?: "–"

    private fun couleur(niveau: GaugeLevel): Int = sombre.getColor(when (niveau) {
        GaugeLevel.ECO -> R.color.dash_eco
        GaugeLevel.ACCENT -> R.color.dash_accent
        GaugeLevel.WARN -> R.color.dash_warn
        GaugeLevel.DANGER -> R.color.dash_danger
    })

    private companion object {
        /**
         * Fond de l'appli SAIC du combiné (`screen_background_dark`). Celui de nos cartes, plus
         * clair, ressortait en rectangle sur le combiné (photo du 2026-10-14).
         */
        val FOND = Color.rgb(0x0C, 0x0C, 0x0C)
        val COTE = ClusterGauge.SIDE_PX.toFloat()
        const val RAYON = 88f
        const val EPAISSEUR = 13f
        /** Un peu moins que l'intervalle des relevés : l'anneau a fini de glisser quand le suivant arrive. */
        const val ANIMATION_MS = 800L
    }
}
