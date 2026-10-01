package com.mg4.control.util

import android.content.Context

/**
 * Taille du texte de toute l'application — Réglages → Interface.
 *
 * Appliquée par l'échelle de police d'Android (`Configuration.fontScale`, posée dans
 * [LocaleHelper.applyLocale]) et non taille par taille : les ~700 tailles de l'app sont écrites en
 * dur dans les layouts, sans style commun. L'échelle couvre d'un coup les écrans, les boîtes de
 * dialogue et les popups du service, sans risque d'oubli ni de double application.
 *
 * Contrepartie : l'agrandissement est PROPORTIONNEL. Les coefficients sont calés sur 16 sp, la
 * taille la plus répandue, qui gagne exactement +2 puis +4 ; une étiquette de 10 sp gagne moins,
 * un titre de 22 sp davantage — ce qui garde la hiérarchie des textes.
 */
enum class TextSize(val key: String, val scale: Float) {
    STANDARD("standard", 1f),
    LARGE("large", 1.125f),
    XLARGE("xlarge", 1.25f);

    companion object {
        private const val PREFS = "mg4_settings"
        private const val KEY   = "text_size"

        /** Taille correspondant à une clé enregistrée ; STANDARD si absente ou inconnue. */
        fun fromKey(key: String?): TextSize = entries.firstOrNull { it.key == key } ?: STANDARD

        fun get(context: Context): TextSize = fromKey(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        )

        fun set(context: Context, size: TextSize) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, size.key)
                .apply()
        }
    }
}
