package com.mg4.control.hardware

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.mg4.control.debug.AppLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sonde ACC / TJA / ICA (issue #133, 2026-10-24), en **lecture seule** : elle n'écrit rien sur le
 * véhicule. Le but : savoir quand la conduite assistée s'enclenche et se coupe, pour baisser la
 * régénération (couper la conduite à une pédale, passer en niveau 1) à sa sortie — le coup de
 * frein d'une régénération forte au moment où l'ACC rend la main.
 *
 * Ce que l'appli lisait jusque-là (`getAccTjaMode`, `getMixedIntelligentDrive`) est le MODE choisi
 * dans les réglages, pas l'état enclenché. Les signaux suivis ici ont été trouvés dans les
 * firmwares le 2026-10-14, sans leurs valeurs : aucune appli de l'écran central ne les lit, c'est
 * le combiné qui affiche l'ACC. D'où la sonde.
 *
 * Pendant READY, ils sont relus toutes les [SUIVI_MS] et chaque changement est noté avec l'heure,
 * le temps depuis le READY, la vitesse et la régénération : de quoi faire correspondre les valeurs
 * à ce que le conducteur a fait (enclencher, couper au bouton, freiner, reprendre le volant) et
 * mesurer en combien de temps la coupure se voit. Journal : [TAG] ; les derniers changements
 * vont aussi dans le rapport du Diagnostic ([reports]).
 */
object AccProbe {

    const val TAG = "MG4_ACC"

    private const val AREA_GLOBAL = 0x1000000
    /** Assez rapide pour mesurer le délai de la coupure, assez lent pour rester discret. */
    private const val SUIVI_MS = 200L
    private const val MAX_EVENEMENTS = 80

    private data class Signal(val nom: String, val id: Int)

    private val SIGNAUX = listOf(
        // SWI133, SWI165, SWI68, SWI69 (pas SWI131).
        Signal("ACC_SYSTEM_STATUS", 0x2140a1d9),
        // Partout. Valeurs A9 connues : 0 aucune, 1 suivi de véhicule, 2 centrage, 3 combiné.
        Signal("AAD_LANE_TJAICAOPMD", 0x2140a12a),
        // Demande de reprise en main : SWI133, SWI165, SWI68, SWI69.
        Signal("ACC_DRIVER_TKOV_REQ", 0x2140a1da),
        // SWI133, SWI68, SWI69 (pas SWI165, SWI131).
        Signal("TJA_PILOT_SYS", 0x21407b44),
    )

    private val evenements = ArrayDeque<String>()

    /** Les derniers changements, pour le rapport du Diagnostic. */
    val reports: List<String>
        @Synchronized get() = if (evenements.isEmpty()) emptyList()
            else listOf("── Sonde ACC / TJA / ICA : changements pendant READY ──\n" + evenements.joinToString("\n"))

    private val handler: Handler by lazy { Handler(HandlerThread("mg4-acc-probe").also { it.start() }.looper) }
    /** Jeton du suivi, arrêté hors READY. */
    private val SUIVI = Any()
    /** Dernière valeur vue par signal. Fil de la sonde uniquement. */
    private val derniers = HashMap<Int, String>()
    private var debutReadyMs = 0L

    private val suivi = object : Runnable {
        override fun run() {
            val cpm = MG4Hardware.carPropertyManager()
            val debut = derniers.isEmpty()
            val changements = mutableListOf<String>()
            SIGNAUX.forEach { s ->
                val v = lire(cpm, s.id, AREA_GLOBAL)
                val avant = derniers.put(s.id, v)
                if (avant != null && avant != v) changements += "${s.nom} : $avant → $v"
            }
            when {
                debut -> evenement("début READY : " +
                    SIGNAUX.joinToString(" · ") { "${it.nom}=${derniers[it.id]}" } +
                    " · zone 0 : " + SIGNAUX.joinToString(" · ") { "${it.nom}=${lire(cpm, it.id, 0)}" })
                changements.isNotEmpty() -> evenement(changements.joinToString(" · "))
            }
            handler.postDelayed(this, SUIVI, SUIVI_MS)
        }
    }

    private val readyListener = ReadyWatcher.Listener { ready, _ ->
        handler.removeCallbacksAndMessages(SUIVI)
        if (!ready) return@Listener
        handler.post {
            derniers.clear()
            debutReadyMs = SystemClock.elapsedRealtime()
        }
        handler.postDelayed(suivi, SUIVI, SUIVI_MS)
    }

    fun start() {
        ReadyWatcher.add(readyListener)
    }

    /** Une ligne datée : heure, temps depuis le READY (horloge fiable même mal réglée), vitesse, régénération. */
    @Synchronized
    private fun evenement(texte: String) {
        val heure = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val depuis = String.format(Locale.US, "+%.1f s", (SystemClock.elapsedRealtime() - debutReadyMs) / 1000f)
        val vitesse = MG4Hardware.getVehicleSpeedKmh()?.let { String.format(Locale.US, "%.0f km/h", it) } ?: "vitesse ?"
        val regen = runCatching { MG4Hardware.getRegenLevel()?.label }.getOrNull() ?: "?"
        val ligne = "$heure ($depuis) $texte | $vitesse · régén. $regen"
        AppLogger.i(TAG, ligne)
        evenements.addLast("  $ligne")
        while (evenements.size > MAX_EVENEMENTS) evenements.removeFirst()
    }

    private fun lire(cpm: Any?, id: Int, area: Int): String {
        cpm ?: return "cpm absent"
        return try {
            cpm.javaClass.getMethod("getIntProperty", Int::class.java, Int::class.java)
                .invoke(cpm, id, area)?.toString() ?: "null"
        } catch (e: Exception) {
            // Signal absent de ce firmware, ou zone refusée : le nom de l'exception suffit.
            (e.cause ?: e).javaClass.simpleName
        }
    }
}
