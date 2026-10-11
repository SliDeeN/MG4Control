package com.mg4.control.hardware

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.PropertySweep
import com.mg4.control.util.FirmwareInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sonde clé, en **lecture seule** : la voiture dit-elle à l'écran de bord si la clé sans fil est
 * là ? Elle n'écrit rien sur le véhicule. PROVISOIRE — à retirer une fois le relevé fait.
 *
 * La recherche dans les firmwares (2026-10-11, cinq tables de propriétés) n'a trouvé AUCUN signal
 * de présence ni de force de signal — seulement la disponibilité du calculateur PEPS, des codes
 * d'équipement et des réglages. Reste à le constater sur la voiture, de deux façons :
 *
 *  1. **Les signaux nommés** de [SIGNALS], relus à chaque ouverture du Diagnostic.
 *  2. **Un balayage à l'aveugle** de toutes les propriétés entières et booléennes du constructeur
 *     ([PropertySweep.sweepable]), comparé au balayage précédent : c'est lui qui dirait si la clé
 *     se cache sous un autre nom. Il tourne sur son propre fil, sans retarder le Diagnostic ; son
 *     résultat apparaît donc à l'ouverture SUIVANTE.
 *
 * Mode d'emploi : voiture à l'arrêt, sans toucher à rien d'autre —
 *  a. clé dans la voiture, ouvrir le Diagnostic, attendre 30 s, le fermer ;
 *  b. éloigner la clé, ouvrir le Diagnostic, attendre 30 s, le fermer ;
 *  c. rouvrir le Diagnostic : la section « Sonde clé : balayage » liste ce qui a changé entre a et b.
 */
object KeyProbe {

    const val TAG = "MG4_KEY"

    private const val AREA_GLOBAL = 0x1000000
    private const val MAX_RELEVES = 6
    /** Au-delà, le balayage n'a pas été fait à l'arrêt : inutile de noyer le rapport. */
    private const val MAX_CHANGEMENTS = 40

    private data class Signal(val name: String, val id: Int)

    private val SIGNALS = listOf(
        Signal("SENSOR_PEPSAVLBLY (calculateur PEPS joignable)", 0x21401582),
        Signal("SENSOR_BCMAVLBLY (calculateur de carrosserie, pour comparer)", 0x2140157f),
        Signal("SENSOR_REMOTELY_START_CAR_STATE", 0x21401562),
        Signal("COM_AUTO_IDENTIFICATION", 0x2140d70a),
        Signal("AUTO_KEY_IDEN_RECALL_OPT (mémoire siège/rétros par clé)", 0x2140c62d),
        Signal("DLOCK_UNLOCK_WITH_KEY", 0x2540c617),
        Signal("DLOCK_ENTRY_UNLOCK (déverrouillage à l'approche)", 0x2540c618),
        Signal("CAL_REMOTE_KEYLESS_ENTRY_SYSTEM (équipement)", 0x21704230),
        Signal("CAL_VEHICLE_START_TYPE (équipement)", 0x2170426d),
        Signal("SENSOR_EPTRDY (READY, contexte)", 0x2140157c),
        Signal("IGNITION_STATE (AOSP, contexte)", 0x11400409),
    )

    /** Une propriété déclarée par le véhicule et les zones où elle se lit. */
    private class Config(val id: Int, val zones: IntArray)

    private val historique = ArrayDeque<String>()
    private var dernierBalayage: String? = null

    /** Relevés des signaux nommés, puis le résultat du dernier balayage. */
    val reports: List<String>
        @Synchronized get() = historique.toList() + listOfNotNull(dernierBalayage)

    /** Fil dédié : un balayage fait un bon millier de lectures binder. */
    private val handler: Handler by lazy {
        Handler(HandlerThread("mg4-key-probe").also { it.start() }.looper)
    }

    // Fil de la sonde uniquement.
    private var precedent: Map<String, String>? = null
    private var precedentA = 0L
    private var numero = 0

    /** Relève les signaux nommés, lance un balayage en arrière-plan et renvoie le texte du relevé. */
    fun run(origin: String): String {
        val horloge = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        val cpm = MG4Hardware.carPropertyManager()
        val configs = configs(cpm)
        val lignes = mutableListOf("══ Sonde clé [$origin] · ${FirmwareInfo.getGeneration()} · $horloge ══")
        SIGNALS.forEach { s ->
            val config = configs?.firstOrNull { it.id == s.id }
            val zones = config?.zones ?: intArrayOf(AREA_GLOBAL, 0)
            val declaree = if (configs == null) "?" else if (config != null) "oui" else "non"
            lignes += "  ${s.name} 0x${Integer.toHexString(s.id)} : déclarée=$declaree · " +
                zones.joinToString(" · ") { z -> "zone 0x${Integer.toHexString(z)}=" + lire(cpm, s.id, z) }
        }
        lignes.forEach { AppLogger.i(TAG, it) }
        garde(lignes.joinToString("\n"))
        handler.post { balayer(origin) }
        return lignes.joinToString("\n")
    }

