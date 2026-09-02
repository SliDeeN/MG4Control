package com.mg4.control.profile

import android.content.Context
import com.mg4.control.debug.AppLogger

/**
 * Profil actif — le dernier appliqué, quelle qu'en soit la source.
 *
 * À ne pas confondre avec [ProfileApplier.lastManualProfileId], qui répond à une tout autre
 * question. Celui-là ne retient que les applications MANUELLES, vit en mémoire et s'efface à
 * l'extinction : il sert à ce que le passage en READY respecte un choix explicite plutôt que de
 * réappliquer le profil par défaut. Le toucher aurait changé cette règle de précédence.
 *
 * Ici on retient TOUT — démarrage, contact, Bluetooth, automatisation température, choix manuel —
 * et on l'écrit en préférence, parce que la question posée est « sur quel profil roule-t-on en ce
 * moment ? » et qu'elle doit encore avoir une réponse après un redémarrage du service.
 *
 * ⚠️ LIMITE À CONNAÎTRE : c'est le dernier profil APPLIQUÉ, pas l'état réel du véhicule. Un
 * réglage changé à la main depuis le Dashboard ne l'invalide pas. L'alternative — comparer l'état
 * du véhicule à chaque profil — coûterait une dizaine de lectures véhicule à chaque appui de
 * touche, pour un résultat ambigu dès que deux profils se ressemblent.
 */
object ActiveProfile {

    private const val TAG = "MG4_PROFILE"

    /** Même fichier que les profils eux-mêmes : cette information leur appartient. */
    private const val PREFS = "mg4_profiles"
    private const val KEY = "active_profile_id"

    /** Id du profil actif, ou `null` si aucun n'a encore été appliqué. */
    fun id(ctx: Context?): String? =
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY, null)

    fun set(ctx: Context?, profileId: String?) {
        val prefs = ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        if (profileId == null) prefs.edit().remove(KEY).apply()
        else prefs.edit().putString(KEY, profileId).apply()
        AppLogger.i(TAG, "profil actif = ${profileId ?: "aucun"}")
    }

    /**
     * Efface le profil actif s'il désigne un profil qui n'existe plus.
     *
     * Sans ça, supprimer un profil laisserait les raccourcis qui lui sont associés se résoudre
     * sur un identifiant fantôme : ils ne se déclencheraient jamais, sans que rien ne l'explique.
     */
    fun forgetIfMissing(ctx: Context?, existants: Collection<String>) {
        val actuel = id(ctx) ?: return
        if (actuel !in existants) set(ctx, null)
    }
}
