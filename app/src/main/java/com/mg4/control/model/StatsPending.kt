package com.mg4.control.model

/**
 * Trajet ou charge **en cours**, conservé sur disque entre deux relevés.
 *
 * Sans ça, un trajet se perd systématiquement quand le boîtier coupe l'application au moment où la
 * voiture s'éteint — c'est-à-dire précisément à l'instant où le trajet se termine. En gardant le
 * point de départ ET le dernier relevé connu, le démarrage suivant peut clore ce qui restait ouvert.
 *
 * Dans `model` pour être couvert par les règles de conservation : voir [[StatsHistory]].
 */
data class PendingTrip(
    val startMs: Long,
    val odometerStart: Int?,
    val energyStart: Float?,
    val socStart: Float?,
    val tempC: Float?,
    /** Dernier relevé observé pendant le trajet — celui qui servira de fin en cas de coupure. */
    val lastMs: Long,
    val odometerLast: Int?,
    val energyLast: Float?,
    val socLast: Float?,
    val climateLast: Float?,
    val accessoriesLast: Float?,
    val regenLast: Float?,
)

data class PendingCharge(
    val startMs: Long,
    val socStart: Float?,
    val type: ChargeType?,
    val tempC: Float?,
    val powerSum: Float,
    val powerCount: Int,
    val lastMs: Long,
    val socLast: Float?,
)

/** Les deux ensemble, tels qu'ils sont enregistrés. */
data class PendingState(
    val trip: PendingTrip? = null,
    val charge: PendingCharge? = null,
) {
    val isEmpty: Boolean get() = trip == null && charge == null
}
