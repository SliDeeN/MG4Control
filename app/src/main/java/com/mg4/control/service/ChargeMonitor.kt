package com.mg4.control.service

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.EnergyReader
import com.mg4.control.hardware.ReadyWatcher
import com.mg4.control.model.ChargeDisplay
import com.mg4.control.model.ChargeLimit
import com.mg4.control.model.ChargeLog
import com.mg4.control.model.ChargePoint
import com.mg4.control.model.ChargeSession
import com.mg4.control.model.ChargeType
import com.mg4.control.model.StatsTracker
import com.mg4.control.stats.StatsStore
import com.mg4.control.util.ChargePopupSettings

/**
 * Suivi de la charge pour le pop-up de charge (2026-10-16) : tourne dans le service tant que
 * l'option est active (Réglages › Interface, cachée par défaut), pop-up ouvert ou non.
 *
 *  - Hors charge, une seule lecture toutes les [VEILLE_MS] : la voiture charge-t-elle ?
 *  - Dès qu'une charge commence — ou que l'application en découvre une —, une [ChargeLog] la suit
 *    depuis son début : puissance, niveau, énergie, et le pop-up s'ouvre (pas en READY).
 *  - Une charge qui reprend moins de [REPRISE_MS] après s'être arrêtée reste la même session : une
 *    borne rapide marque parfois une pause.
 *  - Au passage en READY, le pop-up se ferme ([ReadyWatcher]).
 *
 * Écritures véhicule : la seule est la limite de charge, demandée par le curseur du pop-up
 * ([demanderLimite]) et suivie jusqu'à ce que la voiture la reflète. Le prix du kWh corrigé dans
 * le pop-up ne vaut que pour cette charge : il passe à la session de l'onglet Statistiques
 * ([avecTarif], [fixerPrix]).
 */
object ChargeMonitor {

    const val TAG = "MG4_CHARGE"

    private const val VEILLE_MS = 10_000L
    private const val CHARGE_MS = 2_000L
    /** Pop-up ouvert pendant la charge : la puissance chaque seconde. */
    private const val VISIBLE_MS = 1_000L
    /** Temps restant, limite, type de borne et autonomie : bougent peu. */
    private const val LENT_MS = 10_000L
    private const val REPRISE_MS = 120_000L
    /** Délai laissé à la voiture pour refléter une nouvelle limite avant de la dire refusée. */
    private const val CONSIGNE_MS = 20_000L
    /** Durée d'affichage du refus d'une limite. */
    private const val REFUS_MS = 8_000L

    /** Ce que montre le pop-up : un instantané, construit sur le fil du suivi. */
    data class Etat(
        val enCharge: Boolean,
        val finie: Boolean,
        val type: ChargeType?,
        val soc: Float?,
        /** Puissance qui entre dans la batterie. */
        val kw: Float?,
        val kwMax: Float?,
        val energieKwh: Float,
        val dureeMin: Int,
        val restantMin: Int?,
        val limitePct: Int?,
        val limiteEnAttente: Boolean,
        /** Limite que la voiture n'a pas prise, montrée quelques secondes. */
        val limiteRefusee: Int?,
        val autonomieKm: Int?,
        val autonomieGagneeKm: Int?,
        val prix: Float,
        val prixCorrige: Boolean,
        val prixDefaut: Float,
        val devise: String,
        val debutHorlogeMs: Long?,
        val horlogeMs: Long,
        val ecouleMs: Long,
        val points: List<ChargePoint>,
    )

    private val fil = HandlerThread("mg4-charge").apply { start() }
    private val worker = Handler(fil.looper)
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var appContext: Context? = null

    // ── Fil du suivi ──
    private var enMarche = false
    @Volatile private var log: ChargeLog? = null
    private var enCharge = false
    private var brut: EnergyReader.ChargeRaw? = null
    private var autonomie: Int? = null
    private var kw: Float? = null
    private var soc: Float? = null
    private var dernierLentMs = 0L
    private var consigne: Int? = null
    private var consigneMs = 0L
    private var refus: Int? = null
    private var refusMs = 0L

    /** Dernier instantané ; `null` tant qu'aucune charge n'a été vue. */
    @Volatile var etat: Etat? = null
        private set

