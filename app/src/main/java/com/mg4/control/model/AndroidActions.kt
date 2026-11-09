package com.mg4.control.model

/**
 * Actions Android des automatisations (2026-11-09, premier lot) : accueil, retour, fermer une
 * appli, ouvrir une page des paramètres. Ici ce qui se décide sans Android, pour les tests ;
 * l'exécution est dans `AndroidControls`.
 */
object AndroidActions {

    /** [RuleAction.value] de ANDROID_KEY. */
    const val KEY_HOME = "home"
    const val KEY_BACK = "back"

    /**
     * Pages des paramètres Android, dans l'ordre du choix : clé enregistrée dans la règle →
     * action d'intent. Une page que SAIC aurait retirée échoue proprement (✗ au journal).
     */
    val SETTINGS_PAGES: Map<String, String> = linkedMapOf(
        "wifi" to "android.settings.WIFI_SETTINGS",
        "bluetooth" to "android.settings.BLUETOOTH_SETTINGS",
        "hotspot" to "android.settings.TETHER_SETTINGS",
        "sound" to "android.settings.SOUND_SETTINGS",
        "display" to "android.settings.DISPLAY_SETTINGS",
        "apps" to "android.settings.MANAGE_APPLICATIONS_SETTINGS",
        "accessibility" to "android.settings.ACCESSIBILITY_SETTINGS",
        "date" to "android.settings.DATE_SETTINGS",
        "language" to "android.settings.LOCALE_SETTINGS",
        "storage" to "android.settings.INTERNAL_STORAGE_SETTINGS",
    )

    /**
     * Préfixes des applis de la voiture et d'Android, jamais fermées de force — en plus de toute
     * appli système. Pas les applis Google : YouTube Music ou Maps installés à la main se ferment.
     */
    private val PROTEGES = listOf("android", "com.android.", "com.saicmotor.", "com.saic.", "com.allgo.")

    /**
     * Une appli peut-elle être fermée de force ? Jamais MG4Control, une appli système ni une
     * appli de la voiture ou d'Android : les arrêter casserait l'écran central (lanceur, média,
     * clim, projection…).
     */
    fun closable(pkg: String?, own: String, system: Boolean): Boolean {
        if (pkg.isNullOrBlank() || pkg == own || system) return false
        return PROTEGES.none { p -> if (p.endsWith(".")) pkg.startsWith(p) else pkg == p }
    }
}
