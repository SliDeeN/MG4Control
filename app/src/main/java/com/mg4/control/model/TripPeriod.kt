package com.mg4.control.model

/**
 * Période des compteurs de trajet (2026-11-22, sa demande) : consommation, distance, énergie,
 * récupération et durée de conduite, sur l'écran conducteur et dans les bulles. Les libellés ne
 * changent pas avec la période (sa décision) : à chacun de se souvenir de ce qu'il a réglé.
 */
enum class TripPeriod(val key: String) {
    /** Depuis le dernier passage en READY : le comportement d'avant l'option. */
    READY("ready"),
    /** Depuis l'allumage de l'écran central : plusieurs passages en READY s'additionnent. */
    BOOT("boot"),
    /** Depuis la dernière charge, d'un réveil de la voiture à l'autre. */
    CHARGE("charge");

    companion object {
        /** `null` ou inconnu = [READY], le comportement d'avant l'option. */
        fun of(key: String?): TripPeriod = entries.firstOrNull { it.key == key } ?: READY

        /** Valeur à enregistrer : `null` pour [READY], pour que rien ne change chez qui n'y touche pas. */
        fun keyOf(p: TripPeriod): String? = p.key.takeIf { p != READY }

        /** Les informations qui ont une période. */
        fun applies(key: String?): Boolean =
            key in ClusterInfo.TRIP_KEYS || key == RuleSignal.DRIVE_MINUTES.name

        /**
         * Une charge a eu lieu pendant que l'écran dormait : la batterie a pris au moins
         * [CHARGE_SOC_STEP] points entre la sortie de READY et le passage suivant.
         */
        fun chargedMeanwhile(socAtOff: Float?, socNow: Float?): Boolean =
            socAtOff != null && socNow != null && socNow - socAtOff >= CHARGE_SOC_STEP

        const val CHARGE_SOC_STEP = 2f
    }
}

/** Totaux d'une période : énergie en kWh, distance en km, temps passé en READY en ms. */
data class TripTotals(
    val consumedKwh: Double = 0.0,
    val regenKwh: Double = 0.0,
    val km: Double = 0.0,
    val readyMs: Long = 0L,
) {
    operator fun plus(o: TripTotals) =
        TripTotals(consumedKwh + o.consumedKwh, regenKwh + o.regenKwh, km + o.km, readyMs + o.readyMs)

    val minutes: Double get() = readyMs / 60_000.0

    /** Les infos du combiné pour ces totaux ; moyenne nette, `null` sous [ClusterTrip.MIN_KM]. */
    fun toTrip(powerKw: Float?): ClusterInfo.Trip = ClusterInfo.Trip(
        averageWhPerKm = if (km >= ClusterTrip.MIN_KM) ((consumedKwh - regenKwh) * 1000.0 / km).toFloat() else null,
        distanceKm = km.toFloat(),
        consumedKwh = consumedKwh.toFloat(),
        regenWh = (regenKwh * 1000.0).toFloat(),
        powerKw = powerKw,
    )
}
