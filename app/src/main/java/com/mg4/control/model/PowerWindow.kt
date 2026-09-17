package com.mg4.control.model

/**
 * Vitres électriques : une propriété véhicule par vitre, la même sur les 6 firmwares.
 *
 * Décodé des firmwares : `VEHICLE_DRIVERWINDOW`… (com.android.car SWI131/133/165, type float,
 * permission CONTROL_CAR_SEATS). L'ancien SDK l'écrit via `VehicleControlManager.setDriveWindow`,
 * A9 via `CarVehicleSettingClient.setVehicleWindowStatus` : tous deux finissent en
 * `setFloatProperty(propriété, 0x1000000, valeur)`.
 *
 * Mesuré en voiture le 2026-09-17 (peut dépendre de la finition, codes de configuration relevés
 * par la sonde) :
 * - [hasNativeAuto] : la descente auto (4) n'ouvre que la vitre conducteur, et la montée auto (3), qui
 *   fermait les quatre au premier essai, a cessé de fermer les autres après la calibration (état interne
 *   du module de vitre, invisible pour l'app). Ailleurs, les courses auto sont donc émulées dans les deux
 *   sens par la commande manuelle répétée — sans l'anti-pincement de la course native.
 * - [hasPositionSensor] : seule la vitre conducteur remonte une position ; les autres restent figées
 *   hors plage (127.5 / 255) et leur position est estimée après calibration ([WindowEstimator]).
 *
 * Pas de sécurité enfant : le verrou AOSP `WINDOW_LOCK` est ignoré par le véhicule (interrupteurs
 * arrière toujours actifs), et sans capteur l'app ne voit pas ces interrupteurs.
 */
enum class PowerWindow(
    val propId: Int,
    val hasNativeAuto: Boolean,
    val hasPositionSensor: Boolean,
    val shortName: String,
) {
    FRONT_LEFT (0x11603801, hasNativeAuto = true,  hasPositionSensor = true,  shortName = "AVG"),
    FRONT_RIGHT(0x11603802, hasNativeAuto = false, hasPositionSensor = false, shortName = "AVD"),
    REAR_LEFT  (0x11603803, hasNativeAuto = false, hasPositionSensor = false, shortName = "ARG"),
    REAR_RIGHT (0x11603804, hasNativeAuto = false, hasPositionSensor = false, shortName = "ARD"),
}

/**
 * Valeurs de commande d'une vitre. 0, 1 et 3 viennent du projet winclose (SWI69) ; 2 et 4 ont été
 * essayées en voiture le 2026-09-17 via l'onglet Vitres : 3 et 4 ne sont fiables que sur la vitre
 * conducteur (voir [PowerWindow.hasNativeAuto]). 5 à 7 restent inconnues (test brut). Le service SWI68 refuse
 * toute valeur hors de 0..7.
 */
object WindowCommand {

    const val STOP        = 0
    const val MANUAL_UP   = 1
    const val MANUAL_DOWN = 2
    const val AUTO_UP     = 3
    const val AUTO_DOWN   = 4
    const val MAX         = 7

    /** Au-delà, un appui court n'est plus pris pour un « stop » : la course auto native est finie. */
    const val AUTO_TRAVEL_MS = 6_000L
    /** Durée d'une course auto émulée tant que la vitre n'est pas calibrée. */
    const val EMULATED_COURSE_MS = AUTO_TRAVEL_MS
    /**
     * Plus grande position valide. Le service véhicule SWI68 rejette toute lecture au-delà
     * (`max_vehicle_window_get` = 100) ; en voiture, 127.5 et 255 restent figés sur les vitres
     * sans capteur de position.
     */
    const val POSITION_MAX = 100f
    /** Durée d'appui qui fait basculer du mode auto au mode manuel. */
    const val HOLD_DELAY_MS  = 400L
    /** Cadence de répétition en manuel, celle que winclose a validée en voiture. */
    const val HOLD_REPEAT_MS = 120L
    /** Garde-fou si le relâché du doigt se perd : bien plus qu'une course complète. */
    const val HOLD_MAX_MS    = 15_000L

    enum class Direction { UP, DOWN }

    fun manual(direction: Direction): Int = if (direction == Direction.UP) MANUAL_UP else MANUAL_DOWN

    fun auto(direction: Direction): Int = if (direction == Direction.UP) AUTO_UP else AUTO_DOWN

    /** Sens de mouvement d'une commande ; null pour le stop et les valeurs inconnues (5 à 7). */
    fun directionOf(value: Int): Direction? = when (value) {
        MANUAL_UP, AUTO_UP     -> Direction.UP
        MANUAL_DOWN, AUTO_DOWN -> Direction.DOWN
        else                   -> null
    }

    fun isValid(value: Int): Boolean = value in 0..MAX

    /** Position utilisable, ou null (illisible, ou valeur de remplacement d'une vitre sans capteur). */
    fun position(raw: Float?): Float? = raw?.takeIf { it in 0f..POSITION_MAX }

    /** Durée d'une course émulée : la course calibrée dans ce sens si elle existe, sinon la valeur par défaut. */
    fun emulatedCourseMs(direction: Direction, calibration: WindowCalibration?): Long = when (direction) {
        Direction.DOWN -> calibration?.emulatedOpenMs
        Direction.UP   -> calibration?.emulatedCloseMs
    } ?: EMULATED_COURSE_MS

    /**
     * Une fermeture émulée n'a pas l'anti-pincement de la course native : elle ne continue que
     * sous les yeux de l'utilisateur (arrêtée si l'onglet est quitté). Une ouverture peut finir seule.
     */
    fun stopsWhenUnattended(value: Int): Boolean = directionOf(value) == Direction.UP

    /**
     * Appui court : lance la course automatique, sauf si une course vient d'être lancée sur cette
     * vitre — l'appui l'arrête alors, comme l'interrupteur physique. La position n'étant pas
     * lisible sur toutes les finitions, « en cours » se juge au temps écoulé.
     */
    fun forShortPress(direction: Direction, lastAutoMs: Long?, nowMs: Long): Int {
        val elapsed = if (lastAutoMs == null) -1L else nowMs - lastAutoMs
        return if (elapsed in 0 until AUTO_TRAVEL_MS) STOP else auto(direction)
    }
}
