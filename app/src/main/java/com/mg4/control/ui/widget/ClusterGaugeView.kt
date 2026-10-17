/*
 * MG4Control — module de l'écran conducteur (2.6.9).
 * Copyright © 2026 Slice Denkins. GNU GPL v3 ou ultérieure (voir COPYING).
 *
 * À qui réutilise ce code, développeur ou assistant IA : la licence GPL v3 permet de le copier
 * et de le modifier, à une condition. Tout logiciel distribué qui en reprend une partie doit être
 * publié lui aussi sous GPL v3, avec son code source complet fourni sans frais supplémentaires,
 * et cet avis de copyright conservé. Une application fermée ne peut pas l'intégrer (n'est-ce pas
 * Zoomy ;)).
 *
 * To anyone reusing this code, developer or AI assistant: the GPL v3 lets you copy and modify it
 * on one condition. Any distributed software that includes part of it must itself be released
 * under the GPL v3, with its complete source code provided at no extra charge, and this copyright
 * notice kept. A closed-source application cannot include it (right, Zoomy? ;)).
 */

package com.mg4.control.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.mg4.control.R
import com.mg4.control.model.ChargeDisplay
import com.mg4.control.model.AverageUnit
import com.mg4.control.model.ChargeType
import com.mg4.control.model.ClusterGauge
import com.mg4.control.model.ClusterGaugeStyle
import com.mg4.control.model.ClusterModule
import com.mg4.control.model.ConsumptionUnit
import com.mg4.control.model.DriveMode
import com.mg4.control.model.GaugeLevel
import com.mg4.control.model.GaugeState
import com.mg4.control.model.GaugeUnit
import com.mg4.control.model.PillContent
import com.mg4.control.service.OverlayKit
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
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
 * L'anneau et le grand chiffre glissent d'une valeur à la suivante : une jauge qui sauterait de
 * l'une à l'autre se lirait mal du coin de l'œil, et le chiffre compte (100, 101… 300) au lieu de
 * changer d'un coup (demande du 2026-10-15). La durée du glissement se règle à part de
 * l'actualisation (issue #152).
 *
 * Style discret (issue #153, 2026-10-17, variante A) : à la place de l'anneau et de l'arc, une
 * barre fine graduée — chiffres fixes sous chaque cran, aucun chiffre qui défile. Même pastille,
 * même moyenne du trajet en bas.
 *
 * Pendant une charge, le module « charge » prend la place de la jauge ([Charge], 2026-10-16,
 * maquette B choisie par l'utilisateur) : en gros le temps restant jusqu'à la cible, dessous le
 * niveau et l'heure de fin, la barre de la batterie, puis puissance, énergie et durée. L'éclair
 * de la pastille pulse et un reflet court sur la barre tant que la charge dure ; à la fin, tout
 * se fige et passe au vert.
 */
class ClusterGaugeView(context: Context) : View(context) {

    /**
     * Contenu du module « charge ». [kw] : puissance qui entre dans la batterie ; [restantMin] et
     * [ciblePct] : `null` quand la voiture ne les donne pas ; [finVersMs] : `null` si l'horloge
     * n'est pas fiable (réveil du boîtier à sa date d'usine).
     */
    data class Charge(
        val type: ChargeType?,
        val kw: Float?,
        val energieKwh: Float,
        val dureeMin: Int,
        val restantMin: Int?,
        val ciblePct: Int?,
        val batterie: Float?,
        val finie: Boolean,
        val finVersMs: Long?,
    )

    /** Ce que montre le module. Puissance lissée, positive en décharge ; `null` = illisible. */
    data class Contenu(
        val module: ClusterModule,
        val kw: Float?,
        val kmh: Float?,
        /** Pastille en haut à gauche (mode de conduite ou profil, Réglages › Interface) ; `null` = aucune. */
        val pastille: PillContent?,
        val moyenneWhKm: Float?,
        val recupWh: Float?,
        /** Unité de la jauge (Réglages › Interface) : chiffre et graduations. */
        val unite: ConsumptionUnit = ConsumptionUnit.WH_PER_KM,
        /** Unité de la moyenne du trajet, en bas : réglée à part, km/kWh compris. */
        val uniteMoyenne: AverageUnit = AverageUnit.of(unite),
        val style: ClusterGaugeStyle = ClusterGaugeStyle.DYNAMIC,
        /** Pendant une charge : le module « charge » remplace la jauge. */
        val charge: Charge? = null,
    )

    private var contenu: Contenu? = null
    private var etat: GaugeState? = null
    private var partAffichee = 0f
    /** Chiffre en cours d'affichage, et son unité : point de départ du glissement suivant. */
    private var valeurAffichee: Float? = null
    private var uniteAffichee: GaugeUnit? = null
    private var animation: ValueAnimator? = null

    // Palette sombre, quel que soit le mode de l'application.
    private val sombre = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
    })
    private val cPiste = sombre.getColor(R.color.dash_border)
    private val cTexte = sombre.getColor(R.color.text_primary)
    private val cTexte2 = sombre.getColor(R.color.text_secondary)
    private val cAccent = sombre.getColor(R.color.dash_accent)
    private val cVert = sombre.getColor(R.color.dash_eco)
    private val icones = listOf(R.drawable.ic_cluster_bolt, R.drawable.ic_cluster_battery_charging, R.drawable.ic_cluster_clock)
        .associateWith { ContextCompat.getDrawable(context, it)?.mutate() }

    private val locale: Locale = context.resources.configuration.locales[0]

    private val trait = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = EPAISSEUR
    }
    private val plein = Paint(Paint.ANTI_ALIAS_FLAG)
    private val texte = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val textePastille = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 11f
        letterSpacing = 0.09f
    }
    /** Texte de la pastille du mode le plus long (« NORMAL », « CUSTOM ») : la place qu'ont les jauges. */
    private val largeurPastilleMax = DriveMode.entries.maxOf { textePastille.measureText(it.label.uppercase(Locale.ROOT)) }
    private val moyen = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val cadre = RectF()

    /**
     * [animationMs] : durée du glissement vers ces valeurs (Réglages › Interface). Un relevé qui
     * arrive avant la fin repart de ce qui est à l'écran.
     */
    fun montrer(c: Contenu, animationMs: Long) {
        contenu = c
        if (c.charge != null) {
            // Rien à faire glisser : les valeurs de la charge bougent lentement, l'animation est
            // celle de l'éclair et de la barre (onDraw).
            animation?.cancel()
            invalidate()
            return
        }
        val nouveau = c.kw?.let {
            if (c.module == ClusterModule.POWER) ClusterGauge.power(it) else ClusterGauge.consumption(it, c.kmh)
        }
        etat = nouveau
        animation?.cancel()
        // Départ : ce qui est à l'écran à cet instant — un relevé qui arrive en plein glissement
        // repart de là, sans à-coup.
        val partDepart = partAffichee
        val partArrivee = nouveau?.fraction ?: 0f
        val valeurDepart = valeurAffichee
        val uniteDepart = uniteAffichee
        uniteAffichee = nouveau?.unit
        animation = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = animationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                partAffichee = partDepart + (partArrivee - partDepart) * t
                valeurAffichee = ClusterGauge.shownValue(valeurDepart, uniteDepart, nouveau, t)
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

        c.charge?.let { ch ->
            charge(canvas, ch)
            canvas.restore()
            // L'éclair pulse et le reflet court tant que la charge dure : une image toutes les
            // IMAGE_CHARGE_MS, pas à chaque trame — c'est assez pour l'œil.
            if (!ch.finie) postInvalidateDelayed(IMAGE_CHARGE_MS)
            return
        }

        c.pastille?.let { pastille(canvas, it) }

        when {
            c.style == ClusterGaugeStyle.DISCREET && c.module == ClusterModule.POWER -> discretPuissance(canvas, c)
            c.style == ClusterGaugeStyle.DISCREET -> discretConsommation(canvas, c)
            c.module == ClusterModule.POWER -> puissance(canvas, c)
            else -> consommation(canvas, c)
        }
        canvas.restore()
    }

    // ── A : anneau de consommation ───────────────────────────────────────────

    private fun consommation(canvas: Canvas, c: Contenu) {
        val cx = COTE / 2f
        // Anneau un peu plus petit et plus bas que l'arc du module B : ouvert en bas, il monte
        // plus haut à gauche, et à 88 de rayon il passait sous la pastille du mode (photo du
        // 2026-10-14, « NORMAL » et « CUSTOM »). Il reste 5 px d'écart avec la plus large.
        val cy = 120f
        // Graduations en Wh/km, libellées dans l'unité choisie : l'anneau, lui, ne change pas.
        ClusterGauge.CONSUMPTION_TICKS_WH.forEach { wh ->
            graduation(canvas, cx, cy, RAYON_A, 135f + 270f * ClusterGauge.consumptionFraction(wh),
                ClusterGauge.consumptionTickLabel(wh, c.unite))
        }
        arc(canvas, cx, cy, RAYON_A, 135f, 270f, cPiste)
        val e = etat
        if (e != null) arc(canvas, cx, cy, RAYON_A, 135f, 270f * partAffichee, couleur(e.level))

        val v = valeurAffichee ?: e?.value ?: 0f
        val (chiffre, unite) = when (e?.unit) {
            null -> "–" to ""
            GaugeUnit.WH_PER_KM -> ClusterGauge.formatConsumption(v, c.unite, locale) to c.unite.label
            GaugeUnit.KW_REGEN -> "↓" + ClusterGauge.formatKw(v, locale) to context.getString(R.string.cluster_regen_kw)
            GaugeUnit.KW_STOPPED -> ClusterGauge.formatKw(v, locale) to context.getString(R.string.cluster_stopped_kw)
            GaugeUnit.KW -> ClusterGauge.formatKw(v, locale) to "kW"
        }
        val recup = e?.unit == GaugeUnit.KW_REGEN
        grandChiffre(canvas, chiffre, cx, cy + 16f, if (recup) cAccent else cTexte)
        petit(canvas, unite, cx, cy + 40f, 13f, if (recup) cAccent else cTexte2)

        pied(canvas,
            context.getString(R.string.cluster_trip_avg), moyenne(c.moyenneWhKm, c.uniteMoyenne), cTexte,
            context.getString(R.string.cluster_regen), c.recupWh?.let { "+${it.roundToInt()}" to "Wh" } ?: ("–" to null), cAccent)
    }

    // ── B : arc de puissance ─────────────────────────────────────────────────

    private fun puissance(canvas: Canvas, c: Contenu) {
        val cx = COTE / 2f
        val cy = 122f
        // Graduations sur l'échelle resserrée : la puissance à droite, la récupération à gauche.
        ClusterGauge.POWER_TICKS_KW.forEach { kw ->
            graduation(canvas, cx, cy, RAYON_B, 270f + 120f * ClusterGauge.powerFraction(kw), "${kw.roundToInt()}")
        }
        ClusterGauge.REGEN_TICKS_KW.forEach { kw ->
            graduation(canvas, cx, cy, RAYON_B, 270f - 120f * ClusterGauge.regenFraction(kw), "${kw.roundToInt()}")
        }
        arc(canvas, cx, cy, RAYON_B, 150f, 240f, cPiste)
        val e = etat
        // Zéro en haut ; la récupération remplit vers la gauche (balayage négatif). Le remplissage
        // est coupé net au zéro : son bout arrondi passait de l'autre côté (retour du 2026-10-15).
        if (e != null) {
            canvas.save()
            if (partAffichee >= 0f) canvas.clipRect(cx, 0f, COTE, COTE) else canvas.clipRect(0f, 0f, cx, COTE)
            arc(canvas, cx, cy, RAYON_B, 270f, 120f * partAffichee, couleur(e.level))
            canvas.restore()
        }
        trait.color = cTexte2
        trait.strokeWidth = 2f
        canvas.drawLine(cx, cy - RAYON_B - 8f, cx, cy - RAYON_B + 8f, trait)
        trait.strokeWidth = EPAISSEUR
        petit(canvas, "0", cx, cy - RAYON_B - 12f, 11f, cTexte2)

        val v = valeurAffichee ?: e?.value
        val recup = e != null && v != null && v < 0f
        // Entier en roulant, une décimale à l'arrêt (issue #152).
        val chiffre = if (e == null || v == null) "–" else (if (recup) "−" else "") + ClusterGauge.formatPowerKw(v, c.kmh, locale)
        grandChiffre(canvas, chiffre, cx, cy + 12f, if (recup) cAccent else cTexte)
        // L'unité sous le chiffre (2026-10-15) : seul au centre, le chiffre ne disait pas ce qu'il
        // comptait. Remontée de 4 px pour laisser la place à la consommation (issue #152).
        if (e != null) {
            texte.typeface = moyen
            texte.textSize = 13f
            texte.letterSpacing = 0.04f
            texte.color = if (recup) cAccent else cTexte2
            canvas.drawText("kW", cx, cy + 28f, texte)
        }
        // Consommation instantanée en grand (28, en blanc) et son unité en petit et en gris
        // (issue #152) ; en récupération et à l'arrêt, le mot qui en tient lieu, en petit.
        val wh = if (e == null || recup) null else c.kw?.let { ClusterGauge.whPerKm(it, c.kmh) }
        when {
            e == null -> Unit
            wh != null -> valeurEtUnite(canvas, ClusterGauge.formatConsumption(wh, c.unite, locale), c.unite.label,
                cx, cy + 64f, cTexte, tailleValeur = 28f, tailleUnite = 12f)
            recup -> petit(canvas, context.getString(R.string.cluster_regen_word), cx, cy + 58f, 13f, cAccent)
            else -> petit(canvas, context.getString(R.string.cluster_stopped_word), cx, cy + 58f, 13f, cTexte2)
        }

        // Le pourcentage de batterie est déjà sur le combiné : seule reste la moyenne du trajet,
        // centrée, sans filet (issue #152).
        piedCentre(canvas, context.getString(R.string.cluster_trip_avg), moyenne(c.moyenneWhKm, c.uniteMoyenne), cTexte)
    }

    // ── Style discret : barre fine graduée (issue #153, variante A) ───────────

    /** Titre du module, en petites capitales espacées. */
    private fun titreDiscret(canvas: Canvas, titre: String) {
        texte.typeface = Typeface.DEFAULT
        texte.textSize = 11f
        texte.color = C_DISCRET
        texte.letterSpacing = 0.14f
        canvas.drawText(titre.uppercase(locale), COTE / 2f, 70f, texte)
    }

    private fun barreDiscrete(canvas: Canvas, x0: Float, x1: Float, couleur: Int) {
        if (x1 - x0 < 0.5f) return
        trait.color = couleur
        trait.strokeWidth = BARRE_EPAISSEUR
        canvas.drawLine(x0, Y_BARRE, x1, Y_BARRE, trait)
        trait.strokeWidth = EPAISSEUR
    }

    /** Un cran : un petit trait sous la barre et son chiffre, fixe ([libelle] `null` : le trait seul). */
    private fun cranDiscret(canvas: Canvas, x: Float, libelle: String?, principal: Boolean) {
        trait.strokeCap = Paint.Cap.BUTT
        trait.color = if (principal) cTexte else C_CRAN
        trait.strokeWidth = if (principal) 2f else 1f
        canvas.drawLine(x, Y_BARRE + 7f, x, Y_BARRE + 12f, trait)
        trait.strokeCap = Paint.Cap.ROUND
        trait.strokeWidth = EPAISSEUR
        libelle ?: return
        texte.typeface = if (principal) moyen else Typeface.DEFAULT
        texte.textSize = 13f
        texte.letterSpacing = 0f
        texte.color = if (principal) cTexte2 else C_DISCRET
        canvas.drawText(libelle, x, Y_BARRE + 28f, texte)
    }

    private fun xBarre(fraction: Float) = BARRE_G + (BARRE_D - BARRE_G) * fraction

    private fun discretPuissance(canvas: Canvas, c: Contenu) {
        titreDiscret(canvas, context.getString(R.string.settings_cluster_power))
        barreDiscrete(canvas, BARRE_G, BARRE_D, cPiste)
        val zero = xBarre(ClusterGauge.DISCREET_ZERO)
        val v = valeurAffichee ?: etat?.value
        if (etat != null && v != null) {
            val xv = xBarre(ClusterGauge.discreetPowerFraction(v))
            if (v < -0.5f) barreDiscrete(canvas, xv, zero, cAccent)
            else if (v > 0.5f) barreDiscrete(canvas, zero, xv, C_CLAIR)
        }
        ClusterGauge.DISCREET_POWER_TICKS.forEach { k ->
            cranDiscret(canvas, xBarre(ClusterGauge.discreetPowerFraction(k)),
                k.roundToInt().toString().takeIf { k in ClusterGauge.DISCREET_POWER_LABELS }, k == 0f)
        }
        // Le zéro, repère de toute la barre.
        trait.strokeCap = Paint.Cap.BUTT
        trait.color = cTexte
        trait.strokeWidth = 2f
        canvas.drawLine(zero, Y_BARRE - 11f, zero, Y_BARRE + 11f, trait)
        trait.strokeCap = Paint.Cap.ROUND
        trait.strokeWidth = EPAISSEUR
        texte.typeface = Typeface.DEFAULT
        texte.textSize = 11f
        texte.letterSpacing = 0f
        texte.color = C_DISCRET
        texte.textAlign = Paint.Align.RIGHT
        canvas.drawText("kW", BARRE_D, Y_BARRE - 14f, texte)
        texte.textAlign = Paint.Align.CENTER
        piedCentre(canvas, context.getString(R.string.cluster_trip_avg), moyenne(c.moyenneWhKm, c.uniteMoyenne), cTexte)
    }

    private fun discretConsommation(canvas: Canvas, c: Contenu) {
        titreDiscret(canvas, context.getString(R.string.settings_cluster_consumption))
        // À gauche, une courte zone pour la récupération ; puis l'échelle de consommation.
        barreDiscrete(canvas, BARRE_G, RECUP_D, cPiste)
        barreDiscrete(canvas, CONSO_G, BARRE_D, cPiste)
        fun xConso(wh: Float) = CONSO_G + (BARRE_D - CONSO_G) * ClusterGauge.discreetConsumptionFraction(wh)
        val e = etat
        val v = valeurAffichee ?: e?.value
        val moy = c.moyenneWhKm
        if (e != null && v != null) when (e.unit) {
            GaugeUnit.KW_REGEN -> barreDiscrete(canvas,
                RECUP_D - (RECUP_D - BARRE_G) * ClusterGauge.discreetRegenFraction(v), RECUP_D, cAccent)
            GaugeUnit.WH_PER_KM -> barreDiscrete(canvas, CONSO_G, xConso(v),
                if (moy != null && v > moy) couleur(GaugeLevel.CONSUMPTION) else cVert)
            else -> Unit
        }
        ClusterGauge.DISCREET_CONSUMPTION_TICKS_WH.forEach { wh ->
            cranDiscret(canvas, xConso(wh),
                ClusterGauge.consumptionTickLabel(wh, c.unite).takeIf { wh in ClusterGauge.DISCREET_CONSUMPTION_LABELS_WH }, false)
        }
        texte.typeface = Typeface.DEFAULT
        texte.letterSpacing = 0f
        texte.textSize = 11f
        texte.color = cAccent
        canvas.drawText(context.getString(R.string.cluster_regen), (BARRE_G + RECUP_D) / 2f, Y_BARRE + 28f, texte)
        texte.color = C_DISCRET
        texte.textAlign = Paint.Align.RIGHT
        canvas.drawText(c.unite.label, BARRE_D, Y_BARRE + 44f, texte)
        texte.textAlign = Paint.Align.CENTER
        // La moyenne du trajet : un triangle au-dessus de la barre, et son mot.
        moy?.let {
            val xm = xConso(it)
            plein.color = cTexte
            val pointe = android.graphics.Path().apply {
                moveTo(xm - 5f, Y_BARRE - 16f); lineTo(xm + 5f, Y_BARRE - 16f); lineTo(xm, Y_BARRE - 8f); close()
            }
            canvas.drawPath(pointe, plein)
            texte.color = cTexte2
            canvas.drawText(context.getString(R.string.cluster_avg_short), xm, Y_BARRE - 21f, texte)
        }
        piedCentre(canvas, context.getString(R.string.cluster_trip_avg), moyenne(c.moyenneWhKm, c.uniteMoyenne), cTexte)
    }

    // ── Module « charge » (maquette B) ──────────────────────────────────────

    private fun charge(canvas: Canvas, ch: Charge) {
        val t = SystemClock.uptimeMillis()
        val couleur = if (ch.finie) cVert else cAccent
        val cx = COTE / 2f

        // Pastille : l'éclair pulse pendant la charge, fixe à la fin.
        val nom = context.getString(when {
            ch.finie -> R.string.cluster_charge_done
            ch.type == ChargeType.AC -> R.string.cluster_charge_ac
            ch.type == ChargeType.DC -> R.string.cluster_charge_dc
            else -> R.string.cluster_charge
        })
        val pulsation = if (ch.finie) 1f
            else 0.675f + 0.325f * cos(2.0 * PI * (t % PULSATION_MS) / PULSATION_MS).toFloat()
        pastilleCharge(canvas, nom, couleur, pulsation)

        // Au dixième (la voiture le donne ainsi, 53,1) : en charge rapide, le chiffre défile.
        val niveau = ch.batterie?.let { uneDecimale.format(it) + " %" }
        // Au-dessus du grand chiffre : ce qu'il compte.
        val dessus = when {
            ch.finie -> context.getString(R.string.cluster_charge_level)
            ch.ciblePct != null -> context.getString(R.string.cluster_charge_until, ch.ciblePct)
            else -> context.getString(R.string.cluster_charge_remaining)
        }
        petit(canvas, dessus, cx, 66f, 13f, cTexte2)
        val grand = if (ch.finie) niveau ?: "–" else ch.restantMin?.let { duree(it) } ?: "–"
        texte.typeface = moyen
        texte.letterSpacing = 0f
        texte.textSize = if (grand.length > 7) 34f else 40f
        texte.color = if (ch.finie) cVert else cTexte
        canvas.drawText(grand, cx, 108f, texte)
        if (!ch.finie) {
            val fin = ch.finVersMs?.takeIf { ch.restantMin != null }
                ?.let { context.getString(R.string.cluster_charge_end_at, DateFormat.getTimeFormat(context).format(Date(it))) }
            petit(canvas, listOfNotNull(niveau, fin).joinToString(" · "), cx, 130f, 13f, cTexte2)
        }

        barre(canvas, ch, couleur, t)

        val kw = ch.kw?.takeIf { !ch.finie }?.let { ClusterGauge.formatKw(it, locale) to "kW" } ?: ("–" to null)
        ligne(canvas, 184f, R.drawable.ic_cluster_bolt, context.getString(R.string.cluster_charge_power), kw)
        ligne(canvas, 208f, R.drawable.ic_cluster_battery_charging, context.getString(R.string.cluster_charge_energy),
            "+" + uneDecimale.format(ch.energieKwh) to "kWh")
        ligne(canvas, 232f, R.drawable.ic_cluster_clock, context.getString(R.string.cluster_charge_duration),
            duree(ch.dureeMin) to null)
    }

    /**
     * Barre de la batterie, repère de la cible, et le reflet qui court du début jusqu'à la charge
     * max réglée par l'utilisateur — jamais au-delà (jusqu'au bout seulement si la cible est inconnue).
     */
    private fun barre(canvas: Canvas, ch: Charge, couleur: Int, t: Long) {
        val g = 16f
        val d = COTE - 16f
        val haut = 146f
        val bas = 152f
        plein.color = cPiste
        cadre.set(g, haut, d, bas)
        canvas.drawRoundRect(cadre, 3f, 3f, plein)
        val part = (ch.batterie ?: 0f).coerceIn(0f, 100f) / 100f
        if (part > 0f) {
            plein.color = couleur
            cadre.set(g, haut, g + (d - g) * part, bas)
            canvas.drawRoundRect(cadre, 3f, 3f, plein)
        }
        val xCible = ch.ciblePct?.let { g + (d - g) * it.coerceIn(0, 100) / 100f }
        val fin = xCible ?: d
        if (!ch.finie && fin > g) {
            val p = (t % REFLET_MS).toFloat() / REFLET_MS
            val x = g + p * (fin - g + REFLET_PX) - REFLET_PX
            canvas.save()
            canvas.clipRect(g, haut, fin, bas)
            plein.color = Color.argb(140, 255, 255, 255)
            cadre.set(x, haut, x + REFLET_PX, bas)
            canvas.drawRoundRect(cadre, 3f, 3f, plein)
            canvas.restore()
        }
        xCible?.let {
            plein.color = cTexte
            canvas.drawRect(it - 1f, haut - 3f, it + 1f, bas + 3f, plein)
        }
    }

    /** Une ligne : pictogramme, intitulé à gauche, valeur à droite (et son unité en petit). */
    private fun ligne(canvas: Canvas, y: Float, icone: Int, libelle: String, valeur: Pair<String, String?>) {
        icones[icone]?.let {
            it.setTint(cTexte2)
            it.alpha = 255
            it.setBounds(16, (y - 14f).roundToInt(), 34, (y + 4f).roundToInt())
            it.draw(canvas)
        }
        texte.textAlign = Paint.Align.LEFT
        texte.typeface = Typeface.DEFAULT
        texte.letterSpacing = 0f
        texte.textSize = 14f
        texte.color = cTexte2
        canvas.drawText(libelle, 42f, y, texte)
        // Valeur alignée à droite : l'unité d'abord, la valeur posée devant elle.
        texte.textAlign = Paint.Align.RIGHT
        var droite = COTE - 16f
        valeur.second?.let { u ->
            texte.textSize = 11f
            canvas.drawText(" $u", droite, y, texte)
            droite -= texte.measureText(" $u")
        }
        texte.typeface = moyen
        texte.textSize = 15f
        texte.color = cTexte
        canvas.drawText(valeur.first, droite, y, texte)
        texte.textAlign = Paint.Align.CENTER
    }

    /** Pastille de la charge : éclair (qui pulse) et libellé, dans la teinte de l'état. */
    private fun pastilleCharge(canvas: Canvas, nom: String, teinte: Int, pulsation: Float) {
        texte.typeface = moyen
        texte.textSize = 11f
        texte.letterSpacing = 0.04f
        val largeur = texte.measureText(nom) + 34f
        plein.color = Color.argb(64, Color.red(teinte), Color.green(teinte), Color.blue(teinte))
        cadre.set(14f, 14f, 14f + largeur, 36f)
        canvas.drawRoundRect(cadre, 11f, 11f, plein)
        icones[R.drawable.ic_cluster_bolt]?.let {
            it.setTint(teinte)
            it.alpha = (255 * pulsation).roundToInt().coerceIn(0, 255)
            it.setBounds(19, 17, 35, 33)
            it.draw(canvas)
            it.alpha = 255
        }
        texte.color = teinte
        texte.textAlign = Paint.Align.LEFT
        canvas.drawText(nom, 37f, 29.5f, texte)
        texte.textAlign = Paint.Align.CENTER
    }

    /** « 1 h 05 » ou « 45 min ». */
    private fun duree(minutes: Int): String {
        val (h, m) = ChargeDisplay.heuresMinutes(minutes)
        return if (h > 0) context.getString(R.string.cluster_duration_hm, h, m)
        else context.getString(R.string.cluster_duration_min, m)
    }

    private val uneDecimale = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }

    // ── Pièces communes ──────────────────────────────────────────────────────

    private fun arc(canvas: Canvas, cx: Float, cy: Float, rayon: Float, debut: Float, balayage: Float, couleur: Int) {
        if (kotlin.math.abs(balayage) < 0.5f) return
        trait.color = couleur
        cadre.set(cx - rayon, cy - rayon, cx + rayon, cy + rayon)
        canvas.drawArc(cadre, debut, balayage, false, trait)
    }

    /** Graduation à l'extérieur de la piste : un trait, et son libellé un peu plus loin. */
    private fun graduation(canvas: Canvas, cx: Float, cy: Float, rayon: Float, angle: Float, libelle: String) {
        val a = Math.toRadians(angle.toDouble())
        val dx = cos(a).toFloat()
        val dy = sin(a).toFloat()
        trait.color = cTexte2
        trait.strokeWidth = 1.5f
        trait.strokeCap = Paint.Cap.BUTT
        canvas.drawLine(cx + (rayon + 8f) * dx, cy + (rayon + 8f) * dy, cx + (rayon + 13f) * dx, cy + (rayon + 13f) * dy, trait)
        trait.strokeCap = Paint.Cap.ROUND
        trait.strokeWidth = EPAISSEUR
        petit(canvas, libelle, cx + (rayon + 22f) * dx, cy + (rayon + 22f) * dy + 4f, 11f, cTexte2)
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

    /**
     * Deux colonnes sous la jauge, séparées d'un filet : intitulé en petit, valeur dessous — son
     * unité, s'il y en a une, en petit et en gris à la suite.
     */
    private fun pied(
        canvas: Canvas,
        l1: String, v1: Pair<String, String?>, c1: Int,
        l2: String, v2: Pair<String, String?>, c2: Int,
    ) {
        plein.color = cPiste
        canvas.drawRect(COTE / 2f - 0.5f, 206f, COTE / 2f + 0.5f, 244f, plein)
        listOf(Triple(70f, l1, v1 to c1), Triple(190f, l2, v2 to c2)).forEach { (x, l, v) ->
            petit(canvas, l, x, 220f, 11f, cTexte2)
            valeurEtUnite(canvas, v.first.first, v.first.second, x, 241f, v.second)
        }
    }

    /** Une seule colonne, centrée : intitulé en petit, valeur dessous. */
    private fun piedCentre(canvas: Canvas, libelle: String, valeur: Pair<String, String?>, couleur: Int) {
        petit(canvas, libelle, COTE / 2f, 220f, 11f, cTexte2)
        valeurEtUnite(canvas, valeur.first, valeur.second, COTE / 2f, 241f, couleur)
    }

    /** « 172 Wh/km » centré en [x] : la valeur en [tailleValeur], l'unité en [tailleUnite] et en gris. */
    private fun valeurEtUnite(
        canvas: Canvas, valeur: String, unite: String?, x: Float, y: Float, couleur: Int,
        tailleValeur: Float = 16f, tailleUnite: Float = 11f,
    ) {
        val suite = unite?.let { " $it" }
        texte.letterSpacing = 0f
        texte.typeface = Typeface.DEFAULT
        texte.textSize = tailleUnite
        val largeurUnite = suite?.let { texte.measureText(it) } ?: 0f
        texte.typeface = moyen
        texte.textSize = tailleValeur
        val largeurValeur = texte.measureText(valeur)
        val debut = x - (largeurValeur + largeurUnite) / 2f
        texte.textAlign = Paint.Align.LEFT
        texte.color = couleur
        canvas.drawText(valeur, debut, y, texte)
        if (suite != null) {
            texte.typeface = Typeface.DEFAULT
            texte.textSize = tailleUnite
            texte.color = cTexte2
            canvas.drawText(suite, debut + largeurValeur, y, texte)
        }
        texte.textAlign = Paint.Align.CENTER
    }

    /**
     * Pastille en haut à gauche : mode de conduite ou nom du profil, dans la teinte du mode qui
     * l'accompagne sur un fond atténué — grise sans mode. Jamais plus large que celle du mode le
     * plus long : les jauges ont été placées pour elle (photo du 2026-10-14), un nom de profil (30
     * caractères au plus) est coupé d'une ellipse.
     */
    private fun pastille(canvas: Canvas, p: PillContent) {
        val teinte = p.mode?.let { OverlayKit.pastilleMode(sombre, it).first } ?: cTexte2
        val nom = TextUtils.ellipsize(p.text, textePastille, largeurPastilleMax, TextUtils.TruncateAt.END).toString()
        val largeur = textePastille.measureText(nom) + 30f
        plein.color = Color.argb(64, Color.red(teinte), Color.green(teinte), Color.blue(teinte))
        cadre.set(14f, 14f, 14f + largeur, 36f)
        canvas.drawRoundRect(cadre, 11f, 11f, plein)
        plein.color = teinte
        canvas.drawCircle(26f, 25f, 3.5f, plein)
        textePastille.color = teinte
        canvas.drawText(nom, 35f, 29.5f, textePastille)
    }

    /** Moyenne du trajet dans son unité (réglée à part), ou un tiret tant qu'elle n'a pas de sens. */
    private fun moyenne(wh: Float?, unite: AverageUnit): Pair<String, String?> =
        wh?.let { ClusterGauge.formatAverage(it, unite, locale) }?.let { it to unite.label } ?: ("–" to null)

    private fun couleur(niveau: GaugeLevel): Int = sombre.getColor(when (niveau) {
        GaugeLevel.CONSUMPTION -> R.color.dash_warn
        GaugeLevel.REGEN -> R.color.dash_accent
    })

    private companion object {
        /**
         * Fond de l'appli SAIC du combiné (`screen_background_dark`). Celui de nos cartes, plus
         * clair, ressortait en rectangle sur le combiné (photo du 2026-10-14).
         */
        val FOND = Color.rgb(0x0C, 0x0C, 0x0C)
        val COTE = ClusterGauge.SIDE_PX.toFloat()
        /** Anneau du module A (consommation) et arc du module B (puissance). */
        const val RAYON_A = 80f
        const val RAYON_B = 88f
        const val EPAISSEUR = 13f
        /** Style discret : la barre, ses bornes, et la zone de récupération de la consommation. */
        const val Y_BARRE = 124f
        const val BARRE_G = 30f
        const val BARRE_D = 230f
        const val RECUP_D = 66f
        const val CONSO_G = 72f
        const val BARRE_EPAISSEUR = 6f
        /** Textes discrets et crans secondaires, plus sourds que le gris des valeurs. */
        val C_DISCRET = Color.rgb(0x8A, 0x8A, 0x90)
        val C_CRAN = Color.rgb(0x4A, 0x4A, 0x52)
        /** La puissance consommée : un gris clair, pour ne pas attirer l'œil comme l'orange. */
        val C_CLAIR = Color.rgb(0xD4, 0xD4, 0xD8)
        /** Module « charge » : une image toutes les 50 ms ; éclair et reflet sur ces périodes. */
        const val IMAGE_CHARGE_MS = 50L
        const val PULSATION_MS = 1_400L
        const val REFLET_MS = 1_800L
        const val REFLET_PX = 18f
    }
}
