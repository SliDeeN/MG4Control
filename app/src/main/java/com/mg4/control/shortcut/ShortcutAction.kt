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
    VOLUME_DOWN(35),

    // Vitres : actions directes, sans état à suivre — chaque pression lance la course complète
    // des quatre vitres. La voiture ne renvoyant rien sur leur position (hors vitre conducteur),
    // une bascule mémorisée n'aurait rien à quoi se raccrocher.
    WINDOWS_OPEN_ALL(36),
    WINDOWS_CLOSE_ALL(37),
    HVAC_AC_TOGGLE(38),

    // Éclairage : famille « lue puis écrite », le réglage se change aussi depuis l'écran d'origine.
    AUTO_HIGH_BEAM_TOGGLE(39),

    // Batterie : « lue puis écrite » aussi — l'écran d'origine et la fenêtre « température basse »
    // de SystemUI l'activent sans nous.
    BATTERY_HEAT_TOGGLE(40),

    // Pop-up HVAC (issues #120 et #125) : réglage rapide de la clim au joystick droit. Comme
    // PROFILE_PICKER, l'action n'écrit rien elle-même — elle ouvre la fenêtre, ou la referme.
    HVAC_POPUP(41),

    // Cycle de profils : applique le profil suivant d'une séquence composée par l'utilisateur
    // (voir ProfileCycle). Le profil visé dépend du profil actif, pas d'un état mémorisé ici.
    PROFILE_CYCLE(42),

    // Cycle de sources audio (2026-10-15) : passe à la source suivante d'une séquence composée par
    // l'utilisateur (voir AudioSourceCycle). 43 est retiré, ne pas le réattribuer.
    AUDIO_SOURCE_CYCLE(44),

    // Pop-up de charge (2026-10-16) : le rouvre s'il a été fermé pendant une charge, ou le referme.
    // Comme HVAC_POPUP, l'action n'écrit rien elle-même. Option coupée : un message dit où l'activer.
    CHARGE_POPUP(45),

    // Pré-climatisation (issue #130, 2026-10-23) : ouvre son pop-up de réglage, ou réaffiche l'écran
    // de suivi pendant un programme. N'écrit rien elle-même : c'est « Démarrer » qui lance.
    PRE_CLIMATE(46),

    // Android Auto / CarPlay (2026-10-23) : ouvre la projection du téléphone en session (AllGo le
    // dit), sinon un message ; projection déjà à l'écran : retour à l'écran d'avant. Voir
    // ProjectionShortcut. N'écrit rien dans la voiture.
    PROJECTION(47),

    // Raccourci perso (issue #172, 2026-10-25) : ce que l'utilisateur a défini dans la page
    // « Raccourcis perso » — une appli, la fonction d'une autre appli, une diffusion, un lien ou un
    // webhook. L'emplacement ne garde que l'identifiant du raccourci perso (shortcut_<slot>_custom_id,
    // ou FloatingButton.customId) ; voir CustomShortcutRunner.
    CUSTOM(48),

    // Débit d'air en mode AUTO (issue #178, 2026-10-29) : « lue puis écrite », faible → moyen →
    // fort → faible. Le réglage se change aussi dans l'écran clim d'origine.
    HVAC_AUTO_VOLUME_CYCLE(49),

    // Paramètres Android (2026-10-31, repris du fork sensoyg) : ouvre l'écran des paramètres
    // d'Android, que le launcher SAIC ne propose pas. Voir AndroidSettings. N'écrit rien.
    ANDROID_SETTINGS(50);

    companion object {
        /**
         * Identifiants retirés, mais peut-être encore écrits dans les préférences d'une voiture.
         * 43 : le pop-up HVAC « nouveau design » de l'essai d'octobre 2026, devenu LE pop-up HVAC —
         * un raccourci qui le porte doit continuer de l'ouvrir. Ne pas réattribuer ces numéros.
         */
        private val RETIRES = mapOf(43 to HVAC_POPUP)

        fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: RETIRES[id] ?: NONE
    }
}

enum class PressType(val key: String) {
    SINGLE("single"),
    LONG("long"),
    DOUBLE("double")
}
