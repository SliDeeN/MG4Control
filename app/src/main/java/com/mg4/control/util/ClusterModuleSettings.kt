package com.mg4.control.util

import android.content.Context
import com.mg4.control.model.ClusterModule

/**
 * Réglage « Module sur le combiné » (Réglages › Interface) : un interrupteur, désactivé par
 * défaut, et le module montré. Le module est relu à chaque relevé : en changer vaut tout de
 * suite, sans réafficher la fenêtre.
 */
object ClusterModuleSettings {

    /** Même fichier que les autres réglages. */
    private const val PREFS = "mg4_settings"
    const val KEY_ENABLED = "cluster_module_enabled"
    const val KEY_MODULE = "cluster_module"

    fun isEnabled(ctx: Context?): Boolean =
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getBoolean(KEY_ENABLED, false) ?: false

    fun module(ctx: Context?): ClusterModule =
        ClusterModule.fromKey(ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY_MODULE, null))

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    fun setModule(ctx: Context, module: ClusterModule) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODULE, module.key).apply()
    }
}
