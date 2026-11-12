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
import android.graphics.DashPathEffect
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
import com.mg4.control.model.ClusterInfo
import com.mg4.control.model.ClusterLayout
import com.mg4.control.model.ClusterLayouts
import com.mg4.control.model.ClusterModule
import com.mg4.control.model.ConsumptionUnit
import com.mg4.control.model.DistanceUnit
import com.mg4.control.model.DriveMode
import com.mg4.control.model.GaugeLevel
import com.mg4.control.model.GaugeState
import com.mg4.control.model.GaugeUnit
import com.mg4.control.model.PillContent
import com.mg4.control.model.PreClimateTrend
import com.mg4.control.service.OverlayKit
import com.mg4.control.service.PreClimate
import java.text.NumberFormat
import java.util.Calendar
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
 *
 * Pendant une pré-climatisation (issue #130, 2026-10-23, maquette C « fiche »), sa fiche passe
 * avant tout ([PreClimate]) : la consigne en grand, bleue ou orange comme l'écran central, la barre
 * de l'étape, puis le reste et la batterie ; en mode chien, un mot pour les passants. Sans
 * animation, à sa demande : l'écran central en a déjà une.
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

    /**
     * Fiche de la pré-climatisation. [enMarche] : la clim tourne (sinon consigne en gris) ;
     * [part] : part écoulée de l'étape, pour la barre ; [verrou] : `null` pendant le décompte.
     * Heures en minutes depuis minuit.
     */
    data class PreClim(
        val chien: Boolean,
        /** Surveillance (issue #180) : sa propre fiche, temps restant en grand (maquette C du 2026-10-30). */
        val surveillance: Boolean = false,
        val tendance: PreClimateTrend,
        val enMarche: Boolean,
        val consigneC: Int,
        val dehorsC: Float?,
        val part: Float,
        val etape: Etape,
        val batterie: Float?,
        val verrou: PreClimate.LockState?,
        val debutMinute: Int,
    )

    /** La ligne sous la barre de la fiche. */
    sealed class Etape {
        data class Decompte(val secondes: Int) : Etape()
        data class Restant(val minutes: Int, val finMinute: Int) : Etape()
        data class Cycle(val numero: Int, val total: Int, val finMinute: Int) : Etape()
        data class Attente(val prochainMinute: Int) : Etape()
    }

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
        /** km ou miles pour tout le module (2026-10-23). */
        val distance: DistanceUnit = DistanceUnit.KM,
        val style: ClusterGaugeStyle = ClusterGaugeStyle.DYNAMIC,
        /** Pendant une charge : le module « charge » remplace la jauge. */
        val charge: Charge? = null,
        /** Pendant une pré-climatisation : sa fiche, avant la charge et la jauge. */
        val preClim: PreClim? = null,
        /** Fin de l'échelle de puissance (issue #165) : choisie, ou apprise en roulant. */
        val pleineEchelleKw: Float = ClusterGauge.POWER_FULL_KW,
        /**
         * Disposition (issue #229) : jauge et cases d'information. `null` : l'affichage d'avant,
         * pastille et pied dessinés par la jauge elle-même.
         */
        val disposition: ClusterLayout? = null,
        /** Ce qu'affichent les quatre cases, prêt à dessiner (Info 1 à 4, `null` = case libre). */
        val blocs: List<Bloc?> = emptyList(),
    )

    /**
     * Une case d'information prête à dessiner (issue #229). [valeur] et [unite] : le chiffre et son
     * unité en petit ; [libelle] : le nom de l'information (style « libellé ») ; [icone] : son
     * pictogramme (style « icône ») ; [pastille] : la pastille du profil ou du mode (style
     * « pastille ») ; [couleur] : celle de la valeur, `null` = blanc.
     */
    data class Bloc(
        val valeur: String,
        val unite: String?,
        val libelle: String,
        val icone: Int?,
        val pastille: PillContent?,
        val style: String,
        val taille: String,
        val couleur: Int? = null,
    )

    /**
     * Personnalisation en cours (popup de l'issue #229) : cases libres visibles, élément choisi
     * entouré, et ce qu'on fait glisser sous le doigt. Coordonnées dans le carré de 260.
     *
     * @param selection case choisie (0 à 3), [JAUGE] pour la jauge, `null` = rien
     * @param glisse    case ou [JAUGE] qu'on déplace, `null` = aucun glisser
     */
    data class Edition(
        val selection: Int? = null,
        val glisse: Int? = null,
        val doigtX: Float = 0f,
        val doigtY: Float = 0f,
        val cible: Int? = null,
        val cibleJauge: String? = null,
    )

    /** Ce qu'on personnalise ([Edition]) ; `null` sur le combiné. */
    var edition: Edition? = null
        set(v) { field = v; invalidate() }

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
    private val cPiste = sombre.getColor(R.color.cluster_track)
    private val cTexte = sombre.getColor(R.color.cluster_text)
    private val cTexte2 = sombre.getColor(R.color.cluster_text_secondary)
    private val cAccent = sombre.getColor(R.color.cluster_accent)
    private val cVert = sombre.getColor(R.color.dash_eco)
    private val cChaud = sombre.getColor(R.color.preclim_heat)
    private val cAlerte = sombre.getColor(R.color.dash_warn)
    private val cDanger = sombre.getColor(R.color.dash_danger)
    private val icones = listOf(R.drawable.ic_cluster_bolt, R.drawable.ic_cluster_battery_charging, R.drawable.ic_cluster_clock,
        R.drawable.ic_hvac_ac, R.drawable.ic_cluster_flame, R.drawable.ic_preclim_paw, R.drawable.ic_preclim_cctv)
        .associateWith { ContextCompat.getDrawable(context, it)?.mutate() }

    private val locale: Locale = context.resources.configuration.locales[0]

    /** Épaisseur des jauges et couleurs de la consommation et de la récupération (issue #229). */
    private var epaisseur = EPAISSEUR
    private var epaisseurBarre = BARRE_EPAISSEUR
    private var cConso = sombre.getColor(R.color.dash_warn)
    private var cRecup = cAccent
    /** Consommée sur la barre discrète de puissance : gris clair, sauf couleur choisie. */
    private var cConsoDiscrete = C_CLAIR
    private var curseurMoyenne = false
    private var kwSousConso = false
    private var consoSousKw = true
    /** Faux quand la disposition dessine ses cases : la jauge n'a plus ni pastille ni pied. */
    private var avecPied = true

    private val trait = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = EPAISSEUR
    }
    private val pointilles = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f)
    }
    private val iconesBlocs = mutableMapOf<Int, android.graphics.drawable.Drawable?>()
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
    /** Textes de la fiche de la pré-climatisation (alignés à gauche ou à droite, coupés au besoin). */
    private val texteFiche = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val cadre = RectF()

    /**
     * [animationMs] : durée du glissement vers ces valeurs (Réglages › Interface). Un relevé qui
     * arrive avant la fin repart de ce qui est à l'écran.
     */
    fun montrer(c: Contenu, animationMs: Long) {
        contenu = c
        if (c.charge != null || c.preClim != null) {
            // Rien à faire glisser : les valeurs de la charge bougent lentement, l'animation est
            // celle de l'éclair et de la barre (onDraw).
            animation?.cancel()
            invalidate()
            return
        }
        val nouveau = c.kw?.let {
            if (c.module == ClusterModule.POWER) ClusterGauge.power(it, c.pleineEchelleKw) else ClusterGauge.consumption(it, c.kmh, c.distance)
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
        // Fiches de charge et de pré-climatisation : l'épaisseur d'origine, quelle que soit la jauge réglée.
        trait.strokeWidth = EPAISSEUR

        c.preClim?.let { p ->
            // Rien ne bouge : redessinée à chaque relevé.
            preClim(canvas, p)
            canvas.restore()
            return
        }

        c.charge?.let { ch ->
            charge(canvas, ch)
            canvas.restore()
            // L'éclair pulse et le reflet court tant que la charge dure : une image toutes les
            // IMAGE_CHARGE_MS, pas à chaque trame — c'est assez pour l'œil.
            if (!ch.finie) postInvalidateDelayed(IMAGE_CHARGE_MS)
            return
        }

        regler(c)
        val d = c.disposition
        if (d == null) {
            c.pastille?.let { pastille(canvas, it) }
            jauge(canvas, c)
            canvas.restore()
            return
        }
        // Disposition (issue #229) : la jauge dans sa zone, à l'échelle, puis les cases.
        val ed = edition
        val geo = ClusterLayouts.geometry(d, showEmpty = ed != null)
        val zone = geo.gauge
        val (haut, bas) = coeur(c)
        val e = minOf(zone.h / (bas - haut), zone.w / COTE, 1f)
        if (e > 0.2f) {
            canvas.save()
            canvas.clipRect(zone.x, zone.y, zone.x + zone.w, zone.y + zone.h)
            canvas.translate(zone.x + (zone.w - COTE * e) / 2f, zone.y + (zone.h - (bas - haut) * e) / 2f - haut * e)
            canvas.scale(e, e)
            jauge(canvas, c)
            canvas.restore()
        }
        geo.cells.forEach { cell ->
            val b = c.blocs.getOrNull(cell.slot)
            if (ed?.glisse == cell.slot) caseVide(canvas, cell.box, null)
            else if (b != null) bloc(canvas, b, cell.box, gauche = cell.slot % 2 == 0)
            else if (ed != null) caseVide(canvas, cell.box, cell.slot)
        }
        if (ed != null) edition(canvas, ed, d, geo, c)
        canvas.restore()
    }

    private fun jauge(canvas: Canvas, c: Contenu) = when {
        c.style == ClusterGaugeStyle.DISCREET && c.module == ClusterModule.POWER -> discretPuissance(canvas, c)
        c.style == ClusterGaugeStyle.DISCREET -> discretConsommation(canvas, c)
        c.module == ClusterModule.POWER -> puissance(canvas, c)
        else -> consommation(canvas, c)
    }

    /** Hauteurs occupées par la jauge seule dans le carré de 260 : ce qu'on met à l'échelle de sa zone. */
    private fun coeur(c: Contenu): Pair<Float, Float> = when {
        c.style == ClusterGaugeStyle.DISCREET -> 54f to 174f
        c.module == ClusterModule.POWER -> 6f to 194f
        else -> 4f to 200f
    }

    /** L'allure réglée (issue #229), ou celle d'avant sans disposition. */
    private fun regler(c: Contenu) {
        val d = c.disposition
        avecPied = d == null
        val f = d?.let { ClusterLayouts.thicknessFactor(it) } ?: 1f
        epaisseur = EPAISSEUR * f
        epaisseurBarre = BARRE_EPAISSEUR * f
        trait.strokeWidth = epaisseur
        cConso = d?.consumptionColor ?: sombre.getColor(R.color.dash_warn)
        cRecup = d?.regenColor ?: cAccent
        cConsoDiscrete = d?.consumptionColor ?: C_CLAIR
        curseurMoyenne = d?.let { ClusterLayouts.averageCursor(it, c.style) } ?: (c.style == ClusterGaugeStyle.DISCREET)
        kwSousConso = d?.consumptionKw == true
        consoSousKw = d?.let { ClusterLayouts.powerConsumption(it) } ?: true
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
        // Moyenne du trajet (issue #229) : un point clair à l'intérieur de l'anneau, à sa place.
        if (curseurMoyenne) c.moyenneWhKm?.takeIf { it > 0f }?.let { moy ->
            val a = Math.toRadians((135f + 270f * ClusterGauge.consumptionFraction(moy * c.distance.factor)).toDouble())
            val r = RAYON_A - epaisseur / 2f - 7f
            plein.color = cTexte
            canvas.drawCircle(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), 3.5f, plein)
        }

        val v = valeurAffichee ?: e?.value ?: 0f
        val (chiffre, unite) = when (e?.unit) {
            null -> "–" to ""
            GaugeUnit.WH_PER_KM -> ClusterGauge.formatConsumption(v, c.unite, locale) to c.unite.labelFor(c.distance)
            // En roulant, la récupération s'écrit en consommation négative (issue #158), tirée des
            // kW récupérés affichés — elle glisse avec eux ; sous 5 km/h, les kW comme avant.
            GaugeUnit.KW_REGEN -> ClusterGauge.regenWhPerKm(-v, c.kmh)
                ?.let { ClusterGauge.formatConsumption(it * c.distance.factor, c.unite, locale) to c.unite.labelFor(c.distance) }
                ?: ("↓" + ClusterGauge.formatKw(v, locale) to context.getString(R.string.cluster_regen_kw))
            GaugeUnit.KW_STOPPED -> ClusterGauge.formatKw(v, locale) to context.getString(R.string.cluster_stopped_kw)
            GaugeUnit.KW -> ClusterGauge.formatKw(v, locale) to "kW"
        }
        val recup = e?.unit == GaugeUnit.KW_REGEN
        grandChiffre(canvas, chiffre, cx, cy + 16f, if (recup) cRecup else cTexte)
        petit(canvas, unite, cx, cy + 40f, 13f, if (recup) cRecup else cTexte2)
        // Les kW sous l'unité (issue #229), en roulant : à l'arrêt, le chiffre est déjà en kW.
        if (kwSousConso && (e?.unit == GaugeUnit.WH_PER_KM || e?.unit == GaugeUnit.KW_REGEN)) c.kw?.let { kw ->
            petit(canvas, ClusterGauge.formatKw(kotlin.math.abs(kw), locale) + " kW", cx, cy + 58f, 13f, if (recup) cRecup else cTexte2)
        }

        if (avecPied) pied(canvas,
            context.getString(R.string.cluster_trip_avg), moyenne(c.moyenneWhKm, c.uniteMoyenne, c.distance), cTexte,
            context.getString(R.string.cluster_regen), c.recupWh?.let { "+${it.roundToInt()}" to "Wh" } ?: ("–" to null), cRecup)
    }

    // ── B : arc de puissance ─────────────────────────────────────────────────

    private fun puissance(canvas: Canvas, c: Contenu) {
        val cx = COTE / 2f
        val cy = 122f
        // Graduations sur l'échelle resserrée : la puissance à droite, la récupération à gauche.
        ClusterGauge.powerTicks(c.pleineEchelleKw).forEach { kw ->
            graduation(canvas, cx, cy, RAYON_B, 270f + 120f * ClusterGauge.powerFraction(kw, c.pleineEchelleKw), "${kw.roundToInt()}")
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
        trait.strokeWidth = epaisseur
        petit(canvas, "0", cx, cy - RAYON_B - 12f, 11f, cTexte2)

        val v = valeurAffichee ?: e?.value
        val recup = e != null && v != null && v < 0f
        // Entier en roulant, une décimale à l'arrêt (issue #152).
        val chiffre = if (e == null || v == null) "–" else (if (recup) "−" else "") + ClusterGauge.formatPowerKw(v, c.kmh, locale)
        grandChiffre(canvas, chiffre, cx, cy + 12f, if (recup) cRecup else cTexte)
        // L'unité sous le chiffre (2026-10-15) : seul au centre, le chiffre ne disait pas ce qu'il
        // comptait. Remontée de 4 px pour laisser la place à la consommation (issue #152).
        if (e != null) {
            texte.typeface = moyen
            texte.textSize = 13f
            texte.letterSpacing = 0.04f
            texte.color = if (recup) cRecup else cTexte2
            canvas.drawText("kW", cx, cy + 28f, texte)
        }
        // Consommation instantanée en grand (28, en blanc) et son unité en petit et en gris
        // (issue #152) ; en récupération, négative et en bleu (issue #158) ; sous 5 km/h, le mot
        // qui en tient lieu, en petit.
        val wh = if (e == null || recup) null else c.kw?.let { ClusterGauge.whPerKm(it, c.kmh) }?.times(c.distance.factor)
        val whRecup = if (e == null || !recup) null else c.kw?.let { ClusterGauge.regenWhPerKm(it, c.kmh) }?.times(c.distance.factor)
        when {
            // La consommation sous les kW peut se masquer (issue #229).
            e == null || !consoSousKw -> Unit
            wh != null -> valeurEtUnite(canvas, ClusterGauge.formatConsumption(wh, c.unite, locale), c.unite.labelFor(c.distance),
                cx, cy + 64f, cTexte, tailleValeur = 28f, tailleUnite = 12f)
            whRecup != null -> valeurEtUnite(canvas, ClusterGauge.formatConsumption(whRecup, c.unite, locale), c.unite.labelFor(c.distance),
                cx, cy + 64f, cRecup, tailleValeur = 28f, tailleUnite = 12f)
            recup -> petit(canvas, context.getString(R.string.cluster_regen_word), cx, cy + 58f, 13f, cRecup)
            else -> petit(canvas, context.getString(R.string.cluster_stopped_word), cx, cy + 58f, 13f, cTexte2)
        }

        // Le pourcentage de batterie est déjà sur le combiné : seule reste la moyenne du trajet,
        // centrée, sans filet (issue #152).
        if (avecPied) piedCentre(canvas, context.getString(R.string.cluster_trip_avg), moyenne(c.moyenneWhKm, c.uniteMoyenne, c.distance), cTexte)
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
        trait.strokeWidth = epaisseurBarre
        canvas.drawLine(x0, Y_BARRE, x1, Y_BARRE, trait)
        trait.strokeWidth = epaisseur
    }

    /** Un cran : un petit trait sous la barre et son chiffre, fixe ([libelle] `null` : le trait seul). */
    private fun cranDiscret(canvas: Canvas, x: Float, libelle: String?, principal: Boolean) {
        trait.strokeCap = Paint.Cap.BUTT
        trait.color = if (principal) cTexte else C_CRAN
        trait.strokeWidth = if (principal) 2f else 1f
        canvas.drawLine(x, Y_BARRE + 7f, x, Y_BARRE + 12f, trait)
        trait.strokeCap = Paint.Cap.ROUND
        trait.strokeWidth = epaisseur
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
            val xv = xBarre(ClusterGauge.discreetPowerFraction(v, c.pleineEchelleKw))
            if (v < -0.5f) barreDiscrete(canvas, xv, zero, cRecup)
            else if (v > 0.5f) barreDiscrete(canvas, zero, xv, cConsoDiscrete)
        }
        val chiffres = ClusterGauge.discreetPowerLabels(c.pleineEchelleKw)
        ClusterGauge.discreetPowerTicks(c.pleineEchelleKw).forEach { k ->
            cranDiscret(canvas, xBarre(ClusterGauge.discreetPowerFraction(k, c.pleineEchelleKw)),
                k.roundToInt().toString().takeIf { k in chiffres }, k == 0f)
        }
        // Le zéro, repère de toute la barre.
        trait.strokeCap = Paint.Cap.BUTT
        trait.color = cTexte
        trait.strokeWidth = 2f
        canvas.drawLine(zero, Y_BARRE - 11f, zero, Y_BARRE + 11f, trait)
        trait.strokeCap = Paint.Cap.ROUND
        trait.strokeWidth = epaisseur
        texte.typeface = Typeface.DEFAULT
        texte.textSize = 11f
        texte.letterSpacing = 0f
        texte.color = C_DISCRET
        texte.textAlign = Paint.Align.RIGHT
        canvas.drawText("kW", BARRE_D, Y_BARRE - 14f, texte)
        texte.textAlign = Paint.Align.CENTER
        if (avecPied) piedCentre(canvas, context.getString(R.string.cluster_trip_avg), moyenne(c.moyenneWhKm, c.uniteMoyenne, c.distance), cTexte)
    }

    private fun discretConsommation(canvas: Canvas, c: Contenu) {
        titreDiscret(canvas, context.getString(R.string.settings_cluster_consumption))
        // À gauche, une courte zone pour la récupération ; puis l'échelle de consommation.
        barreDiscrete(canvas, BARRE_G, RECUP_D, cPiste)
        barreDiscrete(canvas, CONSO_G, BARRE_D, cPiste)
        fun xConso(wh: Float) = CONSO_G + (BARRE_D - CONSO_G) * ClusterGauge.discreetConsumptionFraction(wh)
        val e = etat
        val v = valeurAffichee ?: e?.value
        // Même unité que la barre : en miles, la moyenne passe en Wh/mi pour se placer dessus.
        val moy = c.moyenneWhKm?.times(c.distance.factor)
        if (e != null && v != null) when (e.unit) {
            GaugeUnit.KW_REGEN -> barreDiscrete(canvas,
                RECUP_D - (RECUP_D - BARRE_G) * ClusterGauge.discreetRegenFraction(v), RECUP_D, cRecup)
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
        texte.color = cRecup
        canvas.drawText(context.getString(R.string.cluster_regen), (BARRE_G + RECUP_D) / 2f, Y_BARRE + 28f, texte)
        texte.color = C_DISCRET
        texte.textAlign = Paint.Align.RIGHT
        canvas.drawText(c.unite.labelFor(c.distance), BARRE_D, Y_BARRE + 44f, texte)
        texte.textAlign = Paint.Align.CENTER
        // La moyenne du trajet : un triangle au-dessus de la barre, et son mot (masquable, issue #229).
        moy?.takeIf { curseurMoyenne }?.let {
            val xm = xConso(it)
            plein.color = cTexte
            val pointe = android.graphics.Path().apply {
                moveTo(xm - 5f, Y_BARRE - 16f); lineTo(xm + 5f, Y_BARRE - 16f); lineTo(xm, Y_BARRE - 8f); close()
            }
            canvas.drawPath(pointe, plein)
            texte.color = cTexte2
            canvas.drawText(context.getString(R.string.cluster_avg_short), xm, Y_BARRE - 21f, texte)
        }
        if (avecPied) piedCentre(canvas, context.getString(R.string.cluster_trip_avg), moyenne(c.moyenneWhKm, c.uniteMoyenne, c.distance), cTexte)
    }

    // ── Pré-climatisation (maquette C, « fiche ») ────────────────────────────

    private fun preClim(canvas: Canvas, p: PreClim) {
        if (p.surveillance) { surveillance(canvas, p); return }
        val couleur = if (p.tendance == PreClimateTrend.HEATING) cChaud else cAccent
        val d = COTE - MARGE_FICHE
        val consigne = context.getString(R.string.preclim_value_celsius, p.consigneC)
        val dehors = p.dehorsC?.let { context.getString(R.string.preclim_value_celsius, it.roundToInt()) } ?: "–"
        val batterie = p.batterie?.let { context.getString(R.string.preclim_value_percent, it.roundToInt()) } ?: "–"

        // En-tête : patte, flamme ou flocon, et le titre.
        val icone = when {
            p.chien -> R.drawable.ic_preclim_paw
            p.tendance == PreClimateTrend.HEATING -> R.drawable.ic_cluster_flame
            else -> R.drawable.ic_hvac_ac
        }
        icones[icone]?.let {
            it.setTint(couleur)
            it.alpha = 255
            it.setBounds(18, 21, 40, 43)
            it.draw(canvas)
        }
        aGauche(canvas, context.getString(if (p.chien) R.string.preclim_dog else R.string.preclim_title),
            48f, 38f, 16f, cTexte, moyen)

        if (p.chien) {
            aGauche(canvas, context.getString(R.string.cluster_dog_fine), MARGE_FICHE, 85f, 20f, cTexte, moyen)
            aGauche(canvas, context.getString(R.string.cluster_dog_set), MARGE_FICHE, 112f, 13f, cTexte2, Typeface.DEFAULT)
            aGauche(canvas, consigne, MARGE_FICHE, 168f, 50f, couleur, moyen)
            aDroite(canvas, context.getString(R.string.cluster_preclim_outside) + " " + dehors, d, 166f, 14f, cTexte2, Typeface.DEFAULT)
            aGauche(canvas, context.getString(R.string.cluster_dog_since, heure(p.debutMinute), batterie),
                MARGE_FICHE, 234f, 13f, cTexte2, Typeface.DEFAULT)
            return
        }

        aGauche(canvas, consigne, MARGE_FICHE, 114f, 52f, if (p.enMarche) couleur else cTexte2, moyen)
        aDroite(canvas, context.getString(R.string.cluster_preclim_outside), d, 88f, 13f, C_DISCRET, Typeface.DEFAULT)
        aDroite(canvas, dehors, d, 110f, 18f, cTexte2, moyen)

        // Barre de l'étape, sans reflet.
        plein.color = cPiste
        cadre.set(MARGE_FICHE, 144f, d, 151f)
        canvas.drawRoundRect(cadre, 3.5f, 3.5f, plein)
        val part = p.part.coerceIn(0f, 1f)
        if (part > 0f) {
            plein.color = couleur
            cadre.set(MARGE_FICHE, 144f, MARGE_FICHE + (d - MARGE_FICHE) * part, 151f)
            canvas.drawRoundRect(cadre, 3.5f, 3.5f, plein)
        }

        val ligne = when (val e = p.etape) {
            is Etape.Decompte -> context.getString(R.string.cluster_preclim_lock_in, e.secondes)
            is Etape.Restant -> context.getString(R.string.cluster_preclim_remaining, duree(e.minutes), heure(e.finMinute))
            is Etape.Cycle -> context.getString(R.string.cluster_preclim_cycle, e.numero, e.total, heure(e.finMinute))
            is Etape.Attente -> context.getString(R.string.cluster_preclim_next, heure(e.prochainMinute))
        }
        aGauche(canvas, ligne, MARGE_FICHE, 181f, 14f, cTexte, Typeface.DEFAULT)

        val verrou = when (p.verrou) {
            PreClimate.LockState.LOCKED -> context.getString(R.string.preclim_lock_ok)
            PreClimate.LockState.FAILED -> context.getString(R.string.preclim_lock_failed)
            PreClimate.LockState.PENDING -> context.getString(R.string.preclim_lock_pending)
            null -> null
        }
        aGauche(canvas, listOfNotNull(context.getString(R.string.preclim_battery, batterie), verrou).joinToString(" · "),
            MARGE_FICHE, 234f, 13f, if (p.verrou == PreClimate.LockState.FAILED) cAlerte else cTexte2, Typeface.DEFAULT)
    }

    /**
     * Surveillance (issue #180, maquette C) : le temps restant en grand, en rouge, et l'heure d'arrêt
     * à droite ; pendant le décompte, les secondes avant le verrouillage. Ni animation ni
     * clignotement. Clim coupée : pas de consigne ni de température extérieure.
     */
    private fun surveillance(canvas: Canvas, p: PreClim) {
        val d = COTE - MARGE_FICHE
        val batterie = p.batterie?.let { context.getString(R.string.preclim_value_percent, it.roundToInt()) } ?: "–"
        icones[R.drawable.ic_preclim_cctv]?.let {
            it.setTint(cDanger)
            it.alpha = 255
            it.setBounds(18, 21, 40, 43)
            it.draw(canvas)
        }
        aGauche(canvas, context.getString(R.string.preclim_surv), 48f, 38f, 16f, cTexte, moyen)

        when (val e = p.etape) {
            is Etape.Decompte -> {
                aGauche(canvas, context.getString(R.string.cluster_surv_lock_in), MARGE_FICHE, 82f, 13f, C_DISCRET, Typeface.DEFAULT)
                aGauche(canvas, context.getString(R.string.preclim_value_seconds, e.secondes), MARGE_FICHE, 140f, 52f, cTexte, moyen)
            }
            is Etape.Restant -> {
                aGauche(canvas, context.getString(R.string.cluster_surv_remaining), MARGE_FICHE, 82f, 13f, C_DISCRET, Typeface.DEFAULT)
                aGauche(canvas, duree(e.minutes), MARGE_FICHE, 140f, 52f, cDanger, moyen)
                aDroite(canvas, context.getString(R.string.cluster_surv_stop), d, 82f, 13f, C_DISCRET, Typeface.DEFAULT)
                aDroite(canvas, heure(e.finMinute), d, 104f, 18f, cTexte2, moyen)
            }
            // La surveillance n'existe qu'en « Maintenant » : ni cycle ni attente.
            is Etape.Cycle, is Etape.Attente -> Unit
        }

        // Barre de l'étape, sans reflet : le décompte se vide, le temps écoulé la remplit.
        plein.color = cPiste
        cadre.set(MARGE_FICHE, 160f, d, 167f)
        canvas.drawRoundRect(cadre, 3.5f, 3.5f, plein)
        val part = p.part.coerceIn(0f, 1f)
        if (part > 0f) {
            plein.color = cDanger
            cadre.set(MARGE_FICHE, 160f, MARGE_FICHE + (d - MARGE_FICHE) * part, 167f)
            canvas.drawRoundRect(cadre, 3.5f, 3.5f, plein)
        }
        aGauche(canvas, context.getString(R.string.cluster_surv_state), MARGE_FICHE, 192f, 13f, cTexte2, Typeface.DEFAULT)

        val verrou = when (p.verrou) {
            PreClimate.LockState.LOCKED -> context.getString(R.string.preclim_lock_ok)
            PreClimate.LockState.FAILED -> context.getString(R.string.preclim_lock_failed)
            PreClimate.LockState.PENDING -> context.getString(R.string.preclim_lock_pending)
            null -> null
        }
        aGauche(canvas, listOfNotNull(context.getString(R.string.preclim_battery, batterie), verrou).joinToString(" · "),
            MARGE_FICHE, 234f, 13f, if (p.verrou == PreClimate.LockState.FAILED) cAlerte else cTexte2, Typeface.DEFAULT)
    }

    private fun aGauche(canvas: Canvas, s: String, x: Float, y: Float, taille: Float, couleur: Int, police: Typeface) =
        ecrire(canvas, s, x, y, taille, couleur, police, Paint.Align.LEFT)

    private fun aDroite(canvas: Canvas, s: String, x: Float, y: Float, taille: Float, couleur: Int, police: Typeface) =
        ecrire(canvas, s, x, y, taille, couleur, police, Paint.Align.RIGHT)

    /** Une ligne de la fiche, coupée d'une ellipse si elle déborde du carré. */
    private fun ecrire(canvas: Canvas, s: String, x: Float, y: Float, taille: Float, couleur: Int, police: Typeface, align: Paint.Align) {
        texteFiche.typeface = police
        texteFiche.textSize = taille
        texteFiche.color = couleur
        texteFiche.textAlign = align
        val place = if (align == Paint.Align.LEFT) COTE - MARGE_FICHE - x else x - MARGE_FICHE
        canvas.drawText(TextUtils.ellipsize(s, texteFiche, place, TextUtils.TruncateAt.END).toString(), x, y, texteFiche)
    }

    /** Heure murale ([minuteDuJour] depuis minuit) au format de la voiture. */
    private fun heure(minuteDuJour: Int): String {
        val m = Math.floorMod(minuteDuJour, 1440)
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, m / 60)
            set(Calendar.MINUTE, m % 60)
        }
        return DateFormat.getTimeFormat(context).format(c.time)
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
        trait.strokeWidth = epaisseur
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

    /**
     * Une seule colonne, centrée : intitulé en petit, valeur dessous — en 20 et non 16 : seule en
     * pied depuis le retrait de la batterie, la moyenne du trajet avait la place (issue #158).
     */
    private fun piedCentre(canvas: Canvas, libelle: String, valeur: Pair<String, String?>, couleur: Int) {
        petit(canvas, libelle, COTE / 2f, 220f, 11f, cTexte2)
        valeurEtUnite(canvas, valeur.first, valeur.second, COTE / 2f, 244f, couleur, tailleValeur = 20f)
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
    private fun pastille(canvas: Canvas, p: PillContent) = pastille(canvas, p, 14f, 25f, largeurPastilleMax, false)

    /**
     * Pastille de [p] dont le bord gauche (ou droit, [aDroite]) est en [x] et le milieu en [yMilieu] ;
     * le texte coupé d'une ellipse au-delà de [largeurMax].
     */
    private fun pastille(canvas: Canvas, p: PillContent, x: Float, yMilieu: Float, largeurMax: Float, aDroite: Boolean) {
        val teinte = p.mode?.let { OverlayKit.pastilleMode(sombre, it).first } ?: cTexte2
        val nom = TextUtils.ellipsize(p.text, textePastille, largeurMax, TextUtils.TruncateAt.END).toString()
        val largeur = textePastille.measureText(nom) + 30f
        val x0 = if (aDroite) x - largeur else x
        plein.color = Color.argb(64, Color.red(teinte), Color.green(teinte), Color.blue(teinte))
        cadre.set(x0, yMilieu - 11f, x0 + largeur, yMilieu + 11f)
        canvas.drawRoundRect(cadre, 11f, 11f, plein)
        plein.color = teinte
        canvas.drawCircle(x0 + 12f, yMilieu, 3.5f, plein)
        textePastille.color = teinte
        canvas.drawText(nom, x0 + 21f, yMilieu + 4.5f, textePastille)
    }

    // ── Cases d'information (issue #229) ─────────────────────────────────────

    /** Tailles S, M, L : valeur, unité, libellé. */
    private fun tailles(t: String): Triple<Float, Float, Float> = when (t) {
        ClusterInfo.SIZE_S -> Triple(13f, 10f, 10f)
        ClusterInfo.SIZE_L -> Triple(21f, 12f, 11f)
        else -> Triple(16f, 11f, 11f)
    }

    private fun bloc(canvas: Canvas, b: Bloc, box: ClusterLayouts.Box, gauche: Boolean) {
        val milieu = box.y + box.h / 2f
        if (b.style == ClusterInfo.STYLE_PILL && b.pastille != null) {
            val large = box.w > COTE / 2f
            // À gauche dans une case de gauche ou toute la largeur, à droite dans une case de droite.
            val aDroite = !gauche && !large
            pastille(canvas, b.pastille, if (aDroite) box.x + box.w - 10f else box.x + 10f, milieu,
                minOf(largeurPastilleMax * 1.6f, box.w - 50f), aDroite)
            return
        }
        val (tv, tu, tl) = tailles(b.taille)
        val couleur = b.couleur ?: cTexte
        val dispo = box.w - 10f
        when (b.style) {
            ClusterInfo.STYLE_LABEL -> {
                texte.typeface = Typeface.DEFAULT
                texte.textSize = tl
                texte.letterSpacing = 0.04f
                texte.color = cTexte2
                val lib = TextUtils.ellipsize(b.libelle, TextPaint(texte), dispo, TextUtils.TruncateAt.END).toString()
                canvas.drawText(lib, box.x + box.w / 2f, box.y + 4f + tl, texte)
                ajuste(canvas, b.valeur, b.unite, box.x + box.w / 2f, box.y + box.h - 5f, couleur, tv, tu, dispo)
            }
            ClusterInfo.STYLE_ICON -> {
                val cote = tv + 3f
                val l = largeurValeur(b.valeur, b.unite, tv, tu)
                val total = minOf(cote + 6f + l, dispo)
                val x0 = box.x + (box.w - total) / 2f
                b.icone?.let { id ->
                    iconesBlocs.getOrPut(id) { ContextCompat.getDrawable(context, id)?.mutate() }?.let { dr ->
                        dr.setTint(cTexte2)
                        dr.setBounds(x0.toInt(), (milieu - cote / 2f).toInt(), (x0 + cote).toInt(), (milieu + cote / 2f).toInt())
                        dr.draw(canvas)
                    }
                }
                val xv = x0 + cote + 6f
                ajuste(canvas, b.valeur, b.unite, xv + (total - cote - 6f) / 2f, milieu + tv * 0.36f, couleur, tv, tu, total - cote - 6f)
            }
            else -> ajuste(canvas, b.valeur, b.unite, box.x + box.w / 2f, milieu + tv * 0.36f, couleur, tv, tu, dispo)
        }
    }

    private fun largeurValeur(valeur: String, unite: String?, tv: Float, tu: Float): Float {
        texte.letterSpacing = 0f
        texte.typeface = moyen
        texte.textSize = tv
        var l = texte.measureText(valeur)
        unite?.let {
            texte.typeface = Typeface.DEFAULT
            texte.textSize = tu
            l += texte.measureText(" $it")
        }
        return l
    }

    /** Valeur et unité centrées en [x], rapetissées (jusqu'aux 2/3) pour tenir dans [dispo]. */
    private fun ajuste(canvas: Canvas, valeur: String, unite: String?, x: Float, y: Float, couleur: Int, tv: Float, tu: Float, dispo: Float) {
        val l = largeurValeur(valeur, unite, tv, tu)
        val f = if (l > dispo) (dispo / l).coerceAtLeast(0.66f) else 1f
        valeurEtUnite(canvas, valeur, unite, x, y, couleur, tailleValeur = tv * f, tailleUnite = tu * f)
    }

    /** Case libre pendant la personnalisation : un cadre en pointillés, et « + Info N ». */
    private fun caseVide(canvas: Canvas, box: ClusterLayouts.Box, slot: Int?) {
        pointilles.color = C_CRAN
        cadre.set(box.x + 1f, box.y + 2f, box.x + box.w - 1f, box.y + box.h - 2f)
        canvas.drawRoundRect(cadre, 8f, 8f, pointilles)
        slot ?: return
        petit(canvas, "+ " + context.getString(R.string.cluster_layout_slot_fmt, slot + 1),
            box.x + box.w / 2f, box.y + box.h / 2f + 4f, 11f, C_DISCRET)
    }

    /** Sélection, cible et élément sous le doigt (popup de personnalisation). */
    private fun edition(canvas: Canvas, ed: Edition, d: ClusterLayout, geo: ClusterLayouts.Geometry, c: Contenu) {
        trait.strokeCap = Paint.Cap.BUTT
        trait.strokeWidth = 2f
        trait.color = cAccent
        fun entourer(box: ClusterLayouts.Box) {
            cadre.set(box.x + 1f, box.y + 1f, box.x + box.w - 1f, box.y + box.h - 1f)
            canvas.drawRoundRect(cadre, 8f, 8f, trait)
        }
        when (val s = ed.selection) {
            null -> Unit
            JAUGE -> if (ed.glisse == null) entourer(geo.gauge)
            else -> geo.cells.firstOrNull { it.slot == s }?.let { if (ed.glisse == null) entourer(it.box) }
        }
        plein.color = Color.argb(70, Color.red(cAccent), Color.green(cAccent), Color.blue(cAccent))
        if (ed.glisse == JAUGE) {
            ed.cibleJauge?.let { pos ->
                val g = ClusterLayouts.geometry(d.copy(gauge = pos), showEmpty = true).gauge
                canvas.drawRect(g.x, g.y, g.x + g.w, g.y + g.h, plein)
                entourer(g)
            }
        } else if (ed.glisse != null) {
            geo.cells.firstOrNull { it.slot == ed.cible }?.let { cible ->
                canvas.drawRect(cible.box.x, cible.box.y, cible.box.x + cible.box.w, cible.box.y + cible.box.h, plein)
            }
            // Le bloc suit le doigt, entouré d'orange.
            val source = geo.cells.firstOrNull { it.slot == ed.glisse }?.box
            val b = c.blocs.getOrNull(ed.glisse)
            if (source != null && b != null) {
                val box = ClusterLayouts.Box(ed.doigtX - source.w / 2f, ed.doigtY - source.h / 2f, source.w, source.h)
                plein.color = Color.argb(230, 0x1C, 0x1C, 0x20)
                canvas.drawRoundRect(box.x, box.y, box.x + box.w, box.y + box.h, 8f, 8f, plein)
                bloc(canvas, b, box, gauche = ed.glisse % 2 == 0)
                trait.color = cConso
                entourer(box)
            }
        }
        trait.strokeCap = Paint.Cap.ROUND
        trait.strokeWidth = epaisseur
    }

    /** Moyenne du trajet dans son unité (réglée à part), ou un tiret tant qu'elle n'a pas de sens. */
    private fun moyenne(wh: Float?, unite: AverageUnit, distance: DistanceUnit): Pair<String, String?> =
        wh?.let { ClusterGauge.formatAverage(it, unite, locale, distance) }?.let { it to unite.labelFor(distance) } ?: ("–" to null)

    private fun couleur(niveau: GaugeLevel): Int = when (niveau) {
        GaugeLevel.CONSUMPTION -> cConso
        GaugeLevel.REGEN -> cRecup
    }

    companion object {
        /** [Edition.selection] et [Edition.glisse] : la jauge plutôt qu'une case. */
        const val JAUGE = -1

        /**
         * Fond de l'appli SAIC du combiné (`screen_background_dark`). Celui de nos cartes, plus
         * clair, ressortait en rectangle sur le combiné (photo du 2026-10-14).
         */
        private val FOND = Color.rgb(0x0C, 0x0C, 0x0C)
        private val COTE = ClusterGauge.SIDE_PX.toFloat()
        /** Anneau du module A (consommation) et arc du module B (puissance). */
        private const val RAYON_A = 80f
        private const val RAYON_B = 88f
        private const val EPAISSEUR = 13f
        /** Style discret : la barre, ses bornes, et la zone de récupération de la consommation. */
        private const val Y_BARRE = 124f
        private const val BARRE_G = 30f
        private const val BARRE_D = 230f
        private const val RECUP_D = 66f
        private const val CONSO_G = 72f
        private const val BARRE_EPAISSEUR = 6f
        /** Textes discrets et crans secondaires, plus sourds que le gris des valeurs. */
        private val C_DISCRET = Color.rgb(0x8A, 0x8A, 0x90)
        private val C_CRAN = Color.rgb(0x4A, 0x4A, 0x52)
        /** La puissance consommée : un gris clair, pour ne pas attirer l'œil comme l'orange. */
        private val C_CLAIR = Color.rgb(0xD4, 0xD4, 0xD8)
        /** Module « charge » : une image toutes les 50 ms ; éclair et reflet sur ces périodes. */
        private const val IMAGE_CHARGE_MS = 50L
        private const val PULSATION_MS = 1_400L
        private const val REFLET_MS = 1_800L
        private const val REFLET_PX = 18f
        /** Fiche de la pré-climatisation : marges gauche et droite. */
        private const val MARGE_FICHE = 22f
    }
}
