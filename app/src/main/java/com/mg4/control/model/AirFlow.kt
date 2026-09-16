package com.mg4.control.model

/**
 * Sens de l'air : correspondance entre les boutons cumulables de l'écran (Visage, Pieds,
 * Pare-brise AV) et la valeur de la propriété véhicule `HVAC_BLOWER_DIRECTION` (0x1540250e).
 *
 * L'échelle a été décodée des firmwares (tableau d'icônes `res_blow_on` de SystemUI, identique
 * sur SWI69 et SWI131) : les deux familles écrivent cette même propriété avec cette même valeur,
 * l'ancien SDK via `setBlowerDirectionMode`, A9 via `setFanDirection`. Ce n'est PAS un masque de
 * bits — « visage + pare-brise » vaut 6 — d'où une table explicite.
 *
 * | Valeur | Visage | Pieds | Pare-brise |
 * |--------|:------:|:-----:|:----------:|
 * | 0      |   ✓    |       |            |
 * | 1      |   ✓    |   ✓   |            |
 * | 2      |        |   ✓   |            |
 * | 3      |        |   ✓   |     ✓      |
 * | 4      |        |       |     ✓      |
 * | 5      |   ✓    |   ✓   |     ✓      |
 * | 6      |   ✓    |       |     ✓      |
 *
 * 7 (« aucun » dans le firmware) n'est jamais écrit.
 */
object AirFlow {

    // ── Bits du profil (DrivingProfile.hvacAirFlow) ──────────────────────────
    // Le profil range les BOUTONS et non la valeur : c'est ce que l'utilisateur a coché, et la
    // lunette arrière, absente de l'échelle, doit y tenir aussi.
    const val FACE         = 1
    const val FEET         = 2
    const val WINDSHIELD   = 4
    const val REAR_DEFROST = 8

    data class Parts(val face: Boolean, val feet: Boolean, val windshield: Boolean)

    /** Valeur à écrire pour cette combinaison, ou null si aucun des trois n'est choisi. */
    fun directionFor(face: Boolean, feet: Boolean, windshield: Boolean): Int? = when {
        face && feet && windshield -> 5
        face && windshield         -> 6
        face && feet               -> 1
        feet && windshield         -> 3
        face                       -> 0
        feet                       -> 2
        windshield                 -> 4
        else                       -> null
    }

    /** Boutons allumés pour une valeur lue ; null hors échelle (7, illisible, inconnue). */
    fun partsOf(direction: Int): Parts? = when (direction) {
        0    -> Parts(face = true,  feet = false, windshield = false)
        1    -> Parts(face = true,  feet = true,  windshield = false)
        2    -> Parts(face = false, feet = true,  windshield = false)
        3    -> Parts(face = false, feet = true,  windshield = true)
        4    -> Parts(face = false, feet = false, windshield = true)
        5    -> Parts(face = true,  feet = true,  windshield = true)
        6    -> Parts(face = true,  feet = false, windshield = true)
        else -> null
    }

    /** Valeur à écrire pour les boutons cochés d'un profil ; la lunette arrière n'y compte pas. */
    fun directionForMask(mask: Int): Int? = directionFor(
        face       = mask and FACE != 0,
        feet       = mask and FEET != 0,
        windshield = mask and WINDSHIELD != 0
    )
}
