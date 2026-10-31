package com.mg4.control.model

import com.mg4.control.shortcut.AudioSource

/**
 * Sons d'automatisation (issue #161, 2026-10-23) — les règles, sans Android.
 *
 * Des sons importés par l'utilisateur (aucun n'est fourni), chacun joué à un ou plusieurs
 * [SoundEvent] — deux au départ, autant que d'événements depuis le 2026-10-29 ([maxSounds]).
 * Un événement n'appartient qu'à un son : sinon deux sons voudraient partir ensemble
 * et le garde-fou en jetterait toujours un. Le garde-fou ([SoundGuard]) ignore tout déclenchement
 * pendant qu'un son joue, sur toute sa durée : démarrage de l'appli puis READY une seconde plus
 * tard ne donnent qu'un son.
 */
enum class SoundEvent(val key: String) {
    /** Démarrage de MG4Control (celui du service, au démarrage de l'écran). */
    APP_START("app_start"),
    READY_ON("ready_on"),
    READY_OFF("ready_off"),
    PARK("park"),
    DRIVE("drive"),
    /** Une porte avant s'ouvre : seulement là où la voiture la signale (SWI132, SWI133). */
    FRONT_DOOR("front_door"),
    /** Un profil vient d'être appliqué (Bluetooth, sélecteur, automatisations…). */
    PROFILE("profile");

    companion object {
        fun fromKey(key: String?): SoundEvent? = entries.firstOrNull { it.key == key }
    }
}

/** Un emplacement de son : le fichier copié dans l'appli, sa durée, le volume voulu, ses événements. */
data class SoundSlot(
    val path: String? = null,
    /** Nom du fichier importé, pour l'affichage. */
    val title: String? = null,
    val durationMs: Long = 0L,
    /** Volume média de la voiture pendant le son, dans son échelle (0 → max) ; `null` = la moitié. */
    val volume: Int? = null,
    val events: Set<SoundEvent> = emptySet(),
) {
    val hasFile: Boolean get() = path != null
}

object SoundAutomationRules {

    /**
     * Autant de sons que d'événements possibles sur ce firmware : un événement n'appartenant qu'à
     * un son, un son de plus ne pourrait plus rien jouer. La porte n'est lisible que sur SWI132/133.
     */
    fun maxSounds(doorReadable: Boolean): Int =
        SoundEvent.entries.count { it != SoundEvent.FRONT_DOOR || doorReadable }

    /** Plafond absolu, porte comprise : borne de ce qui est enregistré. */
    val MAX_SOUNDS: Int get() = SoundEvent.entries.size
    /** Au-delà, l'import est refusé : une musique bloquerait les autres événements pendant des minutes. */
    const val MAX_DURATION_MS = 30_000L

    /** Rapport engagé, valeurs SAIC : P = 1 (tous les firmwares étudiés), D = 4 (relevé SWI133). */
    const val GEAR_PARK = 1
    const val GEAR_DRIVE = 4

    /** Son qui porte [event] (et a un fichier), ou `null`. */
    fun slotFor(event: SoundEvent, slots: List<SoundSlot>): Int? =
        slots.indexOfFirst { it.hasFile && event in it.events }.takeIf { it >= 0 }

    /** Son qui a déjà choisi [event], fichier ou non : l'autre ne peut plus le prendre. */
    fun owner(event: SoundEvent, slots: List<SoundSlot>): Int? =
        slots.indexOfFirst { event in it.events }.takeIf { it >= 0 }

    /** Coche ou décoche [event] pour le son [index] ; sans effet s'il appartient à l'autre son. */
    fun toggle(slots: List<SoundSlot>, index: Int, event: SoundEvent): List<SoundSlot> {
        val proprio = owner(event, slots)
        if (proprio != null && proprio != index) return slots
        return slots.mapIndexed { i, s ->
            if (i != index) s else s.copy(events = if (event in s.events) s.events - event else s.events + event)
        }
    }

    /** Aucun profil choisi = tous ; sinon le profil actif doit être de la liste (inconnu = non). */
    fun profileAllowed(allowed: Set<String>, profileId: String?): Boolean =
        allowed.isEmpty() || profileId in allowed

    /**
     * Événement du rapport passé de [previous] à [current]. Le premier relevé (`previous` nul)
     * n'est pas un changement, ni une lecture ratée.
     */
    fun gearEvent(previous: Int?, current: Int?): SoundEvent? {
        if (previous == null || current == null || previous == current) return null
        return when (current) {
            GEAR_PARK -> SoundEvent.PARK
            GEAR_DRIVE -> SoundEvent.DRIVE
            else -> null
        }
    }

    /** Volume à régler pour un son : le sien, ramené dans l'échelle de la voiture, ou la moitié. */
    fun volumeFor(slot: SoundSlot, max: Int): Int = (slot.volume ?: (max / 2)).coerceIn(0, max.coerceAtLeast(0))

    /**
     * Revenir au volume d'avant le son ? Seulement s'il vaut encore celui qu'on a mis : le
     * conducteur (ou la baisse à l'ouverture de la porte) a la main s'il y a touché entre-temps.
     */
    fun shouldRestore(current: Int, written: Int): Boolean = current == written

    /**
     * Source à relancer après un son (issue #190) : le son passe par le canal média, et sur l'ancien
     * SDK le service média SAIC le prend parfois pour l'appli « En ligne » — la voiture y reste après
     * le son. On relance la source d'avant seulement dans ce cas précis : elle était connue et
     * n'était pas déjà « En ligne », et la voiture est maintenant sur « En ligne ». Un autre
     * changement (le conducteur a choisi une source pendant le son) est laissé tel quel.
     */
    fun sourceToRestore(before: AudioSource?, after: AudioSource?): AudioSource? =
        before?.takeIf { it != AudioSource.ONLINE && after == AudioSource.ONLINE }

    fun eventsCsv(events: Set<SoundEvent>): String = events.sortedBy { it.ordinal }.joinToString(",") { it.key }

    fun parseEvents(csv: String?): Set<SoundEvent> =
        csv.orEmpty().split(',').mapNotNull { SoundEvent.fromKey(it.trim()) }.toSet()
}

/**
 * Garde-fou anti-doublon : un son « occupe » la sortie pendant sa durée ; tout déclenchement
 * pendant ce temps est ignoré. Temps monotone (ms).
 */
class SoundGuard {
    private var busyUntil = 0L

    fun remainingMs(nowMs: Long): Long = (busyUntil - nowMs).coerceAtLeast(0L)

    /** Prend la sortie pour [durationMs] si elle est libre ; faux si un son joue encore. */
    fun claim(nowMs: Long, durationMs: Long): Boolean {
        if (nowMs < busyUntil) return false
        busyUntil = nowMs + durationMs.coerceAtLeast(0L)
        return true
    }

    /** Le son s'est arrêté plus tôt (fin réelle, erreur, « Arrêter ») : la sortie est libre. */
    fun release() {
        busyUntil = 0L
    }
}
