package com.mg4.control.model

/**
 * Chargeur de téléphone sans fil — ce que valent ses états, pour l'écran d'essai de la sonde clé
 * ([com.mg4.control.hardware.KeyProbe]). PROVISOIRE, comme la sonde.
 *
 * `PHONE_WIRELESS_CHAEGER_WORKING_STATE` est la SEULE propriété à avoir bougé quand la clé s'est
 * éloignée de la voiture (SWI133, 2026-10-11 : 1 → 8). Les écrans d'origine n'en interprètent
 * que deux valeurs ; les autres sont données pour ce qu'elles sont, sans rien inventer.
 */
object WirelessCharger {

    /** `PHONE_WIRELESS_CHAEGER_WORKING_STATE` (la faute de frappe est celle du firmware). */
    const val PROPERTY = 0x2140f445

    fun describe(value: Int?): String = when (value) {
        null -> "illisible"
        // ChargingPhoneController de SystemUI : les deux seules valeurs qui affichent un message.
        2    -> "2 = téléphone en charge"
        5    -> "5 = objet étranger sur le socle"
        // YFVehicleWirlessChargeStatus : énumération du HAL, qui s'arrête à 3.
        0    -> "0 = non supporté (énumération du HAL)"
        1    -> "1 = démarré, en attente (énumération du HAL)"
        3    -> "3 = anormal (énumération du HAL)"
        else -> "$value = valeur inconnue du code d\u2019origine"
    }

    /** Nom d'une zone de porte (VehicleAreaDoor), ou la zone en hexadécimal si elle n'en est pas une. */
    fun doorLabel(area: Int): String = when (area) {
        0x1  -> "AVG"
        0x4  -> "AVD"
        0x10 -> "ARG"
        0x40 -> "ARD"
        else -> "0x" + Integer.toHexString(area)
    }
}
