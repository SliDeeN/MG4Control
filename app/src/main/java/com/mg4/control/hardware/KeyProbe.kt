package com.mg4.control.hardware

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.PropertySweep
import com.mg4.control.model.WirelessCharger
import com.mg4.control.util.FirmwareInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sonde clé, en **lecture seule** : la voiture dit-elle à l'écran de bord si la clé sans fil est
 * là ? Elle n'écrit rien sur le véhicule. PROVISOIRE — à retirer une fois l'essai conclu.
 *
 * La recherche dans les firmwares (2026-10-11, cinq tables de propriétés) n'a trouvé AUCUN signal
 * de présence ni de force de signal. Premier relevé sur SWI133 : les signaux nommés de [SIGNALS]
 * ne bougent pas, mais le balayage à l'aveugle a vu UNE valeur changer quand la clé s'est
 * éloignée — l'état du chargeur de téléphone sans fil ([WirelessCharger]), passé de 1 à 8. Reste
 * à savoir si c'est la clé, la porte ouverte pour la sortir, ou un simple délai.
 *
 * Trois usages :
 *  1. **L'écran d'essai** (bouton caché sous « Diagnostic ») : [poll] relit chaque seconde le
 *     chargeur et de quoi expliquer ses changements (portes, READY, ceinture…), et note chaque
 *     changement dans le [journal]. [sweep] refait un balayage complet à la demande.
 *  2. **Le Diagnostic** : [run] relit les signaux nommés et lance un balayage en arrière-plan.
 *  3. **Le rapport** : [reports] réunit le tout, journal de l'essai compris.
 */
object KeyProbe {

    const val TAG = "MG4_KEY"

