package com.mg4.control.util

import android.content.Context
import com.mg4.control.debug.AppLogger
import com.mg4.control.hardware.VehicleDistanceUnit
import com.mg4.control.model.DistanceUnit

/**
 * Unité de distance de toute l'appli (2026-11-10, Réglages › Interface) : Auto (comme la voiture),
 * km ou miles. Suivie par les Statistiques, l'écran conducteur et les bulles d'information.
 * L'export CSV reste en km (sa demande).
 *
 * Remplace la ligne « Distance » de l'écran conducteur, qui était réglée par profil : à la première
 * lecture, le choix fait là-bas est repris (profil montré, puis réglages communs), sinon Auto.
 */
object DistanceSetting {

    private const val PREFS = "mg4_settings"
    const val KEY = "distance_unit"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Valeur à garder à la migration : le choix déjà enregistré, sinon le premier choix km/miles de
     * l'ancien réglage du combiné ([legacy], dans l'ordre), sinon Auto.
     */
    fun migrated(saved: String?, legacy: List<String?>): String =
        saved ?: legacy.firstOrNull { k -> DistanceUnit.entries.any { it.key == k } } ?: DistanceUnit.AUTO

    private fun key(ctx: Context): String {
        val p = prefs(ctx)
        p.getString(KEY, null)?.let { return it }
        val anciens = runCatching {
            listOf(ClusterProfiles.effective(ctx).distance, ClusterModuleSettings.legacy(ctx).distance)
        }.getOrDefault(emptyList())
        val k = migrated(null, anciens)
        p.edit().putString(KEY, k).apply()
        AppLogger.i("MG4_SETTINGS", "Unité de distance reprise de l'écran conducteur → $k")
        return k
    }

    /** Choix enregistré : km ou miles, `null` = Auto. */
    fun choice(ctx: Context?): DistanceUnit? {
        val c = ctx ?: return null
        val k = key(c)
        return DistanceUnit.entries.firstOrNull { it.key == k }
    }

    fun set(ctx: Context, unit: DistanceUnit?) =
        prefs(ctx).edit().putString(KEY, unit?.key ?: DistanceUnit.AUTO).apply()

    /** Unité affichée, sans appel binder : en Auto, la dernière unité lue dans la voiture, sinon km. */
    fun unit(ctx: Context?): DistanceUnit = choice(ctx) ?: VehicleDistanceUnit.connue() ?: DistanceUnit.KM

    /** Comme [unit], mais relit la voiture en Auto (au plus une fois par minute). Binder : hors du fil principal. */
    fun unitReading(ctx: Context?): DistanceUnit =
        choice(ctx) ?: VehicleDistanceUnit.actualiser() ?: DistanceUnit.KM
}
