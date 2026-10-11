package com.mg4.control.hardware

import com.mg4.control.debug.AppLogger
import com.mg4.control.model.LeaveLock
import com.mg4.control.model.LeaveLock.Reading
import com.mg4.control.util.FirmwareInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sonde du réglage d'origine « Lock when leaving » (verrouiller en s'éloignant avec la clé).
 * PROVISOIRE — à retirer une fois l'essai conclu.
 *
 * Les écrans de réglages SWI133/68/165 portent cet interrupteur et le masquent quand la voiture
 * répond 3 (« indisponible ») ; c'est le cas sur la voiture d'essai (SWI133, 2026-10-11). Le
 * service d'origine ne filtrant rien, ce 3 vient de la voiture. Reste à savoir si elle accepte
 * quand même la consigne : c'est la SEULE écriture de cette sonde ([set]), celle de
 * l'interrupteur d'origine, par sa voie d'origine. Tout le reste est lu, rien d'autre n'est écrit.
 *
 * Trois usages :
 *  1. **L'écran d'essai** (bouton caché sous « Diagnostic ») : [poll] relit chaque seconde le
 *     réglage et de quoi suivre un essai clé en main — verrouillage central, présence de la clé,
 *     portes — et note chaque changement au [journal]. [set] puis [conclude] pour la consigne.
 *  2. **Le Diagnostic** : [run] prend un relevé, pour les firmwares que nous n'avons pas.
 *  3. **Le rapport** : [reports] réunit les relevés et le journal de l'essai.
 */
object LockProbe {

    const val TAG = "MG4_LOCK"

    /** Nom du réglage dans [poll] : l'écran d'essai l'affiche en grand. */
    const val LEAVE = "Lock when leaving"

    private const val AREA_GLOBAL = 0x1000000
    private const val MAX_RELEVES = 6
    private const val MAX_JOURNAL = 200

    /** État du chargeur de téléphone sans fil : 8 quand la clé n'est plus détectée (SWI133). */
    private const val PROP_CHARGER = 0x2140f445
    private const val PROP_DOORS = 0x2640c623
    private const val PROP_READY = 0x2140157c

    private class Brute(val name: String, val id: Int)

    /**
     * Lues telles quelles, JAMAIS écrites. Les deux premières sont le réglage et son pendant, à
     * recouper avec la voie d'origine. Les suivantes sont déclarées par le firmware (SWI133, 68,
     * 69) mais aucune appli d'origine ne s'en sert : leur sens n'est écrit nulle part, seule
     * leur valeur — et le fait qu'elle bouge ou non pendant l'essai — peut renseigner.
     */
    private val BRUTES = listOf(
        Brute("COMFORT_LOCK brut", 0x2140d710),
        Brute("COMFORT_UNLOCK brut", 0x2140d711),
        Brute("COMFORT_LOCK_OPT", 0x2140c633),
        Brute("COMFORT_UNLOCK_OPT", 0x2140c634),
        Brute("AUTOMATIC_LOCK_OPT", 0x2140c631),
        Brute("AUTO_LOCKING_OPT", 0x2140c62f),
        Brute("PASSIVE_SINGLE_OPT", 0x2140c62e),
        Brute("SINGLE_POINT_ENT_OPT", 0x2140c630),
        Brute("PE_START_OPT", 0x2140c632),
        Brute("POWER_MODE_AUTO_OFF", 0x2140c636),
    )

    /** Un relevé : le réglage par sa voie d'origine (null = illisible), puis tout ce qui est suivi. */
    class Releve(val leave: Int?, val lectures: List<Reading>)

    private val historique = ArrayDeque<String>()
    private val journal = ArrayDeque<String>()

    /** Relevés pris à l'ouverture du Diagnostic, puis le journal de l'écran d'essai. */
    val reports: List<String>
        get() {
            val essai = journal()
            return synchronized(historique) { historique.toList() } +
                (if (essai.isEmpty()) emptyList()
                 else listOf("── Sonde verrouillage : journal de l'essai ──\n" + essai.joinToString("\n")))
        }

    // Suivi : sous [verrou], le Diagnostic et l'écran d'essai pouvant lire en même temps.
    private val verrou = Any()
    private var dernieres: List<Reading>? = null
    private var zonesDeclarees: Map<Int, IntArray>? = null

    // ── Diagnostic ───────────────────────────────────────────────────────────

    /** Prend un relevé, le garde pour le rapport et en renvoie le texte. Hors du fil principal. */
    fun run(origin: String): String {
        val horloge = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        val lignes = listOf("══ Sonde verrouillage [$origin] · ${FirmwareInfo.getGeneration()} · $horloge ══") +
            poll().lectures.map { "  ${it.name} : ${it.value}" }
        lignes.forEach { AppLogger.i(TAG, it) }
        synchronized(historique) {
            historique.addLast(lignes.joinToString("\n"))
            while (historique.size > MAX_RELEVES) historique.removeFirst()
        }
        return lignes.joinToString("\n")
    }

    // ── Écran d'essai ────────────────────────────────────────────────────────

