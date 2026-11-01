package com.mg4.control.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.AppLanguage
import java.util.Locale

object LocaleHelper {

    private const val PREFS      = "mg4_settings"
    private const val KEY_LANG   = "language"
    private const val KEY_LANG_SET = "language_set"
    private const val TAG = "MG4_LANG"

    /**
     * Langue appliquée : le choix enregistré, sinon celle de la voiture si l'appli la parle, sinon
     * l'anglais ([AppLanguage]). Avant le 2026-10-23, le français par défaut : les pop-ups du
     * service, qui peuvent paraître avant la première ouverture, étaient en français pour tous.
     */
    fun getLanguage(context: Context): String = AppLanguage.effective(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANG, null), carLanguage())

    /**
     * Langue du système, c'est-à-dire celle réglée dans la voiture. Notre réglage n'y touche pas :
     * il ne s'applique qu'à nos propres contextes ([applyLocale]).
     */
    fun carLanguage(): String? =
        runCatching { Resources.getSystem().configuration.locales[0]?.language }.getOrNull()

    /**
     * Premier lancement (2026-10-23) : reprend la langue de la voiture si l'appli la parle, et
     * l'enregistre comme un choix. Vrai si c'est fait — pas de fenêtre de choix. Les contextes déjà
     * créés l'ont déjà, puisque c'est aussi la langue par défaut de [getLanguage].
     */
    fun adoptCarLanguage(context: Context): Boolean {
        val voiture = carLanguage()
        val langue = AppLanguage.fromCar(voiture)
        if (langue == null) {
            AppLogger.i(TAG, "langue de la voiture « ${voiture ?: "?"} » non traduite : choix demandé")
            return false
        }
        setLanguage(context, langue)
        AppLogger.i(TAG, "langue de la voiture « $voiture » reprise au premier lancement")
        return true
    }

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
