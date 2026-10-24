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
 * dans les réglages, pas l'état enclenché. Aucune appli de l'écran central ne lit l'état : c'est le
 * combiné qui affiche l'ACC.
 *
 * Premier relevé SWI133 (2026-10-24, ACC, TJA et ICA enclenchés puis coupés en roulant) : les
 * quatre premiers signaux de la liste sont restés à 0. Soit le VHAL ne les remplit que pour un
 * abonné — personne ne s'y abonnait —, soit ce ne sont pas les bons. D'où cette version : une
 * vingtaine de candidats de la table SWI133 (`YFVehicleProperty`, plage ADAS 0x2140a1xx),
 * ABONNÉS (rappel à chaque changement) ET relus toutes les [SUIVI_MS].
 *
 * Chaque changement pendant READY est noté avec l'heure, le temps depuis le READY, la vitesse et
 * sa source (« évt » = rappel, « lu » = relecture). Une mesure (distance, cible) n'est notée qu'à
 * son apparition et à sa disparition ; un autre signal bavard, une fois par [PAUSE_MS] au plus,
 * avec le nombre de changements tus. Journal : [TAG] ; les derniers
 * changements vont aussi dans le rapport du Diagnostic ([reports]).
 */
object AccProbe {

    const val TAG = "MG4_ACC"

    private const val AREA_GLOBAL = 0x1000000
    /** Assez rapide pour mesurer le délai de la coupure, assez lent pour rester discret. */
    private const val SUIVI_MS = 200L
    private const val PAUSE_MS = 1_000L
    private const val MAX_EVENEMENTS = 200

    /**
     * [mesure] : une grandeur qui bouge sans cesse en suivant une voiture (distance, cible) — seuls
     * comptent son apparition et sa disparition, notées au passage de 0 à autre chose et retour.
     */
    private data class Signal(val nom: String, val id: Int, val mesure: Boolean = false)

    private val SIGNAUX = listOf(
        // Premier relevé : toujours 0 sans abonnement.
        Signal("ACC_SYSTEM_STATUS", 0x2140a1d9),
        Signal("ACC_DRIVER_TKOV_REQ", 0x2140a1da),
        Signal("AAD_LANE_TJAICAOPMD", 0x2140a12a),
        Signal("TJA_PILOT_SYS", 0x21407b44),
        // Régulateur et assistance de vitesse.
        Signal("ACC_PROMPT_MSG", 0x2140a1cb),
        Signal("ACC_DET_DISTANCE", 0x21407b48, mesure = true),
        Signal("AAD_ACC_SWITCH", 0x2140a17f),
        Signal("AAD_MODE_TYPE", 0x2140a17d),
        Signal("AAD_ACC_OBJ_ID", 0x2140a12b, mesure = true),
        Signal("CC_STATUS_CCA_SWA_L", 0x2140a1f6),
        Signal("FIMC_CC_SW", 0x2140a1e9),
        Signal("CC_SPDVC_CNFM", 0x2140a1af),
        Signal("SPD_AST_MDECM_VCU", 0x2140a1f8),
        Signal("TRAGET_SPEED_SRC_STS_VCU", 0x2140a211),
        Signal("SPEED_AST_SYS_STS_ECM_VCU", 0x2140a213),
        // Maintien dans la voie, changement de voie.
        Signal("LKA_SYS_STATUS", 0x2140a202),
        Signal("AAD_TJA_SYS_FLT_STS", 0x2140a174),
        Signal("TJASYS_FLT_SYS", 0x2140a20c),
        Signal("ILC_ACTIVE_DSP_CMD", 0x21407b45),
        Signal("ILC_SWITCH", 0x2140a1b8),
        Signal("SENSOR_ADASAVLBLY", 0x21401599),
        // Commandes au volant : le levier du régulateur y passe peut-être.
        Signal("SWSSEL_UP_SWA_L", 0x21407b42),
        Signal("SWSSEL_L_SWA_L", 0x21407b43),
        // Le contexte de l'automatisation : régénération et conduite à une pédale.
        Signal("REGENERATIVE_LEVEL", 0x2140a191),
        Signal("SIGNAL_PEDAL_ON", 0x2140a193),
    )
    private val parId = SIGNAUX.associateBy { it.id }

    private val evenements = ArrayDeque<String>()

    /** Les derniers changements, pour le rapport du Diagnostic. */
    val reports: List<String>
        @Synchronized get() = if (evenements.isEmpty()) emptyList()
            else listOf("── Sonde ACC / TJA / ICA : changements pendant READY ──\n" + evenements.joinToString("\n"))

    private val handler: Handler by lazy { Handler(HandlerThread("mg4-acc-probe").also { it.start() }.looper) }
    /** Jeton du suivi, arrêté hors READY. */
    private val SUIVI = Any()

    // Fil de la sonde uniquement.
    private val derniers = HashMap<Int, String>()
    private val dernierNote = HashMap<Int, Long>()
    private val tus = HashMap<Int, Int>()
    private var enReady = false
    private var debutReadyMs = 0L
    private var abonne = false
    /** Référence forte : le gestionnaire ne garde peut-être qu'une référence faible au rappel. */
    private var rappel: Any? = null