    /** Le pop-up, quand il est ouvert : appelé sur le fil principal à chaque relevé. */
    @Volatile var ecouteur: ((Etat) -> Unit)? = null

    private val readyListener = ReadyWatcher.Listener { ready, firstRead ->
        if (ready && !firstRead) ChargePopupOverlay.fermer("passage en READY")
    }

    /** Démarre ou arrête le suivi selon le réglage. Appelable de n'importe quel fil. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        appContext = app
        val actif = ChargePopupSettings.isEnabled(app)
        worker.post {
            if (actif && !enMarche) {
                enMarche = true
                ReadyWatcher.add(readyListener)
                AppLogger.i(TAG, "pop-up de charge : suivi actif")
                worker.post(tic)
            } else if (!actif && enMarche) {
                enMarche = false
                worker.removeCallbacks(tic)
                ReadyWatcher.remove(readyListener)
                log = null
                etat = null
                enCharge = false
                AppLogger.i(TAG, "pop-up de charge : suivi arrêté")
                main.post { ChargePopupOverlay.fermer("option désactivée") }
            }
        }
    }

    /** Le pop-up vient de s'ouvrir : un relevé tout de suite, puis chaque seconde. */
    fun accelerer() {
        worker.post {
            if (!enMarche) return@post
            worker.removeCallbacks(tic)
            worker.post(tic)
        }
    }

    /** Curseur relâché sur [pct] : écrit la limite, puis la suit jusqu'à ce que la voiture la reflète. */
    fun demanderLimite(pct: Int) {
        worker.post {
            val ok = runCatching { EnergyReader.setChargeLimitPercent(pct) }.getOrDefault(false)
            if (ok) {
                consigne = ChargeLimit.cran(pct.toFloat())
                consigneMs = SystemClock.elapsedRealtime()
                refus = null
            } else {
                refus = pct
                refusMs = SystemClock.elapsedRealtime()
            }
            publier(SystemClock.elapsedRealtime())
        }
    }

    /**
     * Prix du kWh pour CETTE charge ([prix] `null` : retour au tarif par défaut du type). Si
     * l'onglet Statistiques a déjà enregistré la session (charge finie), elle est corrigée aussi.
     */
    fun fixerPrix(prix: Float?) {
        worker.post {
            val l = log ?: return@post
            l.prix = prix
            AppLogger.i(TAG, "prix du kWh pour cette charge → ${prix ?: "tarif par défaut"}")
            val ctx = appContext
            if (l.finie && ctx != null) {
                runCatching {
                    val store = StatsStore(ctx)
                    val maintenant = SystemClock.elapsedRealtime()
                    val horloge = System.currentTimeMillis()
                    store.history().charges.lastOrNull { l.recouvre(it.startMs, it.endMs, horloge, maintenant) }
                        ?.let {
                            store.overrideTariff(it.startMs, prix)
                            AppLogger.i(TAG, "session des Statistiques du ${it.startMs} corrigée")
                        }
                }
            }
            publier(SystemClock.elapsedRealtime())
        }
    }

    /**
     * Pour l'onglet Statistiques, qui enregistre la session à la fin de la charge : le prix
     * corrigé dans le pop-up, si la session correspond à la charge suivie.
     */
    fun avecTarif(session: ChargeSession): ChargeSession {
        val l = log ?: return session
        val prix = l.prix ?: return session
        if (session.tariffOverride != null) return session
        val ok = l.recouvre(session.startMs, session.endMs, System.currentTimeMillis(), SystemClock.elapsedRealtime())
        return if (ok) session.copy(tariffOverride = prix) else session
    }

    private val tic = object : Runnable {
        override fun run() {
            if (!enMarche) return
            val maintenant = SystemClock.elapsedRealtime()
            runCatching { releve(maintenant) }
                .onFailure { AppLogger.w(TAG, "relevé manqué : ${it.javaClass.simpleName} ${it.message}") }
            val visible = ChargePopupOverlay.isShowing()
            worker.postDelayed(this, when {
                enCharge && visible -> VISIBLE_MS
                enCharge -> CHARGE_MS
                visible -> LENT_MS
                else -> VEILLE_MS
            })
        }
    }

