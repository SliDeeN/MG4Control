package com.mg4.control.model

import com.mg4.control.shortcut.ShortcutAction

/** Ce qu'une bulle montre de son action (2026-10-24, maquette A). */
enum class FloatingKind {
    /** Bascule : fond plein si activée, transparent si coupée, en permanence. */
    TOGGLE,
    /** Cycle ou réglage pas à pas : la nouvelle valeur, un instant, après l'appui. */
    VALUE,
    /** Le reste (pop-ups, applis, média, vitres…) : le halo seul. */
    NONE,
}

/**
 * Style des boutons flottants selon leur action (maquette A, 2026-10-24). L'état et la valeur
 * affichés sont ceux que la voiture confirme, relus après l'appui — jamais ceux qu'on devine ;
 * illisibles, la bulle garde son allure ordinaire. Les cycles de profils et de sources audio
 * annoncent déjà leur choix dans un message : pas de valeur dans la bulle en plus.
 */
object FloatingButtonStyle {

    /** Relecture des bascules tant que des bulles sont à l'écran : l'écran d'origine les change aussi. */
    const val POLL_MS = 3_000L
    /** Première relecture après un appui. */
    const val READBACK_MS = 800L
    /** Seconde relecture des bascules : certaines commandes SAIC mettent jusqu'à 2 s à se refléter. */
    const val READBACK_LATE_MS = 2_200L
    /** Durée d'affichage de la valeur d'un cycle ou d'un réglage. */
    const val FLASH_MS = 1_200L

    private val TOGGLES = setOf(
        ShortcutAction.ONE_PEDAL, ShortcutAction.ENERGY_SAVING_TOGGLE, ShortcutAction.TSR_TOGGLE,
        ShortcutAction.SOUND_WARNING, ShortcutAction.OVERSPEED_ALARM, ShortcutAction.SPEED_LIMIT_TONE,
        ShortcutAction.ESC_TOGGLE, ShortcutAction.DROWSINESS_TOGGLE,
        ShortcutAction.HVAC_TOGGLE, ShortcutAction.HVAC_AC_TOGGLE,
        ShortcutAction.DEFROST_FRONT_TOGGLE, ShortcutAction.DEFROST_REAR_TOGGLE,
        ShortcutAction.STEERING_HEAT_TOGGLE, ShortcutAction.AUTO_HIGH_BEAM_TOGGLE,
        ShortcutAction.BATTERY_HEAT_TOGGLE,
    )

    private val VALUES = setOf(
        ShortcutAction.REGEN_CYCLE, ShortcutAction.DROWSINESS_SEN_CYCLE,
        ShortcutAction.SEAT_HEAT_LEFT_CYCLE, ShortcutAction.SEAT_HEAT_RIGHT_CYCLE,
        ShortcutAction.HVAC_RECIRC_CYCLE,
        ShortcutAction.HVAC_TEMP_UP, ShortcutAction.HVAC_TEMP_DOWN,
        ShortcutAction.HVAC_FAN_UP, ShortcutAction.HVAC_FAN_DOWN,
        ShortcutAction.BRIGHTNESS_UP, ShortcutAction.BRIGHTNESS_DOWN,
        ShortcutAction.VOLUME_UP, ShortcutAction.VOLUME_DOWN,
    )

    fun kind(action: ShortcutAction?): FloatingKind = when (action) {
        in TOGGLES -> FloatingKind.TOGGLE
        in VALUES -> FloatingKind.VALUE
        else -> FloatingKind.NONE
    }

    /** [FloatingButton.action] est le nom de l'action ; un nom inconnu (action retirée) n'a pas de style. */
    fun kind(actionName: String): FloatingKind = kind(ShortcutAction.entries.firstOrNull { it.name == actionName })
}
