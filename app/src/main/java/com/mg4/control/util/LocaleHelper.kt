package com.mg4.control.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

object LocaleHelper {

    private const val PREFS      = "mg4_settings"
    private const val KEY_LANG   = "language"
    private const val KEY_LANG_SET = "language_set"

    /** Retourne "fr" ou "en" (défaut : "fr"). */
    fun getLanguage(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, "fr") ?: "fr"

    /** Sauvegarde le choix et marque le premier lancement comme fait. */
    fun setLanguage(context: Context, lang: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LANG, lang)
            .putBoolean(KEY_LANG_SET, true)
            .apply()
    }

    /** True si l'utilisateur n'a pas encore choisi de langue. */
    fun isFirstLaunch(context: Context): Boolean =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LANG_SET, false)

    /**
     * Applique la locale ET la taille du texte sauvegardées au contexte fourni, et retourne le
     * contexte modifié.
     *
     * La taille du texte est posée ici parce que tous les contextes de l'app y passent déjà :
     * activité, application, et popups du service (dont le contexte ne reçoit rien d'autre).
     */
    fun applyLocale(context: Context): Context {
        val lang   = getLanguage(context)
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        // Calculée depuis l'échelle SYSTÈME, pas depuis celle du contexte reçu : un contexte déjà
        // enveloppé l'aurait sinon appliquée deux fois (×1,25 × 1,25).
        config.fontScale = Resources.getSystem().configuration.fontScale * TextSize.get(context).scale
        return context.createConfigurationContext(config)
    }
}
