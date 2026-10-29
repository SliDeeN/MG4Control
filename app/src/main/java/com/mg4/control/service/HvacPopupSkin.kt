package com.mg4.control.service

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
 * Ce que le pop-up HVAC montre et ce qu'on y touche (overlay_hvac_popup.xml) : trois panneaux, la
 * température sur une jauge en arc, la ventilation sur une barre à segments, et les sièges
 * chauffants au niveau choisi d'un toucher. Présentation reprise de la maquette d'un utilisateur.
 *
 * Aucune règle ici — ni borne, ni anti-rebond, ni écriture : [HvacPopupOverlay] les garde, donne
 * l'état à [peindre] et reçoit les [Gestes]. Vit et meurt avec le popup affiché ; thread principal
 * seulement.
 *
 * Toutes les commandes sont des vues simples dont le fond est peint ici : un bouton Material
 * aurait imposé ses marges, son relief et sa casse, et aucun ne sait porter deux textes (« A/C »
 * à gauche, « On » à droite). Trois aspects suffisent — au repos, choisi (cerclé d'accent), plein.
 *
 * Les couleurs sont celles de l'application : la fenêtre suit le thème clair ou sombre comme les
 * autres pop-ups.
 *
 * @param localized contexte dans la langue de l'application, pour les textes et les Toast
 */
internal class HvacPopupSkin(
    view: View,
    themed: Context,
    val localized: Context,
) {

    /**
     * Sièges et volant chauffants ; null (côté fenêtre) sur les firmwares qui n'en ont pas, et la
     * colonne reste alors masquée. Un champ à null = illisible : sa commande est grisée.
     */
    data class Chauffages(val siegeGauche: Int?, val siegeDroit: Int?, val volant: Boolean?)

    /** Ce qu'un toucher peut demander à la fenêtre. Le joystick, lui, n'envoie que [commande]. */
    class Gestes(
        /** Température ±, ventilation ±, AUTO : même chemin au doigt qu'au joystick. */
        val commande: (JoystickFocus.Commande) -> Unit,
        /** Bouton cumulable du sens de l'air ([AirFlow]). */
        val air: (part: Int) -> Unit,
        val lunette: () -> Unit,
        val ac: () -> Unit,
        /** Mode ECO de la clim (2026-10-23). */
        val eco: () -> Unit = {},
        /** Choix direct d'un mode ([MG4Hardware.LoopMode]). */
        val recirculation: (mode: Int) -> Unit,
        /** Siège chauffant : un niveau précis, de 0 (éteint) à 3. */
        val siegeNiveau: (gauche: Boolean, niveau: Int) -> Unit,
        val volant: () -> Unit,
        val fermer: () -> Unit,
        /** AUTO enfoncé : tenu, il ouvre le choix du débit (issue #178). */
        val autoEnfonce: () -> Unit = {},
        /** AUTO relâché ; [annule] = doigt sorti du bouton, rien ne se joue. */
        val autoRelache: (annule: Boolean) -> Unit = {},
        /** Débit touché dans le choix ouvert ([MG4Hardware.AutoVolume]). */
        val debit: (Int) -> Unit = {},
    )


    private val texteDecompte: TextView = view.findViewById(R.id.hvac_countdown)
    private val fermer: ImageView       = view.findViewById(R.id.hvac_close)
    private val titre: TextView         = view.findViewById(R.id.hvac_title)

    /**
     * Repère du joystick, dans l'en-tête : ce que fait chaque geste. [cadreRepere] est calé sur
     * [zoneJoystick] — le cadre bleu — pour que la puce soit centrée au-dessus de lui.
     */
    private val cadreRepere: View       = view.findViewById(R.id.hvac_joy_holder)
    private val zoneJoystick: View      = view.findViewById(R.id.hvac_joy_zone)
    private val puce: View              = view.findViewById(R.id.hvac_joy_chip)
    private val puceTitre: TextView     = view.findViewById(R.id.hvac_joy_title)
    private val puceGestes: List<TextView> = listOf(
        view.findViewById(R.id.hvac_joy_temp),
        view.findViewById(R.id.hvac_joy_fan),
        view.findViewById(R.id.hvac_joy_auto),
    )

    private val jauge: ArcGaugeView     = view.findViewById(R.id.hvac_temp_gauge)
    private val temperature: TextView   = view.findViewById(R.id.hvac_temp_value)
    private val tempMin: TextView       = view.findViewById(R.id.hvac_temp_min)
    private val tempMax: TextView       = view.findViewById(R.id.hvac_temp_max)
    private val tempMoins: ImageView    = view.findViewById(R.id.hvac_temp_down)
    private val tempPlus: ImageView     = view.findViewById(R.id.hvac_temp_up)

    private val ventilation: TextView   = view.findViewById(R.id.hvac_fan_value)
    private val libelleVentilation: TextView = view.findViewById(R.id.hvac_fan_label)
    /** Rangée « − AUTO + », et le choix du débit qui la remplace quand il est ouvert (issue #178). */
    private val rangeeCrans: View       = view.findViewById(R.id.hvac_fan_row)
    private val rangeeDebit: View       = view.findViewById(R.id.hvac_autovol_row)
    private val debits: Map<Int, TextView> = mapOf(
        MG4Hardware.AutoVolume.LOW    to view.findViewById<TextView>(R.id.hvac_autovol_low),
        MG4Hardware.AutoVolume.MEDIUM to view.findViewById<TextView>(R.id.hvac_autovol_medium),
        MG4Hardware.AutoVolume.HIGH   to view.findViewById<TextView>(R.id.hvac_autovol_high),
    )
    private val barre: SegmentBarView   = view.findViewById(R.id.hvac_fan_bar)
    private val fanMoins: ImageView     = view.findViewById(R.id.hvac_fan_down)
    private val fanPlus: ImageView      = view.findViewById(R.id.hvac_fan_up)
    private val auto: TextView          = view.findViewById(R.id.hvac_auto)

    private val airFace: TextView       = view.findViewById(R.id.hvac_air_face)
    private val airFeet: TextView       = view.findViewById(R.id.hvac_air_feet)
    private val airWindshield: TextView = view.findViewById(R.id.hvac_air_windshield_front)
    private val airRear: TextView       = view.findViewById(R.id.hvac_air_windshield_rear)

    private val ac: View                = view.findViewById(R.id.hvac_ac)
    private val acIcone: ImageView      = view.findViewById(R.id.hvac_ac_icon)
    private val acTitre: TextView       = view.findViewById(R.id.hvac_ac_title)
    private val acEtat: TextView        = view.findViewById(R.id.hvac_ac_state)

    private val eco: View               = view.findViewById(R.id.hvac_eco)
    private val ecoIcone: ImageView     = view.findViewById(R.id.hvac_eco_icon)
    private val ecoTitre: TextView      = view.findViewById(R.id.hvac_eco_title)

    private val recirculation: Map<Int, TextView> = mapOf(
        MG4Hardware.LoopMode.INNER   to view.findViewById<TextView>(R.id.hvac_loop_inner),
        MG4Hardware.LoopMode.OUTSIDE to view.findViewById<TextView>(R.id.hvac_loop_outside),
        MG4Hardware.LoopMode.AUTO    to view.findViewById<TextView>(R.id.hvac_loop_auto),
    )

    /** Ce que pilote le joystick : ses cinq gestes ont chacun leur bouton, qui se touche aussi. */
    private val cellules: Map<JoystickFocus.Commande, View> = mapOf(
        JoystickFocus.Commande.HAUT    to tempPlus,
        JoystickFocus.Commande.BAS     to tempMoins,
        JoystickFocus.Commande.GAUCHE  to fanMoins,
        JoystickFocus.Commande.DROITE  to fanPlus,
        JoystickFocus.Commande.VALIDER to auto,
    )

    private val colonneChauffages: View = view.findViewById(R.id.hvac_heat_column)

    /** Un siège : son niveau en clair, et son sélecteur — l'indice d'une case EST son niveau. */
    private class Siege(racine: View, etat: Int, selecteur: Int, cases: IntArray) {
        val etat: TextView = racine.findViewById(etat)
        val selecteur: View = racine.findViewById(selecteur)
        val cases: List<View> = cases.map { racine.findViewById<View>(it) }
    }

    private val siegeGauche = Siege(view, R.id.hvac_seat_left_state, R.id.hvac_seat_left_levels, intArrayOf(
        R.id.hvac_seat_left_0, R.id.hvac_seat_left_1, R.id.hvac_seat_left_2, R.id.hvac_seat_left_3))
    private val siegeDroit = Siege(view, R.id.hvac_seat_right_state, R.id.hvac_seat_right_levels, intArrayOf(
        R.id.hvac_seat_right_0, R.id.hvac_seat_right_1, R.id.hvac_seat_right_2, R.id.hvac_seat_right_3))

    private val volant: View              = view.findViewById(R.id.hvac_wheel)
    private val volantIcone: ImageView    = view.findViewById(R.id.hvac_wheel_icon)
    private val volantTexte: TextView     = view.findViewById(R.id.hvac_wheel_label)
    private val interrupteur: FrameLayout = view.findViewById(R.id.hvac_wheel_switch)
    private val curseur: View             = view.findViewById(R.id.hvac_wheel_knob)

    // ── Palette : celle de l'application, dans son thème ─────────────────────
    private val accent          = themed.getColor(R.color.dash_accent)
    private val accentDoux      = themed.getColor(R.color.dash_accent_dim)
    private val surAccent       = themed.getColor(R.color.hvac_on_accent)
    private val chaud           = themed.getColor(R.color.dash_warn)
    private val chaudDoux       = themed.getColor(R.color.dash_warn_dim)
    private val surChaud        = themed.getColor(R.color.hvac_on_heat)
    private val vert            = themed.getColor(R.color.dash_eco)
    private val surVert         = themed.getColor(R.color.hvac_on_eco)
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
    /** AUTO tenu enfoncé : cerclé comme un éclair, jusqu'au relâchement ou à l'ouverture du choix. */
    private var autoTenu = false
    /** Curseur du choix du débit ; null = choix fermé, la rangée « − AUTO + » est là. */
    private var curseurDebit: Int? = null
    private var joystickUtilisable = true

    init {
        // Ce qui ne change pas avec l'état de la clim.
        view.findViewById<ImageView>(R.id.hvac_title_icon).imageTintList = ColorStateList.valueOf(accent)
        view.findViewById<ImageView>(R.id.hvac_fan_icon).imageTintList = ColorStateList.valueOf(accent)
        view.findViewById<TextView>(R.id.hvac_temp_unit).text = "°C"
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
        fond(rangeeDebit, bouton)
        // AUTO porte son débit sur une seconde ligne, plus petite : la mise en majuscules d'Android
        // perdrait cette taille, « AUTO » est donc écrit en capitales à la main.
        auto.isAllCaps = false
        auto.maxLines = 2
    }

    /** À appeler une fois, avant le premier [peindre]. [avecChauffages] rend leur colonne visible. */
    fun brancher(gestes: Gestes, avecChauffages: Boolean) {
        // Même chemin au doigt qu'au joystick : mêmes bornes, même anti-rebond.
        cellules.forEach { (commande, vue) ->
            if (commande != JoystickFocus.Commande.VALIDER) vue.setOnClickListener { gestes.commande(commande) }
        }
        // AUTO se joue au relâchement : tenu, il ouvre le choix du débit (issue #178).
        auto.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> gestes.autoEnfonce()
                MotionEvent.ACTION_UP -> {
                    val dedans = e.x >= 0f && e.y >= 0f && e.x <= v.width && e.y <= v.height
                    gestes.autoRelache(!dedans)
                    if (dedans) v.performClick()
                }
                MotionEvent.ACTION_CANCEL -> gestes.autoRelache(true)
            }
            true
        }
        debits.forEach { (valeur, vue) -> vue.setOnClickListener { gestes.debit(valeur) } }
        airFace.setOnClickListener       { gestes.air(AirFlow.FACE) }
        airFeet.setOnClickListener       { gestes.air(AirFlow.FEET) }
        airWindshield.setOnClickListener { gestes.air(AirFlow.WINDSHIELD) }
        airRear.setOnClickListener       { gestes.lunette() }
        ac.setOnClickListener            { gestes.ac() }
        eco.setOnClickListener           { gestes.eco() }
        recirculation.forEach { (mode, vue) -> vue.setOnClickListener { gestes.recirculation(mode) } }
        fermer.setOnClickListener        { gestes.fermer() }
        placerRepere(avecChauffages)
        if (avecChauffages) {
            colonneChauffages.visibility = View.VISIBLE
            siegeGauche.cases.forEachIndexed { niveau, vue -> vue.setOnClickListener { gestes.siegeNiveau(true, niveau) } }
            siegeDroit.cases.forEachIndexed { niveau, vue -> vue.setOnClickListener { gestes.siegeNiveau(false, niveau) } }
            volant.setOnClickListener { gestes.volant() }
        }
    }

    /**
     * Joystick utilisable : son nom, puis ce que fait chaque geste — haut / bas la température,
     * gauche / droite la ventilation, le clic AUTO. Sinon l'avertissement, seul, au même endroit.
     */
    fun repereJoystick(utilisable: Boolean) {
        joystickUtilisable = utilisable
        val couleur = if (utilisable) accent else chaud
        puceTitre.text =
            if (utilisable) localized.getString(R.string.hvac_popup_joystick_title)
            else localized.getString(R.string.hvac_popup_joystick_off_title) + " · " +
                localized.getString(R.string.hvac_popup_joystick_off_text)
        puceTitre.setTextColor(couleur)
        puceTitre.compoundDrawableTintList = ColorStateList.valueOf(couleur)
        puceGestes.forEach { geste ->
            geste.visibility = if (utilisable) View.VISIBLE else View.GONE
            geste.setTextColor(textePrincipal)
            geste.compoundDrawableTintList = ColorStateList.valueOf(accent)
        }
        fond(puce, if (utilisable) accentDoux else chaudDoux, rayon = rayonPilule)
        if (utilisable) textesRepere()
    }

    /** Ce que fait le joystick : réglages d'ordinaire, navigation dans le choix du débit. */
    private fun textesRepere() {
        val choix = curseurDebit != null
        puceTitre.text = localized.getString(
            if (choix) R.string.clim_section_autovol else R.string.hvac_popup_joystick_title)
        puceGestes[0].text = localized.getString(if (choix) R.string.hvac_popup_joy_cancel else R.string.clim_temperature)
        puceGestes[1].text = localized.getString(if (choix) R.string.hvac_popup_joy_choose else R.string.clim_fan)
        puceGestes[2].text = localized.getString(if (choix) R.string.overlay_joy_validate else R.string.clim_auto)
    }

    /** Ouvre ([curseur] non null), déplace ou referme le choix du débit en AUTO. */
    fun choix(curseur: Int?) {
        curseurDebit = curseur
        if (joystickUtilisable) textesRepere()
        dernier?.let { peindreDebit(it) }
    }

    /** AUTO tenu enfoncé ou relâché. */
    fun appuiAuto(tenu: Boolean) {
        autoTenu = tenu
        dernier?.let { peindreCroix(it) }
    }

    /**
     * Cale le repère au-dessus du cadre bleu : même largeur, même bord gauche. Sans colonne des
     * chauffages le cadre est tout à gauche, sous le titre — qui s'efface alors devant le repère
     * (invisible et non retiré : il pousse toujours le décompte et la croix à droite).
     */
    private fun placerRepere(avecChauffages: Boolean) {
        val place = cadreRepere.layoutParams as FrameLayout.LayoutParams
        place.width = zoneJoystick.layoutParams.width
        place.marginStart = if (!avecChauffages) 0 else {
            val colonne = colonneChauffages.layoutParams as ViewGroup.MarginLayoutParams
            colonne.width + colonne.marginEnd
        }
        cadreRepere.layoutParams = place
        titre.visibility = if (avecChauffages) View.VISIBLE else View.INVISIBLE
    }

    /** Un état null dans [etat] ou [chauffages] = illisible → commande grisée et inerte, plutôt que menteuse. */
    fun peindre(etat: MG4Hardware.ClimateState, chauffages: Chauffages?) {
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
            // Clim éteinte (issue #147) : « OFF », aucun segment.
            r.eteinte -> localized.getString(R.string.climate_off)
                .uppercase(localized.resources.configuration.locales[0])
            r.enAuto -> localized.getString(R.string.clim_auto)
                .uppercase(localized.resources.configuration.locales[0])
            else -> niveau?.toString() ?: "--"
        }
        barre.total = etat.fanMax
        barre.allumes = (niveau ?: 0).coerceIn(0, etat.fanMax)
        peindreCroix(etat)
        peindreDebit(etat)

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

        // ECO : plein et vert quand il est actif, comme le compresseur l'est en bleu.
        val economie = etat.ecoOn == true
        if (economie) fond(eco, vert) else fond(eco, bouton, bordure, traitFin)
        ecoIcone.imageTintList = ColorStateList.valueOf(if (economie) surVert else texteSecondaire)
        ecoTitre.setTextColor(if (economie) surVert else textePrincipal)
        inerte(eco, etat.ecoOn == null)

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

    fun decompte(secondes: Int) {
        texteDecompte.text = localized.getString(R.string.overlay_countdown, secondes)
    }

    /** Retour visuel d'un geste : au volant, c'est le seul signe que la commande est passée. */
    fun eclairer(commande: JoystickFocus.Commande) {
        val etat = dernier ?: return
        eclair = commande
        peindreCroix(etat)
        auto.postDelayed({
            // Un geste plus récent a pris le relais : c'est son propre délai qui éteindra.
            if (eclair == commande) {
                eclair = null
                dernier?.let { peindreCroix(it) }
            }
        }, FLASH_MS)
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
        auto.text = texteAuto(etat.autoVolume)
        tuile(auto, if (r.enAuto) true else etat.autoOn, plein = true)
        if (eclair == JoystickFocus.Commande.VALIDER || autoTenu) {
            (auto.background as? GradientDrawable)?.setStroke(traitEpais, textePrincipal)
        }
    }

    /** « AUTO », et dessous, plus petit, le débit réglé pour l'AUTO quand la voiture le donne. */
    private fun texteAuto(debit: Int?): CharSequence {
        val locale = localized.resources.configuration.locales[0]
        val titre = localized.getString(R.string.clim_auto).uppercase(locale)
        val libelle = debit?.let { libelleDebit(it) } ?: return titre
        return SpannableStringBuilder(titre).append('\n').apply {
            val debut = length
            append(libelle)
            setSpan(RelativeSizeSpan(0.75f), debut, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun libelleDebit(debit: Int): String? = when (debit) {
        MG4Hardware.AutoVolume.LOW    -> localized.getString(R.string.clim_autovol_low)
        MG4Hardware.AutoVolume.MEDIUM -> localized.getString(R.string.clim_autovol_medium)
        MG4Hardware.AutoVolume.HIGH   -> localized.getString(R.string.clim_autovol_high)
        else -> null
    }

    /**
     * Choix du débit (maquette B) : il prend la place de la rangée « − AUTO + ». Le débit réglé
     * sur la voiture est plein d'accent ; le curseur du joystick est cerclé, comme un éclair.
     */
    private fun peindreDebit(etat: MG4Hardware.ClimateState) {
        val ouvert = curseurDebit != null
        rangeeCrans.visibility = if (ouvert) View.GONE else View.VISIBLE
        rangeeDebit.visibility = if (ouvert) View.VISIBLE else View.GONE
        libelleVentilation.text = localized.getString(if (ouvert) R.string.clim_section_autovol else R.string.clim_fan)
        if (!ouvert) return
        debits.forEach { (valeur, vue) ->
            val regle = etat.autoVolume == valeur
            val vise = curseurDebit == valeur
            val trait = if (vise) (if (regle) textePrincipal else accent) else Color.TRANSPARENT
            val epaisseur = if (vise) traitEpais else 0
            when {
                regle -> { fond(vue, accent, trait, epaisseur, rayon = rayonSegment); vue.setTextColor(surAccent) }
                vise  -> { fond(vue, accentDoux, trait, epaisseur, rayon = rayonSegment); vue.setTextColor(textePrincipal) }
                else  -> { fond(vue, Color.TRANSPARENT, rayon = rayonSegment); vue.setTextColor(texteSecondaire) }
            }
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
        OverlayKit.fond(vue, remplissage, trait, epaisseur, rayon, ovale)
    }

    private fun dp(context: Context, value: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    private companion object {
        const val FLASH_MS = 180L
    }
}
