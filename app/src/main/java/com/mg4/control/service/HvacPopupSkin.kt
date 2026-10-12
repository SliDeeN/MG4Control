package com.mg4.control.service

import android.content.Context
import androidx.annotation.LayoutRes
import com.mg4.control.R
import com.mg4.control.accessibility.JoystickFocus
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.model.HvacPopup

/**
 * Présentations du pop-up HVAC. Deux coexistent, chacune sur son raccourci, le temps de les
 * comparer en voiture : celle d'origine, et la « moderne », reprise de la maquette d'un
 * utilisateur (2026-10-12). Même fenêtre, mêmes réglages, mêmes gestes du joystick — seule la mise
 * en page change. Celle qui ne sera pas gardée s'enlève d'un bloc : sa valeur ici, sa mise en
 * page, sa classe [HvacPopupSkin] et son raccourci.
 *
 * [largeurDp] × [hauteurDp] : la carte de base de la mise en page, sans sa colonne des chauffages
 * (voir [HvacPopup.echelle]).
 */
enum class HvacPopupStyle(@LayoutRes val layout: Int, val largeurDp: Float, val hauteurDp: Float) {
    CLASSIC(R.layout.overlay_hvac_popup, HvacPopup.LARGEUR_DP, HvacPopup.HAUTEUR_DP),
    MODERN(R.layout.overlay_hvac_popup_modern, 836f, 392f),
}

/**
 * Ce que [HvacPopupOverlay] demande à une présentation : brancher les gestes sur ses vues, puis
 * peindre l'état qu'on lui donne. Aucune règle ici — ni borne, ni anti-rebond, ni écriture : tout
 * cela reste dans la fenêtre, commun aux deux présentations.
 *
 * Vit et meurt avec le popup affiché ; thread principal seulement.
 */
internal interface HvacPopupSkin {

    /** Contexte dans la langue de l'application, pour les textes et les Toast. */
    val localized: Context

    /**
     * Sièges et volant chauffants ; null (côté fenêtre) sur les firmwares qui n'en ont pas, et la
     * colonne reste alors masquée. Un champ à null = illisible : sa commande est grisée.
     */
    data class Chauffages(val siegeGauche: Int?, val siegeDroit: Int?, val volant: Boolean?)

    /** Ce qu'un toucher peut demander à la fenêtre. Le joystick, lui, n'envoie que [commande]. */
    class Gestes(
        /** Température ±, ventilation ±, AUTO : même chemin au doigt qu'au joystick. */
        val commande: (JoystickFocus.Commande) -> Unit,
        /** Bouton cumulable du sens de l'air ([com.mg4.control.model.AirFlow]). */
        val air: (part: Int) -> Unit,
        val lunette: () -> Unit,
        val ac: () -> Unit,
        /** Choix direct d'un mode ([MG4Hardware.LoopMode]). */
        val recirculation: (mode: Int) -> Unit,
        /** Siège chauffant : un cran, la commande même de la voiture. */
        val siegeSuivant: (gauche: Boolean) -> Unit,
        /** Siège chauffant : un niveau précis, de 0 (éteint) à 3. */
        val siegeNiveau: (gauche: Boolean, niveau: Int) -> Unit,
        val volant: () -> Unit,
        val fermer: () -> Unit,
    )

    /** À appeler une fois, avant le premier [peindre]. [avecChauffages] rend leur colonne visible. */
    fun brancher(gestes: Gestes, avecChauffages: Boolean)

    /**
     * La zone du joystick se pilote au joystick droit du volant. Sans service d'accessibilité il
     * ne nous parvient pas (il règle le volume) : le repère s'éteint et renvoie vers l'écran.
     */
    fun repereJoystick(utilisable: Boolean)

    /** Un état null dans [etat] ou [chauffages] = illisible → commande grisée et inerte, plutôt que menteuse. */
    fun peindre(etat: MG4Hardware.ClimateState, chauffages: Chauffages?)

    fun decompte(secondes: Int)

    /** Retour visuel d'un geste : au volant, c'est le seul signe que la commande est passée. */
    fun eclairer(commande: JoystickFocus.Commande)

    companion object {
        const val FLASH_MS = 180L
    }
}
