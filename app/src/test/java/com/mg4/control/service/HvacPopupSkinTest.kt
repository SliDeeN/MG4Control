package com.mg4.control.service

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.R
import com.mg4.control.accessibility.JoystickFocus.Commande
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.model.AirFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Les deux présentations du pop-up HVAC se gonflent, se branchent et se peignent.
 *
 * Une mise en page se trompe sans que le compilateur le voie : un identifiant posé sur une vue
 * d'un autre type passe à la compilation et lève à l'ouverture de la fenêtre, dans la voiture.
 * Ces tests font ce chemin ici — gonfler, lier chaque vue, peindre un état complet puis un état
 * entièrement illisible — et vérifient que chaque toucher remonte le bon geste.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HvacPopupSkinTest {

    private val themed: Context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_MG4Control)

    private val complet = MG4Hardware.ClimateState(
        powerOn = true, tempC = 21, tempMin = 17, tempMax = 33,
        fanLevel = 3, fanMin = 1, fanMax = 7,
        acOn = true, autoOn = false, loopMode = MG4Hardware.LoopMode.AUTO,
        defrostFront = false, defrostRear = false, airFlow = 0, lastManualFan = 3)

    /** Tout ce qui peut être illisible l'est : c'est l'état d'un firmware qui n'expose rien. */
    private val illisible = MG4Hardware.ClimateState(
        powerOn = null, tempC = null, tempMin = 17, tempMax = 33,
        fanLevel = null, fanMin = 1, fanMax = 7,
        acOn = null, autoOn = null, loopMode = null,
        defrostFront = null, defrostRear = null, airFlow = null)

    private val recus = mutableListOf<String>()
    private val gestes = HvacPopupSkin.Gestes(
        commande      = { recus += "commande ${it.name}" },
        air           = { recus += "air $it" },
        lunette       = { recus += "lunette" },
        ac            = { recus += "ac" },
        recirculation = { recus += "recirculation $it" },
        siegeSuivant  = { recus += "siège suivant ${if (it) "gauche" else "droit"}" },
        siegeNiveau   = { gauche, niveau -> recus += "siège ${if (gauche) "gauche" else "droit"} niveau $niveau" },
        volant        = { recus += "volant" },
        fermer        = { recus += "fermer" },
    )

    private fun gonfler(style: HvacPopupStyle): Pair<View, HvacPopupSkin> {
        val view = LayoutInflater.from(themed).inflate(style.layout, null)
        val skin: HvacPopupSkin = when (style) {
            HvacPopupStyle.CLASSIC -> HvacPopupSkinClassic(view, themed, themed)
            HvacPopupStyle.MODERN  -> HvacPopupSkinModern(view, themed, themed)
        }
        return view to skin
    }

    /** Comme un doigt : `performClick` seul appellerait aussi l'écouteur d'une vue désactivée. */
    private fun toucher(view: View, id: Int) {
        val cible = view.findViewById<View>(id)
        if (cible.isEnabled && cible.isClickable) cible.performClick()
    }

    @Test
    fun `chaque presentation se gonfle et peint tous les etats`() {
        for (style in HvacPopupStyle.entries) {
            for (avecChauffages in listOf(true, false)) {
                val (_, skin) = gonfler(style)
                skin.brancher(gestes, avecChauffages)
                skin.repereJoystick(utilisable = true)
                skin.repereJoystick(utilisable = false)
                skin.decompte(10)
                // Un geste AVANT le premier état peint ne doit rien casser.
                Commande.entries.forEach { skin.eclairer(it) }
                val chauffages = HvacPopupSkin.Chauffages(siegeGauche = 0, siegeDroit = 2, volant = true)
                    .takeIf { avecChauffages }
                skin.peindre(complet, chauffages)
                // Les deux autres niveaux de siège : chacun a sa couleur.
                skin.peindre(complet, chauffages?.copy(siegeGauche = 1, siegeDroit = 3))
                // AUTO : la voiture annonce 15, ce n'est pas un cran.
                skin.peindre(complet.copy(fanLevel = 15, autoOn = true), chauffages)
                skin.peindre(illisible, HvacPopupSkin.Chauffages(null, null, null).takeIf { avecChauffages })
                // L'éclair d'un geste, puis son extinction une fois le délai passé.
                Commande.entries.forEach { skin.eclairer(it) }
                ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            }
        }
    }

    @Test
    fun `la presentation moderne remonte chaque toucher`() {
        val (view, skin) = gonfler(HvacPopupStyle.MODERN)
        skin.brancher(gestes, avecChauffages = true)
        skin.peindre(complet, HvacPopupSkin.Chauffages(siegeGauche = 0, siegeDroit = 2, volant = false))

        toucher(view, R.id.hvac2_temp_up)
        toucher(view, R.id.hvac2_temp_down)
        toucher(view, R.id.hvac2_fan_down)
        toucher(view, R.id.hvac2_fan_up)
        toucher(view, R.id.hvac2_auto)
        toucher(view, R.id.hvac2_air_face)
        toucher(view, R.id.hvac2_air_feet)
        toucher(view, R.id.hvac2_air_windshield_front)
        toucher(view, R.id.hvac2_air_windshield_rear)
        toucher(view, R.id.hvac2_ac)
        toucher(view, R.id.hvac2_loop_inner)
        toucher(view, R.id.hvac2_loop_outside)
        toucher(view, R.id.hvac2_loop_auto)
        toucher(view, R.id.hvac2_seat_left_3)
        toucher(view, R.id.hvac2_seat_right_0)
        toucher(view, R.id.hvac2_wheel)
        toucher(view, R.id.hvac2_close)

        assertEquals(listOf(
            "commande HAUT", "commande BAS", "commande GAUCHE", "commande DROITE", "commande VALIDER",
            "air ${AirFlow.FACE}", "air ${AirFlow.FEET}", "air ${AirFlow.WINDSHIELD}", "lunette",
            "ac",
            "recirculation ${MG4Hardware.LoopMode.INNER}", "recirculation ${MG4Hardware.LoopMode.OUTSIDE}",
            "recirculation ${MG4Hardware.LoopMode.AUTO}",
            "siège gauche niveau 3", "siège droit niveau 0", "volant", "fermer",
        ), recus)
    }

    @Test
    fun `la presentation d origine remonte les memes gestes, les sieges au cran`() {
        val (view, skin) = gonfler(HvacPopupStyle.CLASSIC)
        skin.brancher(gestes, avecChauffages = true)
        skin.peindre(complet, HvacPopupSkin.Chauffages(siegeGauche = 0, siegeDroit = 2, volant = false))

        toucher(view, R.id.hvac_btn_temp_up)
        toucher(view, R.id.hvac_btn_auto)
        toucher(view, R.id.hvac_btn_air_windshield_rear)
        toucher(view, R.id.hvac_btn_ac)
        toucher(view, R.id.hvac_btn_loop_inner)
        toucher(view, R.id.hvac_btn_seat_left)
        toucher(view, R.id.hvac_btn_seat_right)
        toucher(view, R.id.hvac_btn_steering_heat)
        toucher(view, R.id.hvac_btn_close)

        assertEquals(listOf(
            "commande HAUT", "commande VALIDER", "lunette", "ac",
            "recirculation ${MG4Hardware.LoopMode.INNER}",
            "siège suivant gauche", "siège suivant droit", "volant", "fermer",
        ), recus)
    }

    @Test
    fun `une commande illisible ne se touche pas`() {
        val (view, skin) = gonfler(HvacPopupStyle.MODERN)
        skin.brancher(gestes, avecChauffages = true)
        skin.peindre(illisible, HvacPopupSkin.Chauffages(null, null, null))

        toucher(view, R.id.hvac2_ac)
        toucher(view, R.id.hvac2_loop_inner)
        toucher(view, R.id.hvac2_air_face)
        toucher(view, R.id.hvac2_seat_left_3)
        toucher(view, R.id.hvac2_wheel)

        assertEquals(emptyList<String>(), recus)
    }

    /**
     * La carte « moderne » ne doit sa taille qu'à des cotes fixes : c'est ce qui permet à
     * [HvacPopupStyle] d'annoncer sa largeur et sa hauteur, d'où vient l'agrandissement à l'écran.
     * Les modifier dans la mise en page sans les reporter fausserait la part d'écran occupée.
     */
    @Test
    fun `la carte moderne a la taille annoncee`() {
        val densite = themed.resources.displayMetrics.density
        val style = HvacPopupStyle.MODERN
        val colonneChauffagesDp = 224f + 12f

        for (avecChauffages in listOf(false, true)) {
            val (view, skin) = gonfler(style)
            skin.brancher(gestes, avecChauffages)
            view.measure(
                MeasureSpec.makeMeasureSpec((2400 * densite).toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((1200 * densite).toInt(), MeasureSpec.EXACTLY))
            val carte = (view as ViewGroup).getChildAt(0)
            val largeurDp = style.largeurDp + if (avecChauffages) colonneChauffagesDp else 0f
            assertEquals(largeurDp * densite, carte.measuredWidth.toFloat(), 1f)
            assertEquals(style.hauteurDp * densite, carte.measuredHeight.toFloat(), 1f)
        }
    }
}
