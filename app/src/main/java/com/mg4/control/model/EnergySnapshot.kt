package com.mg4.control.model

/**
 * Un instantané des grandeurs énergétiques du véhicule. Tous les champs sont **nullables** :
 * `null` veut dire « pas lu », jamais zéro — un SoC à 0 dirait que la batterie est vide.
 *
 * Relevé sur SWI133 les 18 et 19 septembre 2026 (voir la sonde `EnergyProbe`) :
 * - les compteurs d'énergie sont en **kWh au dixième**, le SoC en **% au dixième**, l'odomètre
 *   au **kilomètre entier** ;
 * - `energySinceStart` se remet à zéro **au contact**, `energySinceCharge` **au branchement** ;
 * - la puissance n'a pas de propriété dédiée : c'est le produit tension × courant, le courant
 *   étant **négatif quand la batterie se remplit**.
 */
data class EnergySnapshot(
    val timestampMs: Long,
    /** Pourcentage de batterie, 0..100. */
    val socPercent: Float? = null,
    /** Odomètre total, en kilomètres entiers. */
    val odometerKm: Int? = null,
    /** Énergie consommée depuis le contact, en kWh (remise à zéro au contact). */
    val energySinceStartKwh: Float? = null,
    /** Idem depuis le dernier branchement. */
    val energySinceChargeKwh: Float? = null,
    /** Postes, depuis le contact : climatisation, accessoires, régénération récupérée. */
    val climateSinceStartKwh: Float? = null,
    val accessoriesSinceStartKwh: Float? = null,
    val regenSinceStartKwh: Float? = null,
    /** Vrai quand la voiture charge (`BMS_CHRG_STS` = 1). */
    val charging: Boolean? = null,
    /** Type de charge lu sur les deux prises ; null tant qu'aucune n'est active. */
    val chargeType: ChargeType? = null,
    /** Puissance instantanée en kW, positive en charge, négative en roulant. */
    val powerKw: Float? = null,
    /** Autonomie annoncée par la voiture, en kilomètres. */
    val rangeKm: Int? = null,
    /**
     * Vitesse instantanée en km/h, toujours positive.
     *
     * C'est la seule voie vers une distance plus fine que le kilomètre : sondés le 2026-09-20,
     * l'odomètre AOSP, celui du combiné et la carte de trajet rendent tous zéro sur SWI133.
     */
    val speedKmh: Float? = null,
    /** Température extérieure en °C, pour expliquer une consommation. */
    val outsideTempC: Float? = null,
) {
    /** Vrai si l'instantané porte assez d'information pour être exploité. */
    val usable: Boolean get() = socPercent != null || odometerKm != null
}

/** Type de prise utilisée, lu sur `CCU_ONBD` (alternatif) et `CCU_OFFBD` (continu). */
enum class ChargeType { AC, DC }
