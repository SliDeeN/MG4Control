package com.mg4.control.accessibility

import android.content.Context
import com.mg4.control.profile.ActiveProfile
import com.mg4.control.shortcut.PressType
import com.mg4.control.shortcut.ShortcutAction

/**
 * Raccourcis « avancés » : couples (code de touche, type d'appui) → action.
 *
 * Distincts des raccourcis classiques, qui reposent sur le broadcast `hardkey.report` du
 * véhicule. Ceux-ci passent par [KeyCaptureService], qui voit la touche AVANT le launcher et
 * peut la consommer.
 *
 * ⚠️ CONSÉQUENCE À CONNAÎTRE : une touche enregistrée ici est réclamée **en bloc**. Pour
 * intercepter un appui long il faut consommer le DOWN, or à cet instant on ne sait pas encore
 * si l'appui sera court, long ou double — et on ne peut pas « dé-consommer » après coup. Les
 * types d'appui non attribués ne retombent donc PAS sur le launcher : ils ne font rien.
 * C'est ce que [isClaimed] exprime, et c'est pour ça qu'elle ignore le type d'appui.
 */
object AdvancedShortcuts {

    const val PREFS = "mg4_settings"

    /** Interrupteur maître de la fonctionnalité. Défaut false : la voie classique reste la norme. */
    const val KEY_ENABLED = "advanced_shortcuts_enabled"

    /**
     * Seuil de l'appui long, en millisecondes.
     *
     * Le chemin classique n'a pas ce problème : le véhicule lui livre un extra
     * `android.intent.extra.hardkey.longpress` déjà calculé. L'accessibilité ne fournit que des
     * KeyEvent bruts, on mesure donc l'appui nous-mêmes. 500 ms est la valeur Android par
     * défaut ; le seuil du véhicule est inconnu, un écart de classement entre les deux systèmes
     * reste donc possible.
     */
    const val LONG_PRESS_MS = 500L

    /**
     * Fenêtre d'attente d'un éventuel second appui, en millisecondes.
     *
     * ⚠️ Elle a un COÛT, et il faut le connaître avant d'attribuer un double appui : sur une
     * touche qui en porte un, l'action d'appui **court** ne peut plus partir au relâchement —
     * il faut d'abord s'assurer qu'aucun second appui n'arrive, sinon le simple se déclencherait
     * systématiquement avant le double. Les touches sans double appui ne paient rien : leur
     * action part immédiatement.
     *
     * 300 ms est la valeur d'Android (`ViewConfiguration.getDoubleTapTimeout()`).
     */
    const val DOUBLE_TAP_MS = 300L

    /**
     * Fichier où l'écran d'édition range la CIBLE d'un raccourci — l'application à ouvrir, le
     * profil à appliquer — sous la clé `shortcut_<slot>_…`.
     *
     * Ce n'est volontairement pas [PREFS] : c'est le fichier des raccourcis classiques, et c'est
     * lui que relit [com.mg4.control.service.MG4ControlService] au moment d'exécuter l'action,
     * quelle que soit la voie qui l'a déclenchée.
     */
    private const val PREFS_CIBLES = "mg4_shortcuts"

    private const val PREFIXE = "adv_sc_"

    /**
     * Sépare le profil du reste de la clé.
     *
     * `@` parce qu'il n'apparaît ni dans un code de touche, ni dans un nom de type d'appui, ni
     * dans un identifiant de profil — ce sont des UUID, faits de chiffres, de lettres et de
     * tirets. Aucune clé existante ne peut donc en contenir un.
     */
    private const val SEP_PROFIL = '@'

    /**
     * Clé de stockage d'un raccourci.
     *
     * ⚠️ Le suffixe vient de [PressType.key] — `single` / `long` / `double`. Les deux premiers
     * sont exactement ceux utilisés avant l'ajout du double appui : les raccourcis déjà
     * enregistrés continuent donc d'être relus tels quels, sans migration.
     *
     * ⚠️ De même pour le profil : un raccourci valable pour TOUS les profils s'écrit sans
     * suffixe, exactement comme avant que les profils entrent en jeu. C'est ce qui rend la
     * nouveauté invisible aux raccourcis déjà en place.
     */
    private fun cle(keyCode: Int, press: PressType, profileId: String? = null): String {
        val base = "$PREFIXE${keyCode}_${press.key}"
        return if (profileId == null) base else "$base$SEP_PROFIL$profileId"
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
    }

    data class Mapping(
        val keyCode: Int,
        val press: PressType,
        val action: ShortcutAction,
        /** Profil auquel ce raccourci est réservé. `null` = tous les profils. */
        val profileId: String? = null
    )

    /**
     * Toutes les attributions, triées par touche, type d'appui, puis profil — « tous les
     * profils » en tête de chaque groupe, ce qui est aussi l'ordre de résolution.
     */
    fun all(context: Context): List<Mapping> {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.all.keys
            .filter { it.startsWith(PREFIXE) }
            .mapNotNull { cle ->
                val reste = cle.removePrefix(PREFIXE)
                val coupure = reste.indexOf(SEP_PROFIL)
                val partieTouche = if (coupure < 0) reste else reste.substring(0, coupure)
                val profil = if (coupure < 0) null
                             else reste.substring(coupure + 1).takeIf { it.isNotEmpty() }
                // Le suffixe est cherché par correspondance exacte, sans valeur de repli : une
                // clé inconnue doit être IGNORÉE, pas rattachée arbitrairement à un type d'appui.
                val press = PressType.values().firstOrNull { partieTouche.endsWith("_${it.key}") }
                    ?: return@mapNotNull null
                val code = partieTouche.removeSuffix("_${press.key}").toIntOrNull()
                    ?: return@mapNotNull null
                val id = runCatching { p.getInt(cle, 0) }.getOrDefault(0)
                val action = ShortcutAction.fromId(id)
                if (action == ShortcutAction.NONE) null else Mapping(code, press, action, profil)
            }
            // `profileId ?: ""` place « tous les profils » avant les variantes : la ligne de
            // repli se lit ainsi toujours en premier, comme elle est résolue en dernier.
            .sortedWith(compareBy({ it.keyCode }, { it.press.ordinal }, { it.profileId ?: "" }))
    }

