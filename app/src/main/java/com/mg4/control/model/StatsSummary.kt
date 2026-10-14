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
    /**
     * Distance des seuls trajets dont l'énergie est connue ([Trip.energyKnown]). C'est elle, et
     * non [distanceKm], que divisent la consommation et le coût aux 100 km : un trajet enregistré
     * à 0,0 kWh par des compteurs muets (issue #117) ferait sinon baisser la moyenne.
     */
    val measuredDistanceKm: Float,
    /** Vrai quand la période a des trajets mais qu'aucun n'a d'énergie connue : rien à annoncer. */
    val energyUnknown: Boolean,
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
    /**
     * Incertitude relative de [consumptionPer100] sur la période, cumulée trajet par trajet.
     * Voir [Trip.consumptionUncertainty] : même règle, mêmes résolutions, addition prudente.
     */
    val consumptionUncertainty: Float?,
) {
    /** Vrai quand la consommation de la période doit être annoncée comme approchée. */
    val consumptionApproximate: Boolean
        get() {
            consumptionPer100 ?: return false
            val u = consumptionUncertainty ?: return true
            return u > Resolution.APPROXIMATE_ABOVE
        }

    private val floor: Float
        get() = if (precise) Trip.MIN_DISTANCE_PRECISE_KM else Trip.MIN_DISTANCE_ODOMETER_KM

    /** kWh/100 km sur la période, null en dessous de la distance plancher. */
    val consumptionPer100: Float?
        get() = if (measuredDistanceKm >= floor) energyKwh * 100f / measuredDistanceKm else null

    val averageSpeedKmh: Float?
        get() = if (distanceKm >= floor && drivingMs > 0L)
            distanceKm * 3_600_000f / drivingMs else null

    /** Coût aux 100 km, pour comparer avec un plein de carburant. */
    val costPer100: Float?
        get() {
            val cout = drivingCost ?: return null
            return if (measuredDistanceKm >= floor) cout * 100f / measuredDistanceKm else null
        }

    companion object {

        fun of(trips: List<Trip>, charges: List<ChargeSession>, settings: StatsSettings): StatsSummary {
            val chargedKwh = charges.sumOf { (it.energyKwh ?: 0f).toDouble() }.toFloat()
            val chargeCost = charges.sumOf { (it.cost(settings) ?: 0f).toDouble() }.toFloat()
            // Prix moyen réel : une session corrigée à la main tire donc la moyenne avec elle.
            val prixMoyen = if (chargedKwh > 0f) chargeCost / chargedKwh else null
            // Net, comme l'affichage d'origine : le compteur du véhicule est brut, régénération
            // comprise. Voir [Trip.energyKwh].
            // Seuls les trajets dont l'énergie est connue entrent dans l'énergie, les ratios et
            // le coût. La distance, la durée et le nombre de trajets, eux, restent entiers.
            val mesures = trips.filter { it.energyKnown }
            val inconnue = trips.isNotEmpty() && mesures.isEmpty()
            val energie = mesures.sumOf { it.netEnergyKwh.toDouble() }.toFloat()
            val distance = trips.sumOf { it.distance.toDouble() }.toFloat().roundTenth()
            val distanceMesuree = mesures.sumOf { it.distance.toDouble() }.toFloat().roundTenth()
            // Chaque trajet apporte son propre arrondi : sur une période, ils se diluent dans des
            // totaux plus gros, ce qui fait justement disparaître le « ≈ » au bout de quelques
            // trajets.
            // Une énergie intégrée n'a pas le pas des compteurs : son erreur est proportionnelle.
            val uEnergie = mesures.sumOf {
                (if (it.energyIntegrated) Resolution.INTEGRATED_ENERGY_RELATIVE * it.netEnergyKwh
                 else Resolution.ENERGY_KWH * if (it.regenKwh != null) 2f else 1f).toDouble()
            }.toFloat()
            val uDistance = mesures.sumOf {
                (if (it.distancePrecise) Resolution.DISTANCE_PRECISE_KM
                 else Resolution.DISTANCE_ODOMETER_KM).toDouble()
            }.toFloat()
            return StatsSummary(
                tripCount = trips.size,
                distanceKm = distance,
                measuredDistanceKm = distanceMesuree,
                energyUnknown = inconnue,
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
                drivingCost = if (inconnue) null else energie * (prixMoyen ?: settings.priceAc),
                precise = trips.isNotEmpty() && trips.all { it.distancePrecise },
                consumptionUncertainty = if (energie > 0f && distanceMesuree > 0f)
                    uEnergie / energie + uDistance / distanceMesuree else null,
            )
        }
    }
}
