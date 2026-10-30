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
import com.mg4.control.hardware.ChargeProbe
import com.mg4.control.hardware.ReadyWatcher
import com.mg4.control.hardware.VehicleDistanceUnit
import com.mg4.control.model.ChargeDisplay
import com.mg4.control.model.ChargeEnCours
import com.mg4.control.model.ClusterGauge
import com.mg4.control.model.ClusterGaugeStyle
import com.mg4.control.model.ClusterModule
import com.mg4.control.model.ClusterPill
import com.mg4.control.model.ClusterTab
import com.mg4.control.model.ClusterTrip
import com.mg4.control.model.DriveMode
import com.mg4.control.model.DrivingProfile
import com.mg4.control.model.PowerScale
import com.mg4.control.model.PreClimateMode
import com.mg4.control.model.PreClimatePlan
import com.mg4.control.model.PreClimateTrend
import com.mg4.control.model.StatsTracker
import com.mg4.control.profile.ActiveProfile
import com.mg4.control.profile.ProfileManager
import com.mg4.control.ui.widget.ClusterGaugeView
import com.mg4.control.util.ClusterModuleSettings
import com.mg4.control.util.LocaleHelper
import kotlin.math.roundToInt

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
 * cadence réglée (0,1 à 1 s, [ClusterModuleSettings.refreshMs]), mode de conduite et profil de
 * la pastille toutes les dix secondes ; la jauge glisse d'un relevé au suivant pendant la durée
 * réglée à part ([ClusterModuleSettings.animationMs], issue #152).
 *
 * Pendant une charge (2026-10-16), le module « charge » prend la place de la jauge des onglets qui
 * en ont une : détectée à chaque relevé (`BMS_CHRG_STS`), la charge garde le module jusqu'à ce que
 * la prise ne soit plus signalée — « Charge terminée » entre les deux — ou jusqu'au READY. Temps
 * restant, cible et type de borne relus toutes les dix secondes ; la sonde [ChargeProbe] en garde
 * trace. Sur SWI133, trappe ouverte et pendant toute la charge, le combiné affiche par défaut son
 * propre avertissement à la place du carré et bloque les onglets (essai du 2026-10-16) ; coupé
 * pour cela, le module a été remis en service le 2026-10-17 : l'utilisateur a trouvé comment faire
 * revenir l'affichage du combiné ([MODULE_CHARGE]).
 *
 * Pendant une pré-climatisation (issue #130, 2026-10-23), sa fiche passe avant tout, sur les onglets
 * Navigation et Musique, même réglés sur « Aucun » ; la fenêtre est posée pour elle si le module est
 * désactivé, et retirée à la fin ([PreClimate.surLeCombine]).
 *
 * Vu sur le combiné le 2026-10-14 (SWI133) : la fenêtre s'y affiche bien. La place du carré vient
 * de la mise en page de l'appli SAIC ([ClusterGauge.placement]) ; le journal ([TAG]) donne l'écran
 * trouvé, la place retenue et chaque changement d'onglet.
 */
internal object ClusterModuleOverlay {

    const val TAG = "MG4_CLUSTER"

    /** Mode de conduite et profil bougent peu : relus toutes les dix secondes, quelle que soit la cadence. */
    private const val LENT_MS = 10_000L
    /**
     * Module « charge » à la place de la jauge (voir plus haut). À `false`, aucune session ne
     * démarre, donc rien de la charge n'est lu ni dessiné ici. Remis à `true` le 2026-10-17.
     */
    private const val MODULE_CHARGE = true
    /** En charge, la batterie s'affiche au dixième : relue chaque seconde pour que le chiffre défile. */
    private const val BATTERIE_CHARGE_MS = 1_000L
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
    private var dernierSocMs = 0L
    /** Dernier onglet et dernier module annoncés au journal : un mot par changement, pas par relevé. */
    private var dernierOnglet: Int? = -1
    private var dernierModule: ClusterModule? = null
    private var dernierPreClim = false
    private var mode: DriveMode? = null
    /**
     * Profil de la pastille (issue #152) : relu quand le profil actif change, et avec les valeurs
     * lentes — un nom ou un mode modifié dans l'éditeur apparaît au plus dix secondes après.
     */
    private var profilId: String? = null
    private var profil: DrivingProfile? = null
    /** Batterie : relue pour le module « charge » seulement. */
    private var batterie: Float? = null
    // Charge : la session, les derniers signaux bruts (relus avec les valeurs lentes), et l'état
    // « terminée » — la charge s'est arrêtée mais la prise est encore signalée.
    private val session = ChargeEnCours()
    private var chargeBrute: EnergyReader.ChargeRaw? = null
    private var chargeFinie = false
    private var batterieDebut: Float? = null
    /** Plus forte puissance lissée du trajet en cours (issue #165) ; fil du lecteur. */
    private var pointeTrajet = 0f

    /**
     * Un passage en READY ouvre un nouveau trajet. Pas le premier état lu : si la voiture est déjà
     * en READY au démarrage, l'affichage vient de remettre le trajet à zéro.
     */
    private val readyListener = ReadyWatcher.Listener { ready, firstRead ->
        // Fin de trajet : sa pointe peut faire redescendre l'échelle de puissance (issue #165).
        if (!ready && !firstRead) lecteur.post { finTrajet() }
        if (ready && !firstRead) verifierEtRecoller("passage en READY")
        if (ready && !firstRead) lecteur.post {
            trajet.reset()
            pointeTrajet = 0f
            AppLogger.i(TAG, "passage en READY : moyenne et récupération repartent de zéro")
            // On repart : la charge, finie ou non, rend la place à la jauge.
            if (session.active) finirCharge(SystemClock.elapsedRealtime(), "passage en READY")
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
        // Sortie de veille du combiné : la fenêtre a pu s'y détacher sans que l'écran ne soit retiré.
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == ecranId) verifierEtRecoller("écran $displayId modifié")
        }
    }

    /** Heure de la dernière pose : une fenêtre tout juste posée n'est pas encore attachée. */
    private var poseeMs = 0L
    private const val ATTACHE_DELAI_MS = 2_000L
    /** Nouveaux essais quand l'écran du combiné manque encore au moment d'une vérification. */
    private val ESSAIS_MS = listOf(1_000L, 2_500L, 5_000L, 10_000L)

    /**
     * Vérifie que le module est bien posé sur l'écran du combiné, et l'y recolle sinon (fork
     * sensoyg, 2026-10-30 : sur DBI68, le module disparaissait après une mise en veille). Appelée
     * au contact (IGNITION_RUN), au passage en READY et quand l'écran du combiné change d'état.
     * Fenêtre absente, détachée ou sur un autre écran : retirée puis reposée. Sans effet quand le
     * module n'a pas à être là (réglage coupé, firmware sans combiné).
     */
    fun verifierEtRecoller(origine: String) {
        val app = appContext ?: return
        main.post {
            if (!ClusterModuleSettings.isSupported() ||
                !(ClusterModuleSettings.isEnabled(app) || PreClimate.surLeCombine(app))) return@post
            val ecran = ecranDuCombine(app)
            if (ecran == null) {
                ESSAIS_MS.forEach { delai -> main.postDelayed({ if (vue == null) afficher("$origine (+$delai ms)", signalerAbsence = false) }, delai) }
                return@post
            }
            val v = vue
            val detachee = v != null && !v.isAttachedToWindow && SystemClock.elapsedRealtime() - poseeMs > ATTACHE_DELAI_MS
            if (v == null || detachee || ecranId != ecran.displayId) {
                AppLogger.i(TAG, "$origine : module ${if (v == null) "absent" else if (detachee) "détaché" else "sur un autre écran"} — recollé")
                masquer(origine)
                afficher(origine, signalerAbsence = false)
            }
        }
    }

    /** Au démarrage du service ([origine] le dit) et à chaque changement du réglage. */
    fun refresh(context: Context, origine: String = "réglage") {
        val app = context.applicationContext
        appContext = app
        main.post {
            // Changement de profil (2026-10-24) : quel profil le combiné suit, et ce qu'il en lit.
            if (origine.startsWith("profil ")) AppLogger.i(TAG, "$origine : profil suivi " +
                "${com.mg4.control.util.ClusterProfiles.shownProfileId(app)} → module " +
                if (ClusterModuleSettings.isEnabled(app)) "activé" else "coupé")
            // Hors des firmwares pris en charge, rien — même si le réglage a été activé pendant les
            // essais sur A9 : sans ça, une fenêtre invisible resterait posée sur l'écran du combiné.
            // Module désactivé : posé quand même le temps d'une pré-climatisation.
            if (ClusterModuleSettings.isSupported() &&
                (ClusterModuleSettings.isEnabled(app) || PreClimate.surLeCombine(app))) {
                if (!abonne) {
                    abonne = true
                    ReadyWatcher.add(readyListener)
                    (app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                        ?.registerDisplayListener(ecouteurEcrans, main)
                }
                afficher(origine)
            } else {
                desabonner()
                masquer(if (origine == "réglage") "réglage désactivé" else "$origine, module désactivé")
            }
        }
    }

    /**
     * Un réglage du module vient de changer (unité, unité de la moyenne, style, pastille, module) :
     * un relevé tout de suite. Ils sont relus à chaque relevé, mais à une seconde d'actualisation,
     * le changement pouvait tarder jusqu'au suivant.
     */
    fun relire() {
        lecteur.post {
            if (!enMarche) return@post
            lecteur.removeCallbacks(tic)
            lecteur.post(tic)
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
            poseeMs = SystemClock.elapsedRealtime()
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
            dernierPreClim = false
            session.terminer()
            chargeBrute = null
            chargeFinie = false
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
            val animation = ClusterModuleSettings.animationMs(ctx).toLong()
            val maintenant = SystemClock.elapsedRealtime()
            val kw = runCatching { EnergyReader.dischargeKw() }.getOrNull()
            val kmh = runCatching { MG4Hardware.getVehicleSpeedKmh() }.getOrNull()?.takeIf { it in 0f..300f }
            trajet.add(maintenant, kw, kmh)
            // Lissage par relevé : plus la cadence est rapide, plus la jauge suit vite. C'est ce
            // qu'on demande au curseur — le chiffre bouge aussi davantage. Style discret (issue
            // #153) : lissage dans le temps, deux secondes quelle que soit la cadence.
            val style = ClusterModuleSettings.style(ctx)
            if (kw != null) {
                kwLisse = if (style == ClusterGaugeStyle.DISCREET)
                    ClusterGauge.smoothTime(kwLisse, kw, maintenant - dernierKwMs)
                else ClusterGauge.smooth(kwLisse, kw)
                dernierKwMs = maintenant
            } else if (maintenant - dernierKwMs > MANQUE_TOLERE_MS) {
                kwLisse = null
            }
            // Échelle de puissance (issue #165) : la pointe du trajet, et le plafond appris qui
            // monte TOUT DE SUITE quand une valeur le dépasse — la jauge n'est jamais coincée en butée.
            kwLisse?.takeIf { it > 0f }?.let { k ->
                if (k > pointeTrajet) pointeTrajet = k
                val appris = ClusterModuleSettings.learnedPowerKw(ctx)
                val plafond = PowerScale.apresReleve(appris, k)
                if (plafond != appris) {
                    ClusterModuleSettings.saveLearnedPower(ctx, plafond)
                    AppLogger.i(TAG, "puissance : pointe ${k.toInt()} kW → plafond appris $appris → $plafond kW")
                }
            }
            // La charge se détecte à chaque relevé : le module doit changer dès le branchement.
            // Lecture ratée : l'état d'avant, plutôt qu'un « Charge terminée » d'un relevé.
            val enCharge = MODULE_CHARGE &&
                (runCatching { EnergyReader.charging() }.getOrNull() ?: (session.active && !chargeFinie))
            if (enCharge && !session.active) {
                session.commencer(maintenant)
                chargeFinie = false
                dernierLentMs = 0L   // temps restant, cible et type tout de suite
                AppLogger.i(TAG, "charge détectée : le module de charge remplace les jauges")
            }
            val choixPastille = ClusterModuleSettings.pill(ctx)
            val idActif = if (choixPastille == ClusterPill.PROFILE) ActiveProfile.id(ctx) else null
            val lent = dernierLentMs == 0L || maintenant - dernierLentMs >= LENT_MS
            if (idActif != profilId || (lent && idActif != null)) {
                profilId = idActif
                profil = idActif?.let { id -> runCatching { ProfileManager(ctx).getById(id) }.getOrNull() }
            }
            if (lent) {
                dernierLentMs = maintenant
                dernierSocMs = maintenant
                mode = runCatching { MG4Hardware.getDriveMode() }.getOrNull()
                if (session.active) {
                    batterie = runCatching { EnergyReader.soc() }.getOrNull()
                    if (batterieDebut == null) batterieDebut = batterie
                    chargeBrute = runCatching { EnergyReader.chargeRaw() }.getOrNull()
                    chargeBrute?.let { ChargeProbe.observer(it, kw?.let { k -> -k }) }
                }
            } else if (session.active && maintenant - dernierSocMs >= BATTERIE_CHARGE_MS) {
                dernierSocMs = maintenant
                runCatching { EnergyReader.soc() }.getOrNull()?.let { batterie = it }
            }
            if (session.active) {
                // Puissance qui entre dans la batterie : l'inverse de celle qui en sort.
                session.ajouter(maintenant, kw?.let { -it })
                if (enCharge) {
                    chargeFinie = false
                } else {
                    val brut = chargeBrute
                    if (brut != null && ChargeDisplay.priseSignalee(brut.ac, brut.dc)) {
                        if (!chargeFinie) {
                            chargeFinie = true
                            AppLogger.i(TAG, "charge arrêtée, prise encore signalée : « charge terminée »")
                        }
                    } else {
                        finirCharge(maintenant, "prise débranchée")
                    }
                }
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
            // Pré-climatisation : sa fiche sur les onglets qui portent l'image de l'écran central,
            // quel que soit le module réglé pour eux.
            val preClim = PreClimate.etat?.takeIf { PreClimate.surLeCombine(ctx) }
                ?.takeIf { onglet == null || onglet == ClusterTab.NAVIGATION.value || onglet == ClusterTab.MEDIA.value }
                ?.let { vuePreClim(it, maintenant) }
            if ((preClim != null) != dernierPreClim) {
                dernierPreClim = preClim != null
                AppLogger.i(TAG, if (preClim != null) "pré-climatisation : sa fiche remplace le module"
                    else "pré-climatisation : la fiche se retire")
            }
            val contenu = if (preClim != null) ClusterGaugeView.Contenu(
                // Ni jauge ni pastille tant que la fiche est là.
                module = module ?: ClusterModule.CONSUMPTION, kw = null, kmh = null, pastille = null,
                moyenneWhKm = null, recupWh = null, preClim = preClim,
            ) else module?.let {
                ClusterGaugeView.Contenu(
                    module = it,
                    kw = kwLisse,
                    kmh = kmh,
                    pastille = ClusterGauge.pill(choixPastille, mode, profil?.name,
                        // Couleur du mode du profil s'il règle la conduite, sinon celle de la voiture.
                        profil?.takeIf { p -> p.appliesDrive }?.driveMode, langue(ctx)),
                    moyenneWhKm = trajet.averageWhPerKm(),
                    recupWh = trajet.regenWh(),
                    unite = ClusterModuleSettings.consumptionUnit(ctx),
                    uniteMoyenne = ClusterModuleSettings.averageUnit(ctx),
                    // Auto : l'unité de la voiture, relue au plus une fois par minute (binder, ce fil).
                    distance = ClusterModuleSettings.distanceUnit(ctx,
                        if (ClusterModuleSettings.distanceChoice(ctx) == null) VehicleDistanceUnit.actualiser() else null),
                    style = style,
                    pleineEchelleKw = ClusterModuleSettings.fullScaleKw(ctx),
                    // À la place de la jauge réglée pour l'onglet, jamais sur un onglet sans module.
                    charge = if (session.active) vueCharge(maintenant) else null,
                )
            }
            main.post {
                val v = vue ?: return@post
                if (contenu == null) {
                    // Transparente : la page d'origine de l'onglet reparaît dessous.
                    v.visibility = View.INVISIBLE
                } else {
                    v.visibility = View.VISIBLE
                    v.montrer(contenu, animation)
                }
            }
            lecteur.postDelayed(this, intervalle)
        }
    }

    /**
     * Fin d'un trajet (sortie de READY) : sa pointe rejoint celles des derniers trajets, et le
     * plafond appris peut redescendre ([PowerScale.apresTrajet]). Fil du lecteur.
     */
    private fun finTrajet() {
        val ctx = appContext ?: return
        val pointe = pointeTrajet
        pointeTrajet = 0f
        if (pointe <= 0f) return
        val pointes = (ClusterModuleSettings.powerPeaks(ctx) + pointe).takeLast(PowerScale.MEMOIRE_TRAJETS)
        val avant = ClusterModuleSettings.learnedPowerKw(ctx)
        val apres = PowerScale.apresTrajet(avant, pointes)
        ClusterModuleSettings.saveLearnedPower(ctx, apres, pointes)
        AppLogger.i(TAG, "fin du trajet : pointe ${pointe.toInt()} kW, plafond appris $avant → $apres kW " +
            "(pointes : ${pointes.joinToString { it.toInt().toString() }})")
    }

    /** Langue choisie dans l'application (comme la vue) : le nom du profil s'écrit en capitales dans celle-ci. */
    private fun langue(ctx: Context): java.util.Locale = java.util.Locale.forLanguageTag(LocaleHelper.getLanguage(ctx))

    /** Ce que montre la fiche de la pré-climatisation à cet instant (fil du lecteur). */
    private fun vuePreClim(e: PreClimate.Etat, maintenant: Long): ClusterGaugeView.PreClim {
        val step = e.step
        val decompte = e.phase == PreClimate.Phase.COUNTDOWN
        // « Maintenant » : la clim tourne dès le décompte, réglée au démarrage.
        val enMarche = (e.phase == PreClimate.Phase.ACTIVE && step is PreClimatePlan.Step.Running) ||
            (decompte && e.req.mode == PreClimateMode.NOW)
        val etape = when {
            decompte -> ClusterGaugeView.Etape.Decompte(((e.lockAtMs - maintenant).coerceAtLeast(0L) + 999).div(1000).toInt())
            step is PreClimatePlan.Step.Waiting -> ClusterGaugeView.Etape.Attente(e.wallTimes.getOrNull(step.index)?.first ?: 0)
            step is PreClimatePlan.Step.Running && e.req.mode == PreClimateMode.AT_TIME ->
                ClusterGaugeView.Etape.Cycle(step.index + 1, e.cycles.size, e.wallTimes.getOrNull(step.index)?.second ?: 0)
            else -> ClusterGaugeView.Etape.Restant(
                minutes = if (step is PreClimatePlan.Step.Running) minutesRestantes(step.untilMs - maintenant) else e.req.maxMin,
                finMinute = (e.startWallMinute + e.req.maxMin).roundToInt())
        }
        // Comme l'écran central : le décompte se vide, le reste se remplit.
        val part = if (decompte) (e.lockAtMs - maintenant).coerceAtLeast(0L).toFloat() / (e.lockAtMs - e.startMs).coerceAtLeast(1L)
            else step?.let { PreClimatePlan.fraction(it, maintenant) } ?: 0f
        return ClusterGaugeView.PreClim(
            chien = e.req.dog,
            surveillance = e.req.surveillance,
            tendance = if (enMarche) PreClimatePlan.trend(e.req.tempC, e.outsideC) else PreClimateTrend.HOLDING,
            enMarche = enMarche,
            consigneC = e.req.tempC,
            dehorsC = e.outsideC,
            part = part,
            etape = etape,
            batterie = e.soc,
            verrou = if (decompte) null else e.lock,
            debutMinute = e.startWallMinute.toInt(),
        )
    }

    /** Minutes jusqu'à l'échéance, arrondies au-dessus : « 1 min » jusqu'à la dernière seconde. */
    private fun minutesRestantes(ms: Long): Int = ((ms.coerceAtLeast(0L) + 59_999L) / 60_000L).toInt()

    /** Ce que montre le module « charge » à cet instant (fil du lecteur). */
    private fun vueCharge(maintenant: Long): ClusterGaugeView.Charge {
        val brut = chargeBrute
        val restant = if (chargeFinie) null else brut?.let { ChargeDisplay.minutesRestantes(it.restant) }
        val horloge = System.currentTimeMillis()
        return ClusterGaugeView.Charge(
            type = brut?.let { ChargeDisplay.type(it.ac, it.dc) },
            kw = kwLisse?.let { -it },
            energieKwh = session.energieKwh,
            dureeMin = session.dureeMinutes(maintenant),
            restantMin = restant,
            ciblePct = brut?.let { ChargeDisplay.ciblePourcent(it.cible) },
            batterie = batterie,
            finie = chargeFinie,
            // L'heure de fin seulement sur une horloge fiable : au réveil, le boîtier peut se
            // croire en 2019.
            finVersMs = restant?.takeIf { StatsTracker.clockPlausible(horloge) }?.let { horloge + it * 60_000L },
        )
    }

    /** Fin de la session de charge (fil du lecteur) : un bilan au journal, et la jauge revient. */
    private fun finirCharge(maintenant: Long, raison: String) {
        AppLogger.i(TAG, "fin de charge ($raison) : +${"%.1f".format(java.util.Locale.US, session.energieKwh)} kWh en " +
            "${session.dureeMinutes(maintenant)} min, batterie ${batterieDebut?.let { "%.1f".format(java.util.Locale.US, it) } ?: "?"} → " +
            "${batterie?.let { "%.1f".format(java.util.Locale.US, it) } ?: "?"} % — la jauge revient")
        session.terminer()
        chargeBrute = null
        chargeFinie = false
        batterieDebut = null
    }
}