    private const val AREA_GLOBAL = 0x1000000
    private const val MAX_RELEVES = 6
    /** Au-delà, le balayage n'a pas été fait à l'arrêt : inutile de noyer le rapport. */
    private const val MAX_CHANGEMENTS = 40
    private const val MAX_JOURNAL = 200

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
        Signal("PHONE_WIRELESS_CHAEGER_WORKING_STATE (chargeur sans fil)", WirelessCharger.PROPERTY),
        Signal("SENSOR_EPTRDY (READY, contexte)", 0x2140157c),
        Signal("IGNITION_STATE (AOSP, contexte)", 0x11400409),
    )

    /** Nom du chargeur dans [poll] : l'écran d'essai l'affiche en grand. */
    const val CHARGEUR = "Chargeur sans fil"

    /**
     * Relus chaque seconde par l'écran d'essai : le chargeur, puis ce qui peut expliquer qu'il
     * bouge sans que la clé y soit pour rien — une porte, la sortie de READY, le conducteur parti.
     */
    private val SUIVIS = listOf(
        Signal(CHARGEUR, WirelessCharger.PROPERTY),
        Signal("Portes (1 = ouverte)", 0x2640c623),
        Signal("READY", 0x2140157c),
        Signal("Ceinture conducteur", 0x21401550),
        Signal("Calculateur PEPS joignable", 0x21401582),
        Signal("Prise de recharge", 0x2140f408),
    )

    /** Une valeur suivie, telle que l'écran d'essai l'affiche. */
    class Lecture(val nom: String, val valeur: String)

    /** Une propriété déclarée par le véhicule et les zones où elle se lit. */
    private class Config(val id: Int, val zones: IntArray)

    private val historique = ArrayDeque<String>()
    private var dernierBalayage: String? = null
    private val journal = ArrayDeque<String>()

    /** Relevés des signaux nommés, dernier balayage, puis le journal de l'écran d'essai. */
    val reports: List<String>
        get() {
            val essai = journal()
            return synchronized(this) { historique.toList() + listOfNotNull(dernierBalayage) } +
                (if (essai.isEmpty()) emptyList()
                 else listOf("── Sonde clé : journal de l'essai ──\n" + essai.joinToString("\n")))
        }

    /** Fil dédié : un balayage fait plusieurs centaines de lectures binder. */
    private val handler: Handler by lazy {
        Handler(HandlerThread("mg4-key-probe").also { it.start() }.looper)
    }

    // Balayage : sous [verrouBalayage], le Diagnostic et l'écran d'essai pouvant en lancer un chacun.
    private val verrouBalayage = Any()
    private var precedent: Map<String, String>? = null
    private var precedentA = 0L
    private var numero = 0

    // Suivi en direct : sous [verrouSuivi].
    private val verrouSuivi = Any()
    private var dernieres: Map<String, String>? = null
    private var zonesSuivies: Map<Int, IntArray>? = null

    // ── Diagnostic ───────────────────────────────────────────────────────────

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
        synchronized(this) {
            historique.addLast(lignes.joinToString("\n"))
            while (historique.size > MAX_RELEVES) historique.removeFirst()
        }
        handler.post { balayer(origin) }
        return lignes.joinToString("\n")
    }

    // ── Écran d'essai ────────────────────────────────────────────────────────

    /**
     * Relit les signaux suivis et note au journal ceux qui ont changé depuis la lecture
     * précédente ; [noter] y ajoute le relevé complet. Une dizaine de lectures binder → hors du
     * fil principal.
     */
    fun poll(noter: Boolean = false): List<Lecture> = synchronized(verrouSuivi) {
        val cpm = MG4Hardware.carPropertyManager()
        // Les zones ne changent pas : la liste des propriétés (un bon millier) n'est lue qu'une fois.
        val zones = zonesSuivies ?: configs(cpm)?.let { cs ->
            SUIVIS.associate { s -> s.id to (cs.firstOrNull { it.id == s.id }?.zones ?: intArrayOf(0)) }
        }?.also { zonesSuivies = it }
        val lectures = SUIVIS.map { s ->
            val z = zones?.get(s.id) ?: intArrayOf(0)
            Lecture(s.name,
                if (z.size == 1) lire(cpm, s.id, z[0])
                else z.joinToString("  ") { zone -> WirelessCharger.doorLabel(zone) + "=" + lire(cpm, s.id, zone) })
        }
        val carte = lectures.associate { it.nom to it.valeur }
        dernieres?.let { avant -> PropertySweep.changes(avant, carte).forEach { note("changement · $it") } }
        if (noter) note("lecture · " + lectures.joinToString(" · ") { "${it.nom} = ${it.valeur}" })
        dernieres = carte
        lectures
    }

    /** Balayage complet tout de suite, noté au journal. Environ une seconde → hors du fil principal. */
    fun sweep(origin: String): String = balayer(origin).also { note(it) }

    /** Journal de l'essai, du plus ancien au plus récent. */
    fun journal(): List<String> = synchronized(journal) { journal.toList() }

    fun clearJournal() = synchronized(journal) { journal.clear() }

    private fun note(texte: String) {
        val heure = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        AppLogger.i(TAG, "essai · $texte")
        synchronized(journal) {
            journal.addLast("$heure  $texte")
            while (journal.size > MAX_JOURNAL) journal.removeFirst()
        }
    }

    // ── Balayage ─────────────────────────────────────────────────────────────

    private fun balayer(origin: String): String = synchronized(verrouBalayage) {
        val cpm = MG4Hardware.carPropertyManager()
        val configs = configs(cpm)?.filter { PropertySweep.sweepable(it.id) }
        if (cpm == null || configs == null) {
            return@synchronized publier("── Sonde clé : balayage [$origin] impossible — liste des propriétés illisible ──")
        }
        val debut = SystemClock.elapsedRealtime()
        val releve = HashMap<String, String>()
        var echecs = 0
        configs.forEach { c ->
            c.zones.forEach { zone ->
                // Zone 0 déclarée pour une propriété globale : selon la voie, c'est à la zone
                // globale du HAL qu'elle répond, d'où l'essai des deux.
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
            lignes += "  premier balayage : rien à comparer."
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

    private fun publier(texte: String): String {
        synchronized(this) { dernierBalayage = texte }
        return texte
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
