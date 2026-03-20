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
}

