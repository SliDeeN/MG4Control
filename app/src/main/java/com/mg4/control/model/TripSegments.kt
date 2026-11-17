package com.mg4.control.model

import kotlin.math.roundToInt

/**
 * Trajet découpé par profil (2026-11-05, proposition 3 retenue) : un profil peut changer en cours
 * de route — beaucoup s'en servent comme d'un style de conduite (Autoroute, Confort, Sport) — et
 * chaque morceau garde sa distance et son énergie. Le trajet reste UN trajet à l'écran : « Mixte »
 * s'il a plusieurs profils, avec sa répartition dans le détail ; le filtre par profil n'en compte
 * que la part du profil choisi ([Trip.portion]).
 *
 * Un morceau naît à chaque changement de profil ([SegmentStart], relevé dans [PendingTrip]).
 * À la fin du trajet, [build] en fait des [TripSegment] : les morceaux de moins de [MIN_KM]
 * (profil choisi à l'arrêt avant de partir, aller-retour rapide) sont fondus dans leur voisin, et
 * deux morceaux voisins d'un même profil n'en font qu'un.
 */
data class TripSegment(
    /** Identifiant du profil ; null = inconnu (relevé sans profil actif). */
    val profileId: String?,
    /** Nom du profil au moment du trajet : il reste lisible si le profil est supprimé depuis. */
    val profileName: String?,
    val durationMs: Long,
    val distanceKm: Float,
    /** Énergie brute, comme [Trip.energyKwh] ; la récupération à part. */
    val energyKwh: Float,
    val regenKwh: Float?,
) {
    val netEnergyKwh: Float get() = (energyKwh - (regenKwh ?: 0f)).coerceAtLeast(0f).roundTenth()
}

/** Début d'un morceau, relevé au changement de profil : les compteurs du trajet à cet instant. */
data class SegmentStart(
    val profileId: String?,
    val profileName: String?,
    val ms: Long,
    /** Distance intégrée depuis le début du trajet. */
    val km: Float,
    val odometerKm: Int?,
    val energyKwh: Float?,
    val regenKwh: Float?,
    val integratedEnergyKwh: Float?,
    val integratedRegenKwh: Float?,
)

object TripSegments {

    /** En dessous, un morceau est fondu dans son voisin. */
    const val MIN_KM = 0.5f

    /**
     * Morceaux d'un trajet terminé. [fin] : les compteurs au dernier relevé (même forme qu'un
     * début de morceau). [integrated] : distance intégrée disponible (sinon l'odomètre) ;
     * [parIntegration] : énergie intégrée plutôt que compteurs du véhicule — les mêmes choix
     * que pour le trajet entier. Null sans aucun début relevé (trajet d'avant la fonction).
     */
    fun build(debuts: List<SegmentStart>?, fin: SegmentStart, integrated: Boolean, parIntegration: Boolean): List<TripSegment>? {
        if (debuts.isNullOrEmpty()) return null
        val bruts = debuts.mapIndexed { i, d ->
            val f = debuts.getOrNull(i + 1) ?: fin
            val km = if (integrated) (f.km - d.km).coerceAtLeast(0f)
                else ((f.odometerKm ?: 0) - (d.odometerKm ?: 0)).coerceAtLeast(0).toFloat()
            val energie = if (parIntegration) ecart(d.integratedEnergyKwh, f.integratedEnergyKwh)
                else ecart(d.energyKwh, f.energyKwh)
            val regen = if (parIntegration) ecartOuNull(d.integratedRegenKwh, f.integratedRegenKwh)
                else ecartOuNull(d.regenKwh, f.regenKwh)
            TripSegment(d.profileId, d.profileName, (f.ms - d.ms).coerceAtLeast(0L), km, energie, regen)
        }
        return fusionner(bruts).map {
            it.copy(distanceKm = it.distanceKm.roundTenth(), energyKwh = it.energyKwh.roundTenth(),
                regenKwh = it.regenKwh?.roundTenth())
        }
    }

    /** Petits morceaux fondus dans le précédent (le suivant pour le premier), puis voisins identiques réunis. */
    fun fusionner(morceaux: List<TripSegment>): List<TripSegment> {
        var liste = reunir(morceaux)
        while (liste.size > 1) {
            val i = liste.indexOfFirst { it.distanceKm < MIN_KM }
            if (i < 0) break
            val cible = if (i == 0) 1 else i - 1
            val fondu = additionner(liste[cible], liste[i])
            liste = liste.toMutableList().apply { set(cible, fondu); removeAt(i) }
            liste = reunir(liste)
        }
        return liste
    }

    private fun reunir(morceaux: List<TripSegment>): List<TripSegment> {
        val res = mutableListOf<TripSegment>()
        morceaux.forEach { m ->
            val dernier = res.lastOrNull()
            if (dernier != null && dernier.profileId == m.profileId) res[res.lastIndex] = additionner(dernier, m)
            else res += m
        }
        return res
    }

    /** [b] ajouté à [a] ; le profil reste celui de [a]. */
    private fun additionner(a: TripSegment, b: TripSegment) = a.copy(
        durationMs = a.durationMs + b.durationMs,
        distanceKm = a.distanceKm + b.distanceKm,
        energyKwh = a.energyKwh + b.energyKwh,
        regenKwh = if (a.regenKwh == null && b.regenKwh == null) null else (a.regenKwh ?: 0f) + (b.regenKwh ?: 0f),
    )

    /** Compteur cumulé depuis le début du trajet : la différence, jamais négative. */
    private fun ecart(debut: Float?, fin: Float?): Float = ecartOuNull(debut, fin) ?: 0f

    private fun ecartOuNull(debut: Float?, fin: Float?): Float? {
        fin ?: return null
        return (fin - (debut ?: 0f)).coerceAtLeast(0f)
    }

    /** Profils du trajet, dans l'ordre, sans doublon. */
    fun profils(t: Trip): List<String?> = t.segments.orEmpty().map { it.profileId }.distinct()
}

/** Plusieurs profils pendant ce trajet. */
val Trip.isMixed: Boolean get() = TripSegments.profils(this).size > 1

/**
 * La part du trajet roulée sous [profileId] : même trajet, ramené à ses morceaux de ce profil —
 * distance, énergie, récupération et durée (donc consommation et vitesse moyenne) ; null s'il
 * n'y a pas roulé. Les postes clim et accessoires, connus pour le trajet entier seulement,
 * deviennent inconnus.
 */
fun Trip.portion(profileId: String): Trip? {
    val m = segments?.filter { it.profileId == profileId }.orEmpty()
    if (m.isEmpty()) return null
    val km = m.sumOf { it.distanceKm.toDouble() }.toFloat()
    val regen = if (m.all { it.regenKwh == null }) null else m.sumOf { (it.regenKwh ?: 0f).toDouble() }.toFloat()
    return copy(
        endMs = startMs + m.sumOf { it.durationMs },
        distanceKm = km.roundToInt(),
        integratedKm = km.roundTenth(),
        energyKwh = m.sumOf { it.energyKwh.toDouble() }.toFloat().roundTenth(),
        regenKwh = regen?.roundTenth(),
        climateKwh = null,
        accessoriesKwh = null,
        auxiliaryKwh = null,
        segments = m,
    )
}
