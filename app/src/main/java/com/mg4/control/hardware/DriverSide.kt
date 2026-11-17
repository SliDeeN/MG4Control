package com.mg4.control.hardware

import com.mg4.control.debug.AppLogger

/**
 * Côté du volant, tel que le lit le service d'adaptation SAIC (`SaicAdapterService`, SWI69,
 * `CarInfo.getDriverPosition`, décodé le 2026-11-02) : `CarInfoManager.getByteProperty(0x2170421e)`,
 * un mot de configuration de la voiture dont l'octet 0 vaut 0 pour un volant à GAUCHE, autre chose
 * pour un volant à DROITE, et -1 (0xff) quand il n'est pas renseigné.
 *
 * Lu par le CarPropertyManager en octets, comme [BatteryTypeConfig]. La propriété n'a été vue que
 * dans le code du SWI69 : sur les autres firmwares elle peut manquer, d'où « illisible » et le
 * repli sur le choix manuel (Réglages › Interface, [com.mg4.control.util.NavSide]).
 *
 * Une configuration ne change pas : lue une fois, gardée. Lecture binder, hors du fil principal.
 */
object DriverSide {

    private const val TAG = "MG4_UI"

    /** Mot de configuration lu par `CarInfo.getDriverPosition` (SWI69). */
    const val PROP_DRIVER_POSITION = 0x2170421e
    private const val AREA_GLOBAL = 0x1000000

    enum class Side { LEFT, RIGHT }

    @Volatile
    private var cote: Side? = null

    /** Le côté du volant, ou `null` s'il est illisible ou non renseigné. */
    fun read(): Side? = cote ?: lire()?.also { cote = it }

    /** Sonde du Diagnostic : les octets lus et ce qu'on en déduit. */
    fun journaliser() {
        AppLogger.i(TAG, "diag côté du volant (0x2170421e) : ${read() ?: "illisible ou non renseigné"}")
    }

    private fun lire(): Side? {
        val cpm = MG4Hardware.carPropertyManager() ?: return null
        val getter = runCatching {
            cpm.javaClass.getMethod("getProperty", Class::class.java, Int::class.java, Int::class.java)
        }.getOrNull() ?: return null
        for (zone in intArrayOf(AREA_GLOBAL, 0)) {
            val octets = runCatching {
                val valeur = getter.invoke(cpm, ByteArray::class.java, PROP_DRIVER_POSITION, zone)
                valeur?.javaClass?.getMethod("getValue")?.invoke(valeur) as? ByteArray
            }.getOrNull() ?: continue
            val premier = octets.firstOrNull()?.toInt() ?: continue
            val side = when (premier) {
                -1 -> null
                0 -> Side.LEFT
                else -> Side.RIGHT
            }
            AppLogger.i(TAG, "côté du volant 0x2170421e = ${octets.joinToString(" ", "[", "]") { "%02x".format(it) }} → ${side ?: "non renseigné"}")
            return side
        }
        AppLogger.w(TAG, "côté du volant 0x2170421e illisible")
        return null
    }
}
