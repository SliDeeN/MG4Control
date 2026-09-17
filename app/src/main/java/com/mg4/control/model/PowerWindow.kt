package com.mg4.control.model

/**
 * Vitres électriques : une propriété véhicule par vitre, la même sur les 6 firmwares.
 *
 * Décodé des firmwares : `VEHICLE_DRIVERWINDOW`… (com.android.car SWI131/133/165, type float,
 * permission CONTROL_CAR_SEATS). L'ancien SDK l'écrit via `VehicleControlManager.setDriveWindow`,
 * A9 via `CarVehicleSettingClient.setVehicleWindowStatus` : tous deux finissent en
 * `setFloatProperty(propriété, 0x1000000, valeur)`.
 *
 * [lockArea] : zone de la vitre pour le verrou AOSP `WINDOW_LOCK` (VehicleAreaWindow ROW_x_LEFT/RIGHT).
 * Aucune appli d'origine ne s'en sert : sa prise en compte par le véhicule reste à vérifier.
 */
enum class PowerWindow(val propId: Int, val isRear: Boolean, val lockArea: Int, val shortName: String) {
    FRONT_LEFT (0x11603801, isRear = false, lockArea = 0x10,  shortName = "AVG"),
    FRONT_RIGHT(0x11603802, isRear = false, lockArea = 0x40,  shortName = "AVD"),
    REAR_LEFT  (0x11603803, isRear = true,  lockArea = 0x100, shortName = "ARG"),
    REAR_RIGHT (0x11603804, isRear = true,  lockArea = 0x400, shortName = "ARD"),
}

/**
 * Valeurs de commande d'une vitre. Seules 0, 1 et 3 sont connues (mesurées par le projet winclose
 * sur SWI69) ; 2 et 4 sont supposées par symétrie et se vérifient avec le test brut de l'onglet
 * Vitres. Le service SWI68 refuse toute valeur hors de 0..7.
 */
object WindowCommand {

    const val STOP        = 0
    const val MANUAL_UP   = 1
    const val MANUAL_DOWN = 2   // supposé
    const val AUTO_UP     = 3
    const val AUTO_DOWN   = 4   // supposé
    const val MAX         = 7

    /** Au-delà, un appui court n'est plus pris pour un « stop » : la course auto est finie. */
    const val AUTO_TRAVEL_MS = 6_000L
    /** Durée d'appui qui fait basculer du mode auto au mode manuel. */
    const val HOLD_DELAY_MS  = 400L
    /** Cadence de répétition en manuel, celle que winclose a validée en voiture. */
    const val HOLD_REPEAT_MS = 120L
    /** Garde-fou si le relâché du doigt se perd : bien plus qu'une course complète. */
    const val HOLD_MAX_MS    = 15_000L

    enum class Direction { UP, DOWN }

    fun manual(direction: Direction): Int = if (direction == Direction.UP) MANUAL_UP else MANUAL_DOWN

    fun auto(direction: Direction): Int = if (direction == Direction.UP) AUTO_UP else AUTO_DOWN

    fun isValid(value: Int): Boolean = value in 0..MAX

    /**
     * Appui court : lance la course automatique, sauf si une course vient d'être lancée sur cette
     * vitre — l'appui l'arrête alors, comme l'interrupteur physique. La position n'étant pas
     * lisible sur toutes les finitions, « en cours » se juge au temps écoulé.
     */
    fun forShortPress(direction: Direction, lastAutoMs: Long?, nowMs: Long): Int {
        val elapsed = if (lastAutoMs == null) -1L else nowMs - lastAutoMs
        return if (elapsed in 0 until AUTO_TRAVEL_MS) STOP else auto(direction)
    }

    /** Sécurité enfant : l'app ne commande plus les vitres arrière. */
    fun isBlocked(window: PowerWindow, childLock: Boolean): Boolean = childLock && window.isRear

    fun targetsForAll(childLock: Boolean): List<PowerWindow> =
        PowerWindow.entries.filterNot { isBlocked(it, childLock) }
}