    private fun releve(maintenant: Long) {
        // Lecture ratée : l'état d'avant, plutôt qu'une fin de charge suivie d'une « reprise ».
        val charge = EnergyReader.charging() ?: enCharge
        var nouvelle = false
        val l0 = log
        if (charge) {
            val fin = l0?.finMs
            if (l0 == null || (fin != null && maintenant - fin > REPRISE_MS)) {
                val horloge = System.currentTimeMillis()
                soc = EnergyReader.soc()
                autonomie = EnergyReader.rangeKm()
                log = ChargeLog(maintenant, horloge.takeIf { StatsTracker.clockPlausible(it) }, soc, autonomie)
                dernierLentMs = 0L
                brut = null
                consigne = null
                refus = null
                nouvelle = true
                AppLogger.i(TAG, "charge détectée (${soc ?: "?"} %) : suivi depuis maintenant")
            } else if (fin != null) {
                l0.reprendre()
                AppLogger.i(TAG, "la charge reprend : même session")
            }
        } else if (enCharge) {
            log?.terminer(maintenant)
            log?.let { AppLogger.i(TAG, "fin de charge : +${"%.1f".format(java.util.Locale.US, it.energieKwh)} kWh " +
                "en ${it.dureeMinutes(maintenant)} min, ${it.socDebut ?: "?"} → ${soc ?: "?"} %") }
        }
        enCharge = charge
        val l = log ?: return

        if (charge) {
            kw = EnergyReader.dischargeKw()?.let { -it }
            EnergyReader.soc()?.let { soc = it }
            l.ajouter(maintenant, kw, soc)
            if (dernierLentMs == 0L || maintenant - dernierLentMs >= LENT_MS || consigne != null) {
                dernierLentMs = maintenant
                brut = EnergyReader.chargeRaw()
                EnergyReader.rangeKm()?.let { autonomie = it }
            }
        } else {
            kw = null
        }
        publier(maintenant)
        if (nouvelle) {
            val ctx = appContext
            if (ctx != null && ReadyWatcher.ready != true) main.post { ChargePopupOverlay.ouvrir(ctx, "début de charge") }
        }
    }

    /** Construit l'instantané et le passe au pop-up (fil du suivi). */
    private fun publier(maintenant: Long) {
        val l = log ?: return
        val ctx = appContext ?: return
        val r = brut
        val lue = ChargeLimit.pct(r?.cible)
        val c = consigne
        if (c != null) {
            if (lue == c) {
                AppLogger.i(TAG, "limite $c % reflétée par la voiture après ${maintenant - consigneMs} ms")
                consigne = null
            } else if (maintenant - consigneMs > CONSIGNE_MS) {
                AppLogger.w(TAG, "limite $c % toujours pas reflétée (lu : ${lue ?: "?"}) : refusée")
                consigne = null
                refus = c
                refusMs = maintenant
            }
        }
        if (refus != null && maintenant - refusMs > REFUS_MS) refus = null
        val type = r?.let { ChargeDisplay.type(it.ac, it.dc) }
        val reglages = runCatching { StatsStore(ctx).settings() }.getOrNull()
        val prixDefaut = reglages?.priceFor(type) ?: 0f
        val e = Etat(
            enCharge = enCharge,
            finie = l.finie,
            type = type,
            soc = soc,
            kw = kw,
            kwMax = l.kwMax,
            energieKwh = l.energieKwh,
            dureeMin = l.dureeMinutes(maintenant),
            restantMin = if (enCharge) ChargeDisplay.minutesRestantes(r?.restant) else null,
            limitePct = consigne ?: lue,
            limiteEnAttente = consigne != null,
            limiteRefusee = refus,
            autonomieKm = autonomie,
            autonomieGagneeKm = autonomie?.let { a -> l.autonomieDebutKm?.let { a - it } }?.takeIf { it >= 0 },
            prix = l.prix ?: prixDefaut,
            prixCorrige = l.prix != null,
            prixDefaut = prixDefaut,
            devise = reglages?.currency ?: "€",
            debutHorlogeMs = l.debutHorlogeMs,
            horlogeMs = System.currentTimeMillis(),
            ecouleMs = (l.finMs ?: maintenant) - l.debutMs,
            points = l.courbe(),
        )
        etat = e
        main.post { ecouteur?.invoke(e) }
    }
}
