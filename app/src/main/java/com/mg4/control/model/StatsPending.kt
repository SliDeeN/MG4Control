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
    /** Première température relevée. Ne sert plus qu'à reprendre un état de l'ancien format. */
    val tempC: Float?,
    /** Dernier relevé observé pendant le trajet — celui qui servira de fin en cas de coupure. */
    val lastMs: Long,
    val odometerLast: Int?,
    val energyLast: Float?,
    val socLast: Float?,
    val climateLast: Float?,
    val accessoriesLast: Float?,
    val regenLast: Float?,
    /** Somme et nombre des températures relevées, pour en faire une moyenne. */
    val tempSum: Float = 0f,
    val tempCount: Int = 0,
    /** Distance accumulée en intégrant la vitesse, en km. Voir [Trip.integratedKm]. */
    val integratedKm: Float = 0f,
    /** Vitesse du relevé précédent, l'autre côté du trapèze. */
    val lastSpeedKmh: Float? = null,
    /**
     * Vrai dès qu'un intervalle a pu être intégré. Sans ce drapeau, une distance de 0 km ne se
     * distinguerait pas d'une distance jamais mesurée — et l'écran annoncerait un trajet immobile.
     */
    val integrated: Boolean = false,
) {
    val averageTempC: Float? get() = moyenne(tempSum, tempCount, tempC)
}

data class PendingCharge(
    val startMs: Long,
    val socStart: Float?,
    val type: ChargeType?,
    /** Première température relevée. Ne sert plus qu'à reprendre un état de l'ancien format. */
    val tempC: Float?,
    val powerSum: Float,
    val powerCount: Int,
    val lastMs: Long,
    val socLast: Float?,
    val tempSum: Float = 0f,
    val tempCount: Int = 0,
) {
    val averageTempC: Float? get() = moyenne(tempSum, tempCount, tempC)
}

/**
 * Moyenne des températures relevées pendant un trajet ou une charge, **et non celle du départ**.
 *
 * Le capteur extérieur de la MG4 est lent et mal placé : une voiture qui sort d'un garage annonce
 * la température du garage pendant plusieurs minutes. Sous contact, le collecteur relève à cadence
 * fixe, la moyenne des échantillons vaut donc une moyenne dans le temps.
 *
 * [repli] couvre un état enregistré avant cette mesure, qui ne portait que la première valeur.
 */
private fun moyenne(sum: Float, count: Int, repli: Float?): Float? =
    if (count > 0) (sum / count).roundTenth() else repli

/** Les deux ensemble, tels qu'ils sont enregistrés. */
data class PendingState(
    val trip: PendingTrip? = null,
    val charge: PendingCharge? = null,
) {
    val isEmpty: Boolean get() = trip == null && charge == null
}
