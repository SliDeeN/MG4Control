package com.mg4.control.service

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.mg4.control.R
import com.mg4.control.accessibility.JoystickFocus
import com.mg4.control.accessibility.KeyCaptureService
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.model.AirFlow
import com.mg4.control.model.HvacPopup
import com.mg4.control.model.SeatHeat
import com.mg4.control.util.FirmwareInfo
import com.mg4.control.util.LocaleHelper
import com.mg4.control.util.PopupDelay
import com.mg4.control.util.ThemeHelper
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Pop-up HVAC (issues #120 et #125) : réglage rapide de la clim par-dessus n'importe quel écran,
 * Android Auto en plein écran compris, là où le bandeau clim d'origine n'est plus accessible.
 *
 * Calqué sur [ProfileConfirmOverlay] pour la fenêtre, mais le joystick droit n'y déplace AUCUN
 * focus : chaque direction agit directement et le clic central bascule la ventilation AUTO
 * ([HvacPopup]). A/C, recirculation et sens de l'air se règlent au doigt, et la fenêtre se ferme
 * au doigt aussi. Les cellules de la croix se touchent également — sans service d'accessibilité,
 * le joystick ne nous parvient pas (et règle le volume) : l'écran est alors le seul moyen d'agir.
 *
 * Aucun verrou de conduite : la clim est un réglage de confort (cf. T-904), et c'est justement
 * en roulant que ce popup sert.
 *
 * Ouvert et refermé par le même raccourci ([toggle]) ; se referme seul, sans action, après le
 * délai réglé dans Réglages › Interface ([PopupDelay]).
 *
 * Deux présentations ([HvacPopupStyle]), une par raccourci, le temps de les comparer : cette
 * classe garde les règles — lecture, bornes, anti-rebond, écritures, relecture — et confie la
 * peinture à un [HvacPopupSkin]. L'autre raccourci, pressé pendant que la fenêtre est ouverte,
 * passe d'une présentation à l'autre.
 */
object HvacPopupOverlay {

    private const val TAG = "MG4_OVERLAY"
    private const val TICK_MS = 100L

    /** Plusieurs pas rapprochés du joystick ne donnent qu'UNE écriture, celle de la valeur finale. */
    private const val WRITE_DEBOUNCE_MS = 200L

    /** Le véhicule met un instant à propager : relire trop tôt rendrait l'ANCIENNE valeur. */
    private const val SETTLE_MS = 700L

    /** Les sièges chauffants annoncent leur niveau plus tard encore que la clim. */
    private const val SEAT_SETTLE_MS = 1_500L
    private const val FLASH_MS = 180L

    /** Relecture d'entretien, en battements de [TICK_MS] : la voiture peut changer d'elle-même. */
    private const val RELECTURE_TICKS = 15

    private val handler = Handler(Looper.getMainLooper())

    /** Lectures et écritures clim : appels binder, certains bloquants (bascules relues). */
    private val vehicule = Executors.newSingleThreadExecutor()

    /**
     * Sièges chauffants réglés à un niveau PRÉCIS (présentation « moderne ») : jusqu'à trois
     * crans espacés, puis une vérification — plusieurs secondes. Une file par siège, pour ne
     * retenir ni la température ni la ventilation derrière, et garder l'ordre des demandes.
     */
    private val fileSiegeGauche by lazy { Executors.newSingleThreadExecutor() }
    private val fileSiegeDroit by lazy { Executors.newSingleThreadExecutor() }

    /** Numéro de la dernière demande par siège : une consigne dépassée ne s'envoie pas. */
    private val demandeSiegeGauche = AtomicInteger()
    private val demandeSiegeDroit = AtomicInteger()

    @Volatile private var overlayView: View? = null

    // ── Tout ce qui suit : thread principal seulement ────────────────────────
    private var ouvertureEnCours = false
    private var vues: HvacPopupSkin? = null
    private var styleAffiche: HvacPopupStyle? = null
    private var gestionnaire: WindowManager? = null

    /** Dernier état lu, corrigé de ce qui vient d'être demandé (voir [surCommande]). */
    private var etat: MG4Hardware.ClimateState? = null

    /** Sièges et volant chauffants ; null sur les firmwares qui n'en ont pas. */
    private var chauffages: HvacPopupSkin.Chauffages? = null
    /** Délai de fermeture lu à l'ouverture : le changer n'agit pas sur un pop-up déjà affiché. */
    private var delaiMs = PopupDelay.DEFAULT_SECONDS * 1_000L
    private var echeance = 0L
    private var tick: Runnable? = null

    /** Écritures en attente d'anti-rebond, par réglage. */
    private val differees = mutableMapOf<String, Runnable>()
    private var ecrituresEnVol = 0

    /** Jusqu'à cet instant la voiture n'a pas fini de propager la dernière écriture : ne pas relire. */
    private var silenceJusqua = 0L
    private var battements = 0

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

    /**
     * Raccourci : ouvre le popup dans la présentation [style], ou le referme s'il est déjà à
     * l'écran dans celle-ci. Ouvert dans l'autre, il change de présentation.
     */
    fun toggle(context: Context, style: HvacPopupStyle = HvacPopupStyle.CLASSIC) {
        val app = context.applicationContext
        handler.post {
            if (overlayView != null) {
                val autre = styleAffiche != style
                AppLogger.i(TAG, if (autre) "Pop-up HVAC — raccourci de l'autre présentation → ${style.name}"
                    else "Pop-up HVAC — second appui du raccourci → fermeture")
                fermer()
                if (!autre) return@post
            }
            if (ouvertureEnCours) return@post
            ouvertureEnCours = true
            vehicule.execute {
                val lu = lireEtat()
                val chauds = lireChauffages()
                handler.post {
                    ouvertureEnCours = false
                    // Sans état, il n'y aurait ni valeur à montrer ni base pour « un cran de plus ».
                    if (lu == null) AppLogger.w(TAG, "Pop-up HVAC non affiché : état clim illisible")
                    else showOnMain(app, lu, chauds, style)
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

    /**
     * Même règle que la carte des chauffages du tableau de bord : SWI133, SWI68 et SWI165.
     * Ailleurs (finitions sans ces équipements, ou firmware sans commande connue) → null.
     */
    private fun lireChauffages(): HvacPopupSkin.Chauffages? {
        if (!FirmwareInfo.hasHeatFeatures()) return null
        return try {
            HvacPopupSkin.Chauffages(
                siegeGauche = MG4Hardware.getSeatHeatLeftOrNull(),
                siegeDroit  = MG4Hardware.getSeatHeatRightOrNull(),
                volant      = MG4Hardware.getSteeringHeatOrNull(),
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "Pop-up HVAC — lecture chauffages : ${e.message}")
            HvacPopupSkin.Chauffages(null, null, null)
        }
    }

    private fun showOnMain(
        context: Context, lu: MG4Hardware.ClimateState, chauds: HvacPopupSkin.Chauffages?, style: HvacPopupStyle,
    ) {
        fermer()
        delaiMs = PopupDelay.millis(context)

        val localized = LocaleHelper.applyLocale(context)
        // La mise en page est écrite pour une carte de base (900 × 390 dp pour celle d'origine,
        // 1136 avec la colonne des chauffages, à la même échelle), trop petite sur l'écran de la
        // voiture (constaté sur SWI133). Plutôt que de figer d'autres dp, qui ne conviendraient
        // qu'à UNE densité, on la gonfle avec une densité corrigée : dp et sp suivent d'un bloc,
        // pictos et texte restent nets, et la fenêtre prend la même part de l'écran partout.
        val mesures = localized.resources.displayMetrics
        val echelle = HvacPopup.echelle(
            mesures.widthPixels, mesures.heightPixels, mesures.density, style.largeurDp, style.hauteurDp)
        // Même contexte pour le thème : clair ou sombre comme l'application, pas comme le système.
        val auTheme = ThemeHelper.withAppTheme(localized)
        val agrandi = auTheme.createConfigurationContext(
            Configuration(auTheme.resources.configuration).apply {
                densityDpi = (mesures.densityDpi * echelle).roundToInt()
            })
        val themed = ContextThemeWrapper(agrandi, R.style.Theme_MG4Control)
        // Gonflée, branchée et peinte une première fois AVANT d'être posée à l'écran, et sous
        // garde : une mise en page fautive (vue introuvable, mauvais type) lèverait ici, sur le
        // thread principal du service, et l'emporterait avec elle. La fenêtre ne s'ouvre pas, le
        // journal dit pourquoi.
        val (view, v) = try {
            val gonflee = LayoutInflater.from(themed).inflate(style.layout, null)
            val presentation: HvacPopupSkin = when (style) {
                HvacPopupStyle.CLASSIC -> HvacPopupSkinClassic(gonflee, themed, localized)
                HvacPopupStyle.MODERN  -> HvacPopupSkinModern(gonflee, themed, localized)
            }
            gonflee.findViewById<View>(R.id.hvac_backdrop).setOnClickListener { fermer() }
            presentation.brancher(gestes, avecChauffages = chauds != null)
            presentation.repereJoystick(utilisable = KeyCaptureService.isEnabled(context))
            presentation.peindre(lu, chauds)
            gonflee to presentation
        } catch (e: Exception) {
            AppLogger.e(TAG, "Pop-up HVAC (${style.name}) non affiché — mise en page : " +
                "${e.javaClass.simpleName} ${e.message}")
            return
        }

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
        styleAffiche = style
        etat = lu
        chauffages = chauds
        overlayView = view
        AppLogger.i(TAG, "Pop-up HVAC affiché (${style.name}) : ${lu.tempC} °C (${lu.tempMin}..${lu.tempMax}), " +
            "ventilation ${lu.fanLevel} (${lu.fanMin}..${lu.fanMax}) AUTO=${lu.autoOn}, A/C=${lu.acOn}, " +
            "recirculation ${lu.loopMode}, sens de l'air ${lu.airFlow}, chauffages ${chauds ?: "absents"} ; " +
            "échelle ×${String.format(Locale.US, "%.2f", echelle)} (écran ${mesures.widthPixels}×" +
            "${mesures.heightPixels} px, ${mesures.densityDpi} dpi)")
        afficher()

        relancerDelai()
        battements = 0
        val t = object : Runnable {
            override fun run() {
                val actuelles = vues ?: return
                // AUTO fait varier la ventilation, et l'écran d'origine reste utilisable derrière :
                // sans relecture, la fenêtre finirait par afficher un état qui n'est plus le bon.
                if (++battements % RELECTURE_TICKS == 0) relire()
                val reste = echeance - SystemClock.uptimeMillis()
                if (reste <= 0) {
                    AppLogger.i(TAG, "Pop-up HVAC — délai écoulé → fermeture")
                    fermer()
                    return
                }
                actuelles.decompte(((reste + 999) / 1_000).toInt())
                handler.postDelayed(this, TICK_MS)
            }
        }
        tick = t
        handler.post(t)
    }

    /** Ce que les vues remontent : mêmes chemins quelle que soit la présentation. */
    private val gestes = HvacPopupSkin.Gestes(
        commande      = ::surCommande,
        air           = ::basculerAir,
        lunette       = ::basculerLunette,
        ac            = ::basculerAc,
        recirculation = ::choisirRecirculation,
        siegeSuivant  = ::cyclerSiege,
        siegeNiveau   = ::choisirSiege,
        volant        = ::basculerVolant,
        fermer        = ::fermer,
    )

    /** Chaque geste, au joystick comme au doigt, redonne le délai complet. */
    private fun relancerDelai() {
        echeance = SystemClock.uptimeMillis() + delaiMs
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
            // Un niveau écrit à la main est aussi ce qui fait SORTIR d'AUTO, comme sur l'écran
            // d'origine : l'état corrigé n'est donc plus en AUTO.
            is HvacPopup.Action.Ventilation -> {
                val depuis = if (s.reglages().enAuto) "AUTO" else s.fanLevel.toString()
                AppLogger.i(TAG, "Pop-up HVAC — ${commande.name} : ventilation $depuis → ${action.cible}")
                etat = s.copy(fanLevel = action.cible, autoOn = s.autoOn?.let { false })
                afficher()
                ecrireDiffere("ventilation") { MG4Hardware.setClimateFan(action.cible) }
            }
            // Clic central hors AUTO. En AUTO, il rend la main : c'est alors une [Ventilation].
            HvacPopup.Action.Auto -> {
                AppLogger.i(TAG, "Pop-up HVAC — ${commande.name} : mode AUTO activé")
                etat = s.copy(autoOn = true)
                afficher()
                ecrire { MG4Hardware.setClimateAuto(true) }
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

    /** Compresseur seul : la ventilation continue, sans refroidir. */
    private fun basculerAc() {
        val s = etat ?: return
        relancerDelai()
        val actuel = s.acOn ?: return
        AppLogger.i(TAG, "Pop-up HVAC — A/C : $actuel → ${!actuel}")
        etat = s.copy(acOn = !actuel)
        afficher()
        ecrire { MG4Hardware.setClimateAc(!actuel) }
    }

    /**
     * Recirculation : choix DIRECT du mode. La commande du véhicule, elle, est cyclique —
     * [MG4Hardware.setClimateLoopMode] avance jusqu'au mode voulu, ce qui peut prendre une seconde.
     */
    private fun choisirRecirculation(mode: Int) {
        val s = etat ?: return
        relancerDelai()
        val actuel = s.loopMode ?: return
        if (actuel == mode) return
        AppLogger.i(TAG, "Pop-up HVAC — recirculation : $actuel → $mode")
        etat = s.copy(loopMode = mode)
        afficher()
        ecrire { MG4Hardware.setClimateLoopMode(mode) }
    }

    /**
     * Siège chauffant : un appui = UN cran envoyé, la commande même de la voiture. Le niveau
     * suivant est donc le sien — éteint → 3 → 2 → 1 → éteint ([SeatHeat.next]) — et c'est lui
     * qu'on affiche aussitôt, sans attendre la relecture. Deux appuis rapprochés envoient deux
     * crans, espacés par le matériel.
     */
    private fun cyclerSiege(gauche: Boolean) {
        val c = chauffages ?: return
        relancerDelai()
        val actuel = if (gauche) c.siegeGauche else c.siegeDroit
        val suivant = SeatHeat.next(actuel) ?: return
        val cote = if (gauche) "gauche" else "droit"
        AppLogger.i(TAG, "Pop-up HVAC — siège chauffant $cote : $actuel → $suivant")
        chauffages = if (gauche) c.copy(siegeGauche = suivant) else c.copy(siegeDroit = suivant)
        afficher()
        ecrire(SEAT_SETTLE_MS) {
            if (gauche) MG4Hardware.stepSeatHeatLeft() else MG4Hardware.stepSeatHeatRight()
        }
    }

    /**
     * Siège chauffant, niveau choisi DIRECTEMENT (présentation « moderne »). La voiture ne connaît
     * qu'« un cran », et un cran descend : [MG4Hardware.setSeatHeatLeft] compte ceux qu'il faut —
     * un pour descendre d'un niveau, trois pour en monter un, en passant par « éteint ».
     *
     * Le niveau demandé s'affiche aussitôt. L'anti-rebond ne garde que le dernier toucher, et une
     * consigne restée en file derrière une série de crans en cours ne part pas si une plus
     * récente l'a remplacée : le siège ne passe pas par tous les niveaux touchés en chemin.
     */
    private fun choisirSiege(gauche: Boolean, niveau: Int) {
        val c = chauffages ?: return
        relancerDelai()
        val actuel = (if (gauche) c.siegeGauche else c.siegeDroit) ?: return
        if (actuel == niveau || niveau !in 0..SeatHeat.MAX_LEVEL) return
        val cote = if (gauche) "gauche" else "droit"
        AppLogger.i(TAG, "Pop-up HVAC — siège chauffant $cote : $actuel → $niveau (niveau choisi)")
        chauffages = if (gauche) c.copy(siegeGauche = niveau) else c.copy(siegeDroit = niveau)
        afficher()
        val demandes = if (gauche) demandeSiegeGauche else demandeSiegeDroit
        val demande = demandes.incrementAndGet()
        ecrireDiffere("siège $cote", SEAT_SETTLE_MS, if (gauche) fileSiegeGauche else fileSiegeDroit) {
            when {
                demandes.get() != demande -> true
                gauche -> MG4Hardware.setSeatHeatLeft(niveau)
                else   -> MG4Hardware.setSeatHeatRight(niveau)
            }
        }
    }

    private fun basculerVolant() {
        val c = chauffages ?: return
        relancerDelai()
        val actuel = c.volant ?: return
        AppLogger.i(TAG, "Pop-up HVAC — volant chauffant : $actuel → ${!actuel}")
        chauffages = c.copy(volant = !actuel)
        afficher()
        ecrire { MG4Hardware.setSteeringHeat(!actuel) }
    }

    private fun ecrireDiffere(
        reglage: String, apaisement: Long = SETTLE_MS, file: Executor = vehicule, action: () -> Boolean,
    ) {
        differees.remove(reglage)?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            differees.remove(reglage)
            ecrire(apaisement, file, action)
        }
        differees[reglage] = r
        handler.postDelayed(r, WRITE_DEBOUNCE_MS)
    }

    /**
     * @param apaisement délai avant de relire : en deçà, la voiture rendrait l'ancienne valeur.
     * @param file       où l'écriture attend son tour ; [vehicule] sauf pour ce qui dure.
     */
    private fun ecrire(apaisement: Long = SETTLE_MS, file: Executor = vehicule, action: () -> Boolean) {
        ecrituresEnVol++
        file.execute {
            val ok = try { action() } catch (e: Exception) {
                AppLogger.w(TAG, "Pop-up HVAC — écriture clim : ${e.message}")
                false
            }
            handler.post {
                ecrituresEnVol--
                if (!ok) vues?.let { Toast.makeText(it.localized, R.string.clim_write_failed, Toast.LENGTH_SHORT).show() }
                silenceJusqua = maxOf(silenceJusqua, SystemClock.uptimeMillis() + apaisement)
                handler.postDelayed({ relire() }, apaisement)
            }
        }
    }

    /** Remet l'écran sur ce que la voiture a réellement retenu, une fois les écritures passées. */
    private fun relire() {
        // Une écriture plus récente attend ou est en vol : sa propre relecture suivra.
        if (overlayView == null || ecrituresEnVol > 0 || differees.isNotEmpty()) return
        // Trop tôt après une écriture, la voiture rendrait l'ANCIENNE valeur et l'écran reculerait.
        if (SystemClock.uptimeMillis() < silenceJusqua) return
        vehicule.execute {
            val lu = lireEtat() ?: return@execute
            val chauds = lireChauffages()
            handler.post {
                if (overlayView != null && ecrituresEnVol == 0 && differees.isEmpty()) {
                    etat = lu
                    chauffages = chauds
                    afficher()
                }
            }
        }
    }

    private fun afficher() {
        val s = etat ?: return
        vues?.peindre(s, chauffages)
    }

    private fun fermer() {
        tick?.let { handler.removeCallbacks(it) }
        tick = null
        // Une consigne encore retenue par l'anti-rebond part MAINTENANT : fermer ne l'annule pas
        // (un pas de température suivi aussitôt de la fermeture doit être appliqué).
        val restantes = differees.values.toList()
        differees.clear()
        restantes.forEach { handler.removeCallbacks(it); it.run() }
        vues = null
        styleAffiche = null
        etat = null
        chauffages = null
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
}
