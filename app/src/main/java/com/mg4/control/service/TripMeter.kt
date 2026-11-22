package com.mg4.control.service

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.EnergyReader
import com.mg4.control.hardware.MG4Hardware
import com.mg4.control.hardware.ReadyWatcher
import com.mg4.control.model.ClusterInfo
import com.mg4.control.model.PowerIntegrator
import com.mg4.control.model.SpeedIntegrator
import com.mg4.control.model.TripPeriod
import com.mg4.control.model.TripTotals

/**
 * Compteurs de trajet par période (2026-11-22) : depuis READY, depuis l'allumage de l'écran
 * central, depuis la dernière charge. Pour les bulles (toutes périodes) et l'écran conducteur
 * (démarrage et charge ; sa période READY garde son propre compteur, plus fin).
 *
 * En READY, puissance batterie et vitesse sont intégrées chaque seconde, comme le pied des
 * modules. En sortant de READY, le trajet s'ajoute aux deux autres périodes, gardées dans les
 * préférences : « démarrage » repart de zéro quand le compteur de démarrages d'Android change,
 * « charge » quand une charge est vue (relevé toutes les 10 s hors READY) ou qu'au passage suivant
 * en READY la batterie a pris au moins [TripPeriod.CHARGE_SOC_STEP] points.
 */
object TripMeter {

    private const val TAG = "MG4_TRIP"
    private const val PREFS = "mg4_trip_meter"
    private const val TICK_READY_MS = 1_000L
    private const val TICK_IDLE_MS = 10_000L

    private val thread = HandlerThread("mg4-trip").apply { start() }
    private val worker = Handler(thread.looper)
    private val verrou = Any()

    @Volatile private var app: Context? = null
    private var demarre = false

    // Trajet en cours (ou le dernier, une fois sorti de READY : [compte] = déjà ajouté aux bases).
    private val energie = PowerIntegrator()
    private val vitesses = SpeedIntegrator()
    private var debutReadyMs = 0L
    private var finReadyMs = 0L
    private var enReady = false
    private var compte = true
    private var chargeVue = false

    private var baseDemarrage = TripTotals()
    private var baseCharge = TripTotals()

    /** Au démarrage du service. */
    fun start(context: Context) {
        val ctx = context.applicationContext
        worker.post {
            if (demarre) return@post
            demarre = true
            app = ctx
            charger(ctx)
            ReadyWatcher.add(ecoute)
            worker.post(tic)
        }
    }

    private val ecoute = ReadyWatcher.Listener { ready, _ ->
        worker.post { if (ready) entreeReady() else sortieReady() }
    }

    private val tic = object : Runnable {
        override fun run() {
            if (enReady) {
                val t = SystemClock.elapsedRealtime()
                val kw = runCatching { EnergyReader.dischargeKw() }.getOrNull()
                val kmh = runCatching { MG4Hardware.getVehicleSpeedKmh() }.getOrNull()?.takeIf { it in 0f..300f }
                synchronized(verrou) {
                    energie.add(t, kw)
                    vitesses.add(t, kmh)
                }
            } else {
                surveillerCharge()
            }
            worker.postDelayed(this, if (enReady) TICK_READY_MS else TICK_IDLE_MS)
        }
    }

    private fun entreeReady() {
        if (enReady) return
        val ctx = app ?: return
        val p = prefs(ctx)
        val soc = runCatching { EnergyReader.soc() }.getOrNull()
        val avant = if (p.contains("soc_off")) p.getFloat("soc_off", 0f) else null
        if (TripPeriod.chargedMeanwhile(avant, soc)) remettreCharge(ctx, "batterie passée de $avant à $soc % depuis la sortie de READY")
        synchronized(verrou) {
            energie.reset()
            vitesses.reset()
            debutReadyMs = SystemClock.elapsedRealtime()
            enReady = true
            compte = false
        }
        worker.removeCallbacks(tic)
        worker.post(tic)
    }

