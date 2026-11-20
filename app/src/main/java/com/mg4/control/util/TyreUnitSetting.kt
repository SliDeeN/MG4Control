package com.mg4.control.util

import android.content.Context
import com.mg4.control.hardware.TyreProbe
import com.mg4.control.model.TyrePressure

/**
 * Unité de pression des pneus (2026-11-20) : celle choisie dans le pop-up Pression des pneus, sinon
 * celle du combiné (dernier relevé connu), sinon bar. Une seule pour le pop-up, les bulles, l'écran
 * conducteur et les automatisations.
 */
object TyreUnitSetting {

    private const val PREFS = "mg4_settings"
    const val KEY = "tyre_unit"

    fun unit(ctx: Context?, clusterCode: Int? = TyreProbe.dernierConnu?.uniteCombine): TyrePressure.PressureUnit =
        TyrePressure.PressureUnit.fromName(ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY, null))
            ?: TyrePressure.PressureUnit.fromCluster(clusterCode)

    fun set(ctx: Context, unit: TyrePressure.PressureUnit) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, unit.name).apply()
    }
}
