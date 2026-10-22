package com.mg4.control.hardware

import com.mg4.control.debug.AppLogger
import com.mg4.control.model.ChargeLimitSupport

/**
 * Code du TYPE DE BATTERIE de la voiture, tel que le lit l'appli de charge SAIC des A9
 * (`EvCharge`, SWI69 / SWI131 / SWI132, décodée le 2026-10-18) : `getBatteryTypeConfigCode`
 * = `CarInfoManager.getByteProperty(0x21704244)`, soit le CarPropertyManager en octets, zone
 * globale ; elle garde le DERNIER octet. C'est lui qui décide si elle montre le réglage de la
 * limite de charge ([ChargeLimitSupport]).
 *
 * Un code de configuration ne change pas : lu une fois, gardé. Illisible, il est relu au
 * prochain appel. Lecture binder : hors du fil principal de préférence (le suivi de charge le lit
 * dès qu'une charge commence).
 */
object BatteryTypeConfig {

    private const val TAG = "MG4_CHARGE"

    /** Propriété de configuration lue par EvCharge (A9). */
    const val PROP_BATTERY_TYPE = 0x21704244
    private const val AREA_GLOBAL = 0x1000000

    @Volatile
    private var code: Int? = null

    /** Sonde du Diagnostic : le code (ou « illisible ») et ce qu'en déduit le pop-up de charge. */
    fun journaliser() {
        val c = code()
        AppLogger.i(TAG, "diag type de batterie (0x21704244) : ${c ?: "illisible"} → curseur de limite sur A9 : " +
            if (ChargeLimitSupport.reglable(a9 = true, code = c)) "oui" else "non")
    }

    /** Le code, ou `null` s'il est illisible. */
    fun code(): Int? = code ?: lire()?.also { code = it }

    private fun lire(): Int? {
        val cpm = MG4Hardware.carPropertyManager() ?: return null
        val getter = runCatching {
            cpm.javaClass.getMethod("getProperty", Class::class.java, Int::class.java, Int::class.java)
        }.getOrNull() ?: return null
        // La zone globale d'abord, comme EvCharge ; la zone 0 sinon.
        for (zone in intArrayOf(AREA_GLOBAL, 0)) {
            val octets = runCatching {
                val valeur = getter.invoke(cpm, ByteArray::class.java, PROP_BATTERY_TYPE, zone)
                valeur?.javaClass?.getMethod("getValue")?.invoke(valeur) as? ByteArray
            }.getOrNull() ?: continue
            val c = octets.lastOrNull()?.toInt() ?: continue
            AppLogger.i(TAG, "type de batterie 0x21704244 = ${octets.joinToString(" ", "[", "]") { "%02x".format(it) }} " +
                "→ code $c${if (c == ChargeLimitSupport.CODE_LFP) " (LFP)" else ""}, " +
                "limite réglable selon EvCharge : ${if (c in ChargeLimitSupport.CODES_REGLABLES) "oui" else "non"}")
            return c
        }
        AppLogger.w(TAG, "type de batterie 0x21704244 illisible")
        return null
    }
}
