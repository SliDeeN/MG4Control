package com.mg4.control.service

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.mg4.control.R
import com.mg4.control.accessibility.JoystickFocus.Commande
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.model.AirFlow
import com.mg4.control.model.HvacPopup
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Le pop-up HVAC se gonfle, se branche et se peint.
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
        siegeNiveau   = { gauche, niveau -> recus += "siège ${if (gauche) "gauche" else "droit"} niveau $niveau" },
        volant        = { recus += "volant" },
        fermer        = { recus += "fermer" },
        autoEnfonce   = { recus += "auto enfoncé" },
        autoRelache   = { annule -> recus += if (annule) "auto annulé" else "auto relâché" },
    )

    private fun gonfler(): Pair<View, HvacPopupSkin> {
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_hvac_popup, null)
        return view to HvacPopupSkin(view, themed, themed)
    }

    /** Comme un doigt : `performClick` seul appellerait aussi l'écouteur d'une vue désactivée. */
    private fun toucher(view: View, id: Int) {
        val cible = view.findViewById<View>(id)
        if (cible.isEnabled && cible.isClickable) cible.performClick()
    }

    /**
     * Un vrai doigt, posé puis levé sur la vue : AUTO se joue au relâchement, pas au clic, pour
     * qu'un appui tenu fasse avancer le débit (issue #178).
     */
    private fun appuyer(view: View, id: Int) {
        val cible = view.findViewById<View>(id)
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val e = MotionEvent.obtain(0L, 0L, action, 0f, 0f, 0)
            cible.dispatchTouchEvent(e)
            e.recycle()
        }
    }

    @Test
    fun `le pop-up se gonfle et peint tous les etats`() {
        for (avecChauffages in listOf(true, false)) {
            val (_, skin) = gonfler()
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

    @Test
    fun `chaque toucher remonte son geste`() {
        val (view, skin) = gonfler()
        skin.brancher(gestes, avecChauffages = true)
        skin.peindre(complet, HvacPopupSkin.Chauffages(siegeGauche = 0, siegeDroit = 2, volant = false))

        toucher(view, R.id.hvac_temp_up)
        toucher(view, R.id.hvac_temp_down)
        toucher(view, R.id.hvac_fan_down)
        toucher(view, R.id.hvac_fan_up)
        appuyer(view, R.id.hvac_auto)
        toucher(view, R.id.hvac_air_face)
        toucher(view, R.id.hvac_air_feet)
        toucher(view, R.id.hvac_air_windshield_front)
        toucher(view, R.id.hvac_air_windshield_rear)
        toucher(view, R.id.hvac_ac)
        toucher(view, R.id.hvac_loop_inner)
        toucher(view, R.id.hvac_loop_outside)
        toucher(view, R.id.hvac_loop_auto)
        toucher(view, R.id.hvac_seat_left_3)
        toucher(view, R.id.hvac_seat_right_0)
        toucher(view, R.id.hvac_wheel)
        toucher(view, R.id.hvac_close)

        assertEquals(listOf(
            // AUTO remonte l'appui et le relâchement : c'est l'overlay qui en fait la bascule AUTO.
            "commande HAUT", "commande BAS", "commande GAUCHE", "commande DROITE", "auto enfoncé", "auto relâché",
            "air ${AirFlow.FACE}", "air ${AirFlow.FEET}", "air ${AirFlow.WINDSHIELD}", "lunette",
            "ac",
            "recirculation ${MG4Hardware.LoopMode.INNER}", "recirculation ${MG4Hardware.LoopMode.OUTSIDE}",
            "recirculation ${MG4Hardware.LoopMode.AUTO}",
            "siège gauche niveau 3", "siège droit niveau 0", "volant", "fermer",
        ), recus)
    }

    @Test
    fun `une commande illisible ne se touche pas`() {
        val (view, skin) = gonfler()
        skin.brancher(gestes, avecChauffages = true)
        skin.peindre(illisible, HvacPopupSkin.Chauffages(null, null, null))

        toucher(view, R.id.hvac_ac)
        toucher(view, R.id.hvac_loop_inner)
        toucher(view, R.id.hvac_air_face)
        toucher(view, R.id.hvac_seat_left_3)
        toucher(view, R.id.hvac_wheel)

        assertEquals(emptyList<String>(), recus)
    }

    /**
     * La carte ne doit sa taille qu'à des cotes fixes : c'est ce qui permet à [HvacPopup]
     * d'annoncer sa largeur et sa hauteur, d'où vient l'agrandissement à l'écran. Les modifier
     * dans la mise en page sans les reporter fausserait la part d'écran occupée.
     */
    @Test
    fun `la carte a la taille annoncee`() {
        val densite = themed.resources.displayMetrics.density

        for (avecChauffages in listOf(false, true)) {
            val (view, skin) = gonfler()
            skin.brancher(gestes, avecChauffages)
            view.measure(
                MeasureSpec.makeMeasureSpec((2400 * densite).toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((1200 * densite).toInt(), MeasureSpec.EXACTLY))
            val carte = (view as ViewGroup).getChildAt(0)
            val largeurDp = HvacPopup.LARGEUR_DP + if (avecChauffages) HvacPopup.CHAUFFAGES_DP else 0f
            assertEquals(largeurDp * densite, carte.measuredWidth.toFloat(), 1f)
            assertEquals(HvacPopup.HAUTEUR_DP * densite, carte.measuredHeight.toFloat(), 1f)
        }
    }
}
