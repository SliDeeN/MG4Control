package com.mg4.control.shortcut

enum class ShortcutAction(val id: Int) {
    NONE(0),
    ONE_PEDAL(1),
    AEB_CYCLE(2),
    SOUND_WARNING(3),
    OVERSPEED_ALARM(4),
    SPEED_LIMIT_TONE(5),
    ADAS_CYCLE(6),
    OPEN_APP(7),
    OPEN_CUSTOM_APP(8),
    ENERGY_SAVING_TOGGLE(9),
    TSR_TOGGLE(10),
    APPLY_PROFILE(11),
    PROFILE_PICKER(12),
    VEHICLE_POWER_OFF(13),

    // Actions « lues puis écrites » : elles interrogent le véhicule à chaque pression plutôt
    // que de suivre un état mémorisé. Indispensable ici — l'utilisateur peut agir sur la clim
    // ou l'ESC depuis l'écran d'origine, un état gardé en mémoire dériverait aussitôt.
    ESC_TOGGLE(14),
    DROWSINESS_TOGGLE(15),
    DROWSINESS_SEN_CYCLE(16),
    HVAC_TOGGLE(17),
    HVAC_TEMP_UP(18),
    HVAC_TEMP_DOWN(19),
    HVAC_FAN_UP(20),
    HVAC_FAN_DOWN(21),

    // Même famille « lue puis écrite » : confort et affichage. Elles reprennent les réglages
    // que le launcher d'origine met en accès direct, et que les raccourcis ne couvraient pas.
    REGEN_CYCLE(22),
    SEAT_HEAT_LEFT_CYCLE(23),
    SEAT_HEAT_RIGHT_CYCLE(24),
    STEERING_HEAT_TOGGLE(25),
    DEFROST_FRONT_TOGGLE(26),
    DEFROST_REAR_TOGGLE(27),
    HVAC_RECIRC_CYCLE(28),
    BRIGHTNESS_UP(29),
    BRIGHTNESS_DOWN(30),

    // Média : seules actions qui ne touchent NI au véhicule NI à un état mémorisé — elles
    // envoient une touche au système, qui la remet à l'application en train de jouer.
    MEDIA_NEXT(31),
    MEDIA_PREVIOUS(32),
    MEDIA_PLAY_PAUSE(33),

    // Volume : réglage du VÉHICULE, contrairement aux trois précédentes. Il ne dépend d'aucune
    // application et fonctionne donc même quand les touches média restent sans effet.
    VOLUME_UP(34),
    VOLUME_DOWN(35);

    companion object {
        fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: NONE
    }
}

enum class PressType(val key: String) {
    SINGLE("single"),
    LONG("long"),
    DOUBLE("double")
}
