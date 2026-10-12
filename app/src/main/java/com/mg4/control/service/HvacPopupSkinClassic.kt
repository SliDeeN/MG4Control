package com.mg4.control.service

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.mg4.control.R
import com.mg4.control.accessibility.JoystickFocus
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.model.AirFlow
import com.mg4.control.model.HvacPopup

/**
 * Présentation d'origine du pop-up HVAC (overlay_hvac_popup.xml) : la croix du joystick à côté
 * des valeurs, des boutons Material, et les sièges chauffants au cran — un appui, un cran.
 */
internal class HvacPopupSkinClassic(
    view: View,
    themed: Context,
    override val localized: Context,
) : HvacPopupSkin {

    private val temperature: TextView         = view.findViewById(R.id.hvac_popup_temp)
    private val ventilation: TextView         = view.findViewById(R.id.hvac_popup_fan)
    private val barreVentilation: ProgressBar = view.findViewById(R.id.hvac_popup_fan_bar)
    private val texteDecompte: TextView       = view.findViewById(R.id.hvac_popup_countdown)
    private val joystickIcone: ImageView      = view.findViewById(R.id.hvac_joy_icon)
    private val joystickTitre: TextView       = view.findViewById(R.id.hvac_joy_title)
    private val joystickTexte: TextView       = view.findViewById(R.id.hvac_joy_text)
    private val airFace: MaterialButton       = view.findViewById(R.id.hvac_btn_air_face)
    private val airFeet: MaterialButton       = view.findViewById(R.id.hvac_btn_air_feet)
    private val airWindshield: MaterialButton = view.findViewById(R.id.hvac_btn_air_windshield_front)
    private val airRear: MaterialButton       = view.findViewById(R.id.hvac_btn_air_windshield_rear)
    private val ac: MaterialButton            = view.findViewById(R.id.hvac_btn_ac)
    private val colonneChauffages: View       = view.findViewById(R.id.hvac_heat_column)
    private val siegeGauche: MaterialButton   = view.findViewById(R.id.hvac_btn_seat_left)
    private val siegeDroit: MaterialButton    = view.findViewById(R.id.hvac_btn_seat_right)
    private val volant: MaterialButton        = view.findViewById(R.id.hvac_btn_steering_heat)
    private val fermer: MaterialButton        = view.findViewById(R.id.hvac_btn_close)

    private val recirculation: Map<Int, MaterialButton> = mapOf(
        MG4Hardware.LoopMode.INNER   to view.findViewById<MaterialButton>(R.id.hvac_btn_loop_inner),
        MG4Hardware.LoopMode.OUTSIDE to view.findViewById<MaterialButton>(R.id.hvac_btn_loop_outside),
        MG4Hardware.LoopMode.AUTO    to view.findViewById<MaterialButton>(R.id.hvac_btn_loop_auto),
    )

    private val cellules: Map<JoystickFocus.Commande, MaterialButton> = mapOf(
        JoystickFocus.Commande.HAUT    to view.findViewById<MaterialButton>(R.id.hvac_btn_temp_up),
        JoystickFocus.Commande.BAS     to view.findViewById<MaterialButton>(R.id.hvac_btn_temp_down),
        JoystickFocus.Commande.GAUCHE  to view.findViewById<MaterialButton>(R.id.hvac_btn_fan_down),
        JoystickFocus.Commande.DROITE  to view.findViewById<MaterialButton>(R.id.hvac_btn_fan_up),
        JoystickFocus.Commande.VALIDER to view.findViewById<MaterialButton>(R.id.hvac_btn_auto),
    )

    private val fondActif    = themed.getColor(R.color.dash_accent_dim)
    private val fondInactif  = themed.getColor(R.color.dash_btn)
    private val texteActif   = themed.getColor(R.color.dash_accent)
    private val texteInactif = themed.getColor(R.color.text_secondary)
    private val bordure      = themed.getColor(R.color.dash_border)
    private val alerte       = themed.getColor(R.color.dash_warn)
    private val traitFin     = dp(themed, 1f)
    private val traitEpais   = dp(themed, 3f)

    override fun brancher(gestes: HvacPopupSkin.Gestes, avecChauffages: Boolean) {
        // Même chemin au doigt qu'au joystick : mêmes bornes, même anti-rebond.
        cellules.forEach { (commande, bouton) -> bouton.setOnClickListener { gestes.commande(commande) } }
        airFace.setOnClickListener       { gestes.air(AirFlow.FACE) }
        airFeet.setOnClickListener       { gestes.air(AirFlow.FEET) }
        airWindshield.setOnClickListener { gestes.air(AirFlow.WINDSHIELD) }
        airRear.setOnClickListener       { gestes.lunette() }
        ac.setOnClickListener            { gestes.ac() }
        recirculation.forEach { (mode, bouton) -> bouton.setOnClickListener { gestes.recirculation(mode) } }
        fermer.setOnClickListener        { gestes.fermer() }
        if (avecChauffages) {
            colonneChauffages.visibility = View.VISIBLE
            siegeGauche.setOnClickListener { gestes.siegeSuivant(true) }
            siegeDroit.setOnClickListener  { gestes.siegeSuivant(false) }
            volant.setOnClickListener      { gestes.volant() }
        }
    }

    /** L'état « utilisable » est celui de la mise en page : seul l'autre demande d'y toucher. */
    override fun repereJoystick(utilisable: Boolean) {
        if (utilisable) return
        joystickIcone.setImageResource(R.drawable.ic_hvac_joystick_off)
        joystickTitre.setText(R.string.hvac_popup_joystick_off_title)
        joystickTitre.setTextColor(alerte)
        joystickTexte.setText(R.string.hvac_popup_joystick_off_text)
    }

    override fun peindre(etat: MG4Hardware.ClimateState, chauffages: HvacPopupSkin.Chauffages?) {
        val s = etat
        temperature.text = s.tempC?.let { "$it °C" } ?: "-- °C"
        // En AUTO la voiture annonce 15 pour la ventilation : on écrit « AUTO », pas ce nombre,
        // et la barre reste vide — il n'y a pas de cran à montrer.
        val r = s.reglages()
        val niveau = r.ventilationReelle
        ventilation.text = when {
            r.enAuto -> localized.getString(R.string.clim_auto)
                .uppercase(localized.resources.configuration.locales[0])
            else -> niveau?.toString() ?: "--"
        }
        barreVentilation.max = s.fanMax
        barreVentilation.progress = (niveau ?: 0).coerceIn(0, s.fanMax)
        bascule(cellules.getValue(JoystickFocus.Commande.VALIDER), if (r.enAuto) true else s.autoOn)
        bascule(ac, s.acOn)
        recirculation.forEach { (mode, bouton) -> bascule(bouton, s.loopMode?.let { it == mode }) }
        // Valeur illisible → boutons grisés. Valeur lue mais hors échelle (7 « aucun »)
        // → boutons actifs, aucun allumé : l'utilisateur peut choisir un sens.
        val air = s.airFlow?.let { AirFlow.partsOf(it) }
        bascule(airFace,       s.airFlow?.let { air?.face == true })
        bascule(airFeet,       s.airFlow?.let { air?.feet == true })
        bascule(airWindshield, s.airFlow?.let { air?.windshield == true })
        bascule(airRear,       s.defrostRear)
        chauffages?.let { c ->
            siege(siegeGauche, R.string.climate_seat_left, c.siegeGauche)
            siege(siegeDroit, R.string.climate_seat_right, c.siegeDroit)
            bascule(volant, c.volant)
            volant.text = localized.getString(R.string.climate_steering_heat) + "\n" +
                localized.getString(if (c.volant == true) R.string.climate_on else R.string.climate_off)
        }
    }

    override fun decompte(secondes: Int) {
        texteDecompte.text = localized.getString(R.string.overlay_countdown, secondes)
    }

    override fun eclairer(commande: JoystickFocus.Commande) {
        val bouton = cellules[commande] ?: return
        bouton.strokeColor = ColorStateList.valueOf(texteActif)
        bouton.strokeWidth = traitEpais
        bouton.postDelayed({
            bouton.strokeColor = ColorStateList.valueOf(bordure)
            bouton.strokeWidth = traitFin
        }, HvacPopupSkin.FLASH_MS)
    }

    /**
     * Bouton bascule, peint comme ceux de la page Clim : accentué quand actif.
     * Un état null = non lisible sur ce firmware → bouton grisé et inerte, plutôt que menteur.
     */
    private fun bascule(bouton: MaterialButton, actif: Boolean?) {
        val allume = actif == true
        bouton.backgroundTintList = ColorStateList.valueOf(if (allume) fondActif else fondInactif)
        bouton.setTextColor(if (allume) texteActif else texteInactif)
        bouton.iconTint = ColorStateList.valueOf(if (allume) texteActif else texteInactif)
        bouton.isEnabled = actif != null
        bouton.alpha = if (actif != null) 1f else 0.35f
    }

    /** Siège chauffant : son nom, et son niveau en pastilles ; allumé dès le niveau 1. */
    private fun siege(bouton: MaterialButton, nom: Int, niveau: Int?) {
        bascule(bouton, niveau?.let { it > 0 })
        bouton.text = localized.getString(nom) + "\n" + HvacPopup.pastilles(niveau ?: 0)
    }

    private fun dp(context: Context, value: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()
}