    fun set(
        context: Context, keyCode: Int, press: PressType,
        action: ShortcutAction, profileId: String? = null
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(cle(keyCode, press, profileId), action.id).apply()
    }

    /**
     * Le raccourci qui doit s'appliquer MAINTENANT pour ce couple touche + appui.
     *
     * Deux crans, dans cet ordre : celui réservé au profil actif, puis celui valable pour tous
     * les profils. Rien d'autre — un raccourci réservé à « Sport » ne doit pas se déclencher
     * sous « Hiver » au prétexte qu'aucun autre ne convient.
     */
    fun resolve(context: Context, keyCode: Int, press: PressType): Mapping? {
        val actif = ActiveProfile.id(context)
        if (actif != null) {
            actionFor(context, keyCode, press, actif)?.let {
                return Mapping(keyCode, press, it, actif)
            }
        }
        return actionFor(context, keyCode, press, null)?.let { Mapping(keyCode, press, it, null) }
    }

    /**
     * Clé d'emplacement transmise au service principal, qui s'en sert pour retrouver
     * `shortcut_<slot>_custom_app` et `shortcut_<slot>_profile_id`.
     *
     * ⚠️ Centralisée à dessein : l'écran d'édition ÉCRIT ces deux réglages et
     * [KeyCaptureService] transmet la clé qui les fera RELIRE. Deux constructions séparées de
     * la même chaîne finiraient par diverger, et le raccourci ouvrirait alors « rien ».
     */
    fun slotKey(keyCode: Int, press: PressType, profileId: String? = null): String {
        val base = "adv_${keyCode}_${press.key}"
        // Deux raccourcis sur la même touche et le même appui, réservés à deux profils, ont
        // chacun leur cible : sans le profil ici, ils partageraient l'application à ouvrir.
        return if (profileId == null) base else "${base}_p$profileId"
    }

    fun remove(context: Context, keyCode: Int, press: PressType, profileId: String? = null) {
        // Retirer aussi l'app et le profil associés : les laisser derrière ferait resurgir un
        // ancien choix si la même touche était réattribuée plus tard à la même action.
        //
        // ⚠️ DEUX fichiers de préférences, et c'est facile à manquer : l'attribution vit dans
        // [PREFS], mais sa cible est rangée dans [PREFS_CIBLES] — c'est ce dernier que lit le
        // service au moment de déclencher l'action. Le nettoyage n'a longtemps porté que sur le
        // premier, et laissait donc les cibles derrière lui.
        val slot = slotKey(keyCode, press, profileId)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(cle(keyCode, press, profileId))
            .apply()
        context.getSharedPreferences(PREFS_CIBLES, Context.MODE_PRIVATE).edit()
            .remove("shortcut_${slot}_custom_app")
            .remove("shortcut_${slot}_profile_id")
            .apply()
    }

    /** Action attribuée à ce triplet exact, ou null. Aucun repli : voir [resolve]. */
    fun actionFor(
        context: Context, keyCode: Int, press: PressType, profileId: String? = null
    ): ShortcutAction? {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(cle(keyCode, press, profileId), 0)
        return ShortcutAction.fromId(id).takeIf { it != ShortcutAction.NONE }
    }

    /**
     * Vrai si CETTE TOUCHE est réclamée — quel que soit le type d'appui ET quel que soit le
     * profil. Voir l'avertissement en tête de fichier : c'est cette méthode qui décide de la
     * consommation, et une touche se réclame en bloc.
     *
     * Balayage par préfixe plutôt que par énumération : les profils possibles ne sont pas
     * connus d'ici, et les tester un par un obligerait à charger la liste des profils à chaque
     * appui de touche.
     */
    fun isClaimed(context: Context, keyCode: Int): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Le tiret bas final évite que la touche 17 réponde pour la touche 170.
        val prefixeTouche = "$PREFIXE${keyCode}_"
        return p.all.keys.any {
            it.startsWith(prefixeTouche) && runCatching { p.getInt(it, 0) }.getOrDefault(0) != 0
        }
    }

    /**
     * Nom lisible d'une touche du volant, ou null si inconnue.
     *
     * Les codes viennent du système classique et des relevés faits sur véhicule. Une touche
     * inconnue reste utilisable — elle s'affiche par son code, ce qui suffit aux remontées.
     */
    fun nomTouche(keyCode: Int): String? = when (keyCode) {
        17            -> "Étoile gauche"
        286, 18       -> "Étoile droite"
        297           -> "Joystick haut"
        298           -> "Joystick bas"
        299           -> "Joystick gauche"
        300           -> "Joystick droite"
        301           -> "Joystick centre"
        else          -> null
    }
}