    // ── Balayage ─────────────────────────────────────────────────────────────

    private fun balayer(origin: String) {
        val cpm = MG4Hardware.carPropertyManager()
        val configs = configs(cpm)?.filter { PropertySweep.sweepable(it.id) }
        if (cpm == null || configs == null) {
            publier("── Sonde clé : balayage [$origin] impossible — liste des propriétés illisible ──")
            return
        }
        val debut = SystemClock.elapsedRealtime()
        val releve = HashMap<String, String>()
        var echecs = 0
        configs.forEach { c ->
            c.zones.forEach { zone ->
                // Zone 0 déclarée pour une propriété globale : c'est à la zone globale du HAL
                // qu'elle répond sur ces voitures, d'où l'essai des deux.
                val valeur = if (zone == 0) lireOuNull(cpm, c.id, AREA_GLOBAL) ?: lireOuNull(cpm, c.id, 0)
                             else lireOuNull(cpm, c.id, zone)
                if (valeur == null) echecs++ else releve[PropertySweep.key(c.id, zone)] = valeur
            }
        }
        val duree = (SystemClock.elapsedRealtime() - debut) / 1000.0
        numero++
        val lignes = mutableListOf(
            "── Sonde clé : balayage n° $numero [$origin] — ${releve.size} valeurs lues en " +
                "%.1f s, $echecs illisibles ──".format(Locale.US, duree))
        val avant = precedent
        if (avant == null) {
            lignes += "  premier balayage : rien à comparer. Éloigner la clé, puis rouvrir le Diagnostic."
        } else {
            val changements = PropertySweep.changes(avant, releve)
            val ecart = (SystemClock.elapsedRealtime() - precedentA) / 1000
            lignes += "  depuis le balayage précédent (il y a $ecart s) : ${changements.size} changement(s)"
            changements.take(MAX_CHANGEMENTS).forEach { lignes += "    $it" }
            if (changements.size > MAX_CHANGEMENTS) lignes += "    … et ${changements.size - MAX_CHANGEMENTS} autres"
        }
        precedent = releve
        precedentA = SystemClock.elapsedRealtime()
        lignes.forEach { AppLogger.i(TAG, it) }
        publier(lignes.joinToString("\n"))
    }

    @Synchronized
    private fun publier(texte: String) {
        dernierBalayage = texte
    }

    @Synchronized
    private fun garde(texte: String) {
        historique.addLast(texte)
        while (historique.size > MAX_RELEVES) historique.removeFirst()
    }

    // ── Lectures ─────────────────────────────────────────────────────────────

    /** Propriétés déclarées par le véhicule avec leurs zones, ou null si la liste est illisible. */
    private fun configs(cpm: Any?): List<Config>? = runCatching {
        (cpm!!.javaClass.getMethod("getPropertyList").invoke(cpm) as? List<*>)
            ?.filterNotNull()
            ?.mapNotNull { c ->
                val id = runCatching { c.javaClass.getMethod("getPropertyId").invoke(c) as? Int }.getOrNull()
                    ?: return@mapNotNull null
                val zones = runCatching { c.javaClass.getMethod("getAreaIds").invoke(c) as? IntArray }.getOrNull()
                Config(id, zones?.takeIf { it.isNotEmpty() } ?: intArrayOf(0))
            }
    }.getOrNull()

    /** Valeur lisible, ou le nom de l'exception : distinguer « absente » de « refusée » est une réponse. */
    private fun lire(cpm: Any?, id: Int, zone: Int): String {
        cpm ?: return "cpm absent"
        return try {
            valeur(cpm, id, zone)
        } catch (e: Exception) {
            val cause = e.cause ?: e
            cause.javaClass.simpleName + (cause.message?.let { " ($it)" } ?: "")
        }
    }

    private fun lireOuNull(cpm: Any, id: Int, zone: Int): String? =
        runCatching { valeur(cpm, id, zone) }.getOrNull()

    private fun valeur(cpm: Any, id: Int, zone: Int): String = when (id and 0x00ff0000) {
        0x00200000 -> typee(cpm, "getBooleanProperty", id, zone)
        0x00600000 -> typee(cpm, "getFloatProperty", id, zone)
        0x00700000 -> {
            // Bloc d'octets (codes d'équipement) : pas d'accesseur typé, on passe par getProperty.
            val v = cpm.javaClass
                .getMethod("getProperty", Class::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(cpm, ByteArray::class.java, id, zone)
            val octets = v?.javaClass?.getMethod("getValue")?.invoke(v) as? ByteArray
            octets?.let { PropertySweep.hex(it) } ?: "null"
        }
        else -> typee(cpm, "getIntProperty", id, zone)
    }

    private fun typee(cpm: Any, accesseur: String, id: Int, zone: Int): String =
        cpm.javaClass.getMethod(accesseur, Int::class.java, Int::class.java).invoke(cpm, id, zone)?.toString() ?: "null"
}
