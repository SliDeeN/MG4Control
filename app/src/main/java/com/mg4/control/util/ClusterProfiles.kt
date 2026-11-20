package com.mg4.control.util

import android.content.Context
import com.mg4.control.debug.AppLogger
import com.mg4.control.model.ClusterConfig
import com.mg4.control.model.ClusterConfigs
import com.mg4.control.profile.ActiveProfile
import com.mg4.control.profile.ProfileManager

/**
 * Écran conducteur par profil (2026-10-24) : quels réglages suit le combiné, et comment la carte
 * de Réglages › Interface les modifie pour un ou plusieurs profils à la fois.
 *
 * Le combiné relit ses réglages à chaque relevé (jusqu'à dix fois par seconde) : le profil montré
 * et ses réglages sont gardés tant que ni les profils enregistrés, ni le profil en cours, ni le
 * profil par défaut ne changent — trois lectures de préférences en mémoire, pas de décodage.
 */
object ClusterProfiles {

    private const val TAG = "MG4_CLUSTER"
    /** Même fichier que les autres réglages. */
    private const val PREFS = "mg4_settings"
    private const val KEY_MIGRATED = "cluster_per_profile_migrated"
    private const val KEY_LIBRARY_MIGRATED = "cluster_library_migrated"

    private class Lecture(
        val brut: String?, val actif: String?, val defaut: String?,
        val profilId: String?, val reglages: ClusterConfig?,
    )

    @Volatile private var derniere: Lecture? = null

    /** Réglages que suit le combiné : ceux du profil montré, ou les communs sans aucun profil. */
    fun effective(ctx: Context?): ClusterConfig {
        val c = ctx ?: return ClusterConfig()
        return lire(c).reglages ?: ClusterModuleSettings.legacy(c)
    }

    /** Profil que suit le combiné ([ClusterConfigs.shown]) ; `null` sans aucun profil. */
    fun shownProfileId(ctx: Context): String? = lire(ctx).profilId

    private fun lire(ctx: Context): Lecture {
        val brut = ProfileManager.rawJson(ctx)
        val actif = ActiveProfile.id(ctx)
        val defaut = ProfileManager.defaultIdOf(ctx)
        derniere?.let { if (it.brut == brut && it.actif == actif && it.defaut == defaut) return it }
        val profils = if (brut == null) emptyList() else ProfileManager(ctx).getAll()
        val montre = ClusterConfigs.shown(profils, actif, defaut)
        return Lecture(brut, actif, defaut, montre?.id, montre?.let(ClusterConfigs::of)).also { derniere = it }
    }

    /** Réglages des profils [ids], dans l'ordre de la liste ; sans aucun profil, les communs seuls. */
    fun configs(ctx: Context, ids: Collection<String>): List<ClusterConfig> {
        val profils = ProfileManager(ctx).getAll()
        if (profils.isEmpty()) return listOf(ClusterModuleSettings.legacy(ctx))
        return profils.filter { it.id in ids }.map(ClusterConfigs::of).ifEmpty { listOf(effective(ctx)) }
    }

    /**
     * [transform] appliqué aux réglages des profils [ids] — sans aucun profil, aux réglages communs.
     * Retourne `true` si quelque chose a changé.
     */
    fun update(ctx: Context, ids: Collection<String>, transform: (ClusterConfig) -> ClusterConfig): Boolean {
        val pm = ProfileManager(ctx)
        if (pm.getAll().isEmpty()) {
            val avant = ClusterModuleSettings.legacy(ctx)
            val apres = transform(avant)
            if (apres != avant) ClusterModuleSettings.saveLegacy(ctx, apres)
            return apres != avant
        }
        return pm.updateAll { p -> if (p.id in ids) p.copy(cluster = transform(ClusterConfigs.of(p))) else p }
    }

