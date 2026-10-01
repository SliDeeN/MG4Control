package com.mg4.control.model

/**
 * Réglages de l'onglet Statistiques. Pur : la persistance vit dans `stats/StatsStore`.
 *
 * [capacityKwh] sert à convertir une différence de pourcentage en kilowattheures — la seule
 * méthode qui marche quand le boîtier a dormi pendant la charge. La MG4 existe en plusieurs
 * capacités, d'où le réglage plutôt qu'une constante.
 */
data class StatsSettings(
    val enabled: Boolean = false,
    val retention: Retention = Retention.MONTHS_3,
    /** Prix du kWh en charge alternative (domicile, en général). */
    val priceAc: Float = 0.187f,
    /** Prix du kWh en charge continue (borne rapide). */
    val priceDc: Float = 0.45f,
    /** Symbole affiché : la langue ne dit pas la monnaie. */
    val currency: String = "€",
    /** Capacité utile de la batterie, en kWh. */
    val capacityKwh: Float = DEFAULT_CAPACITY_KWH,
) {
    fun priceFor(type: ChargeType?): Float = if (type == ChargeType.DC) priceDc else priceAc

    /** Convertit une différence de pourcentage en kWh. Null si l'une des bornes manque. */
    fun kwhFromSoc(socStart: Float?, socEnd: Float?): Float? {
        if (socStart == null || socEnd == null) return null
        val delta = socEnd - socStart
        return if (delta <= 0f) null else (delta / 100f * capacityKwh).roundTenth()
    }

    enum class Retention(val days: Int) {
        DAYS_30(30),
        MONTHS_3(90),
        MONTHS_6(182),
        YEAR(365),
    }

    companion object {
        /** MG4 64 kWh : ~62 kWh utiles. Réglable, les finitions diffèrent. */
        const val DEFAULT_CAPACITY_KWH = 62f
        const val MIN_CAPACITY_KWH = 20f
        const val MAX_CAPACITY_KWH = 120f
        const val MAX_PRICE = 5f

        fun clampPrice(value: Float): Float = value.coerceIn(0f, MAX_PRICE)
        fun clampCapacity(value: Float): Float = value.coerceIn(MIN_CAPACITY_KWH, MAX_CAPACITY_KWH)
    }
}