    private fun sortieReady() {
        if (!enReady) return
        val ctx = app ?: return
        val trajet: TripTotals
        synchronized(verrou) {
            finReadyMs = SystemClock.elapsedRealtime()
            enReady = false
            trajet = courantLocked()
            baseDemarrage += trajet
            baseCharge += trajet
            compte = true
        }
        val soc = runCatching { EnergyReader.soc() }.getOrNull()
        prefs(ctx).edit().apply {
            ecrire(this, "boot", baseDemarrage)
            ecrire(this, "charge", baseCharge)
            if (soc != null) putFloat("soc_off", soc)
        }.apply()
        AppLogger.i(TAG, "fin de READY : trajet ${fmt(trajet)} ; depuis le démarrage ${fmt(baseDemarrage)} ; " +
            "depuis la charge ${fmt(baseCharge)}")
    }

    /** Hors READY : une charge en cours remet la période « charge » à zéro (une fois par charge). */
    private fun surveillerCharge() {
        val ctx = app ?: return
        val enCharge = runCatching { EnergyReader.charging() }.getOrNull() == true
        if (enCharge && !chargeVue) remettreCharge(ctx, "charge en cours")
        chargeVue = enCharge
    }

    private fun remettreCharge(ctx: Context, pourquoi: String) {
        synchronized(verrou) { baseCharge = TripTotals() }
        prefs(ctx).edit().apply { ecrire(this, "charge", baseCharge) }.apply()
        AppLogger.i(TAG, "période « depuis la dernière charge » remise à zéro : $pourquoi")
    }

    /** Totaux de la période [p], trajet en cours compris. */
    fun totals(p: TripPeriod): TripTotals = synchronized(verrou) {
        val courant = if (compte && p != TripPeriod.READY) TripTotals() else courantLocked()
        when (p) {
            TripPeriod.READY -> courant
            TripPeriod.BOOT -> baseDemarrage + courant
            TripPeriod.CHARGE -> baseCharge + courant
        }
    }

    /** Les infos du combiné pour la période [p]. */
    fun trip(p: TripPeriod, powerKw: Float?): ClusterInfo.Trip = totals(p).toTrip(powerKw)

    private fun courantLocked(): TripTotals {
        val e = energie.result()
        val fin = if (enReady) SystemClock.elapsedRealtime() else finReadyMs
        return TripTotals(e.consumedKwh.toDouble(), e.regenKwh.toDouble(), vitesses.result().km.toDouble(),
            if (debutReadyMs == 0L) 0L else (fin - debutReadyMs).coerceAtLeast(0L))
    }

    private fun charger(ctx: Context) {
        val p = prefs(ctx)
        val demarrages = runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)
        val memeDemarrage = demarrages >= 0 && p.getInt("boot_count", -2) == demarrages
        synchronized(verrou) {
            baseDemarrage = if (memeDemarrage) lire(p, "boot") else TripTotals()
            baseCharge = lire(p, "charge")
        }
        if (!memeDemarrage) p.edit().putInt("boot_count", demarrages).apply { ecrire(this, "boot", TripTotals()) }.apply()
        AppLogger.i(TAG, "compteurs par période : démarrage n° $demarrages (${if (memeDemarrage) "repris" else "nouveau"}), " +
            "depuis la charge ${fmt(baseCharge)}")
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun lire(p: android.content.SharedPreferences, k: String) = TripTotals(
        p.getFloat("${k}_kwh", 0f).toDouble(), p.getFloat("${k}_regen", 0f).toDouble(),
        p.getFloat("${k}_km", 0f).toDouble(), p.getLong("${k}_ms", 0L))

    private fun ecrire(e: android.content.SharedPreferences.Editor, k: String, t: TripTotals) {
        e.putFloat("${k}_kwh", t.consumedKwh.toFloat()).putFloat("${k}_regen", t.regenKwh.toFloat())
            .putFloat("${k}_km", t.km.toFloat()).putLong("${k}_ms", t.readyMs)
    }

    private fun fmt(t: TripTotals) = String.format(java.util.Locale.US, "%.1f km, %.2f kWh − %.2f kWh, %d min",
        t.km, t.consumedKwh, t.regenKwh, t.readyMs / 60_000)
}