    private val suivi = object : Runnable {
        override fun run() {
            val cpm = MG4Hardware.carPropertyManager()
            if (!abonne && cpm != null) abonner(cpm)
            val debut = derniers.isEmpty()
            SIGNAUX.forEach { s -> noter(s, lire(cpm, s.id), "lu", silencieux = debut) }
            if (debut) evenement("début READY : " + SIGNAUX.joinToString(" · ") { "${it.nom}=${derniers[it.id]}" })
            // Un signal tu depuis assez longtemps : sa dernière valeur, pour ne pas la perdre.
            val maintenant = SystemClock.elapsedRealtime()
            tus.keys.toList().forEach { id ->
                if (maintenant - (dernierNote[id] ?: 0L) >= PAUSE_MS) {
                    val n = tus.remove(id) ?: return@forEach
                    dernierNote[id] = maintenant
                    evenement("${parId[id]?.nom} : maintenant ${derniers[id]} (+$n changement(s) tu(s))")
                }
            }
            handler.postDelayed(this, SUIVI, SUIVI_MS)
        }
    }

    private val readyListener = ReadyWatcher.Listener { ready, _ ->
        handler.removeCallbacksAndMessages(SUIVI)
        handler.post {
            enReady = ready
            if (ready) {
                derniers.clear()
                dernierNote.clear()
                tus.clear()
                debutReadyMs = SystemClock.elapsedRealtime()
            }
        }
        if (ready) handler.postDelayed(suivi, SUIVI, SUIVI_MS)
    }

    fun start() {
        ReadyWatcher.add(readyListener)
    }

    /** Fil de la sonde. [silencieux] : première lecture, notée dans la ligne « début READY ». */
    private fun noter(s: Signal, valeur: String, source: String, silencieux: Boolean = false) {
        val avant = derniers.put(s.id, valeur)
        if (silencieux || !enReady || avant == null || avant == valeur) return
        if (s.mesure && (avant == "0") == (valeur == "0")) return
        val maintenant = SystemClock.elapsedRealtime()
        if (maintenant - (dernierNote[s.id] ?: 0L) < PAUSE_MS) {
            tus[s.id] = (tus[s.id] ?: 0) + 1
            return
        }
        dernierNote[s.id] = maintenant
        val n = tus.remove(s.id)
        evenement("${s.nom} : $avant → $valeur [$source]" + (n?.let { " (+$it changement(s) tu(s) avant)" } ?: ""))
    }

    /**
     * Un rappel par signal, au changement (cadence 0). Un signal absent de ce firmware est refusé :
     * la liste des refus est notée une fois.
     */
    private fun abonner(cpm: Any) {
        abonne = true
        val methode = cpm.javaClass.methods.firstOrNull { it.name == "registerCallback" && it.parameterCount == 3 }
        val type = methode?.parameterTypes?.get(0)
        if (methode == null || type == null || !type.isInterface) {
            AppLogger.w(TAG, "abonnement impossible : pas de registerCallback à 3 paramètres")
            return
        }
        val proxy = java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { soi, m, args ->
            when (m.name) {
                "onChangeEvent" -> {
                    val v = args?.getOrNull(0)
                    runCatching {
                        val id = v!!.javaClass.getMethod("getPropertyId").invoke(v) as Int
                        val valeur = v.javaClass.getMethod("getValue").invoke(v)?.toString() ?: "null"
                        parId[id]?.let { s -> handler.post { noter(s, valeur, "évt") } }
                    }
                    null
                }
                // Le gestionnaire range ses rappels dans des ensembles : ces trois-là doivent répondre.
                "hashCode" -> System.identityHashCode(soi)
                "equals" -> soi === args?.getOrNull(0)
                "toString" -> "AccProbe"
                else -> null
            }
        }
        rappel = proxy
        val refuses = mutableListOf<String>()
        SIGNAUX.forEach { s ->
            runCatching { methode.invoke(cpm, proxy, s.id, 0f) as? Boolean }
                .onSuccess { if (it == false) refuses += s.nom }
                .onFailure { refuses += s.nom }
        }
        AppLogger.i(TAG, "abonné à ${SIGNAUX.size - refuses.size}/${SIGNAUX.size} signaux" +
            if (refuses.isEmpty()) "" else " — refusés : ${refuses.joinToString()}")
    }

    /** Une ligne datée : heure, temps depuis le READY (horloge fiable même mal réglée), vitesse. */
    @Synchronized
    private fun evenement(texte: String) {
        val heure = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val depuis = String.format(Locale.US, "+%.1f s", (SystemClock.elapsedRealtime() - debutReadyMs) / 1000f)
        val vitesse = MG4Hardware.getVehicleSpeedKmh()?.let { String.format(Locale.US, "%.0f km/h", it) } ?: "vitesse ?"
        val ligne = "$heure ($depuis) $texte | $vitesse"
        AppLogger.i(TAG, ligne)
        evenements.addLast("  $ligne")
        while (evenements.size > MAX_EVENEMENTS) evenements.removeFirst()
    }

    private fun lire(cpm: Any?, id: Int): String {
        cpm ?: return "cpm absent"
        return try {
            cpm.javaClass.getMethod("getIntProperty", Int::class.java, Int::class.java)
                .invoke(cpm, id, AREA_GLOBAL)?.toString() ?: "null"
        } catch (e: Exception) {
            // Signal absent de ce firmware, ou zone refusée : le nom de l'exception suffit.
            (e.cause ?: e).javaClass.simpleName
        }
    }
}
