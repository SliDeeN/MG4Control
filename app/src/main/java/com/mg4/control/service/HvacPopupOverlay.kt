package com.mg4.control.service

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.mg4.control.R
import com.mg4.control.accessibility.JoystickFocus
import com.mg4.control.accessibility.KeyCaptureService
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.model.AirFlow
import com.mg4.control.model.HvacPopup
import com.mg4.control.util.LocaleHelper
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Pop-up HVAC (issues #120 et #125) : réglage rapide de la clim par-dessus n'importe quel écran,
 * Android Auto en plein écran compris, là où le bandeau clim d'origine n'est plus accessible.
 *
 * Calqué sur [ProfileConfirmOverlay] pour la fenêtre, mais le joystick droit n'y déplace AUCUN
 * focus : chaque direction agit directement ([HvacPopup]). Le sens de l'air se règle au doigt.
 * Les cellules de la croix se touchent aussi — sans service d'accessibilité, le joystick ne nous
 * parvient pas (et règle le volume) : l'écran est alors le seul moyen d'agir.
 *
 * Aucun verrou de conduite : la clim est un réglage de confort (cf. T-904), et c'est justement
 * en roulant que ce popup sert.
 *
 * Ouvert et refermé par le même raccourci ([toggle]) ; se referme seul après [AUTO_DISMISS_MS]
 * sans action.
 */
object HvacPopupOverlay {

    private const val TAG = "MG4_OVERLAY"
    private const val AUTO_DISMISS_MS = 6_000L
    private const val TICK_MS = 100L

    /** Plusieurs pas rapprochés du joystick ne donnent qu'UNE écriture, celle de la valeur finale. */
    private const val WRITE_DEBOUNCE_MS = 200L

    /** Le véhicule met un instant à propager : relire trop tôt rendrait l'ANCIENNE valeur. */
    private const val SETTLE_MS = 700L
    private const val FLASH_MS = 180L

    private val handler = Handler(Looper.getMainLooper())

    /** Lectures et écritures clim : appels binder, certains bloquants (bascules relues). */
    private val vehicule = Executors.newSingleThreadExecutor()

    @Volatile private var overlayView: View? = null

    // ── Tout ce qui suit : thread principal seulement ────────────────────────
    private var ouvertureEnCours = false
    private var vues: Vues? = null
    private var gestionnaire: WindowManager? = null

    /** Dernier état lu, corrigé de ce qui vient d'être demandé (voir [surCommande]). */
    private var etat: MG4Hardware.ClimateState? = null
    private var echeance = 0L
    private var tick: Runnable? = null

    /** Écritures en attente d'anti-rebond, par réglage. */
    private val differees = mutableMapOf<String, Runnable>()
    private var ecrituresEnVol = 0

    /**
     * Vrai si le popup est à l'écran. Lu depuis [KeyCaptureService] pour décider, AU MOMENT de
     * l'appui, si le joystick doit être avalé.
     */
    fun isShowing(): Boolean = overlayView != null

    /**
     * Commande du joystick droit reçue pendant que le popup est ouvert.
     * Peut être appelé depuis n'importe quel thread ; sans effet si le popup s'est fermé entre-temps.
     */
    fun naviguer(commande: JoystickFocus.Commande) {
        handler.post { surCommande(commande) }
    }

    /** Raccourci : ouvre le popup, ou le referme s'il est déjà à l'écran. */
    fun toggle(context: Context) {
        val app = context.applicationContext
        handler.post {
            if (overlayView != null) {
                AppLogger.i(TAG, "Pop-up HVAC — second appui du raccourci → fermeture")
                fermer()
                return@post
            }
            if (ouvertureEnCours) return@post
            ouvertureEnCours = true
            vehicule.execute {
                val lu = lireEtat()
                handler.post {
                    ouvertureEnCours = false
                    // Sans état, il n'y aurait ni valeur à montrer ni base pour « un cran de plus ».
                    if (lu == null) AppLogger.w(TAG, "Pop-up HVAC non affiché : état clim illisible")
                    else showOnMain(app, lu)
                }
            }
        }
    }

    private fun lireEtat(): MG4Hardware.ClimateState? = try {
        MG4Hardware.getClimateState()
    } catch (e: Exception) {
        AppLogger.w(TAG, "Pop-up HVAC — lecture clim : ${e.message}")
        null
    }

    private fun showOnMain(context: Context, lu: MG4Hardware.ClimateState) {
        fermer()

        val localized = LocaleHelper.applyLocale(context)
        // La mise en page est écrite pour une carte de 600 × 375 dp, trop petite sur l'écran de la
        // voiture (constaté sur SWI133). Plutôt que de figer d'autres dp, qui ne conviendraient
        // qu'à UNE densité, on la gonfle avec une densité corrigée : dp et sp suivent d'un bloc,
        // pictos et texte restent nets, et la fenêtre prend la même part de l'écran partout.
        val mesures = localized.resources.displayMetrics
        val echelle = HvacPopup.echelle(mesures.widthPixels, mesures.heightPixels, mesures.density)
        val agrandi = localized.createConfigurationContext(
            Configuration(localized.resources.configuration).apply {
                densityDpi = (mesures.densityDpi * echelle).roundToInt()
            })
        val themed = ContextThemeWrapper(agrandi, R.style.Theme_MG4Control)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_hvac_popup, null)
        val v = Vues(view, themed, localized)

        view.findViewById<View>(R.id.hvac_backdrop).setOnClickListener { fermer() }
        // Même chemin au doigt qu'au joystick : mêmes bornes, même anti-rebond.
        v.cellules.forEach { (commande, bouton) -> bouton.setOnClickListener { surCommande(commande) } }
        v.airFace.setOnClickListener       { basculerAir(AirFlow.FACE) }
        v.airFeet.setOnClickListener       { basculerAir(AirFlow.FEET) }
        v.airWindshield.setOnClickListener { basculerAir(AirFlow.WINDSHIELD) }
        v.airRear.setOnClickListener       { basculerLunette() }
        if (!KeyCaptureService.isEnabled(context)) v.aide.visibility = View.VISIBLE

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        wm.addView(view, params)
        gestionnaire = wm
        vues = v
        etat = lu
        overlayView = view
        AppLogger.i(TAG, "Pop-up HVAC affiché : ${lu.tempC} °C (${lu.tempMin}..${lu.tempMax}), " +
            "ventilation ${lu.fanLevel} (${lu.fanMin}..${lu.fanMax}), sens de l'air ${lu.airFlow} ; " +
            "échelle ×${String.format(Locale.US, "%.2f", echelle)} (écran ${mesures.widthPixels}×" +
            "${mesures.heightPixels} px, ${mesures.densityDpi} dpi)")
        afficher()

        relancerDelai()
        val t = object : Runnable {
            override fun run() {
                val actuelles = vues ?: return
                val reste = echeance - SystemClock.uptimeMillis()
                if (reste <= 0) {
                    AppLogger.i(TAG, "Pop-up HVAC — délai écoulé → fermeture")
                    fermer()
                    return
                }
                actuelles.minuterie.progress = reste.toInt()
                actuelles.decompte.text = actuelles.localized.getString(
                    R.string.overlay_countdown, ((reste + 999) / 1_000).toInt())
                handler.postDelayed(this, TICK_MS)
            }
        }
        tick = t
        handler.post(t)
    }

    /** Chaque geste, au joystick comme au doigt, redonne le délai complet. */
    private fun relancerDelai() {
        echeance = SystemClock.uptimeMillis() + AUTO_DISMISS_MS
    }

    private fun surCommande(commande: JoystickFocus.Commande) {
        val s = etat ?: return
        if (overlayView == null) return
        relancerDelai()
        vues?.eclairer(commande)
        when (val action = HvacPopup.action(commande, s.reglages())) {
            // L'état est corrigé TOUT DE SUITE de la valeur demandée : le pas suivant doit en
            // partir, sans attendre la relecture — sinon deux pressions rapides n'en feraient qu'une.
            is HvacPopup.Action.Temperature -> {
                AppLogger.i(TAG, "Pop-up HVAC — ${commande.name} : température ${s.tempC} → ${action.cible}")
                etat = s.copy(tempC = action.cible)
                afficher()
                ecrireDiffere("température") { MG4Hardware.setClimateTemp(action.cible) }
            }
            is HvacPopup.Action.Ventilation -> {
                AppLogger.i(TAG, "Pop-up HVAC — ${commande.name} : ventilation ${s.fanLevel} → ${action.cible}")
                etat = s.copy(fanLevel = action.cible)
                afficher()
                ecrireDiffere("ventilation") { MG4Hardware.setClimateFan(action.cible) }
            }
            HvacPopup.Action.Fermer -> {
                AppLogger.i(TAG, "Pop-up HVAC — clic central → fermeture")
                fermer()
            }
            null -> AppLogger.i(TAG, "Pop-up HVAC — ${commande.name} : rien à écrire (butée ou valeur illisible)")
        }
    }

    /** Bouton cumulable du sens de l'air — même règle que la page Clim ([AirFlow.toggled]). */
    private fun basculerAir(part: Int) {
        val s = etat ?: return
        relancerDelai()
        val lu = s.airFlow ?: return
        val cible = AirFlow.toggled(lu, part)
        if (cible == null) {
            AppLogger.i(TAG, "Pop-up HVAC — sens de l'air : dernier bouton actif, appui ignoré (valeur lue=$lu)")
            return
        }
        AppLogger.i(TAG, "Pop-up HVAC — sens de l'air : $lu ⇒ $cible")
        etat = s.copy(airFlow = cible)
        afficher()
        ecrire { MG4Hardware.setClimateAirFlow(cible) }
    }

    /** Lunette arrière : hors de l'échelle du sens de l'air, c'est le dégivrage arrière. */
    private fun basculerLunette() {
        val s = etat ?: return
        relancerDelai()
        val actuel = s.defrostRear ?: return
        AppLogger.i(TAG, "Pop-up HVAC — dégivrage arrière : $actuel → ${!actuel}")
        etat = s.copy(defrostRear = !actuel)
        afficher()
        ecrire { MG4Hardware.setClimateDefrostRear(!actuel) }
    }

    private fun ecrireDiffere(reglage: String, action: () -> Boolean) {
        differees.remove(reglage)?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            differees.remove(reglage)
            ecrire(action)
        }
        differees[reglage] = r
        handler.postDelayed(r, WRITE_DEBOUNCE_MS)
    }

    private fun ecrire(action: () -> Boolean) {
        ecrituresEnVol++
        vehicule.execute {
            val ok = try { action() } catch (e: Exception) {
                AppLogger.w(TAG, "Pop-up HVAC — écriture clim : ${e.message}")
                false
            }
            handler.post {
                ecrituresEnVol--
                if (!ok) vues?.let { Toast.makeText(it.localized, R.string.clim_write_failed, Toast.LENGTH_SHORT).show() }
                handler.postDelayed({ relire() }, SETTLE_MS)
            }
        }
    }

    /** Remet l'écran sur ce que la voiture a réellement retenu, une fois les écritures passées. */
    private fun relire() {
        // Une écriture plus récente attend ou est en vol : sa propre relecture suivra.
        if (overlayView == null || ecrituresEnVol > 0 || differees.isNotEmpty()) return
        vehicule.execute {
            val lu = lireEtat() ?: return@execute
            handler.post {
                if (overlayView != null && ecrituresEnVol == 0 && differees.isEmpty()) {
                    etat = lu
                    afficher()
                }
            }
        }
    }

    private fun afficher() {
        val s = etat ?: return
        val v = vues ?: return
        v.temperature.text = s.tempC?.let { "$it °C" } ?: "-- °C"
        v.ventilation.text = s.fanLevel?.toString() ?: "--"
        v.barreVentilation.max = s.fanMax
        v.barreVentilation.progress = (s.fanLevel ?: 0).coerceIn(0, s.fanMax)
        // Valeur illisible → boutons grisés. Valeur lue mais hors échelle (7 « aucun »)
        // → boutons actifs, aucun allumé : l'utilisateur peut choisir un sens.
        val air = s.airFlow?.let { AirFlow.partsOf(it) }
        v.bascule(v.airFace,       s.airFlow?.let { air?.face == true })
        v.bascule(v.airFeet,       s.airFlow?.let { air?.feet == true })
        v.bascule(v.airWindshield, s.airFlow?.let { air?.windshield == true })
        v.bascule(v.airRear,       s.defrostRear)
    }

    private fun fermer() {
        tick?.let { handler.removeCallbacks(it) }
        tick = null
        // Une consigne encore retenue par l'anti-rebond part MAINTENANT : fermer ne l'annule pas
        // (un pas de température suivi aussitôt du clic central doit être appliqué).
        val restantes = differees.values.toList()
        differees.clear()
        restantes.forEach { handler.removeCallbacks(it); it.run() }
        vues = null
        etat = null
        val v = overlayView ?: return
        overlayView = null
        try {
            gestionnaire?.removeView(v)
            AppLogger.i(TAG, "Pop-up HVAC fermé")
        } catch (e: Exception) {
            AppLogger.i(TAG, "Erreur fermeture pop-up HVAC : ${e.message}")
        }
        gestionnaire = null
    }

    private fun MG4Hardware.ClimateState.reglages() =
        HvacPopup.Reglages(tempC, tempMin, tempMax, fanLevel, fanMin, fanMax)

    /** Vues du popup affiché et ce qu'il faut pour les peindre ; vit et meurt avec lui. */
    private class Vues(view: View, themed: Context, val localized: Context) {
        val temperature: TextView         = view.findViewById(R.id.hvac_popup_temp)
        val ventilation: TextView         = view.findViewById(R.id.hvac_popup_fan)
        val barreVentilation: ProgressBar = view.findViewById(R.id.hvac_popup_fan_bar)
        val minuterie: ProgressBar        = view.findViewById<ProgressBar>(R.id.hvac_popup_timer)
            .apply { max = AUTO_DISMISS_MS.toInt() }
        val decompte: TextView            = view.findViewById(R.id.hvac_popup_countdown)
        val aide: TextView                = view.findViewById(R.id.hvac_popup_hint)
        val airFace: MaterialButton       = view.findViewById(R.id.hvac_btn_air_face)
        val airFeet: MaterialButton       = view.findViewById(R.id.hvac_btn_air_feet)
        val airWindshield: MaterialButton = view.findViewById(R.id.hvac_btn_air_windshield_front)
        val airRear: MaterialButton       = view.findViewById(R.id.hvac_btn_air_windshield_rear)

        val cellules: Map<JoystickFocus.Commande, MaterialButton> = mapOf(
            JoystickFocus.Commande.HAUT    to view.findViewById<MaterialButton>(R.id.hvac_btn_temp_up),
            JoystickFocus.Commande.BAS     to view.findViewById<MaterialButton>(R.id.hvac_btn_temp_down),
            JoystickFocus.Commande.GAUCHE  to view.findViewById<MaterialButton>(R.id.hvac_btn_fan_down),
            JoystickFocus.Commande.DROITE  to view.findViewById<MaterialButton>(R.id.hvac_btn_fan_up),
            JoystickFocus.Commande.VALIDER to view.findViewById<MaterialButton>(R.id.hvac_btn_close),
        )

        private val fondActif    = themed.getColor(R.color.dash_accent_dim)
        private val fondInactif  = themed.getColor(R.color.dash_btn)
        private val texteActif   = themed.getColor(R.color.dash_accent)
        private val texteInactif = themed.getColor(R.color.text_secondary)
        private val bordure      = themed.getColor(R.color.dash_border)
        private val traitFin     = dp(themed, 1f)
        private val traitEpais   = dp(themed, 3f)

        /**
         * Bouton bascule, peint comme ceux de la page Clim : accentué quand actif.
         * Un état null = non lisible sur ce firmware → bouton grisé et inerte, plutôt que menteur.
         */
        fun bascule(bouton: MaterialButton, actif: Boolean?) {
            val allume = actif == true
            bouton.backgroundTintList = ColorStateList.valueOf(if (allume) fondActif else fondInactif)
            bouton.setTextColor(if (allume) texteActif else texteInactif)
            bouton.iconTint = ColorStateList.valueOf(if (allume) texteActif else texteInactif)
            bouton.isEnabled = actif != null
            bouton.alpha = if (actif != null) 1f else 0.35f
        }

        /** Retour visuel d'un geste : au volant, c'est le seul signe que la commande est passée. */
        fun eclairer(commande: JoystickFocus.Commande) {
            val bouton = cellules[commande] ?: return
            bouton.strokeColor = ColorStateList.valueOf(texteActif)
            bouton.strokeWidth = traitEpais
            bouton.postDelayed({
                bouton.strokeColor = ColorStateList.valueOf(bordure)
                bouton.strokeWidth = traitFin
            }, FLASH_MS)
        }

        private fun dp(context: Context, value: Float) = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()
    }
}
