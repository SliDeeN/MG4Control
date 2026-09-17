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
 *   Le service véhicule SWI68 borne ces trois valeurs à 0..3 ; l'échelle 1/2/3 retenue ici reste
 *   une hypothèse, que la sonde `MG4_CUSTOM` confirmera en voiture (surtout pour la pédale, qui
 *   vaut 1/0/2 sur les deux autres familles).
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
            Setting.PEDAL    -> if (family == Family.VSM_68) index + 1 else PEDAL_VALUES[index]
        }
    }

    /** Index correspondant à une valeur lue, ou null si elle est hors échelle (équipement absent). */
    fun index(setting: Setting, value: Int, family: Family): Int? = when (setting) {
        Setting.POWER    -> (value - if (family == Family.A9) 2 else 1).takeIf { it in 0..2 }
        Setting.STEERING -> (value - 1).takeIf { it in 0..2 }
        Setting.PEDAL    -> if (family == Family.VSM_68) (value - 1).takeIf { it in 0..2 }
                            else PEDAL_VALUES.indexOf(value).takeIf { it >= 0 }
    }

    /** Pédale, familles VPM_133 et A9 : confort = 1, normal = 0, sport = 2. */
    private val PEDAL_VALUES = intArrayOf(1, 0, 2)

    private fun IntArray.indexOf(value: Int): Int = indices.firstOrNull { this[it] == value } ?: -1
}
