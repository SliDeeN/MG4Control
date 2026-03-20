package com.mg4control.repository

/**
 * Property IDs extraits de com.saicmotor.hmi.vehiclesettings v0.9.0
 * Source : VehiclePropertyID.smali + DrivingSettingsRepository (r0/d.smali)
 *
 * Accès via réflexion :
 *   VehiclePropertyManager.setIntProperty(propertyId, value)
 *   VehiclePropertyManager.getIntProperty(propertyId)
 */
object VehiclePropertyIds {

    // ─── Mode de conduite ───────────────────────────────────────────────────
    // r0/d.smali méthode w(I) → setIntProperty(0x2040001, value)
    const val DRIVING_MODE = 0x2040001

    object DrivingMode {
        const val ECO    = 2  // DRIVE_MODE_SET_ECO
        const val NORMAL = 3  // DRIVE_MODE_SET_NORMAL
        const val SPORT  = 4  // DRIVE_MODE_SET_SPORT
        const val SNOW   = 6  // DRIVE_MODE_SET_SNOW
    }

    // ─── Niveau de régénération ─────────────────────────────────────────────
    // r0/d.smali méthode A(I) → setIntProperty(0x5030001, value)
    const val REGENERATIVE_LEVEL = 0x5030001

    object RegenLevel {
        const val LOW      = 0  // REGENERATIVE_LEVEL_MODE_LOW
        const val STANDARD = 1  // REGENERATIVE_LEVEL_MODE_STANDARD
        const val HIGH     = 2  // REGENERATIVE_LEVEL_MODE_HIGH
        const val AUTO     = 3  // REGENERATIVE_LEVEL_MODE_AUTO
        const val OFF      = 5  // REGENERATIVE_LEVEL_MODE_OFF
    }

    // ─── One Pedal ──────────────────────────────────────────────────────────
    // o0/l$u.smali → setIntProperty(0x5030003, value)
    const val SIGNAL_PEDAL = 0x5030003

    object SignalPedal {
        const val OFF = 0
        const val ON  = 1
    }

    // ─── Avertissement de dépassement de vitesse ────────────────────────────
    // Confirmé par diagnostic sur SWI133-29176-1300R30 :
    // 0x503004e est le seul ID qui change quand on toggle le réglage dans l'app officielle
    const val OVERSPEED_SOUND_ALARM = 0x503004e

    // ─── Avertissement sonore de changement de limite ───────────────────────
    // o0/t$l0.smali → setIntPropertyRecovery(0x503004f, value)  (mode sans ISA)
    // ID_AAD_SPEED_LIMIT_UPDATE_PROMPT_SOUND dans VehiclePropertyID.smali
    const val SPEED_LIMIT_CHANGE_TONE = 0x503004f

    object AlertSwitch {
        const val OFF = 0
        const val ON  = 1
    }

    // ─── Aide à la conduite (ADAS) ──────────────────────────────────────────
    // Confirmé par analyse smali VehiclePropertyID.smali + r0/h.smali :
    // ID_MIXED_INTELLIGENT_DRIVE = 0x32, lu/écrit via getMixProperty/setMixProperty
    const val MIXED_INTELLIGENT_DRIVE = 0x32

    object AdasMode {
        const val OFF        = 0  // non documenté dans le smali — à confirmer
        const val SPEED_LIMI = 1  // INTELLIGENT_DRIVE_MANUAL (limitateur vitesse)
        const val AUTO       = 2  // INTELLIGENT_DRIVE_AUTO (reconnaissance panneaux)
        const val ACC        = 3  // INTELLIGENT_DRIVE_ACC
        const val ICA        = 4  // INTELLIGENT_DRIVE_ICA
    }

    // IDs suspects à scanner lors du diagnostic
    val ADAS_DIAG_IDS = mapOf(
        "MIXED_INTELLIGENT_DRIVE 0x2050001" to 0x2050001,
        "MIXED_INTELLIGENT_DRIVE 0x2050002" to 0x2050002,
        "INTELLIGENT_DRIVE       0x2040002" to 0x2040002,
        "INTELLIGENT_DRIVE       0x2040003" to 0x2040003,
        "SPEED_LIMIT_ASSIST      0x5040001" to 0x5040001,
        "SPEED_LIMIT_ASSIST      0x5040002" to 0x5040002,
        "ACC_MODE                0x5050001" to 0x5050001,
        "ACC_MODE                0x5050002" to 0x5050002,
        "ICA_MODE                0x5060001" to 0x5060001,
        "DRIVE_ASSIST            0x32"      to 0x32,
        "DRIVE_ASSIST            0x33"      to 0x33,
        "DRIVE_ASSIST            0x34"      to 0x34
    )
}