    /**
     * Une fois, à la mise à jour qui l'introduit : chaque profil existant reçoit les réglages
     * communs d'avant, pour que rien ne change à l'écran. Un profil créé ensuite n'en a pas :
     * module coupé, c'est à l'utilisateur de le régler (sa décision).
     */
    fun migrateIfNeeded(ctx: Context) {
        migrerParProfil(ctx)
        // Toujours, même quand la reprise par profil est déjà faite : avant le 2026-11-20 (soir), un
        // `return` dans le runCatching de celle-ci quittait toute la fonction, et la bibliothèque
        // n'était jamais reprise — les modules réglés disparaissaient à la mise à jour.
        migrateLibraryIfNeeded(ctx)
    }

    private fun migrerParProfil(ctx: Context) {
        runCatching {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_MIGRATED, false)) return
            val communs = ClusterModuleSettings.legacy(ctx)
            val repris = ProfileManager(ctx).updateAll { if (it.cluster == null) it.copy(cluster = communs) else it }
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            if (repris) AppLogger.i(TAG, "écran conducteur par profil : réglages communs repris par les profils " +
                "existants (module ${if (communs.enabled == true) "activé" else "coupé"})")
        }.onFailure { AppLogger.w(TAG, "écran conducteur par profil : reprise des réglages communs — ${it.message}") }
    }

    /**
     * Une fois (2026-11-20) : chaque réglage d'avant devient un module de la bibliothèque, et chaque
     * profil reçoit ses listes par onglet ([ClusterModuleDefs.migrate]) ; puissance maxi,
     * actualisation et animation du profil montré deviennent les réglages communs. Rien ne change à
     * l'écran.
     */
    fun migrateLibraryIfNeeded(ctx: Context) {
        runCatching {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_LIBRARY_MIGRATED, false)) return
            val loc = LocaleHelper.applyLocale(ctx)
            val nom: (com.mg4.control.model.ClusterModule) -> String = { m ->
                loc.getString(when (m) {
                    com.mg4.control.model.ClusterModule.CONSUMPTION -> com.mg4.control.R.string.settings_cluster_consumption
                    com.mg4.control.model.ClusterModule.POWER -> com.mg4.control.R.string.settings_cluster_power
                    com.mg4.control.model.ClusterModule.TYRES -> com.mg4.control.R.string.settings_cluster_tyres
                })
            }
            val nouvelId = { java.util.UUID.randomUUID().toString().take(12) }
            val montre = effective(ctx)
            val pm = ProfileManager(ctx)
            val profils = pm.getAll()
            val existante = ClusterModuleSettings.library(ctx)
            if (profils.isEmpty()) {
                val (biblio, repris) = com.mg4.control.model.ClusterModuleDefs.migrate(listOf(ClusterModuleSettings.legacy(ctx)), nouvelId, nom)
                ClusterModuleSettings.saveLibrary(ctx, existante + biblio)
                ClusterModuleSettings.saveLegacy(ctx, repris.first())
            } else {
                val (biblio, repris) = com.mg4.control.model.ClusterModuleDefs.migrate(profils.map(ClusterConfigs::of), nouvelId, nom)
                ClusterModuleSettings.saveLibrary(ctx, existante + biblio)
                val parId = profils.map { it.id }.zip(repris).toMap()
                pm.updateAll { p -> parId[p.id]?.let { p.copy(cluster = it) } ?: p }
            }
            prefs.edit().apply {
                if (!prefs.contains(ClusterModuleSettings.KEY_COMMON_REFRESH)) montre.refreshMs?.let { putInt(ClusterModuleSettings.KEY_COMMON_REFRESH, it) }
                if (!prefs.contains(ClusterModuleSettings.KEY_COMMON_ANIMATION)) montre.animationMs?.let { putInt(ClusterModuleSettings.KEY_COMMON_ANIMATION, it) }
                if (!prefs.contains(ClusterModuleSettings.KEY_COMMON_POWER_MAX)) montre.powerMax?.let { putInt(ClusterModuleSettings.KEY_COMMON_POWER_MAX, it) }
                putBoolean(KEY_LIBRARY_MIGRATED, true)
            }.apply()
            derniere = null
            AppLogger.i(TAG, "écran conducteur : réglages d'avant repris en ${ClusterModuleSettings.library(ctx).size} module(s) de la bibliothèque")
        }.onFailure { AppLogger.w(TAG, "écran conducteur : reprise en bibliothèque — ${it.message}") }
    }
}
