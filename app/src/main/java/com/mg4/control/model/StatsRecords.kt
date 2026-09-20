package com.mg4.control.model

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Un trajet enregistré : ce qui reste d'une période de conduite une fois la voiture éteinte.
 *
 * Les durées sont en millisecondes d'horloge murale (elles servent à dater, pas à mesurer une
 * précision fine), l'énergie en kWh et la distance en kilomètres.
 */
data class Trip(
    val startMs: Long,
    val endMs: Long,
    val distanceKm: Int,
    /**
     * Énergie **brute** relevée sur le compteur du véhicule : la régénération n'en est PAS déduite.
     *
     * Vérifié le 2026-09-20 en comparant avec l'écran d'origine — la voiture affichait 14,1
     * kWh/100 km là où ce compteur donnait 17,1 sur la même distance, l'écart valant exactement la
     * régénération. C'est donc [netEnergyKwh] qu'il faut montrer et facturer.
     */
    val energyKwh: Float,
    val climateKwh: Float?,
    val accessoriesKwh: Float?,
    val regenKwh: Float?,
    val socStart: Float?,
    val socEnd: Float?,
    val outsideTempC: Float?,
) {
    val durationMs: Long get() = max(0L, endMs - startMs)

    /**
     * Énergie réellement sortie de la batterie : brute moins ce que la régénération a rendu.
     *
     * C'est la convention de tout le monde — la voiture elle-même, Tesla, les planificateurs — et
     * c'est la seule qui ait un sens pour le coût : on ne recharge que ce qu'on a vraiment dépensé.
     */
    val netEnergyKwh: Float
        get() = (energyKwh - (regenKwh ?: 0f)).coerceAtLeast(0f).roundTenth()

    /**
     * Consommation moyenne en kWh/100 km, **null sous la distance plancher**.
     *
     * L'odomètre du véhicule est au kilomètre entier : sur 2 km, la distance est connue à ±50 %
     * près et le ratio n'aurait aucun sens. Mieux vaut ne rien afficher que d'afficher un chiffre
     * faux — l'énergie et la distance, elles, restent justes et restent affichées.
     */
    val consumptionPer100: Float?
        get() = if (distanceKm >= MIN_DISTANCE_FOR_RATIO_KM && distanceKm > 0)
            netEnergyKwh * 100f / distanceKm else null

    /**
     * Énergie du moteur : ce qui reste du total une fois la climatisation et les accessoires
     * retirés. Le véhicule ne la publie pas — il ne donne que le total et ses postes annexes.
     *
     * Nommée « moteur » et non « traction » : la MG4 est une propulsion, et la XPower une quatre
     * roues motrices. Null tant qu'aucun poste n'est connu, sinon on présenterait le total comme
     * une mesure séparée qui n'existe pas.
     */
    val motorKwh: Float?
        get() {
            if (climateKwh == null && accessoriesKwh == null) return null
            // Sur le BRUT : moteur + climatisation + accessoires − régénération = énergie nette.
            val reste = energyKwh - (climateKwh ?: 0f) - (accessoriesKwh ?: 0f)
            return reste.coerceAtLeast(0f).roundTenth()
        }

    /** Vitesse moyenne en km/h, même réserve de précision que la consommation. */
    val averageSpeedKmh: Float?
        get() {
            if (distanceKm < MIN_DISTANCE_FOR_RATIO_KM || durationMs <= 0L) return null
            return distanceKm * 3_600_000f / durationMs
        }

    companion object {
        /** En deçà, on n'affiche aucun ratio : l'odomètre est au kilomètre entier. */
        const val MIN_DISTANCE_FOR_RATIO_KM = 5
    }
}

/**
 * Une session de charge.
 *
 * [energyKwh] vient de la différence de pourcentage multipliée par la capacité utile réglée : c'est
 * la seule méthode qui fonctionne même si le boîtier s'est endormi pendant la nuit. [measuredPowerKw]
 * n'existe que si l'application a pu relever la puissance en direct, donc si elle était éveillée ;
 * sa présence est justement ce qui dit à l'écran s'il peut annoncer une puissance mesurée.
 */
data class ChargeSession(
    val startMs: Long,
    val endMs: Long,
    val type: ChargeType?,
    val socStart: Float?,
    val socEnd: Float?,
    val energyKwh: Float?,
    /** Moyenne des puissances relevées pendant la charge, null si rien n'a pu être relevé. */
    val measuredPowerKw: Float?,
    val outsideTempC: Float?,
    /** Prix du kWh corrigé à la main pour cette session ; null = tarif par défaut du type. */
    val tariffOverride: Float? = null,
) {
    val durationMs: Long get() = max(0L, endMs - startMs)

    /**
     * Puissance à afficher : celle qui a été mesurée si elle existe, sinon énergie ÷ durée.
     * Le second cas est une déduction, pas une mesure — l'écran doit le dire.
     */
    val powerKw: Float?
        get() = measuredPowerKw ?: run {
            val heures = durationMs / 3_600_000f
            val kwh = energyKwh ?: return null
            if (heures <= 0.05f) null else kwh / heures
        }

    /** Prix payé pour cette session, selon le tarif corrigé ou celui de son type de prise. */
    fun cost(settings: StatsSettings): Float? {
        val kwh = energyKwh ?: return null
        return kwh * (tariffOverride ?: settings.priceFor(type))
    }
}

/** Arrondi d'affichage : un dixième, comme la voiture publie ses compteurs. */
fun Float.roundTenth(): Float = (this * 10f).roundToInt() / 10f
