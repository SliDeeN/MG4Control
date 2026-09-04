package com.mg4.control.model

import java.util.UUID

data class DrivingProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val driveMode: DriveMode,
    val regenLevel: RegenLevel,
    val steeringHeat: Boolean = false,
    val seatHeatLeft: Int = 0,        // 0=off, 1, 2, 3
    val seatHeatRight: Int = 0,
    // Prise en compte du chauffage : décorrélée de la valeur. `seatHeatLeft=0` voulait dire
    // « appliquer : éteint » — il manquait un moyen de dire « ne pas y toucher ».
    // ⚠️ NULLABLES À DESSEIN : Gson n'appelle pas le constructeur Kotlin, donc un défaut `= true`
    // ne s'appliquerait PAS aux profils déjà enregistrés (champ absent → false) et le chauffage
    // cesserait silencieusement d'être appliqué. Avec `Boolean?`, un champ absent reste null, et
    // null se lit « activé » via les accesseurs ci-dessous → aucune migration, aucune régression.
    val steeringHeatEnabled: Boolean? = null,
    val seatHeatEnabled: Boolean? = null,
    // ADAS SWI133 (Katman4) — valeurs par défaut OFF pour compatibilité profils existants
    val overspeedAlarm: Boolean = false,
    val speedLimitTone: Boolean = false,
    val adasMode: Int = 0,            // 0=Off, 1=Limiteur, 2=Auto, 3=ACC, 4=ICA
    // ADAS SWI68 — champs distincts pour isoler les configurations par firmware
    val soundWarning: Boolean = false,
    val swi68AdasMode: Int = 0x4,     // Mode ACC/TJA (CarAccTja) : 0x4=Off, 0x1=ACC, 0x2=TJA/ICA
    // Limiteur de vitesse (SAS) — réglage INDÉPENDANT du mode ACC/TJA (SWI132).
    // swi132LimiterConfigured=false (défaut + profils créés avant cette fonction) → le limiteur
    // n'est PAS touché lors de l'application du profil (aucune régression sur l'état voiture).
    val swi132LimiterConfigured: Boolean = false,
    val swi132SasMode: Int = 0,       // SAS : 0=Désactivé, 2=Manuel, 3=Intelligent
    // AEB — Système anti-collision avant (commun SWI133 + SWI68)
    val aebEnabled: Boolean = false,   // false=OFF, true=ON
    val aebMode: Int = 1,              // 1=Alerte seule, 2=Alerte+Freinage auto
    val aebSensitivity: Int = 0,       // 0=non configuré, 1=Faible, 2=Standard, 3=Élevé (SWI133)
    // ELK — Assistant de sortie de voie
    val elkMode: Int = 0,              // 0=non configuré, 1=OFF, 2=Alerte(LDW), 3=Aider(LDP), 5=ELK
    val elkSensitivity: Int = 0,       // 0=non configuré, 1=Faible, 2=Standard, 3=Élevé
    // ELK SWI132 — Alerte sonore + Vibration (spécifique SWI132)
    val lasAudibleWarning: Boolean = true,    // true=ON (défaut ON dans la voiture)
    val lasVibrationReminder: Boolean = true, // true=ON (défaut ON dans la voiture)
    // Économie d'énergie + TSR
    val energySaving: Boolean = false,
    val tsrEnabled: Boolean = false,
    val isDefault: Boolean = false,
    // ESC + avertissement de somnolence + sensibilité de son alerte
    //
    // ⚠️ NULLABLES À DESSEIN, comme le volant/les sièges chauffants : Gson n'appelle pas le
    // constructeur Kotlin, donc un `Boolean = true` ne s'appliquerait PAS aux profils déjà
    // enregistrés — champ absent du JSON → défaut de la JVM, c'est-à-dire `false`. L'ESC se
    // retrouverait DÉSACTIVÉ sur tous les anciens profils, l'inverse de ce qu'on veut.
    // `null` = profil antérieur à la fonctionnalité, et ce sont les accesseurs qui tranchent.
    val escEnabled: Boolean? = null,
    val drowsinessEnabled: Boolean? = null,
    val drowsinessSensitivity: Int? = null,   // 1=Faible, 2=Standard, 3=Élevé
    // ── Climatisation ────────────────────────────────────────────────────────────
    /**
     * Interrupteur du bloc clim. FAUX par défaut, et c'est ce que Gson donne aux profils
     * enregistrés avant la fonctionnalité (champ absent → défaut de la JVM) : aucun d'eux ne se
     * met donc à piloter la climatisation.
     *
     * ⚠️ C'est aussi ce qui rend les défauts ci-dessous sans conséquence. Un profil ancien les
     * lit à 0/false, ce qui n'aurait aucun sens appliqué tel quel — mais ils ne sont JAMAIS lus
     * tant que celui-ci est faux.
     */
    val hvacEnabled: Boolean = false,
    val hvacPower: Boolean = true,
    val hvacAc: Boolean = true,
    /** Ventilation automatique. Exclusive de [hvacFan] : voir MG4Hardware.applyProfileClimate. */
    val hvacAuto: Boolean = false,
    val hvacTemp: Int = 21,
    val hvacFan: Int = 4,
    /** `null` = ne pas y toucher. Même convention que l'automatisation A/C. */
    val hvacDefrostFront: Boolean? = null,
    val hvacDefrostRear: Boolean? = null,
    /** `null` = inchangé, sinon 0=Intérieur, 1=Extérieur, 2=Auto. */
    val hvacLoopMode: Int? = null,
    // [BT-PROFILES] MAC de l'appareil Bluetooth associé à ce profil (null = aucun)
    val btDeviceMac: String? = null
) {
    /** Volant chauffant à appliquer ? (profil d'avant la fonction → oui, comportement inchangé) */
    val appliesSteeringHeat: Boolean get() = steeringHeatEnabled ?: true

    /** Sièges chauffants à appliquer ? (idem) */
    val appliesSeatHeat: Boolean get() = seatHeatEnabled ?: true

    // Défauts pour un profil antérieur à la fonctionnalité : ce sont ceux que la voiture
    // rétablit elle-même à chaque démarrage, donc les appliquer ne change rien à l'état
    // habituel — et surtout, jamais un ESC désactivé par omission.
    val appliesEsc: Boolean get() = escEnabled ?: true
    val appliesDrowsiness: Boolean get() = drowsinessEnabled ?: true
    /** 2 = Standard. Valeur littérale : le modèle ne doit pas dépendre de la couche matérielle. */
    val appliesDrowsinessSensitivity: Int get() = drowsinessSensitivity ?: 2
}
