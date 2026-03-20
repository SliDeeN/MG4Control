package com.mg4control.model

/**
 * Représente un profil de réglages véhicule sauvegardé.
 * Sérialisé/désérialisé en JSON simple dans SharedPreferences.
 */
data class VehicleProfile(
    val id: Int,                        // 1, 2 ou 3
    val name: String,                   // Nom personnalisé
    val driveMode: Int,                 // VehiclePropertyIds.DrivingMode.*
    val regenLevel: Int,                // VehiclePropertyIds.RegenLevel.*
    val onePedal: Boolean,
    val overspeedAlarm: Boolean,
    val speedLimitChangeTone: Boolean,
    val isFavorite: Boolean = false
) {
    companion object {
        /** Profil vide par défaut pour un slot donné */
        fun empty(id: Int) = VehicleProfile(
            id = id,
            name = "Profil $id",
            driveMode = 3,       // NORMAL
            regenLevel = 1,      // STANDARD
            onePedal = false,
            overspeedAlarm = true,
            speedLimitChangeTone = true,
            isFavorite = false
        )

        fun fromJson(json: String): VehicleProfile? {
            return try {
                // Parser JSON minimal sans librairie externe
                fun getString(key: String): String? {
                    val pattern = """"$key"\s*:\s*"([^"]*)"""".toRegex()
                    return pattern.find(json)?.groupValues?.get(1)
                }
                fun getInt(key: String): Int? {
                    val pattern = """"$key"\s*:\s*(-?\d+)""".toRegex()
                    return pattern.find(json)?.groupValues?.get(1)?.toIntOrNull()
                }
                fun getBool(key: String): Boolean? {
                    val pattern = """"$key"\s*:\s*(true|false)""".toRegex()
                    return pattern.find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()
                }
                val id = getInt("id") ?: return null
                VehicleProfile(
                    id                   = id,
                    name                 = getString("name") ?: "Profil",
                    driveMode            = getInt("driveMode") ?: 3,
                    regenLevel           = getInt("regenLevel") ?: 1,
                    onePedal             = getBool("onePedal") ?: false,
                    overspeedAlarm       = getBool("overspeedAlarm") ?: true,
                    speedLimitChangeTone = getBool("speedLimitChangeTone") ?: true,
                    isFavorite           = getBool("isFavorite") ?: false
                )
            } catch (e: Exception) { null }
        }
    }

    fun toJson(): String = """
        {
          "id": $id,
          "name": "$name",
          "driveMode": $driveMode,
          "regenLevel": $regenLevel,
          "onePedal": $onePedal,
          "overspeedAlarm": $overspeedAlarm,
          "speedLimitChangeTone": $speedLimitChangeTone,
          "isFavorite": $isFavorite
        }
    """.trimIndent()

    /** Libellé lisible du mode de conduite */
    fun driveModeLabel() = when (driveMode) {
        2 -> "ECO" ; 3 -> "Normal" ; 4 -> "Sport" ; 6 -> "Neige" ; else -> "?"
    }

    /** Libellé lisible du niveau de régénération */
    fun regenLabel() = when (regenLevel) {
        0 -> "Faible" ; 1 -> "Std" ; 2 -> "Fort" ; 3 -> "Auto" ; else -> "?"
    }
}
