package com.mg4.control.model

/**
 * Langue de l'application au premier lancement (2026-10-23, idée reprise de MG4Dash) : celle de la
 * voiture si l'appli la parle, sans rien demander ; sinon la fenêtre de choix, pour prendre la
 * plus proche plutôt qu'un anglais imposé. La règle, sans Android.
 */
object AppLanguage {

    /** Les langues traduites, dans l'ordre de la fenêtre de choix. */
    val SUPPORTED = listOf("fr", "en", "de", "es", "pt", "it", "tr")

    /** Ni choix ni langue de voiture connue : l'anglais, compris du plus grand nombre. */
    const val FALLBACK = "en"

    /** Langue de la voiture ([carLanguage], code ISO « de », « nl »…) si l'appli la parle, sinon `null`. */
    fun fromCar(carLanguage: String?): String? =
        carLanguage?.lowercase()?.takeIf { it in SUPPORTED }

    /** Langue appliquée : le choix enregistré, sinon celle de la voiture, sinon [FALLBACK]. */
    fun effective(saved: String?, carLanguage: String?): String =
        saved ?: fromCar(carLanguage) ?: FALLBACK
}