    /**
     * Relit tout ce qui est suivi et note au journal ce qui a changé depuis la lecture
     * précédente ; [noter] y ajoute le relevé complet. Une vingtaine de lectures binder → hors du
     * fil principal.
     */
    fun poll(noter: Boolean = false): Releve = synchronized(verrou) {
        val cpm = MG4Hardware.carPropertyManager()
        // Les zones ne changent pas : la liste des propriétés (un bon millier) n'est lue qu'une fois.
        val zones = zonesDeclarees ?: declarees(cpm)?.also { zonesDeclarees = it }
        val leave = LeaveLock.raw(MG4Hardware.readLeaveAutoLockRaw())
        val lectures = mutableListOf(
            Reading(LEAVE, LeaveLock.describe(leave)),
            Reading("Unlock when approaching", LeaveLock.describe(LeaveLock.raw(MG4Hardware.readApproachUnlockRaw()))),
            Reading("Verrouillage central", LeaveLock.lockState(LeaveLock.raw(MG4Hardware.readCentralLockRaw()))),
            Reading("Clé (chargeur sans fil)", LeaveLock.keyState(entier(cpm, zones, PROP_CHARGER))),
            Reading("Portes (1 = ouverte)", brute(cpm, zones, PROP_DOORS)),
            Reading("READY", brute(cpm, zones, PROP_READY)),
        )
        BRUTES.forEach { lectures += Reading(it.name, brute(cpm, zones, it.id)) }
        dernieres?.let { avant -> LeaveLock.changes(avant, lectures).forEach { note("changement · $it") } }
        if (noter) note("lecture · " + lectures.joinToString(" · ") { "${it.name} = ${it.value}" })
        dernieres = lectures
        Releve(leave, lectures)
    }

    /**
     * Envoie la consigne de l'interrupteur d'origine et la note au journal. Faux si elle n'est
     * pas partie : firmware sans voie d'origine, service non lié ou voiture en mouvement.
     */
    fun set(on: Boolean): Boolean {
        val avant = LeaveLock.raw(MG4Hardware.readLeaveAutoLockRaw())
        val envoyee = MG4Hardware.setLeaveAutoLock(on)
        note("consigne · ${if (on) "ACTIVER (1)" else "DÉSACTIVER (0)"} · avant : ${LeaveLock.describe(avant)} · " +
            if (envoyee) "envoyée" else "NON envoyée")
        return envoyee
    }

    /** Relit le réglage après une consigne et note au journal ce qu'il faut en conclure. */
    fun conclude(on: Boolean): LeaveLock.Verdict {
        val apres = LeaveLock.raw(MG4Hardware.readLeaveAutoLockRaw())
        return LeaveLock.verdict(on, apres).also { note("verdict · ${it.text} (lu : ${LeaveLock.describe(apres)})") }
    }

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

    // ── Lectures brutes ──────────────────────────────────────────────────────

    /** Zones déclarées par le véhicule pour les propriétés suivies, ou null si la liste est illisible. */
    private fun declarees(cpm: Any?): Map<Int, IntArray>? = runCatching {
        val suivies = (BRUTES.map { it.id } + listOf(PROP_CHARGER, PROP_DOORS, PROP_READY)).toSet()
        (cpm!!.javaClass.getMethod("getPropertyList").invoke(cpm) as List<*>)
            .filterNotNull()
            .mapNotNull { c ->
                val id = runCatching { c.javaClass.getMethod("getPropertyId").invoke(c) as? Int }.getOrNull()
                    ?.takeIf { it in suivies } ?: return@mapNotNull null
                val zones = runCatching { c.javaClass.getMethod("getAreaIds").invoke(c) as? IntArray }.getOrNull()
                id to (zones?.takeIf { it.isNotEmpty() } ?: intArrayOf(0))
            }
            .toMap()
    }.getOrNull()

    /**
     * Valeur d'une propriété, zone par zone si elle en a plusieurs ; à défaut, ce qui l'empêche
     * d'être lue — « non déclarée » et « refusée » sont déjà des réponses.
     */
    private fun brute(cpm: Any?, zones: Map<Int, IntArray>?, id: Int): String {
        cpm ?: return "cpm absent"
        val z = zones?.get(id)
        return when {
            zones != null && z == null -> "non déclarée · " + lire(cpm, id, AREA_GLOBAL)
            z == null                  -> lire(cpm, id, AREA_GLOBAL)
            z.size == 1                -> lire(cpm, id, z[0])
            else -> z.joinToString("  ") { zone -> porte(zone) + "=" + lire(cpm, id, zone) }
        }
    }

    /** Valeur entière d'une propriété globale, ou null si elle ne se lit pas. */
    private fun entier(cpm: Any?, zones: Map<Int, IntArray>?, id: Int): Int? {
        cpm ?: return null
        val zone = zones?.get(id)?.firstOrNull() ?: 0
        return runCatching { valeur(cpm, id, zone) }.recoverCatching { valeur(cpm, id, AREA_GLOBAL) }
            .getOrNull()?.toIntOrNull()
    }

    /**
     * Valeur lisible, ou le nom de l'exception. Zone 0 déclarée pour une propriété globale :
     * selon la voie, c'est à la zone globale du HAL qu'elle répond, d'où le second essai.
     */
    private fun lire(cpm: Any, id: Int, zone: Int): String = try {
        valeur(cpm, id, zone)
    } catch (e: Exception) {
        (if (zone == 0) runCatching { valeur(cpm, id, AREA_GLOBAL) }.getOrNull() else null) ?: run {
            val cause = e.cause ?: e
            cause.javaClass.simpleName + (cause.message?.let { " ($it)" } ?: "")
        }
    }

    private fun valeur(cpm: Any, id: Int, zone: Int): String =
        cpm.javaClass.getMethod("getIntProperty", Int::class.java, Int::class.java)
            .invoke(cpm, id, zone)?.toString() ?: "null"

    /** Nom d'une zone de porte (VehicleAreaDoor), ou la zone en hexadécimal si elle n'en est pas une. */
    private fun porte(zone: Int): String = when (zone) {
        0x1  -> "AVG"
        0x4  -> "AVD"
        0x10 -> "ARG"
        0x40 -> "ARD"
        else -> "0x" + Integer.toHexString(zone)
    }
}
