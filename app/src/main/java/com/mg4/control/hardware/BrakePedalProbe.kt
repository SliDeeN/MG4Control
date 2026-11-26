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
 * Sonde de la pédale de frein (2026-11-25, issue #278) : aucun firmware n'expose les feux stop,
 * mais tous déclarent `SENSOR_BRAKE_PEDAL_DRIVER_APPLIED_PRESSURE` ([PROP_PRESSION]) et son
 * indicateur de validité ([PROP_VALIDE]), jamais lus jusqu'ici. Relevés toutes les [RELEVE_MS]
 * en READY, avec la vitesse ; le journal ([BrakePedalLog]) dit s'ils répondent et comment.
 * Lecture seule. Fil `mg4-brake`.
 *
 * 2026-11-26 (issue #278, ce qui allume les feux stop) : aussi l'accélération longitudinale mesurée
 * ([PROP_ACCEL], lue par AllGo pour Android Auto / CarPlay), l'accélération « rapide », le mode du
 * système électrique ([PROP_MODE] : 7 = freinage par récupération, lu par SAIC pour le flux
 * d'énergie) et les deux états du freinage intégré IBS — déclarés sur tous les firmwares.
 */
object BrakePedalProbe {

    const val TAG = "MG4_BRAKE"
    const val PROP_PRESSION = 0x2140159d
    const val PROP_VALIDE = 0x214015a3
    const val PROP_ACCEL = 0x21601561
    const val PROP_ACCEL_VALIDE = 0x214015a1
    const val PROP_ACCEL_RAPIDE = 0x21601564
    const val PROP_ACCEL_RAPIDE_VALIDE = 0x214015a2
    const val PROP_MODE = 0x2140a15a
    const val PROP_IBS_RECUP = 0x2140a1e0
    const val PROP_IBS_PEDALE = 0x2140a1e1
    private const val RELEVE_MS = 200L

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
            val cpmP = lire(PROP_PRESSION)
            val cpmV = lire(PROP_VALIDE)
            val vpmP = runCatching { MG4Hardware.vpmIntForProbe(PROP_PRESSION) }.getOrNull()
            val vpmV = runCatching { MG4Hardware.vpmIntForProbe(PROP_VALIDE) }.getOrNull()
            AppLogger.i(TAG, "pédale de frein suivie en READY (${FirmwareInfo.getGeneration()}, toutes les $RELEVE_MS ms) — " +
                "pression 0x${Integer.toHexString(PROP_PRESSION)} : cpm=${cpmP ?: "illisible"} vpm=${vpmP ?: "?"} · " +
                "valide 0x${Integer.toHexString(PROP_VALIDE)} : cpm=${cpmV ?: "illisible"} vpm=${vpmV ?: "?"}")
            AppLogger.i(TAG, "signaux au départ — accél. 0x${Integer.toHexString(PROP_ACCEL)}=${lireF(PROP_ACCEL) ?: "illisible"} " +
                "(valide ${lireSilencieux(PROP_ACCEL_VALIDE) ?: "illisible"}) · accél. rapide 0x${Integer.toHexString(PROP_ACCEL_RAPIDE)}=" +
                "${lireF(PROP_ACCEL_RAPIDE) ?: "illisible"} (valide ${lireSilencieux(PROP_ACCEL_RAPIDE_VALIDE) ?: "illisible"}) · " +
                "mode élec. 0x${Integer.toHexString(PROP_MODE)}=${lireSilencieux(PROP_MODE) ?: "illisible"} · " +
                "IBS récup 0x${Integer.toHexString(PROP_IBS_RECUP)}=${lireSilencieux(PROP_IBS_RECUP) ?: "illisible"} · " +
                "IBS pédale 0x${Integer.toHexString(PROP_IBS_PEDALE)}=${lireSilencieux(PROP_IBS_PEDALE) ?: "illisible"}")
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
            pression = lire(PROP_PRESSION),
            valide = lire(PROP_VALIDE),
            kmh = runCatching { MG4Hardware.getVehicleSpeedKmh() }.getOrNull()?.takeIf { it in 0f..300f },
            accel = lireF(PROP_ACCEL),
            accelValide = lireSilencieux(PROP_ACCEL_VALIDE),
            accelRapide = lireF(PROP_ACCEL_RAPIDE),
            accelRapideValide = lireSilencieux(PROP_ACCEL_RAPIDE_VALIDE),
            mode = lireSilencieux(PROP_MODE),
            ibsRecup = lireSilencieux(PROP_IBS_RECUP),
            ibsPedale = lireSilencieux(PROP_IBS_PEDALE),
        )
        journal?.add(r)?.forEach { AppLogger.i(TAG, it) }
        fil.postDelayed({ relever(t) }, RELEVE_MS)
    }

    /** Pression brute de la pédale (halo de freinage), `null` si illisible. Fil de l'appelant (binder). */
    fun pression(): Int? = lire(PROP_PRESSION)

    /**
     * Flottant CarPropertyManager en zone globale ; `null` si illisible. Lecture silencieuse : un
     * échec relu toutes les 200 ms ne doit pas remplir le journal (la ligne « illisible » suffit).
     */
    private fun lireF(id: Int): Float? {
        val cpm = MG4Hardware.carPropertyManager() ?: return null
        return runCatching {
            cpm.javaClass.getMethod("getFloatProperty", Int::class.java, Int::class.java)
                .invoke(cpm, id, AREA_GLOBAL) as? Float
        }.getOrNull()?.takeIf { !it.isNaN() }
    }

    /** Entier, même lecture silencieuse ; un négatif (échec) rendu `null`. */
    private fun lireSilencieux(id: Int): Int? {
        val cpm = MG4Hardware.carPropertyManager() ?: return null
        return runCatching {
            cpm.javaClass.getMethod("getIntProperty", Int::class.java, Int::class.java)
                .invoke(cpm, id, AREA_GLOBAL) as? Int
        }.getOrNull()?.takeIf { it >= 0 }
    }

    private const val AREA_GLOBAL = 0x1000000

    /** CarPropertyManager en zone globale ; -1 (échec) rendu `null`. */
    private fun lire(id: Int): Int? = runCatching { MG4Hardware.readIntGlobal(id) }.getOrNull()?.takeIf { it >= 0 }
}
