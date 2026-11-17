package com.mg4.control.model

/**
 * Pilotage de l'enregistrement d'une appli caméra 360° pendant la surveillance (issue #180,
 * contrat publié par jamakr4/MG4-360-Camera-App, issue #96 de son dépôt, 2026-11-08).
 *
 * Partie sans Android : reconnaître une appli pilotable parmi les récepteurs déclarés pour
 * MG4Control, compter la durée d'enregistrement affichée, estimer la durée qui tient sur la clé.
 */
object DashcamContract {

    /** Fin des actions du contrat ; le début est le préfixe propre à l'appli (`<paquet>.action.`). */
    const val START = "START_CONTINUOUS"
    const val STOP = "STOP_CONTINUOUS"
    const val QUERY = "QUERY_STATE"
    const val TEST_USB = "TEST_USB_WRITE"
    const val STATE = "RECORDING_STATE"
    const val USB_RESULT = "USB_WRITE_TEST_RESULT"

    /** Ce qu'il faut pour piloter : START et STOP ; la question d'état et le test de clé sont un plus. */
    data class Commands(
        val packageName: String,
        /** Préfixe des actions, `com.drivehub.kamera.action.` par exemple. */
        val prefix: String,
        val startClass: String,
        val stopClass: String,
        val queryClass: String?,
        val testUsbClass: String?,
    ) {
        fun action(nom: String) = prefix + nom
    }

    /** Un récepteur d'une appli : sa classe et les actions de son filtre. */
    data class Receiver(val packageName: String, val className: String, val actions: List<String>)

    /**
     * L'appli pilotable parmi [receivers] (ceux qui déclarent `com.mg4.control.action.SHORTCUT`) :
     * on cherche une action finissant par `.action.START_CONTINUOUS`, puis STOP sous le même
     * préfixe dans la même appli — ainsi un fork qui garde le code mais change de paquet marche
     * aussi. Plusieurs applis pilotables : la première par nom de paquet, pour rester stable.
     */
    fun find(receivers: List<Receiver>): Commands? {
        val parAppli = receivers.groupBy { it.packageName }
        return parAppli.keys.sorted().firstNotNullOfOrNull { pkg ->
            val recepteurs = parAppli.getValue(pkg)
            fun classeDe(action: String) = recepteurs.firstOrNull { action in it.actions }?.className
            recepteurs.asSequence().flatMap { it.actions.asSequence() }
                .filter { it.endsWith(".action.$START") }
                .firstNotNullOfOrNull { a ->
                    val prefixe = a.removeSuffix(START)
                    val start = classeDe(a) ?: return@firstNotNullOfOrNull null
                    val stop = classeDe(prefixe + STOP) ?: return@firstNotNullOfOrNull null
                    Commands(pkg, prefixe, start, stop, classeDe(prefixe + QUERY), classeDe(prefixe + TEST_USB))
                }
        }
    }

    /**
     * Durée demandée à l'appli : celle de la surveillance plus le décompte avant verrouillage (le
     * START part dès « Démarrer »), arrondie à la minute au-dessus ; l'appli plafonne à 120.
     */
    fun maxMinutes(surveillanceMin: Int, lockSeconds: Int): Int =
        (surveillanceMin + (lockSeconds.coerceAtLeast(0) + 59) / 60).coerceIn(1, MAX_MINUTES)

    const val MAX_MINUTES = 120

    /**
     * Débit retenu pour l'estimation : l'appli annonce ~9 Gio pour 2 h (seuil « ok »), soit
     * 4,5 Gio de l'heure. Une estimation, pas une promesse — on l'affiche « environ ».
     */
    private const val OCTETS_PAR_MINUTE = 9.0 * 1024 * 1024 * 1024 / 120

    /** Minutes d'enregistrement qui tiennent dans [freeBytes], ou `null` si inconnu (-1). */
    fun minutesFor(freeBytes: Long): Int? =
        if (freeBytes < 0) null else (freeBytes / OCTETS_PAR_MINUTE).toInt()
}

/** États annoncés par l'appli dans `RECORDING_STATE`. */
enum class DashcamRecState {
    STARTING, STARTED, PAUSED, STOPPED, ERROR, UNKNOWN;

    companion object {
        fun parse(s: String?): DashcamRecState = when (s?.trim()?.lowercase()) {
            "starting" -> STARTING
            "started" -> STARTED
            "paused" -> PAUSED
            "stopped" -> STOPPED
            "error" -> ERROR
            else -> UNKNOWN
        }
    }
}

/**
 * Durée affichée à côté de « REC » : le temps réellement filmé — elle part du premier
 * « started », se fige pendant « paused » (caméra d'origine) et repart ensuite. Les horodatages
 * sont en `elapsedRealtime` (l'horloge murale de la voiture peut sauter au réveil).
 */
class RecClock {
    private var cumulMs = 0L
    private var depuis: Long? = null
    var state: DashcamRecState = DashcamRecState.UNKNOWN
        private set

    fun onState(s: DashcamRecState, nowMs: Long) {
        if (s == DashcamRecState.UNKNOWN) return
        val enCours = depuis
        if (s == DashcamRecState.STARTED) {
            if (enCours == null) depuis = nowMs
        } else if (enCours != null) {
            cumulMs += (nowMs - enCours).coerceAtLeast(0)
            depuis = null
        }
        state = s
    }

    /** Durée filmée jusqu'à [nowMs]. */
    fun elapsedMs(nowMs: Long): Long = cumulMs + (depuis?.let { (nowMs - it).coerceAtLeast(0) } ?: 0)

    /** « REC » s'affiche seulement si l'appli filme ou est en pause après avoir filmé. */
    val visible: Boolean
        get() = state == DashcamRecState.STARTED || (state == DashcamRecState.PAUSED && cumulMs > 0)

    fun reset() {
        cumulMs = 0
        depuis = null
        state = DashcamRecState.UNKNOWN
    }

    companion object {
        /** « 00:12:34 ». */
        fun format(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return "%02d:%02d:%02d".format(java.util.Locale.US, s / 3600, s / 60 % 60, s % 60)
        }
    }
}
