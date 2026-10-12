package com.mg4.control.service

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.mg4.control.R
import com.mg4.control.accessibility.JoystickFocus
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.model.AirFlow
import com.mg4.control.model.HvacPopup
import com.mg4.control.model.SeatHeat
import com.mg4.control.ui.widget.ArcGaugeView
import com.mg4.control.ui.widget.SegmentBarView

/**
 * Présentation « moderne » du pop-up HVAC (overlay_hvac_popup_modern.xml), reprise de la maquette
 * d'un utilisateur : trois panneaux, la température sur une jauge en arc, la ventilation sur une
 * barre à segments, et les sièges chauffants au niveau choisi d'un toucher.
 *
 * Toutes les commandes sont des vues simples dont le fond est peint ici : un bouton Material
 * aurait imposé ses marges, son relief et sa casse, et aucun ne sait porter deux textes (« A/C »
 * à gauche, « On » à droite). Trois aspects suffisent — au repos, choisi (cerclé d'accent), plein.
 *
 * Les couleurs sont celles de l'application, pas le bleu nuit de la maquette : la fenêtre suit le
 * thème clair ou sombre comme les autres pop-ups.
 */
internal class HvacPopupSkinModern(
    view: View,
    themed: Context,
    override val localized: Context,
) : HvacPopupSkin {

    private val texteDecompte: TextView = view.findViewById(R.id.hvac2_countdown)
    private val fermer: ImageView       = view.findViewById(R.id.hvac2_close)
    private val puce: TextView          = view.findViewById(R.id.hvac2_joy_chip)

    private val jauge: ArcGaugeView     = view.findViewById(R.id.hvac2_temp_gauge)
    private val temperature: TextView   = view.findViewById(R.id.hvac2_temp_value)
    private val tempMin: TextView       = view.findViewById(R.id.hvac2_temp_min)
    private val tempMax: TextView       = view.findViewById(R.id.hvac2_temp_max)
    private val tempMoins: ImageView    = view.findViewById(R.id.hvac2_temp_down)
    private val tempPlus: ImageView     = view.findViewById(R.id.hvac2_temp_up)

    private val ventilation: TextView   = view.findViewById(R.id.hvac2_fan_value)
    private val barre: SegmentBarView   = view.findViewById(R.id.hvac2_fan_bar)
    private val fanMoins: ImageView     = view.findViewById(R.id.hvac2_fan_down)
    private val fanPlus: ImageView      = view.findViewById(R.id.hvac2_fan_up)
    private val auto: TextView          = view.findViewById(R.id.hvac2_auto)

    private val airFace: TextView       = view.findViewById(R.id.hvac2_air_face)
    private val airFeet: TextView       = view.findViewById(R.id.hvac2_air_feet)
    private val airWindshield: TextView = view.findViewById(R.id.hvac2_air_windshield_front)
    private val airRear: TextView       = view.findViewById(R.id.hvac2_air_windshield_rear)

    private val ac: View                = view.findViewById(R.id.hvac2_ac)
    private val acIcone: ImageView      = view.findViewById(R.id.hvac2_ac_icon)
    private val acTitre: TextView       = view.findViewById(R.id.hvac2_ac_title)
    private val acEtat: TextView        = view.findViewById(R.id.hvac2_ac_state)

    private val recirculation: Map<Int, TextView> = mapOf(
        MG4Hardware.LoopMode.INNER   to view.findViewById<TextView>(R.id.hvac2_loop_inner),
        MG4Hardware.LoopMode.OUTSIDE to view.findViewById<TextView>(R.id.hvac2_loop_outside),
        MG4Hardware.LoopMode.AUTO    to view.findViewById<TextView>(R.id.hvac2_loop_auto),
    )

    /** Ce que pilote le joystick : ses cinq gestes ont chacun leur bouton, qui se touche aussi. */
    private val cellules: Map<JoystickFocus.Commande, View> = mapOf(
        JoystickFocus.Commande.HAUT    to tempPlus,
        JoystickFocus.Commande.BAS     to tempMoins,
        JoystickFocus.Commande.GAUCHE  to fanMoins,
        JoystickFocus.Commande.DROITE  to fanPlus,
        JoystickFocus.Commande.VALIDER to auto,
    )

    private val colonneChauffages: View = view.findViewById(R.id.hvac2_heat_column)

    /** Un siège : son niveau en clair, et son sélecteur — l'indice d'une case EST son niveau. */
    private class Siege(racine: View, etat: Int, selecteur: Int, cases: IntArray) {
        val etat: TextView = racine.findViewById(etat)
        val selecteur: View = racine.findViewById(selecteur)
        val cases: List<View> = cases.map { racine.findViewById<View>(it) }
    }

    private val siegeGauche = Siege(view, R.id.hvac2_seat_left_state, R.id.hvac2_seat_left_levels, intArrayOf(
        R.id.hvac2_seat_left_0, R.id.hvac2_seat_left_1, R.id.hvac2_seat_left_2, R.id.hvac2_seat_left_3))
    private val siegeDroit = Siege(view, R.id.hvac2_seat_right_state, R.id.hvac2_seat_right_levels, intArrayOf(
        R.id.hvac2_seat_right_0, R.id.hvac2_seat_right_1, R.id.hvac2_seat_right_2, R.id.hvac2_seat_right_3))

    private val volant: View              = view.findViewById(R.id.hvac2_wheel)
    private val volantIcone: ImageView    = view.findViewById(R.id.hvac2_wheel_icon)
    private val volantTexte: TextView     = view.findViewById(R.id.hvac2_wheel_label)
    private val interrupteur: FrameLayout = view.findViewById(R.id.hvac2_wheel_switch)
    private val curseur: View             = view.findViewById(R.id.hvac2_wheel_knob)

    // ── Palette : celle de l'application, dans son thème ─────────────────────
    private val accent          = themed.getColor(R.color.dash_accent)
    private val accentDoux      = themed.getColor(R.color.dash_accent_dim)
    private val surAccent       = themed.getColor(R.color.hvac_on_accent)
    private val chaud           = themed.getColor(R.color.dash_warn)
    private val chaudDoux       = themed.getColor(R.color.dash_warn_dim)
    private val surChaud        = themed.getColor(R.color.hvac_on_heat)
    private val bouton          = themed.getColor(R.color.dash_btn)
    private val bordure         = themed.getColor(R.color.dash_border)
    private val segmentChoisi   = themed.getColor(R.color.hvac_segment_on)
    private val textePrincipal  = themed.getColor(R.color.text_primary)
    private val texteSecondaire = themed.getColor(R.color.text_secondary)
    private val blanc           = themed.getColor(R.color.text_active)

    private val traitFin     = dp(themed, 1f)
    private val traitMoyen   = dp(themed, 2f)
    private val traitEpais   = dp(themed, 3f)
    private val rayonTuile   = dp(themed, 12f).toFloat()
    private val rayonSegment = dp(themed, 9f).toFloat()
    private val rayonPilule  = dp(themed, 14f).toFloat()

    private val niveaux = intArrayOf(
        R.string.climate_off, R.string.climate_level_1, R.string.climate_level_2, R.string.climate_level_3)

    /**
     * Une couleur par niveau de siège chauffant, pour le lire d'un coup d'œil (demande du
     * 2026-10-12) : éteint en gris, puis vert, orange, rouge. L'indice est le niveau.
     */
    private val fondNiveau = intArrayOf(
        segmentChoisi,
        themed.getColor(R.color.dash_eco),
        chaud,
        themed.getColor(R.color.dash_danger),
    )
    private val surNiveau = intArrayOf(
        textePrincipal, themed.getColor(R.color.hvac_on_eco), surChaud, blanc)

    /** Dernier état peint : de quoi rendre son aspect à un bouton après un éclair. */
    private var dernier: MG4Hardware.ClimateState? = null
    private var eclair: JoystickFocus.Commande? = null

    init {
        // Ce qui ne change pas avec l'état de la clim.
        view.findViewById<ImageView>(R.id.hvac2_title_icon).imageTintList = ColorStateList.valueOf(accent)
        view.findViewById<ImageView>(R.id.hvac2_fan_icon).imageTintList = ColorStateList.valueOf(accent)
        view.findViewById<TextView>(R.id.hvac2_temp_unit).text = "°C"
        fond(fermer, bouton, bordure, traitFin, ovale = true)
        fermer.imageTintList = ColorStateList.valueOf(texteSecondaire)
        jauge.couleurPiste = bordure
        jauge.couleurValeur = accent
        jauge.couleurCurseur = blanc
        barre.couleurAllume = accent
        barre.couleurEteint = bordure
        fond(siegeGauche.selecteur, bouton)
        fond(siegeDroit.selecteur, bouton)
        fond(volant, bouton, bordure, traitFin)
        fond(curseur, blanc, ovale = true)
    }

    override fun brancher(gestes: HvacPopupSkin.Gestes, avecChauffages: Boolean) {
        // Même chemin au doigt qu'au joystick : mêmes bornes, même anti-rebond.
        cellules.forEach { (commande, vue) -> vue.setOnClickListener { gestes.commande(commande) } }
        airFace.setOnClickListener       { gestes.air(AirFlow.FACE) }
        airFeet.setOnClickListener       { gestes.air(AirFlow.FEET) }
        airWindshield.setOnClickListener { gestes.air(AirFlow.WINDSHIELD) }
        airRear.setOnClickListener       { gestes.lunette() }
        ac.setOnClickListener            { gestes.ac() }
        recirculation.forEach { (mode, vue) -> vue.setOnClickListener { gestes.recirculation(mode) } }
        fermer.setOnClickListener        { gestes.fermer() }
        if (avecChauffages) {
            colonneChauffages.visibility = View.VISIBLE
            siegeGauche.cases.forEachIndexed { niveau, vue -> vue.setOnClickListener { gestes.siegeNiveau(true, niveau) } }
            siegeDroit.cases.forEachIndexed { niveau, vue -> vue.setOnClickListener { gestes.siegeNiveau(false, niveau) } }
            volant.setOnClickListener { gestes.volant() }
        }
    }

    override fun repereJoystick(utilisable: Boolean) {
        val titre = if (utilisable) R.string.hvac_popup_joystick_title else R.string.hvac_popup_joystick_off_title
        val texte = if (utilisable) R.string.hvac_popup_joystick_text else R.string.hvac_popup_joystick_off_text
        val couleur = if (utilisable) accent else chaud
        puce.text = localized.getString(titre) + " · " + localized.getString(texte)
        puce.setTextColor(couleur)
        puce.compoundDrawableTintList = ColorStateList.valueOf(couleur)
        fond(puce, if (utilisable) accentDoux else chaudDoux, rayon = rayonPilule)
    }

    override fun peindre(etat: MG4Hardware.ClimateState, chauffages: HvacPopupSkin.Chauffages?) {
        dernier = etat
        val r = etat.reglages()

        temperature.text = etat.tempC?.toString() ?: "--"
        tempMin.text = "${etat.tempMin}°"
        tempMax.text = "${etat.tempMax}°"
        jauge.part = HvacPopup.part(etat.tempC, etat.tempMin, etat.tempMax)

        // En AUTO la voiture annonce 15 pour la ventilation : on écrit « AUTO », pas ce nombre,
        // et aucun segment ne s'allume — il n'y a pas de cran à montrer.
        val niveau = r.ventilationReelle
        ventilation.text = when {
            r.enAuto -> localized.getString(R.string.clim_auto)
                .uppercase(localized.resources.configuration.locales[0])
            else -> niveau?.toString() ?: "--"
        }
        barre.total = etat.fanMax
        barre.allumes = (niveau ?: 0).coerceIn(0, etat.fanMax)
        peindreCroix(etat)

        val compresseur = etat.acOn == true
        if (compresseur) fond(ac, accent) else fond(ac, bouton, bordure, traitFin)
        acIcone.imageTintList = ColorStateList.valueOf(if (compresseur) surAccent else texteSecondaire)
        acTitre.setTextColor(if (compresseur) surAccent else textePrincipal)
        acEtat.setTextColor(if (compresseur) surAccent else texteSecondaire)
        acEtat.text = when (etat.acOn) {
            null -> "--"
            else -> localized.getString(if (compresseur) R.string.climate_on else R.string.climate_off)
        }
        inerte(ac, etat.acOn == null)

        recirculation.forEach { (mode, vue) -> tuile(vue, etat.loopMode?.let { it == mode }) }
        // Valeur illisible → tuiles grisées. Valeur lue mais hors échelle (7 « aucun »)
        // → tuiles actives, aucune allumée : l'utilisateur peut choisir un sens.
        val air = etat.airFlow?.let { AirFlow.partsOf(it) }
        tuile(airFace,       etat.airFlow?.let { air?.face == true })
        tuile(airFeet,       etat.airFlow?.let { air?.feet == true })
        tuile(airWindshield, etat.airFlow?.let { air?.windshield == true })
        tuile(airRear,       etat.defrostRear)

        chauffages?.let { c ->
            peindreSiege(siegeGauche, c.siegeGauche)
            peindreSiege(siegeDroit, c.siegeDroit)
            peindreVolant(c.volant)
        }
    }

    override fun decompte(secondes: Int) {
        texteDecompte.text = localized.getString(R.string.overlay_countdown, secondes)
    }

    override fun eclairer(commande: JoystickFocus.Commande) {
        val etat = dernier ?: return
        eclair = commande
        peindreCroix(etat)
        auto.postDelayed({
            // Un geste plus récent a pris le relais : c'est son propre délai qui éteindra.
            if (eclair == commande) {
                eclair = null
                dernier?.let { peindreCroix(it) }
            }
        }, HvacPopupSkin.FLASH_MS)
    }

    // ── Peinture ─────────────────────────────────────────────────────────────

    /**
     * Les cinq boutons du joystick. Le froid en accent, le chaud en ambre ; celui dont le geste
     * vient de passer s'épaissit un instant ([eclair]).
     */
    private fun peindreCroix(etat: MG4Hardware.ClimateState) {
        boutonRond(tempMoins, accent, accentDoux, eclair == JoystickFocus.Commande.BAS)
        boutonRond(tempPlus, chaud, chaudDoux, eclair == JoystickFocus.Commande.HAUT)
        boutonCran(fanMoins, eclair == JoystickFocus.Commande.GAUCHE)
        boutonCran(fanPlus, eclair == JoystickFocus.Commande.DROITE)
        val r = etat.reglages()
        tuile(auto, if (r.enAuto) true else etat.autoOn, plein = true)
        if (eclair == JoystickFocus.Commande.VALIDER) {
            (auto.background as? GradientDrawable)?.setStroke(traitEpais, textePrincipal)
        }
    }

    private fun boutonRond(vue: ImageView, couleur: Int, doux: Int, eclaire: Boolean) {
        fond(vue, if (eclaire) doux else bouton, couleur, if (eclaire) traitEpais else traitMoyen, ovale = true)
        vue.imageTintList = ColorStateList.valueOf(couleur)
    }

    private fun boutonCran(vue: ImageView, eclaire: Boolean) {
        if (eclaire) fond(vue, accentDoux, accent, traitEpais) else fond(vue, bouton, bordure, traitFin)
        vue.imageTintList = ColorStateList.valueOf(if (eclaire) accent else textePrincipal)
    }

    /**
     * Tuile à texte, avec ou sans picto : au repos, choisie (cerclée d'accent) ou — [plein] —
     * remplie d'accent. Un état null = non lisible sur ce firmware → grisée et inerte.
     */
    private fun tuile(vue: TextView, actif: Boolean?, plein: Boolean = false) {
        val allume = actif == true
        val teinte = when {
            allume && plein -> { fond(vue, accent); vue.setTextColor(surAccent); surAccent }
            allume          -> { fond(vue, accentDoux, accent, traitMoyen); vue.setTextColor(textePrincipal); accent }
            else            -> { fond(vue, bouton, bordure, traitFin); vue.setTextColor(texteSecondaire); texteSecondaire }
        }
        vue.compoundDrawableTintList = ColorStateList.valueOf(teinte)
        inerte(vue, actif == null)
    }

    /** Siège chauffant : la case de son niveau s'allume, dans la couleur de ce niveau. */
    private fun peindreSiege(siege: Siege, lu: Int?) {
        val niveau = lu?.coerceIn(0, SeatHeat.MAX_LEVEL)
        siege.etat.text = niveau?.let { localized.getString(niveaux[it]) } ?: "--"
        siege.etat.setTextColor(if (niveau != null && niveau > 0) fondNiveau[niveau] else texteSecondaire)
        siege.cases.forEachIndexed { rang, vue ->
            val teinte = if (rang == niveau) {
                fond(vue, fondNiveau[rang], rayon = rayonSegment)
                surNiveau[rang]
            } else {
                fond(vue, Color.TRANSPARENT, rayon = rayonSegment)
                texteSecondaire
            }
            when (vue) {
                is TextView  -> vue.setTextColor(teinte)
                is ImageView -> vue.imageTintList = ColorStateList.valueOf(teinte)
            }
            inerte(vue, niveau == null)
        }
    }

    /** Volant chauffant : la ligne se touche, l'interrupteur ne fait que montrer l'état. */
    private fun peindreVolant(actif: Boolean?) {
        val allume = actif == true
        volantIcone.imageTintList = ColorStateList.valueOf(if (allume) chaud else texteSecondaire)
        volantTexte.setTextColor(if (allume) textePrincipal else texteSecondaire)
        fond(interrupteur, if (allume) chaud else bordure, rayon = rayonPilule)
        fond(curseur, if (allume) blanc else texteSecondaire, ovale = true)
        val place = curseur.layoutParams as FrameLayout.LayoutParams
        place.gravity = Gravity.CENTER_VERTICAL or (if (allume) Gravity.END else Gravity.START)
        curseur.layoutParams = place
        inerte(volant, actif == null)
    }

    private fun inerte(vue: View, illisible: Boolean) {
        vue.isEnabled = !illisible
        vue.alpha = if (illisible) 0.35f else 1f
    }

    /**
     * Peint le fond d'une vue SANS fond dans la mise en page : le même objet est repeint à chaque
     * état, plutôt qu'un nouveau à chaque relecture de la voiture.
     */
    private fun fond(
        vue: View, remplissage: Int,
        trait: Int = Color.TRANSPARENT, epaisseur: Int = 0,
        rayon: Float = rayonTuile, ovale: Boolean = false,
    ) {
        val forme = vue.background as? GradientDrawable ?: GradientDrawable().also { vue.background = it }
        forme.shape = if (ovale) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
        if (!ovale) forme.cornerRadius = rayon
        forme.setColor(remplissage)
        forme.setStroke(epaisseur, trait)
    }

    private fun dp(context: Context, value: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()
}
