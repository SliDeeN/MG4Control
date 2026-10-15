package com.mg4.control.service

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.ClusterTabReader
import com.mg4.control.hardware.EnergyReader
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.hardware.ReadyWatcher
import com.mg4.control.model.ClusterGauge
import com.mg4.control.model.ClusterModule
import com.mg4.control.model.ClusterTab
import com.mg4.control.model.ClusterTrip
import com.mg4.control.model.DriveMode
import com.mg4.control.ui.widget.ClusterGaugeView
import com.mg4.control.util.ClusterModuleSettings
import com.mg4.control.util.LocaleHelper

/**
 * Module MG4Control sur le combiné (Réglages › Interface, 2026-10-14).
 *
 * Le combiné est un calculateur à part ; seule sa zone centrale vient de l'écran central, par un
 * écran Android à lui (le n° 1, 800 × 480 px), où SystemUI lance l'appli SAIC du combiné. Réservé à
 * SWI133, SWI68 et SWI165 ([ClusterModuleSettings.SUPPORTED]) : sur A9, le combiné ne montre pas
 * cet écran.
 * Le module est une fenêtre de 260 × 260 px posée sur cet écran, à la place du carré de cette
 * appli. Il suit l'onglet choisi au volant : sur Navigation et Musique, le module réglé pour
 * l'onglet, ou rien — la fenêtre devient alors transparente et la page d'origine reparaît.
 *
 * Affiché dès le démarrage de l'application, réglage activé — et non plus en READY seulement
 * (demande du 2026-10-14). Si l'écran du combiné n'existe pas encore à ce moment-là (démarrage du
 * boîtier), le module s'y pose dès qu'il apparaît. La moyenne du trajet et la récupération, elles,
 * repartent de zéro à chaque passage en READY. Lecture seule : puissance batterie et vitesse à la
 * cadence réglée (0,1 à 1 s, [ClusterModuleSettings.refreshMs]), mode de conduite et batterie
 * toutes les dix secondes.
 *
 * Vu sur le combiné le 2026-10-14 (SWI133) : la fenêtre s'y affiche bien. La place du carré vient
 * de la mise en page de l'appli SAIC ([ClusterGauge.placement]) ; le journal ([TAG]) donne l'écran
 * trouvé, la place retenue et chaque changement d'onglet.
 */
internal object ClusterModuleOverlay {

    const val TAG = "MG4_CLUSTER"

    /** Mode de conduite et batterie bougent peu : relus toutes les dix secondes, quelle que soit la cadence. */
    private const val LENT_MS = 10_000L
    /**
     * Puissance illisible depuis plus longtemps : « – » plutôt que la dernière valeur. En temps et
     * non en relevés manqués : à 0,1 s, trois relevés ne feraient que 0,3 s.
     */
    private const val MANQUE_TOLERE_MS = 3_000L

    private val main = Handler(Looper.getMainLooper())
    private val lecteur: Handler by lazy { Handler(HandlerThread("mg4-cluster").also { it.start() }.looper) }

    @Volatile private var appContext: Context? = null

    // ── Fil principal ──
    private var vue: ClusterGaugeView? = null
    private var fenetres: WindowManager? = null
    /** Écran qui porte la fenêtre : s'il disparaît, la fenêtre part avec lui. */
    private var ecranId: Int? = null
    private var abonne = false

    // ── Fil du lecteur ──
    private var enMarche = false
    private val trajet = ClusterTrip()
    private var kwLisse: Float? = null
    private var dernierKwMs = 0L
    private var dernierLentMs = 0L
    /** Dernier onglet et dernier module annoncés au journal : un mot par changement, pas par relevé. */
    private var dernierOnglet: Int? = -1
    private var dernierModule: ClusterModule? = null
    private var mode: DriveMode? = null
    private var batterie: Float? = null

    /**
     * Un passage en READY ouvre un nouveau trajet. Pas le premier état lu : si la voiture est déjà
     * en READY au démarrage, l'affichage vient de remettre le trajet à zéro.
     */
    private val readyListener = ReadyWatcher.Listener { ready, firstRead ->
        if (ready && !firstRead) lecteur.post {
            trajet.reset()
            AppLogger.i(TAG, "passage en READY : moyenne et récupération repartent de zéro")
        }
    }

