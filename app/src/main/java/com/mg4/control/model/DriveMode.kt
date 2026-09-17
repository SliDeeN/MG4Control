package com.mg4.control.model

enum class DriveMode(val value: Int, val label: String) {
    ECO(2, "Eco"),
    NORMAL(3, "Normal"),
    SPORT(4, "Sport"),
    SNOW(6, "Snow"),
    CUSTOM(7, "Custom");

    companion object {
        /** Valeur véhicule → mode, ou null si elle ne correspond à aucun mode connu. */
        fun fromValueOrNull(v: Int): DriveMode? = entries.firstOrNull { it.value == v }

        /**
         * Repli sur Normal, pour les appelants qui doivent bien afficher quelque chose.
         * Une LECTURE véhicule passe par [fromValueOrNull] : confondre « illisible » et
         * « Normal » masquerait à tort la carte du mode Personnalisé.
         */
        fun fromValue(v: Int): DriveMode = fromValueOrNull(v) ?: NORMAL
    }
}
