package com.mg4.control.model

/**
 * Mode de conduite Personnalisé : index manipulés par l'application (0 = Éco/Confort, 1 = Normal,
 * 2 = Sport) ↔ valeurs attendues par le véhicule.
 *
 * Trois familles, relevées dans le code d'origine de chaque firmware :
 * - [Family.VPM_133] : propriétés SAIC `0x2040002` / `0x2040004` / `0x2040005` ;
 * - [Family.A9] : méthodes `setDrivingPowerTrainMode` / `setSteeringMode` / `setBrakePedalMode` —
 *   la puissance y réutilise l'échelle des modes de conduite (Éco = 2) ;
 * - [Family.VSM_68] : méthodes `setElectricPowertrainLevel` / `setSteeringLevel` /
 *   `setBrakePedalLevel` du SDK SAIC, qui écrivent les propriétés `0x2140a18c` à `0x2140a18e`.
 *   Échelles LUES dans l'écran d'origine (DrivingSettingsViewModel des Réglages véhicule SWI68 et
 *   SWI165, position du sélecteur → valeur) : puissance et direction 1/2/3, pédale 1/0/2 — donc
 *   les mêmes qu'en SWI133, seule la voie d'accès change.
 */
object CustomDriveScale {

    enum class Setting { POWER, STEERING, PEDAL }

    enum class Family { VPM_133, A9, VSM_68 }

    /** Valeur à écrire, ou null si [index] n'est pas dans 0..2. */
    fun value(setting: Setting, index: Int, family: Family): Int? {
        if (index !in 0..2) return null
        return when (setting) {
            Setting.POWER    -> index + if (family == Family.A9) 2 else 1
            Setting.STEERING -> index + 1
            Setting.PEDAL    -> PEDAL_VALUES[index]
        }
    }

    /** Index correspondant à une valeur lue, ou null si elle est hors échelle (équipement absent). */
    fun index(setting: Setting, value: Int, family: Family): Int? = when (setting) {
        Setting.POWER    -> (value - if (family == Family.A9) 2 else 1).takeIf { it in 0..2 }
        Setting.STEERING -> (value - 1).takeIf { it in 0..2 }
        Setting.PEDAL    -> PEDAL_VALUES.indexOf(value).takeIf { it >= 0 }
    }

    /** Pédale, les trois familles : confort = 1, normal = 0, sport = 2 (Normal vaut ZÉRO). */
    private val PEDAL_VALUES = intArrayOf(1, 0, 2)

    private fun IntArray.indexOf(value: Int): Int = indices.firstOrNull { this[it] == value } ?: -1
}