    /**
     * Au démarrage du boîtier, l'écran du combiné peut n'apparaître qu'après le service : le module
     * s'y pose dès qu'il arrive. S'il disparaît, la fenêtre part avec lui.
     */
    private val ecouteurEcrans = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            if (vue == null) afficher("écran $displayId ajouté", signalerAbsence = false)
        }
        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == ecranId) masquer("écran $displayId retiré")
        }
        override fun onDisplayChanged(displayId: Int) {}
    }

    /** Au démarrage du service ([origine] le dit) et à chaque changement du réglage. */
    fun refresh(context: Context, origine: String = "réglage") {
        val app = context.applicationContext
        appContext = app
        main.post {
            // Hors des firmwares pris en charge, rien — même si le réglage a été activé pendant les
            // essais sur A9 : sans ça, une fenêtre invisible resterait posée sur l'écran du combiné.
            if (ClusterModuleSettings.isEnabled(app) && ClusterModuleSettings.isSupported()) {
                if (!abonne) {
                    abonne = true
                    ReadyWatcher.add(readyListener)
                    (app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                        ?.registerDisplayListener(ecouteurEcrans, main)
                }
                afficher(origine)
            } else {
                desabonner()
                masquer("réglage désactivé")
            }
        }
    }

    /** Arrêt du service. */
    fun stop() {
        main.post {
            desabonner()
            masquer("arrêt du service")
        }
    }

    private fun desabonner() {
        if (!abonne) return
        abonne = false
        ReadyWatcher.remove(readyListener)
        (appContext?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.unregisterDisplayListener(ecouteurEcrans)
    }

    /**
     * Écran Android du combiné, ou `null` s'il n'y en a pas : voir [ClusterGauge.clusterDisplay].
     * Lu aussi par les Réglages, pour dire sous l'interrupteur si le module peut s'afficher.
     */
    fun ecranDuCombine(ctx: Context): Display? {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return null
        val ecrans = runCatching { dm.displays.toList() }.getOrNull() ?: return null
        val id = ClusterGauge.clusterDisplay(ecrans.map { it.displayId to type(it) }) ?: return null
        return ecrans.firstOrNull { it.displayId == id }
    }

    /** `Display.getType()`, caché dans le SDK. */
    private fun type(d: Display): Int? =
        runCatching { d.javaClass.getMethod("getType").invoke(d) as? Int }.getOrNull()

    // ── Fenêtre (fil principal) ──────────────────────────────────────────────

    /**
     * Sans effet si le module est déjà là. [signalerAbsence] : faux pour les essais déclenchés par
     * l'arrivée d'un écran quelconque, qui ne sont pas tous celui du combiné.
     */
    private fun afficher(origine: String, signalerAbsence: Boolean = true) {
        val ctx = appContext ?: return
        if (vue != null) return
        val ecran = ecranDuCombine(ctx)
        if (ecran == null) {
            if (signalerAbsence) AppLogger.w(TAG, "$origine : aucun écran de combiné parmi ceux d'Android — " +
                "module non affiché, en attente de son arrivée")
            return
        }
        val taille = Point().also { ecran.getRealSize(it) }
        val place = ClusterGauge.placement(taille.x, taille.y)
        try {
            val surEcran = ctx.createDisplayContext(ecran)
            val wm = surEcran.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = ClusterGaugeView(LocaleHelper.applyLocale(surEcran))
            wm.addView(v, WindowManager.LayoutParams(
                place.side, place.side,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = place.x
                y = place.y
                title = "MG4Control combiné"
            })
            vue = v
            fenetres = wm
            ecranId = ecran.displayId
            AppLogger.i(TAG, "$origine : fenêtre posée (navigation=" +
                "${ClusterModuleSettings.moduleFor(ctx, ClusterTab.NAVIGATION)?.key ?: "aucun"}, musique=" +
                "${ClusterModuleSettings.moduleFor(ctx, ClusterTab.MEDIA)?.key ?: "aucun"}) sur l'écran " +
                "${ecran.displayId} « ${ecran.name} » ${taille.x}×${taille.y} px — carré de ${place.side} px " +
                "en (${place.x}, ${place.y})")
            demarrerReleves()
        } catch (e: Exception) {
            AppLogger.e(TAG, "$origine : affichage refusé sur l'écran ${ecran.displayId} — " +
                "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun masquer(origine: String) {
        arreterReleves()
        val v = vue ?: return
        runCatching { fenetres?.removeViewImmediate(v) }
            .onFailure { AppLogger.w(TAG, "$origine : retrait de la fenêtre — ${it.javaClass.simpleName}: ${it.message}") }
        vue = null
        fenetres = null
        ecranId = null
        AppLogger.i(TAG, "$origine : module retiré du combiné")
    }

    // ── Relevés (fil du lecteur) ─────────────────────────────────────────────

    /** Le trajet repart de zéro à chaque affichage, et à chaque passage en READY ([readyListener]). */
    private fun demarrerReleves() {
        lecteur.post {
            enMarche = true
            trajet.reset()
            kwLisse = null
            dernierKwMs = 0L
            dernierLentMs = 0L
            dernierOnglet = -1
            dernierModule = null
            lecteur.removeCallbacks(tic)
            lecteur.post(tic)
        }
    }

    private fun arreterReleves() {
        lecteur.post {
            enMarche = false
            lecteur.removeCallbacks(tic)
        }
    }

    private val tic = object : Runnable {
        override fun run() {
            if (!enMarche) return
            val ctx = appContext ?: return
            val intervalle = ClusterModuleSettings.refreshMs(ctx).toLong()
            val maintenant = SystemClock.elapsedRealtime()
            val kw = runCatching { EnergyReader.dischargeKw() }.getOrNull()
            val kmh = runCatching { MG4Hardware.getVehicleSpeedKmh() }.getOrNull()?.takeIf { it in 0f..300f }
            trajet.add(maintenant, kw, kmh)
            // Lissage par relevé : plus la cadence est rapide, plus la jauge suit vite. C'est ce
            // qu'on demande au curseur — le chiffre bouge aussi davantage.
            if (kw != null) {
                kwLisse = ClusterGauge.smooth(kwLisse, kw)
                dernierKwMs = maintenant
            } else if (maintenant - dernierKwMs > MANQUE_TOLERE_MS) {
                kwLisse = null
            }
            if (dernierLentMs == 0L || maintenant - dernierLentMs >= LENT_MS) {
                dernierLentMs = maintenant
                mode = runCatching { MG4Hardware.getDriveMode() }.getOrNull()
                batterie = runCatching { EnergyReader.soc() }.getOrNull()
            }
            // Le module suit l'onglet choisi au volant. Les relevés, eux, continuent quel que
            // soit l'onglet : la moyenne du trajet ne doit pas avoir de trou.
            val onglet = runCatching { ClusterTabReader.onglet() }.getOrNull()
            val navigation = ClusterModuleSettings.moduleFor(ctx, ClusterTab.NAVIGATION)
            val musique = ClusterModuleSettings.moduleFor(ctx, ClusterTab.MEDIA)
            val module = ClusterModuleSettings.moduleToShow(onglet, navigation, musique)
            if (onglet != dernierOnglet || module != dernierModule) {
                AppLogger.i(TAG, "onglet ${onglet ?: "illisible"} (${ClusterTab.fromValue(onglet)?.name ?: "?"}) → " +
                    (module?.let { "module ${it.key}" } ?: "aucun module, page d'origine"))
                dernierOnglet = onglet
                dernierModule = module
            }
            val contenu = module?.let {
                ClusterGaugeView.Contenu(
                    module = it,
                    kw = kwLisse,
                    kmh = kmh,
                    mode = mode,
                    moyenneWhKm = trajet.averageWhPerKm(),
                    recupWh = trajet.regenWh(),
                    batterie = batterie,
                    unite = ClusterModuleSettings.consumptionUnit(ctx),
                )
            }
            main.post {
                val v = vue ?: return@post
                if (contenu == null) {
                    // Transparente : la page d'origine de l'onglet reparaît dessous.
                    v.visibility = View.INVISIBLE
                } else {
                    v.visibility = View.VISIBLE
                    v.montrer(contenu, intervalle)
                }
            }
            lecteur.postDelayed(this, intervalle)
        }
    }
}
