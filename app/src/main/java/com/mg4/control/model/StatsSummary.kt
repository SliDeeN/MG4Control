package com.mg4.control.model

/**
 * Agrégats d'une période : tout ce que montrent les trois résumés de l'onglet Statistiques.
 *
 * Pur, donc testable — et c'est utile, parce que deux règles ici ne sont pas évidentes :
 *
 * 1. **Le prix d'un trajet suit le prix moyen de l'énergie réellement rechargée** sur la période,
 *    pas le tarif domicile. Sans ça, un plein en borne rapide disparaîtrait du coût.
 * 2. **La consommation moyenne d'une période se calcule sur les totaux**, pas en moyennant les
 *    moyennes : un trajet de 2 km pèserait autant qu'un trajet de 200 km.
 */
data class StatsSummary(
    val tripCount: Int,
    val distanceKm: Float,
    val energyKwh: Float,
    val regenKwh: Float,
    val longestTripKm: Float,
    val drivingMs: Long,
    val chargeCount: Int,
    val chargedKwh: Float,
    val chargeCost: Float,
    val acCount: Int,
    val dcCount: Int,
    /** Prix moyen réellement payé, en monnaie par kWh. Null si rien n'a été rechargé. */
    val averagePricePerKwh: Float?,
    /** Coût estimé de l'énergie consommée en roulant. */
    val drivingCost: Float?,
    /**
     * Vrai si **tous** les trajets de la période ont une distance intégrée. Le plancher des ratios
     * en dépend : il serait incohérent qu'une ligne de trajet annonce une consommation que le
     * résumé de la même période refuse d'afficher.
     */
    val precise: Boolean,
) {
    private val floor: Float
        get() = if (precise) Trip.MIN_DISTANCE_PRECISE_KM else Trip.MIN_DISTANCE_ODOMETER_KM

    /** kWh/100 km sur la période, null en dessous de la distance plancher. */
    val consumptionPer100: Float?
        get() = if (distanceKm >= floor) energyKwh * 100f / distanceKm else null

    val averageSpeedKmh: Float?
        get() = if (distanceKm >= floor && drivingMs > 0L)
            distanceKm * 3_600_000f / drivingMs else null

    /** Coût aux 100 km, pour comparer avec un plein de carburant. */
    val costPer100: Float?
        get() {
            val cout = drivingCost ?: return null
            return if (distanceKm >= floor) cout * 100f / distanceKm else null
        }

    companion object {

        fun of(trips: List<Trip>, charges: List<ChargeSession>, settings: StatsSettings): StatsSummary {
            val chargedKwh = charges.sumOf { (it.energyKwh ?: 0f).toDouble() }.toFloat()
            val chargeCost = charges.sumOf { (it.cost(settings) ?: 0f).toDouble() }.toFloat()
            // Prix moyen réel : une session corrigée à la main tire donc la moyenne avec elle.
            val prixMoyen = if (chargedKwh > 0f) chargeCost / chargedKwh else null
            // Net, comme l'affichage d'origine : le compteur du véhicule est brut, régénération
            // comprise. Voir [Trip.energyKwh].
            val energie = trips.sumOf { it.netEnergyKwh.toDouble() }.toFloat()
            return StatsSummary(
                tripCount = trips.size,
                distanceKm = trips.sumOf { it.distance.toDouble() }.toFloat().roundTenth(),
                energyKwh = energie.roundTenth(),
                regenKwh = trips.sumOf { (it.regenKwh ?: 0f).toDouble() }.toFloat().roundTenth(),
                longestTripKm = trips.maxOfOrNull { it.distance } ?: 0f,
                drivingMs = trips.sumOf { it.durationMs },
                chargeCount = charges.size,
                chargedKwh = chargedKwh.roundTenth(),
                chargeCost = chargeCost,
                acCount = charges.count { it.type == ChargeType.AC },
                dcCount = charges.count { it.type == ChargeType.DC },
                averagePricePerKwh = prixMoyen,
                // Faute de charge sur la période, on retombe sur le tarif alternatif : c'est une
                // estimation, et l'écran l'annonce comme telle.
                drivingCost = energie * (prixMoyen ?: settings.priceAc),
                precise = trips.isNotEmpty() && trips.all { it.distancePrecise },
            )
        }
    }
}
