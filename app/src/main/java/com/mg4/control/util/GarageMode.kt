package com.mg4.control.util

import android.content.Context
import com.mg4.control.debug.AppLogger

/**
 * Mode Garage — MG4Control en veille complète, sans rien désinstaller ni reconfigurer.
 *
 * Le besoin est concret : laisser la voiture à l'atelier sans qu'un technicien voie des réglages
 * changer tout seuls au contact, ni un bouton du volant faire autre chose que prévu. Désinstaller
 * l'application ferait le travail, mais coûterait toute la configuration.
 *
 * Ce que le mode suspend, ce sont les comportements AUTONOMES — ceux que personne n'a demandés
 * sur le moment :
 *  • les raccourcis, classiques comme avancés (les touches retournent au launcher d'origine) ;
 *  • le profil appliqué au démarrage et au contact, y compris par Bluetooth ;
 *  • les automatisations par température, profil comme climatisation ;
 *  • la baisse de volume à l'ouverture de porte ;
 *  • l'API externe et l'alerte de mise à jour sur l'écran du véhicule.
 *
 * Ce qu'il ne touche PAS : l'application elle-même. Ouvrir MG4Control et appliquer un profil à la
 * main reste possible — c'est une action de l'utilisateur, pas un comportement observable par
 * quelqu'un qui ne fait que rouler. Et rien n'est effacé : sortir du mode rend l'ensemble à
 * l'identique.
 */
object GarageMode {

    private const val TAG = "MG4_GARAGE"

    private const val PREFS = "mg4_settings"

    /** Défaut false : l'application fonctionne normalement tant qu'on ne demande rien. */
    const val KEY = "garage_mode"

    /**
     * Ancien interrupteur « le profil par défaut s'applique automatiquement au lancement ».
     * Conservé le temps de la reprise ci-dessous, puis retiré des préférences.
     */
    private const val OLD_KEY_AUTO_APPLY = "auto_apply_profile"
    private const val KEY_MIGRATED = "garage_mode_migrated"

    fun isOn(ctx: Context?): Boolean =
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getBoolean(KEY, false) ?: false

    fun setOn(ctx: Context, actif: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, actif).apply()
        AppLogger.i(TAG, "Mode Garage ${if (actif) "ACTIVÉ — tout est en veille" else "désactivé"}")
    }

    /**
     * Reprend l'ancien réglage une seule fois.
     *
     * Qui avait décoché « application automatique du profil » voulait que l'application cesse
     * d'agir seule : le Mode Garage est la version complète de ce souhait, et c'est là qu'on le
     * place. Le laisser retomber sur « désactivé » ferait au contraire réapparaître le
     * comportement que la personne avait pris soin de couper.
     *
     * L'inverse n'a pas de sens : personne n'a jamais pu demander le Mode Garage avant qu'il
     * existe, donc rien à reprendre quand l'ancien réglage était coché.
     */
    fun migrateIfNeeded(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return

        val ancienActif = prefs.getBoolean(OLD_KEY_AUTO_APPLY, true)
        val edit = prefs.edit().putBoolean(KEY_MIGRATED, true).remove(OLD_KEY_AUTO_APPLY)
        if (!ancienActif) {
            edit.putBoolean(KEY, true)
            AppLogger.i(TAG, "reprise de l'ancien réglage : application automatique des profils " +
                "était désactivée → Mode Garage activé")
        }
        edit.apply()
    }
}
