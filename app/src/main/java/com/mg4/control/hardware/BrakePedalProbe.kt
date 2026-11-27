/*
 * MG4Control — sonde de la pédale de frein (issue #278).
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

package com.mg4.control.hardware

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.BrakePedalLog
import com.mg4.control.util.FirmwareInfo

/**
 * Sonde de la pédale de frein (2026-11-25, issue #278) et lectures du halo de freinage. Aucun
 * firmware n'expose les feux stop ; tous déclarent `SENSOR_BRAKE_PEDAL_DRIVER_APPLIED_PRESSURE`
 * ([PROP_PRESSION]) et l'accélération longitudinale mesurée ([PROP_ACCEL], lue par AllGo pour
 * Android Auto / CarPlay). Relevés toutes les [RELEVE_MS] en READY, avec la vitesse ; le journal
 * ([BrakePedalLog]) résume chaque appui. Lecture seule. Fil `mg4-brake`.
 *
 * 2026-11-27 (relevés SWI133 du 27/11) : les autres signaux essayés la veille sont retirés —
 * validité de la pédale, accélération « rapide » et états du freinage intégré toujours à 0, mode
 * « freinage par récupération » à 7 au moindre lever de pied.
 */
object BrakePedalProbe {

    const val TAG = "MG4_BRAKE"
    const val PROP_PRESSION = 0x2140159d
    const val PROP_ACCEL = 0x21601561
    private const val RELEVE_MS = 200L
    private const val AREA_GLOBAL = 0x1000000

    private val fil: Handler by lazy { Handler(HandlerThread("mg4-brake").also { it.start() }.looper) }
    @Volatile private var tour = 0
    private var compteur = 0
    private var journal: BrakePedalLog? = null
    private var abonne = false

    private val readyListener = ReadyWatcher.Listener { ready, _ ->
        if (ready) demarrer() else arreter("sortie de READY")
    }

    /** Une fois, au démarrage du service : la sonde suit ensuite chaque cycle READY. */
    @Synchronized
    fun start() {
        if (abonne) return
        abonne = true
        ReadyWatcher.add(readyListener)
    }

    @Synchronized
    private fun demarrer() {
        if (tour != 0) return
        compteur++
        val t = compteur
        tour = t
        fil.post {
            journal = BrakePedalLog()
            AppLogger.i(TAG, "pédale de frein suivie en READY (${FirmwareInfo.getGeneration()}, toutes les $RELEVE_MS ms) — " +
                "pression 0x${Integer.toHexString(PROP_PRESSION)}=${pression() ?: "illisible"} · " +
                "accél. 0x${Integer.toHexString(PROP_ACCEL)}=${acceleration() ?: "illisible"}")
            relever(t)
        }
    }

    @Synchronized
    private fun arreter(pourquoi: String) {
        if (tour == 0) return
        tour = 0
        fil.post {
            journal?.let { AppLogger.i(TAG, it.bilan() + " ($pourquoi)") }
            journal = null
        }
    }

    private fun relever(t: Int) {
        if (tour != t) return
        val r = BrakePedalLog.Releve(
            tMs = SystemClock.elapsedRealtime(),
            pression = pression(),
            kmh = runCatching { MG4Hardware.getVehicleSpeedKmh() }.getOrNull()?.takeIf { it in 0f..300f },
            accel = acceleration(),
        )
        journal?.add(r)?.forEach { AppLogger.i(TAG, it) }
        fil.postDelayed({ relever(t) }, RELEVE_MS)
    }

    /** Pression brute de la pédale (halo de freinage), `null` si illisible. Fil de l'appelant (binder). */
    fun pression(): Int? = runCatching { MG4Hardware.readIntGlobal(PROP_PRESSION) }.getOrNull()?.takeIf { it >= 0 }

    /**
     * Accélération longitudinale mesurée (m/s², négative au ralentissement), `null` si illisible.
     * Lecture silencieuse : un échec relu toutes les 200 ms ne doit pas remplir le journal.
     */
    fun acceleration(): Float? {
        val cpm = MG4Hardware.carPropertyManager() ?: return null
        return runCatching {
            cpm.javaClass.getMethod("getFloatProperty", Int::class.java, Int::class.java)
                .invoke(cpm, PROP_ACCEL, AREA_GLOBAL) as? Float
        }.getOrNull()?.takeIf { !it.isNaN() && it in -20f..20f }
    }
}
